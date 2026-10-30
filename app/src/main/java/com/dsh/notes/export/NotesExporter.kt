package com.dsh.notes.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dsh.notes.data.Note
import com.dsh.notes.data.NoteRepository
import com.dsh.notes.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 导出结果：最终落在哪里、文件名是什么 */
data class ExportResult(
    val treeUri: String,
    val documentUri: String,
    val relativePath: String,
    val displayName: String
)

/**
 * 把笔记写成 .md 文件。
 *
 * 存储方案用 SAF（`ACTION_OPEN_DOCUMENT_TREE`）而不是 `MANAGE_EXTERNAL_STORAGE`：
 *  - 那条「所有文件访问」权限在 Google Play 会被直接拒审，
 *    而且对自用也没必要 —— 用户亲手指定一个目录，之后一直能写；
 *  - SAF 在 Android 8 到 15 上行为一致，不用为分区存储写两套分支。
 *
 * 「选择存放位置」因此落在两个层级上：
 *  1) 设置里的全局默认目录（导一次，以后都用它）；
 *  2) 导出时可以为这一条笔记临时换一个目录，或永久改掉这条笔记的目录。
 */
class NotesExporter(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val repository: NoteRepository
) {

    /**
     * 导出，必要时挑目录。
     *
     * @param treeUriOverride 这条笔记单独指定的目录；为空则用全局默认目录
     * @param allowPick       没有可用目录时，是否弹系统的目录选择器
     */
    suspend fun export(
        note: Note,
        treeUriOverride: String? = null,
        allowPick: Boolean = true,
        includeTranscript: Boolean = true
    ): ExportResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val treeUri = treeUriOverride?.takeIf { it.isNotBlank() }
            ?: settings.exportTreeUri.takeIf { it.isNotBlank() }

        if (treeUri.isNullOrBlank()) {
            if (!allowPick) throw ExportException("还没有选择保存位置")
            throw ExportException("需要先选择保存位置", needsPick = true)
        }

        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw ExportException("保存位置已经失效（可能目录被删除或权限被回收），请重新选择")

        if (!root.canWrite()) {
            throw ExportException("这个目录没有写入权限，请重新选择")
        }

        // 在选中目录下再建一层子目录，避免把用户的目录塞满散落的 md。
        // 笔记如果归了文件夹，就再往下沉一层：NoteCraft/英语/。
        val folderName = note.folderId?.let { id ->
            runCatching { repository.folderName(id) }.getOrNull()
        }
        val subDir = buildString {
            append(settings.exportSubDir.trim().trim('/'))
            if (!folderName.isNullOrBlank()) {
                if (isNotEmpty()) append('/')
                append(FileNameTemplate.sanitize(folderName))
            }
        }
        val target = resolveTargetDir(root, subDir)

        val baseName = FileNameTemplate.build(settings.fileNamePattern, note)
        var displayName = "$baseName.md"
        var effectiveNote = note

        /*
         * 双向同步的落点全在这里。
         *
         * 文件末尾埋了正文的哈希（见 [ObsidianSync]），于是能分出三种情况：
         *  · 哈希对得上 —— 没人动过，原地覆盖；
         *  · 对不上     —— 在 Obsidian 里改过：先把改动读回内置笔记，再另存一个新文件，
         *                  原来那份原封不动（用户要求保留）；
         *  · 没有哈希   —— 旧版本导出的文件，不认识基线，只另存不回读，
         *                  免得把后来整理好的正文回退成旧内容。
         */
        target.findFile(displayName)?.takeIf { it.isFile }?.let { existing ->
            val old = readText(existing.uri)
            when {
                old == null -> Unit
                ObsidianSync.isUntouched(old) -> existing.delete()
                else -> {
                    if (ObsidianSync.split(old).second != null) {
                        val parsed = ObsidianSync.parse(old, note.title)
                        runCatching {
                            repository.patch(note.id) { current ->
                                current.copy(
                                    title = parsed.title.ifBlank { current.title },
                                    summary = parsed.summary.ifBlank { current.summary },
                                    refinedMarkdown = parsed.refinedMarkdown
                                        .ifBlank { current.refinedMarkdown }
                                )
                            }
                        }
                        effectiveNote = runCatching { repository.get(note.id) }.getOrNull() ?: note
                    }
                    displayName = nextFreeName(target, baseName)
                }
            }
        }

        // Obsidian 那一层：正文里的笔记标题变双链、长引用折叠、末尾挂相关笔记。
        // 全是本地计算，不额外调 AI；笔记库为空时自动退化成原文。
        val allNotes = runCatching { repository.listAllOnce() }.getOrDefault(emptyList())
        val related = runCatching { RelatedNotes.rank(effectiveNote, allNotes) }.getOrDefault(emptyList())
        // 已经把 .md 放进这个目录的笔记，标题也算双链候选 ——
        // 但只认这一层，不递归进子文件夹（用户明确说不做跨文件夹扫描）
        val siblingTitles = runCatching {
            target.listFiles()
                .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                .mapNotNull { it.name?.removeSuffix(".md")?.removeSuffix(".MD") }
        }.getOrDefault(emptyList())
        val content = ObsidianBuilder.enhance(
            content = MarkdownBuilder.buildWithAudioHint(effectiveNote, includeTranscript),
            selfTitle = effectiveNote.title,
            otherTitles = allNotes.map { it.title }.filter { it.isNotBlank() } + siblingTitles,
            relatedTitles = related.map { it.title }
        )
        // 贴上哈希锚点，下次导出才知道这份文件有没有被人改过
        val finalContent = ObsidianSync.withFooter(content)

        val document = target.createFile("text/markdown", displayName)
            ?: target.createFile("text/plain", displayName)
            ?: throw ExportException("在「${target.name}」里创建文件失败")

        context.contentResolver.openOutputStream(document.uri)?.use { output ->
            output.write(finalContent.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw ExportException("写入文件失败")

        ExportResult(
            treeUri = treeUri,
            documentUri = document.uri.toString(),
            relativePath = buildRelativePath(subDir, displayName),
            displayName = displayName
        )
    }

    /**
     * 把这条笔记的录音原文件复制到用户选的目录。
     *
     * 和 .md 不同，音频是原始素材：复制出去就完事，不写回笔记表。
     * 文件名沿用同一套模板（[FileNameTemplate]），只是扩展名换成音频自己的。
     */
    suspend fun exportAudio(note: Note, treeUri: String): ExportResult =
        withContext(Dispatchers.IO) {
            val source = note.audioPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: throw ExportException("这条笔记没有音频文件")

            if (!source.exists()) {
                throw ExportException("音频文件已不在（可能被清理过），转写文字仍然保留")
            }

            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: throw ExportException("保存位置已经失效（可能目录被删除或权限被回收），请重新选择")

            if (!root.canWrite()) {
                throw ExportException("这个目录没有写入权限，请重新选择")
            }

            val settings = settingsStore.current()
            val target = resolveTargetDir(root, settings.exportSubDir)

            val extension = source.extension.ifBlank { "bin" }
            val baseName = FileNameTemplate.build(settings.fileNamePattern, note)
            val displayName = "$baseName.$extension"

            // 同名先删掉，重复导出不应该堆出 (1)(2)
            target.findFile(displayName)?.takeIf { it.isFile }?.delete()

            val document = target.createFile(audioMimeType(extension), displayName)
                ?: throw ExportException("在「${target.name}」里创建文件失败")

            context.contentResolver.openOutputStream(document.uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw ExportException("写入音频失败")

            ExportResult(
                treeUri = treeUri,
                documentUri = document.uri.toString(),
                relativePath = buildRelativePath(settings.exportSubDir, displayName),
                displayName = displayName
            )
        }

    /** 删除上次导出的文件（重新导出前清场） */
    suspend fun deleteExported(note: Note) = withContext(Dispatchers.IO) {
        val uri = note.exportDocumentUri ?: return@withContext
        runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete()
        }.onFailure { Log.w(TAG, "删除旧导出文件失败", it) }
        Unit
    }

    /** 探测一个目录现在还能不能写 */
    fun canWrite(treeUri: String): Boolean = runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.canWrite() == true
    }.getOrDefault(false)

    /** 这个目录在手机里的可读路径，用于界面上展示。拿不到就退回 URI 的尾巴 */
    fun describeLocation(treeUri: String): String {
        if (treeUri.isBlank()) return "未设置"
        val doc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
        if (doc == null) return treeUri.substringAfterLast('%').take(40)
        val parts = generateSequence(doc) { it.parentFile }.map { it.name.orEmpty() }
            .takeWhile { it.isNotBlank() }
            .toList()
            .asReversed()
        // 过滤掉 DocumentFile 名字里自带的 "primary:" 这类前缀
        val readable = parts.map { it.substringAfter(':') }
        return if (readable.isEmpty()) doc.name.orEmpty() else readable.joinToString("/")
    }

    /** 交给系统的目录选择器；调用方负责拿返回值去 takePersistableUriPermission */
    fun buildPickDirectoryIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

    /**
     * 长期持有用户选中目录的读写权限。
     * 不调用这个的话，App 重启后权限就没了 —— 这是 SAF 最常见的坑。
     */
    fun persistPermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { Log.w(TAG, "持久化目录权限失败", it) }
    }

    private fun resolveTargetDir(root: DocumentFile, subDir: String): DocumentFile {
        val name = subDir.trim().trim('/')
        if (name.isBlank()) return root
        var current = root
        name.split('/').filter { it.isNotBlank() }.forEach { segment ->
            val safe = FileNameTemplate.sanitize(segment).ifBlank { "NoteCraft" }
            current = current.findFile(safe)?.takeIf { it.isDirectory }
                ?: current.createDirectory(safe)
                ?: return@forEach
        }
        return current
    }

    private fun buildRelativePath(subDir: String, fileName: String): String {
        val clean = subDir.trim().trim('/')
        return if (clean.isBlank()) fileName else "$clean/$fileName"
    }

    /** 读回已导出的文件；读不到就返回 null（当作「没法判断」） */
    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    /** 原文件要保留时，另找一个没被占用的名字 */
    private fun nextFreeName(dir: DocumentFile, baseName: String): String {
        for (i in 2..99) {
            val candidate = "$baseName ($i).md"
            if (dir.findFile(candidate) == null) return candidate
        }
        return "$baseName (${System.currentTimeMillis()}).md"
    }

    /** 查看这条笔记导出的文件还在不在 */
    fun exportedFileExists(note: Note): Boolean {
        val uri = note.exportDocumentUri ?: return false
        return runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "NotesExporter"

        /** 音频扩展名 → MIME，交给 SAF 建文件用 */
        fun audioMimeType(extension: String): String = when (extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "3gp" -> "audio/3gpp"
            "amr" -> "audio/amr"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
    }
}

/** 导出过程中的可预期错误；[needsPick] 表示「去让用户选个目录就能继续」 */
class ExportException(
    message: String,
    val needsPick: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)
