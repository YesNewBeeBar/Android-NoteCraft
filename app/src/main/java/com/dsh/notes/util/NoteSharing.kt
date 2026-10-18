package com.dsh.notes.util

import android.content.Context
import android.content.Intent
import com.dsh.notes.data.Note

/**
 * 把一条笔记分享给别的 App（微信、QQ、邮件…）。
 *
 * 用系统原生的 ACTION_SEND + 选择器：不需要任何第三方 SDK，
 * 手机上装了谁就列谁。分享出去的是纯文本，接收方粘出来就是能读的 Markdown。
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

    fun share(context: Context, note: Note) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title.ifBlank { "未命名笔记" })
            putExtra(Intent.EXTRA_TEXT, shareText(note))
        }
        // 用系统选择器而不是指定包名：用户装了什么就分享到什么
        val chooser = Intent.createChooser(send, "分享笔记").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }
}
