package com.dsh.notes.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dsh.notes.data.Note
import com.dsh.notes.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val settingsStore: SettingsStore
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

        // 在选中目录下再建一层子目录，避免把用户的目录塞满散落的 md
        val target = resolveTargetDir(root, settings.exportSubDir)

        val baseName = FileNameTemplate.build(settings.fileNamePattern, note)
        val displayName = "$baseName.md"
        val content = MarkdownBuilder.buildWithAudioHint(note, includeTranscript)

        // 同名文件直接覆盖：同一条笔记重复导出不应该堆出一串 (1)(2)
        target.findFile(displayName)?.takeIf { it.isFile }?.delete()

        val document = target.createFile("text/markdown", displayName)
            ?: target.createFile("text/plain", displayName)
            ?: throw ExportException("在「${target.name}」里创建文件失败")

        context.contentResolver.openOutputStream(document.uri)?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw ExportException("写入文件失败")

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

    /** 查看这条笔记导出的文件还在不在 */
    fun exportedFileExists(note: Note): Boolean {
        val uri = note.exportDocumentUri ?: return false
        return runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "NotesExporter"
    }
}

/** 导出过程中的可预期错误；[needsPick] 表示「去让用户选个目录就能继续」 */
class ExportException(
    message: String,
    val needsPick: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)
