package com.dsh.notes.export

/**
 * 把普通 Markdown 加工成「Obsidian 里更好用」的版本。
 *
 * 三件事，都是在已经生成好的文本上做后处理，不碰 AI 输出的内容本身：
 *  1. 双链：正文里提到别的笔记标题，替换成 `[[标题]]`，Obsidian 里可点
 *  2. 引用折叠：连续三行以上的引用块加一个默认折叠的 callout 头
 *  3. 相关笔记：末尾追加一段 `[[双链]]` 列表（相似度由 [RelatedNotes] 算）
 */
object ObsidianBuilder {

    private const val RELATED_HEADING = "## 相关笔记"

    /** 少于这个长度的标题不自动加双链，避免「笔记」「复习」这类词被大量误替换 */
    private const val MIN_TITLE_LENGTH = 4

    fun enhance(
        content: String,
        selfTitle: String,
        otherTitles: List<String>,
        relatedTitles: List<String>
    ): String {
        var text = foldQuotes(content)
        text = injectWikilinks(text, selfTitle, otherTitles)
        if (relatedTitles.isNotEmpty()) {
            text = text.trimEnd() + "\n\n" + RELATED_HEADING + "\n\n" +
                relatedTitles.joinToString("\n") { "- [[${it.trim()}]]" } + "\n"
        }
        return text
    }

    /**
     * 连续 3 行以上的引用块，首行前面插一个 `> [!quote]-` 折叠头。
     * 单行引用（比如摘要、转写说明）保持原样，不然整篇都是折叠块。
     */
    private fun foldQuotes(text: String): String {
        val lines = text.lines()
        val out = ArrayList<String>(lines.size + 8)
        var i = 0
        while (i < lines.size) {
            if (isQuoteLine(lines[i])) {
                var j = i
                while (j < lines.size && isQuoteLine(lines[j])) j++
                val runLength = j - i
                if (runLength >= 3) {
                    out += "> [!quote]- 原文摘录"
                }
                for (k in i until j) out += lines[k]
                i = j
            } else {
                out += lines[i]
                i++
            }
        }
        return out.joinToString("\n")
    }

    private fun isQuoteLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith(">") && t.length > 1
    }

    /** 已经包在 [[ ]] 里的不再包，代码块里完全不碰 */
    private fun injectWikilinks(text: String, selfTitle: String, titles: List<String>): String {
        val self = selfTitle.trim()
        val candidates = titles.map { it.trim() }
            .filter { it.length >= MIN_TITLE_LENGTH && it != self }
            .distinct()
            .sortedByDescending { it.length }
        if (candidates.isEmpty()) return text

        val sb = StringBuilder(text.length + 256)
        var inFence = false
        text.lines().forEach { line ->
            val trimmed = line.trimStart()
            val fence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (fence) {
                inFence = !inFence
                sb.append(line).append('\n')
            } else if (inFence) {
                sb.append(line).append('\n')
            } else {
                var replaced = line
                candidates.forEach { title ->
                    val pattern = Regex("(?<!\\[\\[)" + Regex.escape(title) + "(?!\\]\\])")
                    replaced = pattern.replace(replaced) { m -> "[[${m.value}]]" }
                }
                sb.append(replaced).append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
