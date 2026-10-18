package com.dsh.notes.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.dsh.notes.data.Note
import com.dsh.notes.data.actionItems
import java.io.File

/**
 * 把一条笔记分享给别的 App（微信、QQ、邮件…）。
 *
 * 走系统原生的 ACTION_SEND + 选择器，不接任何第三方 SDK，手机上装了谁就列谁。
 *
 * 分享出去的是**一个 .md 文件**（不是一大段文字）：对方存下来直接就能用，
 * 粘进聊天框也不会被平台截断。同时附上纯文本兜底 —— 少数接收方只读 EXTRA_TEXT。
 */
object NoteSharing {

    fun shareText(note: Note): String = buildString {
        appendLine("# ${note.title.ifBlank { "未命名笔记" }}")
        if (note.summary.isNotBlank()) {
            appendLine()
            appendLine("> ${note.summary}")
        }
        val body = note.displayBody.trim()
        if (body.isNotBlank()) {
            appendLine()
            appendLine(body)
        }
    }.trim()

    /** 摘要 + 待办。开会场景最常要的就是这两块，单独摘出来方便复制 */
    fun summaryAndTodo(note: Note): String {
        val todos = note.actionItems()
        return buildString {
            append(note.title.ifBlank { "未命名笔记" })
            if (note.summary.isNotBlank()) {
                appendLine()
                appendLine()
                append("摘要：${note.summary}")
            }
            if (todos.isNotEmpty()) {
                appendLine()
                appendLine()
                append("待办：")
                todos.forEach { todo ->
                    appendLine()
                    append("- [ ] $todo")
                }
            }
            if (note.summary.isBlank() && todos.isEmpty()) {
                appendLine()
                append("（这条笔记还没有摘要和待办）")
            }
        }
    }

    /**
     * 分享成 .md 文件。
     * @return 失败时返回原因，成功返回 null
     */
    fun shareAsFile(context: Context, note: Note): String? = runCatching {
        val dir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        // 只留这一份，免得缓存目录越攒越多
        dir.listFiles()?.forEach { it.delete() }

        val file = File(dir, "${safeFileName(note)}.md")
        file.writeText(shareText(note), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, note.title.ifBlank { "未命名笔记" })
            putExtra(Intent.EXTRA_TEXT, shareText(note))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
        }

        context.startActivity(
            Intent.createChooser(send, "分享笔记").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        null
    }.getOrElse { it.message ?: "分享失败" }

    /** 文件名里不能有路径分隔符之类；顺便限长，免得过长被拒 */
    private fun safeFileName(note: Note): String {
        val base = note.title.ifBlank { "NoteCraft-${note.id}" }
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .take(40)
        return base.ifBlank { "NoteCraft-${note.id}" }
    }
}
