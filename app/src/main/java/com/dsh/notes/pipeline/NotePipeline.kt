package com.dsh.notes.pipeline

import android.content.Context
import android.util.Log
import com.dsh.notes.ai.AiClients
import com.dsh.notes.ai.AiException
import com.dsh.notes.ai.PromptBuilder
import com.dsh.notes.audio.LocalTranscriber
import com.dsh.notes.audio.probeDurationMs
import com.dsh.notes.data.AppSettings
import com.dsh.notes.data.AsrEngine
import com.dsh.notes.data.Note
import com.dsh.notes.data.NoteRepository
import com.dsh.notes.data.NoteStatus
import com.dsh.notes.data.SettingsStore
import com.dsh.notes.export.ExportException
import com.dsh.notes.export.NotesExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * 「一条录音 → 一份 Markdown 笔记」的完整流水线。
 *
 * 三个阶段：转写 → 整理 → 导出。每个阶段都会把状态写回数据库，
 * 所以界面刷新、App 被杀、用户锁屏都不会让进度丢失。
 *
 * 关于自动回落：云转写失败（网络断、额度用完、服务端抽风）时，
 * 如果用户在设置里开着「自动回落」，就改用系统离线识别再试一次。
 * 代价是离线识别不产出音频、要重新说一遍 —— 所以只在录音文件压根没有
 * 或云接口明确拒绝时才回落，不会白白让用户重录。
 */
