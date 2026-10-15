package com.dsh.notes.export

import com.dsh.notes.data.Note
import com.dsh.notes.data.NoteStatus
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 把一条笔记渲染成最终的 Markdown 文件内容。
 *
 * 顶部用 YAML front matter 存元数据（时间、时长、模型、标签），
 * 这样这份 .md 丢进 Obsidian / Logseq 之类工具时还能被识别出属性，
 * 不是一坨死文本。
 */
object MarkdownBuilder {

    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun build(note: Note, includeTranscript: Boolean = true): String = buildString {
        appendLine("---")
        appendLine("title: ${yaml(note.title.ifBlank { "未命名笔记" })}")
        appendLine("created: ${dateTimeFormat.format(Date(note.createdAt))}")
        if (note.audioDurationMs > 0) {
            appendLine("duration: ${formatDuration(note.audioDurationMs)}")
        }
        note.asrEngine?.takeIf { it.isNotBlank() }?.let { appendLine("asr: ${yaml(it)}") }
        note.llmModel?.takeIf { it.isNotBlank() }?.let { appendLine("model: ${yaml(it)}") }
        note.tags().takeIf { it.isNotEmpty() }?.let { tags ->
            appendLine("tags: [${tags.joinToString(", ") { yaml(it) }}]")
        }
        appendLine("---")
        appendLine()

        appendLine("# ${note.title.ifBlank { "未命名笔记" }}")
        appendLine()

        if (note.summary.isNotBlank()) {
            appendLine("> ${note.summary}")
            appendLine()
        }

        val keyPoints = note.keyPoints()
        if (keyPoints.isNotEmpty()) {
            appendLine("## 要点")
            keyPoints.forEach { appendLine("- $it") }
            appendLine()
        }

        val actionItems = note.actionItems()
        if (actionItems.isNotEmpty()) {
            appendLine("## 待办")
            actionItems.forEach { appendLine("- [ ] $it") }
            appendLine()
        }

        val body = note.refinedMarkdown.ifBlank { note.transcript }
        if (body.isNotBlank()) {
            appendLine("## 正文")
            appendLine()
            appendLine(body.trim())
            appendLine()
        }

        if (includeTranscript && note.transcript.isNotBlank() && note.refinedMarkdown.isNotBlank()) {
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 原始转写")
            appendLine()
            appendLine("> 下面是没有经过 AI 整理的原始文字，保留下来便于核对。")
            appendLine()
            appendLine(note.transcript.trim())
            appendLine()
        }

        if (note.status == NoteStatus.FAILED && !note.errorMessage.isNullOrBlank()) {
            appendLine()
            appendLine("> ⚠️ 处理未完成：${note.errorMessage}")
        }
    }

    /** 导出文件名里带上音频路径备注，方便日后找回录音 */
    fun buildWithAudioHint(note: Note, includeTranscript: Boolean): String {
        val base = build(note, includeTranscript)
        val audio = note.audioPath
        if (audio.isNullOrBlank()) return base
        return base.trimEnd() + "\n\n<!-- 音频文件：$audio -->\n"
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** YAML 里含冒号、引号的值要加引号 */
    private fun yaml(value: String): String {
        val needsQuote = value.any { it in ":#[]{}|>&*!%@`\"" } || value != value.trim()
        return if (needsQuote) "\"${value.replace("\"", "\\\"")}\"" else value
    }
}
