package com.dsh.notes.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.ai.PromptBuilder
import com.dsh.notes.audio.Waveform
import com.dsh.notes.data.Note
import com.dsh.notes.pipeline.AudioFileState
import com.dsh.notes.pipeline.PipelineProgress
import com.dsh.notes.pipeline.PipelineStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 笔记详情页。
 *
 * 这里的操作分三类，界面上也刻意分开：
 *  1) 编辑（改标题、改正文）—— 本地即时生效，不联网；
 *  2) 重跑（重新转写、重新整理）—— 会调 AI，耗时且有成本；
 *  3) 导出（写 md 到指定目录）—— 走 SAF，可能需要用户选目录。
 */
class NoteDetailViewModel(
    private val container: AppContainer,
    private val noteId: Long
) : ViewModel() {

    val note: StateFlow<Note?> = container.repository.observeById(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _progress = MutableStateFlow<PipelineProgress?>(null)

    val progress: StateFlow<PipelineProgress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    private val _peaks = MutableStateFlow<List<Float>>(emptyList())

    /** 音频波形（64 根柱子），进页面时算一次 */
    val peaks: StateFlow<List<Float>> = _peaks.asStateFlow()

    private val _audioState = MutableStateFlow<AudioFileState>(AudioFileState.Missing)

    val audioState: StateFlow<AudioFileState> = _audioState.asStateFlow()

    private val _exportedPath = MutableStateFlow<String?>(null)

    /** 刚导出成功后的相对路径，用于弹一条「已保存到 …」 */
    val exportedPath: StateFlow<String?> = _exportedPath.asStateFlow()

    init {
        viewModelScope.launch {
            val current = container.repository.get(noteId) ?: return@launch
            refreshAudio(current)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            container.repository.get(noteId)?.let { refreshAudio(it) }
        }
    }

    private suspend fun refreshAudio(note: Note) {
        _audioState.value = container.pipeline.audioFileState(note)
        val file = note.audioPath?.let { File(it) }
        if (file != null && file.exists()) {
            _peaks.value = withContext(Dispatchers.IO) {
                Waveform.peaks(file, BARS, sampleRate = 16_000)
            }
        } else {
            _peaks.value = emptyList()
        }
    }

    fun updateTitle(value: String) {
        viewModelScope.launch {
            container.repository.patch(noteId) { it.copy(title = value) }
        }
    }

    fun updateBody(value: String) {
        viewModelScope.launch {
            // 用户手改过的正文就当作最终稿：写进 refinedMarkdown
            container.repository.patch(noteId) { it.copy(refinedMarkdown = value) }
        }
    }

    fun updateTranscript(value: String) {
        viewModelScope.launch {
            container.repository.patch(noteId) { it.copy(transcript = value) }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val current = container.repository.get(noteId)
            current?.audioPath?.let { path -> runCatching { File(path).delete() } }
            if (current != null) container.repository.delete(current)
            onDeleted()
        }
    }

    fun transcribe() = runPipeline { container.pipeline.transcribeOnly(noteId, it) }

    fun summarize() = runPipeline { container.pipeline.summarizeOnly(noteId, it) }

    fun processAll() = runPipeline { container.pipeline.process(noteId, forceTranscribe = true, onProgress = it) }

    /** 只重拟标题：便宜又常用的小功能 */
    fun regenerateTitle() {
        viewModelScope.launch {
            val current = container.repository.get(noteId) ?: return@launch
            val settings = container.settings.current()
            if (!settings.deepseekReady) {
                _message.value = "还没有配置 DeepSeek API Key"
                return@launch
            }
            val source = current.displayBody.take(3000)
            if (source.isBlank()) {
                _message.value = "没有内容可以起标题"
                return@launch
            }
            _progress.value = PipelineProgress.of(noteId, PipelineStage.SUMMARIZING, "正在重拟标题…")
            runCatching {
                container.ai.deepseek.chat(
                    baseUrl = settings.deepseekBaseUrl,
                    apiKey = settings.deepseekApiKey,
                    model = settings.deepseekModel,
                    systemPrompt = PromptBuilder.titlePrompt(),
                    userPrompt = source
                )
            }.onSuccess { title ->
                val cleaned = title.trim().trim('"', '「', '」', '#', '。').take(40)
                if (cleaned.isNotBlank()) {
                    container.repository.patch(noteId) { it.copy(title = cleaned) }
                    _message.value = "标题已更新"
                }
            }.onFailure { error ->
                _message.value = error.message ?: "重拟标题失败"
            }
            _progress.value = null
        }
    }

    /** 用户从系统目录选择器挑好目录后调用 */
    fun exportTo(treeUri: Uri, saveAsDefault: Boolean) {
        viewModelScope.launch {
            container.exporter.persistPermission(treeUri)
            if (saveAsDefault) {
                container.settings.setExportTreeUri(treeUri.toString())
            }
            try {
                container.pipeline.exportNote(
                    noteId = noteId,
                    treeUriOverride = treeUri.toString()
                ) { progress -> _progress.value = progress }
                val refreshed = container.repository.get(noteId)
                _exportedPath.value = refreshed?.exportRelativePath
            } catch (error: Exception) {
                _message.value = error.message ?: "导出失败"
            } finally {
                _progress.value = null
            }
        }
    }

    /** 用当前默认目录导出（没设置过就会失败，界面据此弹目录选择器） */
    fun exportToDefault() {
        viewModelScope.launch {
            try {
                container.pipeline.exportNote(noteId) { progress -> _progress.value = progress }
                val refreshed = container.repository.get(noteId)
                _exportedPath.value = refreshed?.exportRelativePath
            } catch (error: Exception) {
                _message.value = error.message ?: "导出失败"
            } finally {
                _progress.value = null
            }
        }
    }

    /** 把这条笔记的录音原文件导出到用户刚选的目录（一次性，不记默认位置） */
    fun exportAudioTo(treeUri: Uri) {
        viewModelScope.launch {
            val current = container.repository.get(noteId) ?: return@launch
            container.exporter.persistPermission(treeUri)
            try {
                val result = container.exporter.exportAudio(current, treeUri.toString())
                _exportedPath.value = result.relativePath
            } catch (error: Exception) {
                _message.value = error.message ?: "导出音频失败"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearExported() {
        _exportedPath.value = null
    }

    private fun runPipeline(block: suspend ((PipelineProgress) -> Unit) -> Unit) {
        viewModelScope.launch {
            try {
                block { progress -> _progress.value = progress }
                container.repository.get(noteId)?.let { refreshAudio(it) }
            } catch (error: Exception) {
                _message.value = error.message ?: "处理失败"
            } finally {
                _progress.value = null
            }
        }
    }

    companion object {
        private const val BARS = 64
    }
}
