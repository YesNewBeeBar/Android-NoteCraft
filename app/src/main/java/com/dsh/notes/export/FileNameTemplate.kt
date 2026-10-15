package com.dsh.notes.export

import com.dsh.notes.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件名模板。
 *
 * 支持占位符：{date} {time} {datetime} {title} {id}
 * 默认模板 `{date}-{time}-{title}` → `2026-04-05-1430-周会纪要.md`
 *
 * 时间格式化刻意用 Locale.CHINA，避免某些系统语言下数字变成阿拉伯-印度文。
 */
object FileNameTemplate {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFormat = SimpleDateFormat("HHmm", Locale.CHINA)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.CHINA)

    fun build(pattern: String, note: Note): String {
        val created = Date(note.createdAt)
        val title = sanitize(note.title.ifBlank { "笔记" })
        val effectivePattern = pattern.ifBlank { "{date}-{time}-{title}" }

        val name = effectivePattern
            .replace("{date}", dateFormat.format(created))
            .replace("{time}", timeFormat.format(created))
            .replace("{datetime}", dateTimeFormat.format(created))
            .replace("{title}", title)
            .replace("{id}", note.id.toString())

        val cleaned = sanitize(name)
        return if (cleaned.isBlank()) "note-${note.id}" else cleaned.take(80)
    }

    /** 去掉文件名里非法的字符；中文、数字、字母、横线、下划线保留 */
    fun sanitize(raw: String): String =
        raw.replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '-', ' ')
}
