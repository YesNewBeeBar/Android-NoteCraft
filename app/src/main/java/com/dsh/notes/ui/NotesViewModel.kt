package com.dsh.notes.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.data.AudioSource
import com.dsh.notes.data.Note
import com.dsh.notes.data.NoteStatus
import com.dsh.notes.pipeline.ProcessingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入音频的进行状态 */
data class ImportState(
    val running: Boolean = false,
    val fileName: String = "",
    val noteId: Long = 0L,
    val message: String = "",
    val error: String? = null
)

class NotesViewModel(private val container: AppContainer) : ViewModel() {

    /** 处理队列状态：列表里给排队中的笔记打个标 */
    val queueState: StateFlow<ProcessingQueue.QueueState> = container.queue.state

    private val _keyword = MutableStateFlow("")

    val keyword: StateFlow<String> = _keyword.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<Note>> = _keyword
        .flatMapLatest { keyword -> container.repository.observeSearch(keyword) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow(ImportState())

    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun search(value: String) {
        _keyword.value = value
    }

    fun delete(note: Note) {
        viewModelScope.launch {
            // 音频文件跟着笔记一起删，否则私有目录里会攒下一堆孤儿文件
            note.audioPath?.let { path ->
                runCatching { java.io.File(path).delete() }
            }
            container.repository.delete(note)
        }
    }

    /**
     * 导入一个音频文件并走完整流程（转写 → 整理 → 可选导出）。
     *
     * 音频先复制到缓存目录：ContentResolver 给的 Uri 不能直接当 File 用，
     * 有些 Uri 还只允许读一次，而上传和后续处理可能读很多次。
     */
    fun importAudio(uri: Uri) {
        if (_importState.value.running) return
        viewModelScope.launch {
            val name = resolveDisplayName(uri)
            _importState.value = ImportState(running = true, fileName = name, message = "正在读取文件…")
            try {
                val cacheFile = container.ai.asr.copyToCache(container.context, uri, name)
                if (cacheFile.length() <= 0L) throw IllegalStateException("这个文件是空的")

                val settings = container.settings.current()
                val noteId = container.repository.create(
                    Note(
                        title = name.substringBeforeLast('.'),
                        status = NoteStatus.SAVED,
                        audioSource = AudioSource.IMPORTED,
                        audioSizeBytes = cacheFile.length()
                    )
                )

                _importState.value = ImportState(
                    running = true,
                    fileName = name,
                    noteId = noteId,
                    message = "正在转写（${com.dsh.notes.util.formatSize(cacheFile.length())}）…"
                )

                if (!settings.cloudAsrReady && settings.asrEngine == com.dsh.notes.data.AsrEngine.CLOUD) {
                    container.repository.patch(noteId) {
                        it.copy(
                            status = NoteStatus.SAVED,
                            errorMessage = "还没有配置云转写，无法转写这个文件"
                        )
                    }
                    _importState.value = ImportState(
                        running = false,
                        noteId = noteId,
                        error = "还没配置云转写 API Key，音频已保存但没转写"
                    )
                    return@launch
                }

                container.pipeline.transcribeImportedFile(
                    noteId = noteId,
                    cacheFile = cacheFile,
                    displayName = name,
                    onProgress = { progress ->
                        _importState.value = _importState.value.copy(message = progress.message)
                    }
                )
                _importState.value = ImportState(
                    running = false,
                    noteId = noteId,
                    message = "处理完成"
                )
            } catch (error: Exception) {
                _importState.value = ImportState(
                    running = false,
                    fileName = name,
                    error = error.message ?: "导入失败"
                )
            }
        }
    }

    fun clearImportState() {
        _importState.value = ImportState()
    }

    private suspend fun resolveDisplayName(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            container.context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
                }
            }
            uri.lastPathSegment?.substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "import-${System.currentTimeMillis()}.bin"
    }
}
