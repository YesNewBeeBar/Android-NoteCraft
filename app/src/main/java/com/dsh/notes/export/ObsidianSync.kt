package com.dsh.notes.export

import java.security.MessageDigest

/**
 * 导出文件的「双向同步」判据。
 *
 * 文件末尾埋一行 HTML 注释：
 *
 * ```
 * <!-- notecraft:sha=3f9a... -->
 * ```
 *
 * 它是**正文（不含这一行）的哈希**。有了它，不建数据库字段也能判断：
 *
 *  · 文件正文的哈希 == 埋的哈希 → 文件还是我们上次写的样子，没被改过 → 直接覆盖
 *  · 不相等 → 在 Obsidian 里被改过 → 把改动读回内置笔记，并且**另存为新文件**，
 *    原来的那份一个字节都不动（用户明说过：手动改过的要保留原文件）
 *  · 完全没有这一行 → 是旧版本导出的文件，不知道基线，只另存、不回读，
 *    否则会把后来整理好的正文回退成旧内容
 */
object ObsidianSync {

    private const val MARK_PREFIX = "<!-- notecraft:sha="
    private const val MARK_SUFFIX = " -->"

    /** 把哈希锚点贴到正文末尾 */
    fun withFooter(body: String): String {
        val trimmed = body.trimEnd()
        return "$trimmed\n\n$MARK_PREFIX${sha(trimmed)}$MARK_SUFFIX\n"
    }

    /** 拆出正文和锚点里的哈希；没有锚点时 hash 为 null */
    fun split(content: String): Pair<String, String?> {
        val index = content.lastIndexOf(MARK_PREFIX)
        if (index < 0) return content to null
        val end = content.indexOf(MARK_SUFFIX, index)
        if (end < 0) return content to null
        val hash = content.substring(index + MARK_PREFIX.length, end).trim()
        val body = content.substring(0, index).trimEnd()
        return body to hash
    }

    fun isUntouched(content: String): Boolean {
        val (body, hash) = split(content)
        return hash != null && hash == sha(body)
    }

    fun sha(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 从我们导出的格式里把标题、摘要、正文读回来 */
    fun parse(content: String, fallbackTitle: String): ParsedNote {
        val body = split(content).first
        val lines = body.lines()

        var title = fallbackTitle
        var summary = ""
        val refined = StringBuilder()
        var section = ""

        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("# ") && title == fallbackTitle ->
                    title = line.removePrefix("# ").trim()

                line.startsWith("## ") -> section = line.removePrefix("## ").trim()

                // 摘要：H1 后面那一段普通引用（callout 头不算）
                section.isBlank() && line.startsWith("> ") && !line.startsWith("> [") && summary.isBlank() ->
                    summary = line.removePrefix("> ").trim()

                // 正文：## 正文 到下一个 ## 或分隔线为止
                section == "正文" && line == "---" -> section = "done"

                section == "正文" -> refined.append(line).append('\n')
            }
        }

        return ParsedNote(
            title = title,
            summary = summary,
            refinedMarkdown = refined.toString().trim('\n')
        )
    }
}

data class ParsedNote(
    val title: String,
    val summary: String,
    val refinedMarkdown: String
)
