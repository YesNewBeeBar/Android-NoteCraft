package com.dsh.notes.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.data.AudioSource
import com.dsh.notes.data.Folder
import com.dsh.notes.data.Note
import com.dsh.notes.data.NoteStatus
import com.dsh.notes.data.NotesViewMode
import com.dsh.notes.export.ExportException
import com.dsh.notes.pipeline.ProcessingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

/** 列表的文件夹筛选：全部 / 未分类 / 某个文件夹 */
sealed interface FolderFilter {
    data object All : FolderFilter
    data object Unfiled : FolderFilter
    data class In(val id: Long) : FolderFilter
}

class NotesViewModel(private val container: AppContainer) : ViewModel() {

    /** 处理队列状态：列表里给排队中的笔记打个标 */
    val queueState: StateFlow<ProcessingQueue.QueueState> = container.queue.state

    /** 笔记页显示方式：列表 / 图标 / 文件夹 */
    val viewMode: StateFlow<NotesViewMode> = container.settings.flow
        .map { it.notesViewMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesViewMode.LIST)

    fun setViewMode(mode: NotesViewMode) {
        viewModelScope.launch { container.settings.setNotesViewMode(mode) }
    }

    private val _keyword = MutableStateFlow("")

    val keyword: StateFlow<String> = _keyword.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<Note>> = _keyword
        .flatMapLatest { keyword -> container.repository.observeSearch(keyword) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------------------------------------------------------- 文件夹

    val folders: StateFlow<List<Folder>> = container.repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _folderFilter = MutableStateFlow<FolderFilter>(FolderFilter.All)

    val folderFilter: StateFlow<FolderFilter> = _folderFilter.asStateFlow()

    /** 搜索之后再按文件夹筛一遍；笔记量在千级以内，内存里筛足够 */
    val visibleNotes: StateFlow<List<Note>> =
        combine(notes, _folderFilter) { list, filter ->
            when (filter) {
                is FolderFilter.All -> list
                is FolderFilter.Unfiled -> list.filter { it.folderId == null }
                is FolderFilter.In -> list.filter { it.folderId == filter.id }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectFolder(filter: FolderFilter) {
        _folderFilter.value = filter
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { container.repository.createFolder(trimmed) }
    }

    /** 删文件夹不删笔记：里面的笔记回到「未分类」，并把筛选切回全部 */
    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            container.repository.deleteFolder(id)
            if (folderFilter.value == FolderFilter.In(id)) _folderFilter.value = FolderFilter.All
        }
    }

    fun moveToFolder(noteIds: List<Long>, folderId: Long?) {
        if (noteIds.isEmpty()) return
        viewModelScope.launch { container.repository.moveNotesToFolder(noteIds, folderId) }
    }

    /** 多选批量删除（音频文件一起删） */
    fun deleteMany(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var done = 0
            ids.forEach { id ->
                val note = container.repository.get(id) ?: return@forEach
                note.audioPath?.let { path -> runCatching { java.io.File(path).delete() } }
                container.repository.delete(note)
                done++
            }
            _message.value = "已删除 $done 条"
        }
    }

    /** 多选批量保存到默认位置（按各自所属文件夹分目录） */
    fun exportMany(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var done = 0
            var failed = 0
            var needsPick = false
            ids.forEach { id ->
                val note = container.repository.get(id) ?: return@forEach
                runCatching { container.exporter.export(note) }
                    .onSuccess { result ->
                        markExported(id, result)
                        done++
                    }
                    .onFailure { error ->
                        if ((error as? ExportException)?.needsPick == true) needsPick = true
                        failed++
                    }
            }
            _message.value = when {
                needsPick -> "还没设置保存位置，去「设置 → Markdown 存放位置」选一个"
                failed == 0 -> "已保存 $done 条"
                done == 0 -> "保存失败：$failed 条都没成功"
                else -> "已保存 $done 条，$failed 条失败"
            }
        }
    }

    // ---------------------------------------------------------------- 合并整理

    private val _mergeState = MutableStateFlow<String?>(null)

    /** 正在合并时的一行提示；null 表示没在合并 */
    val mergeState: StateFlow<String?> = _mergeState.asStateFlow()

    /**
     * 把选中的多条笔记合并整理成一条新笔记。原件保留。
     * @param onCreated 合并成功后回调新笔记 id，界面据此直接跳进去
     */
    fun mergeNotes(ids: List<Long>, onCreated: (Long) -> Unit) {
        if (ids.size < 2 || _mergeState.value != null) return
        viewModelScope.launch {
            _mergeState.value = "正在合并 ${ids.size} 条笔记…"
            try {
                val newId = container.pipeline.mergeAndOrganize(ids)
                _mergeState.value = null
                onCreated(newId)
            } catch (error: Exception) {
                _mergeState.value = null
                _message.value = error.message ?: "合并失败"
            }
        }
    }

    private val _message = MutableStateFlow<String?>(null)

    /** 一行提示（合并失败、移动完成之类） */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /** 界面自己产生的提示（比如分享失败） */
    fun notify(message: String) {
        _message.value = message
    }

    private val _importState = MutableStateFlow(ImportState())

    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun search(value: String) {
        _keyword.value = value
    }

    /** 列表里直接改标题，不用点进详情页 */
    fun rename(note: Note, title: String) {
        val trimmed = title.trim()
        viewModelScope.launch {
            container.repository.patch(note.id) {
                it.copy(title = trimmed.ifBlank { "未命名笔记" })
            }
        }
    }

    /** 存到设置里选好的那个目录；没选过就提示去选 */
    fun exportMarkdown(note: Note) {
        viewModelScope.launch {
            try {
                val result = container.exporter.export(note)
                // 列表里保存也要挂上「已导出」，和详情页保存保持一致
                markExported(note.id, result)
                _message.value = "已保存到 ${result.relativePath}"
            } catch (error: ExportException) {
                _message.value = if (error.needsPick) {
                    "还没设置保存位置，去「设置 → Markdown 存放位置」选一个"
                } else {
                    error.message ?: "保存失败"
                }
            } catch (error: Exception) {
                _message.value = error.message ?: "保存失败"
            }
        }
    }

    /** 记下导出到哪里了。列表卡片据此显示「· 已导出」 */
    private suspend fun markExported(noteId: Long, result: com.dsh.notes.export.ExportResult) {
        runCatching {
            container.repository.patch(noteId) {
                it.copy(
                    exportTreeUri = result.treeUri,
                    exportDocumentUri = result.documentUri,
                    exportRelativePath = result.relativePath,
                    exportedAt = System.currentTimeMillis()
                )
            }
        }
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
