package com.dsh.notes.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.ai.PromptBuilder
import com.dsh.notes.audio.Waveform
import com.dsh.notes.data.Note
import com.dsh.notes.data.ConfigValidator
import com.dsh.notes.audio.SpeechReader
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
import org.json.JSONObject
import java.io.File

/** 一处可疑的词：原文里的写法 + 模型给出的候选 + 为什么可疑 */
data class Suspect(
    val word: String,
    val suggestions: List<String>,
    val reason: String
)

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

    // ---------------------------------------------------------------- 朗读（系统 TTS）

    private val reader = SpeechReader(container.context)

    val speech: StateFlow<SpeechReader.State> = reader.state

    fun speakSummary() {
        val text = note.value?.summary.orEmpty()
        reader.speak(text, source = "摘要")
    }

    fun speakBody() {
        val text = note.value?.displayBody.orEmpty()
        reader.speak(text, source = "正文")
    }

    fun pauseSpeech() = reader.pause()

    fun resumeSpeech() = reader.resume()

    fun stopSpeech() = reader.stop()

    fun setSpeechSpeed(speed: Float) = reader.setSpeed(speed)

    // ---------------------------------------------------------------- 可疑词校对

    private val _suspects = MutableStateFlow<List<Suspect>>(emptyList())

    /** 待用户确认的可疑词；非空时界面弹出校对框 */
    val suspects: StateFlow<List<Suspect>> = _suspects.asStateFlow()

    private val _checking = MutableStateFlow(false)

    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun clearSuspects() {
        _suspects.value = emptyList()
    }

    /** 把某条可疑词的某个候选写法应用到笔记里 */
    fun applyCorrection(from: String, to: String) {
        if (from.isBlank() || to.isBlank() || from == to) return
        viewModelScope.launch {
            container.repository.patch(noteId) { current ->
                current.copy(
                    refinedMarkdown = current.refinedMarkdown.replace(from, to),
                    transcript = current.transcript.replace(from, to)
                )
            }
            val remaining = _suspects.value.filterNot { it.word == from }
            _suspects.value = remaining
            // 全部确认完了：问一句要不要用改过的文本重新整理
            if (remaining.isEmpty()) _askReorganize.value = true
        }
    }

    private val _askReorganize = MutableStateFlow(false)

    /** 可疑词全部确认后，提示「是否用新文本重新整理」 */
    val askReorganize: StateFlow<Boolean> = _askReorganize.asStateFlow()

    fun dismissReorganize() {
        _askReorganize.value = false
    }

    fun dismissSuspect(word: String) {
        _suspects.value = _suspects.value.filterNot { it.word == word }
    }

    /**
     * 让模型再审一遍转写文字，挑出可能听错的词。
     *
     * 注意：这不是转写服务给的置信度 —— OpenAI 兼容的 /audio/transcriptions
     * 默认只回一段纯文本，我们手上没有任何逐词置信度。这里是让文本模型
     * 根据上下文判断「哪里像同音错字」，属于二次校对，不是原始置信度。
     */
    fun checkSuspects() {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            try {
                val current = container.repository.get(noteId) ?: return@launch
                val settings = container.settings.current()
                ConfigValidator.deepseekProblems(settings).firstOrNull()?.let { problem ->
                    _message.value = "整理用的 DeepSeek 还没配好：${problem.message}"
                    return@launch
                }
                val text = current.transcript.ifBlank { current.displayBody }.take(4000)
                if (text.isBlank()) {
                    _message.value = "这条笔记还没有文字可以校对"
                    return@launch
                }
                val raw = container.ai.deepseek.chat(
                    baseUrl = settings.deepseekBaseUrl,
                    apiKey = settings.deepseekApiKey,
                    model = settings.deepseekModel,
                    systemPrompt = PROOFREAD_PROMPT,
                    userPrompt = text
                )
                val list = parseSuspects(raw)
                if (list.isEmpty()) {
                    _message.value = "没有发现明显可疑的词"
                } else {
                    _suspects.value = list
                }
            } catch (error: Exception) {
                _message.value = error.message ?: "检查失败"
            } finally {
                _checking.value = false
            }
        }
    }

    private fun parseSuspects(raw: String): List<Suspect> {
        // 模型偶尔会包一层 ``` 或加句废话，直接从第一个 { 截到最后一个 }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()

        return runCatching {
            val array = JSONObject(raw.substring(start, end + 1)).optJSONArray("suspects")
                ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val word = item.optString("word").trim()
                    if (word.isBlank()) continue
                    val suggestions = item.optJSONArray("suggestions")
                    val candidates = buildList {
                        if (suggestions != null) {
                            for (j in 0 until suggestions.length()) {
                                val value = suggestions.optString(j).trim()
                                if (value.isNotBlank() && value != word) add(value)
                            }
                        }
                    }
                    add(Suspect(word, candidates, item.optString("reason").trim()))
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        reader.release()
    }

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

    /**
     * 只删音频，不动笔记正文。
     *
     * 这是不可撤销的操作，界面上必须先弹二次确认；
     * 删完把 audioPath 置空并刷新播放器状态，界面会显示「录音文件已清理」。
     */
    fun deleteAudio() {
        viewModelScope.launch {
            val current = container.repository.get(noteId) ?: return@launch
            val path = current.audioPath
            if (path.isNullOrBlank()) {
                _message.value = "这条笔记没有音频文件"
                return@launch
            }
            val deleted = withContext(Dispatchers.IO) {
                runCatching { File(path).delete() }.getOrDefault(false)
            }
            container.repository.patch(noteId) {
                it.copy(audioPath = null, audioDurationMs = 0L)
            }
            val updated = container.repository.get(noteId) ?: current
            refreshAudio(updated)
            _message.value = if (deleted) "音频已删除，正文保留" else "音频文件已不存在，已清理记录"
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
            val deepseekProblem = ConfigValidator.deepseekProblems(settings).firstOrNull()
            if (deepseekProblem != null) {
                _message.value = "整理用的 DeepSeek 还没配好：${deepseekProblem.message}"
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

        /**
         * 可疑词校对的提示词。
         *
         * 只让它「挑词 + 给候选」，不让它改写整段 —— 用户要的是逐条确认，
         * 而不是又生成一版没人核对过的正文。
         */
        private const val PROOFREAD_PROMPT = """你是中文语音转写文本的校对助手。用户会给你一段由语音转写得到的文字。

请找出其中**确实可疑**的词（同音错字、听错的人名、错乱的术语/数字），最多 8 处：
- 只挑那些在上下文里明显讲不通、或者很像同音误听的词；
- 常见口语、专有名词但上下文通顺的，不要挑；
- 每处给出 1~3 个更可能的写法，按可能性从高到低排；
- 如果没有可疑之处，输出空数组。

严格只输出一个 JSON 对象：
{"suspects":[{"word":"原文里的那个词（必须和原文完全一致，便于替换）","suggestions":["候选1","候选2"],"reason":"为什么可疑，一句话"}]}

不要输出任何其它内容，也不要改写整段文字。"""
    }
}