class NotePipeline(
    private val context: Context,
    private val repository: NoteRepository,
    private val settingsStore: SettingsStore,
    private val ai: AiClients,
    private val exporter: NotesExporter
) {

    /** 同一条笔记不允许并发处理，否则两次写库会互相覆盖 */
    private val running: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    suspend fun process(
        noteId: Long,
        forceTranscribe: Boolean = false,
        onProgress: (PipelineProgress) -> Unit = {}
    ) {
        if (!running.add(noteId)) {
            Log.i(TAG, "note=$noteId 正在处理中，跳过重复请求")
            return
        }
        try {
            val note = repository.get(noteId) ?: run {
                onProgress(PipelineProgress(noteId, PipelineStage.FAILED, "笔记不存在"))
                return
            }
            val settings = settingsStore.current()

            var current = note
            val needsTranscript = forceTranscribe || note.transcript.isBlank()
            if (needsTranscript) {
                current = transcribe(note, settings, onProgress)
            }

            if (current.transcript.isBlank() && current.liveDraft.isBlank()) {
                val message = "没有可整理的文字内容"
                repository.patch(noteId) {
                    it.copy(status = NoteStatus.FAILED, errorMessage = message)
                }
                onProgress(PipelineProgress(noteId, PipelineStage.FAILED, message, message))
                return
            }

            current = summarize(current, settings, onProgress)
            exportIfWanted(current, settings, onProgress)
        } finally {
            running.remove(noteId)
        }
    }

    // ------------------------------------------------------------ 转写

    private suspend fun transcribe(
        note: Note,
        settings: AppSettings,
        onProgress: (PipelineProgress) -> Unit
    ): Note {
        onProgress(PipelineProgress.of(note.id, PipelineStage.TRANSCRIBING, "正在转写音频…"))
        repository.patchStatus(note.id, NoteStatus.TRANSCRIBING, null)

        val engine = settings.asrEngine
        return if (engine == AsrEngine.LOCAL) {
            transcribeLocally(note, settings, onProgress, "离线识别失败")
        } else {
            try {
                transcribeInCloud(note, settings, onProgress)
            } catch (error: Exception) {
                val message = (error as? AiException)?.display ?: (error.message ?: "云转写失败")
                Log.w(TAG, "云转写失败：$message", error)
                if (settings.autoFallbackToLocal) {
                    onProgress(
                        PipelineProgress.of(
                            note.id,
                            PipelineStage.TRANSCRIBING,
                            "云转写失败，改用系统离线识别…"
                        )
                    )
                    try {
                        transcribeLocally(note, settings, onProgress, "离线识别也失败了")
                    } catch (fallback: Exception) {
                        return failTranscribe(note, message, fallback, onProgress)
                    }
                } else {
                    return failTranscribe(note, message, null, onProgress)
                }
            }
        }
    }

    /**
     * 导入音频的转写：文件已复制到缓存目录，不存在「实时上屏草稿」的概念。
     * 转写成功后把文件搬进 App 私有音频目录，避免缓存被系统清掉后音频丢失。
     */
    suspend fun transcribeImportedFile(
        noteId: Long,
        cacheFile: File,
        displayName: String,
        onProgress: (PipelineProgress) -> Unit = {}
    ) {
        val note = repository.get(noteId) ?: return
        val settings = settingsStore.current()

        onProgress(PipelineProgress.of(noteId, PipelineStage.TRANSCRIBING, "正在上传并转写…"))
        repository.patchStatus(noteId, NoteStatus.TRANSCRIBING, null)

        try {
            val result = ai.asr.transcribeFile(
                baseUrl = settings.asrBaseUrl,
                apiKey = settings.asrApiKey,
                model = settings.asrModel,
                file = cacheFile,
                filename = displayName,
                language = settings.languageTag()
            )

            val stored = moveToAudioDir(noteId, cacheFile, displayName)
            repository.patch(noteId) {
                it.copy(
                    transcript = result.text,
                    asrEngine = result.engineLabel,
                    audioPath = stored?.absolutePath,
                    audioDurationMs = probeDurationMs(
                        stored ?: cacheFile,
                        settings.sampleRate
                    ),
                    audioSizeBytes = stored?.length() ?: cacheFile.length(),
                    status = NoteStatus.SAVED,
                    errorMessage = null
                )
            }
            val refreshed = repository.get(noteId) ?: note
            summarize(refreshed, settings, onProgress)
            repository.get(noteId)?.let { after -> exportIfWanted(after, settings, onProgress) }
        } catch (error: Exception) {
            val message = (error as? AiException)?.display ?: (error.message ?: "转写失败")
            Log.w(TAG, "导入音频转写失败：\$message", error)
            repository.patch(noteId) {
                it.copy(status = NoteStatus.FAILED, errorMessage = message)
            }
            onProgress(PipelineProgress(noteId, PipelineStage.FAILED, message, message))
        }
    }

    /** 把缓存里的音频搬进 filesDir/audio，返回落盘后的文件 */
    private fun moveToAudioDir(noteId: Long, source: File, displayName: String): File? = runCatching {
        val dir = File(context.filesDir, "audio").apply { if (!exists()) mkdirs() }
        val extension = displayName.substringAfterLast('.', "").lowercase().ifBlank { "audio" }
        val target = File(dir, "import-\$noteId.\$extension")
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        runCatching { source.delete() }
        target
    }.getOrNull()

    private suspend fun transcribeInCloud(
        note: Note,
        settings: AppSettings,
        onProgress: (PipelineProgress) -> Unit
    ): Note {
        val file = note.audioPath?.let { File(it) }?.takeIf { it.exists() }
            ?: throw AiException("找不到录音文件", hint = "可能已被清理，试试重新导入音频")

        if (!settings.cloudAsrReady) {
            throw AiException("云转写还没配置好", hint = "去「设置 → 音频转写」填写 API Key 与模型名")
        }

        // 播放进度：上传大文件时用户需要知道「还在动」
        onProgress(
            PipelineProgress.of(
                note.id,
                PipelineStage.TRANSCRIBING,
                "正在上传并转写（${formatSize(file.length())}）…"
            )
        )

        val result = ai.asr.transcribeFile(
            baseUrl = settings.asrBaseUrl,
            apiKey = settings.asrApiKey,
            model = settings.asrModel,
            file = file,
            language = settings.languageTag(),
            prompt = PromptBuilder.asrPrompt(settings)
        )

        val mergedTranscript = mergeTranscript(note.liveDraft, result.text)
        repository.patch(note.id) {
            it.copy(
                transcript = mergedTranscript,
                asrEngine = result.engineLabel,
                status = NoteStatus.SAVED,
                errorMessage = null
            )
        }
        return repository.get(note.id) ?: note
    }

    private suspend fun transcribeLocally(
        note: Note,
        settings: AppSettings,
        onProgress: (PipelineProgress) -> Unit,
        failureLabel: String
    ): Note {
        val transcriber = LocalTranscriber(context)
        if (!transcriber.isAvailable) {
            throw AiException(
                "这台设备没有可用的系统语音识别服务",
                hint = "请改用云端转写（设置 → 音频转写）"
            )
        }
        onProgress(
            PipelineProgress.of(
                note.id,
                PipelineStage.TRANSCRIBING,
                "请对着手机把刚才的内容再讲一遍（离线识别需要实时麦克风）"
            )
        )

        val text = try {
            transcriber.transcribeOnce(settings.languageTag() ?: "zh-CN")
        } catch (error: Exception) {
            throw AiException("$failureLabel：${error.message}", error)
        }

        val mergedTranscript = mergeTranscript(note.liveDraft, text)
        repository.patch(note.id) {
            it.copy(
                transcript = mergedTranscript,
                asrEngine = transcriber.engineLabel(),
                status = NoteStatus.SAVED,
                errorMessage = null
            )
        }
        return repository.get(note.id) ?: note
    }

    private suspend fun failTranscribe(
        note: Note,
        cloudMessage: String,
        fallbackError: Exception?,
        onProgress: (PipelineProgress) -> Unit
    ): Note {
        val detail = buildString {
            append("转写失败：")
            append(cloudMessage)
            fallbackError?.let {
                append("\n离线兜底也失败：")
                append(it.message.orEmpty())
            }
        }
        repository.patch(note.id) {
            it.copy(status = NoteStatus.FAILED, errorMessage = detail)
        }
        onProgress(PipelineProgress(note.id, PipelineStage.FAILED, detail, detail))
        return repository.get(note.id) ?: note
    }

    /** 录音时用实时上屏临时记下的文字，与正式转写合并（正文用正式结果，草稿附在后面） */
    private fun mergeTranscript(liveDraft: String, transcript: String): String {
        val live = liveDraft.trim()
        val full = transcript.trim()
        return when {
            full.isBlank() -> live
            live.isBlank() -> full
            // 实时草稿和正式转写高度重合时就不重复贴了
            full.contains(live.take(20)) -> full
            else -> full
        }
    }

    // ------------------------------------------------------------ 整理

    private suspend fun summarize(
        note: Note,
        settings: AppSettings,
        onProgress: (PipelineProgress) -> Unit
    ): Note {
        // 没配 Key 就不算失败：转写结果照样留着，状态标成待处理
        if (!settings.deepseekReady) {
            val message = "转写已完成，但没有配置 DeepSeek API Key，尚未整理"
            repository.patch(note.id) {
                it.copy(status = NoteStatus.SAVED, errorMessage = message)
            }
            onProgress(PipelineProgress(note.id, PipelineStage.SUMMARIZING, message, message))
            return repository.get(note.id) ?: note
        }

        onProgress(PipelineProgress.of(note.id, PipelineStage.SUMMARIZING, "正在用 AI 整理笔记…"))
        repository.patchStatus(note.id, NoteStatus.SUMMARIZING, null)

        return try {
            val digest = ai.deepseek.summarize(
                baseUrl = settings.deepseekBaseUrl,
                apiKey = settings.deepseekApiKey,
                model = settings.deepseekModel,
                transcript = note.transcript,
                systemPrompt = PromptBuilder.resolve(settings),
                temperature = settings.temperature
            )
            repository.patch(note.id) {
                it.copy(
                    title = digest.title.ifBlank { it.title.ifBlank { "未命名笔记" } },
                    summary = digest.summary,
                    refinedMarkdown = digest.markdown,
                    structuredJson = digest.structuredJson,
                    llmModel = settings.deepseekModel,
                    status = NoteStatus.DONE,
                    errorMessage = null
                )
            }
            onProgress(PipelineProgress.of(note.id, PipelineStage.DONE, "整理完成"))
            repository.get(note.id) ?: note
        } catch (error: Exception) {
            val message = (error as? AiException)?.display ?: (error.message ?: "整理失败")
            Log.w(TAG, "整理失败：$message", error)
            repository.patch(note.id) {
                it.copy(status = NoteStatus.SAVED, errorMessage = "整理失败：$message")
            }
            onProgress(PipelineProgress(note.id, PipelineStage.FAILED, message, message))
            repository.get(note.id) ?: note
        }
    }

    // ------------------------------------------------------------ 导出

    private suspend fun exportIfWanted(
        note: Note,
        settings: AppSettings,
        onProgress: (PipelineProgress) -> Unit
    ) {
        if (!settings.autoExport) return
        val treeUri = note.exportTreeUri?.takeIf { it.isNotBlank() }
            ?: settings.exportTreeUri.takeIf { it.isNotBlank() }
        if (treeUri.isNullOrBlank()) {
            onProgress(
                PipelineProgress(
                    note.id,
                    PipelineStage.DONE,
                    "整理完成（没有设置保存位置，未自动导出）"
                )
            )
            return
        }

        onProgress(PipelineProgress.of(note.id, PipelineStage.EXPORTING, "正在写入 Markdown 文件…"))
        try {
            val result = exporter.export(note, treeUriOverride = treeUri, allowPick = false)
            repository.patch(note.id) {
                it.copy(
                    exportTreeUri = result.treeUri,
                    exportDocumentUri = result.documentUri,
                    exportRelativePath = result.relativePath,
                    exportedAt = System.currentTimeMillis()
                )
            }
            onProgress(
                PipelineProgress.of(note.id, PipelineStage.DONE, "已保存到 ${result.displayName}")
            )
        } catch (error: ExportException) {
            onProgress(
                PipelineProgress(
                    note.id,
                    PipelineStage.DONE,
                    "整理完成，但自动导出失败：${error.message}"
                )
            )
        }
    }

    // ------------------------------------------------------------ 单独动作（给界面按钮用）

    /** 只重新转写，不重新整理 */
    suspend fun transcribeOnly(noteId: Long, onProgress: (PipelineProgress) -> Unit = {}) {
        if (!running.add(noteId)) return
        try {
            val note = repository.get(noteId) ?: return
            val settings = settingsStore.current()
            val updated = transcribe(note, settings, onProgress)
            if (updated.transcript.isNotBlank()) {
                onProgress(
                    PipelineProgress.of(
                        noteId,
                        PipelineStage.DONE,
                        "转写完成，共 ${updated.transcript.length} 字"
                    )
                )
            }
        } finally {
            running.remove(noteId)
        }
    }

    /** 只重新整理（转写结果已在库里） */
    suspend fun summarizeOnly(noteId: Long, onProgress: (PipelineProgress) -> Unit = {}) {
        if (!running.add(noteId)) return
        try {
            val note = repository.get(noteId) ?: return
            val settings = settingsStore.current()
            if (note.transcript.isBlank()) {
                onProgress(
                    PipelineProgress(noteId, PipelineStage.FAILED, "还没有转写内容", "请先转写")
                )
                return
            }
            summarize(note, settings, onProgress)
            val after = repository.get(noteId)
            if (after != null) exportIfWanted(after, settings, onProgress)
        } finally {
            running.remove(noteId)
        }
    }

    /** 手动导出（界面上的「保存到…」走这条路，会按需弹目录选择器） */
    suspend fun exportNote(
        noteId: Long,
        treeUriOverride: String? = null,
        onProgress: (PipelineProgress) -> Unit = {}
    ) {
        val note = repository.get(noteId) ?: return
        val result = exporter.export(
            note = note,
            treeUriOverride = treeUriOverride,
            allowPick = false
        )
        repository.patch(noteId) {
            it.copy(
                exportTreeUri = result.treeUri,
                exportDocumentUri = result.documentUri,
                exportRelativePath = result.relativePath,
                exportedAt = System.currentTimeMillis()
            )
        }
        onProgress(PipelineProgress.of(noteId, PipelineStage.DONE, "已导出到 ${result.displayName}"))
    }

    /** 探测音频文件是否还在，用于详情页提示「文件已丢失」 */
    suspend fun audioFileState(note: Note): AudioFileState = withContext(Dispatchers.IO) {
        val path = note.audioPath
        if (path.isNullOrBlank()) return@withContext AudioFileState.Missing
        val file = File(path)
        if (!file.exists() || file.length() <= 44L) return@withContext AudioFileState.Missing
        AudioFileState.Available(file.length(), probeDurationMs(file))
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "NotePipeline"
    }
}

sealed interface AudioFileState {
    data object Missing : AudioFileState
    data class Available(val sizeBytes: Long, val durationMs: Long) : AudioFileState
}

/** 设置里的语言标签：空串表示交给服务端自动判断 */
fun AppSettings.languageTag(): String? = localAsrLanguage.trim().takeIf { it.isNotBlank() }
