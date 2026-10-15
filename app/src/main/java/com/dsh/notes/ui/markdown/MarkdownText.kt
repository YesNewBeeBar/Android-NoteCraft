package com.dsh.notes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染器。
 *
 * 为什么自己写而不引第三方库（比如 Markwon / compose-markdown）：
 *  - 需求只要「笔记能读得舒服」，不需要完整 CommonMark；
 *  - 这些库要么基于 AndroidView、要么依赖链很长，在 Compose 里容易出兼容问题；
 *  - 自己写只有 200 行，行为完全可控，也不会因为库停更而卡住。
 *
 * 支持：标题 # ~ ######、无序/有序列表、待办 - [ ]、引用 >、围栏代码块 ```、
 *      分隔线 ---、以及行内的 **粗体**、*斜体*、`代码`、[链接](url)。
 */
object MarkdownParser {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val text: String, val indent: Int, val checked: Boolean?) : Block
        data class Ordered(val text: String, val indent: Int, val number: String) : Block
        data class Quote(val text: String) : Block
        data class Code(val language: String, val body: String) : Block
        data class Paragraph(val text: String) : Block
        data object Divider : Block
        data object Blank : Block
    }

    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val todoRegex = Regex("^\\[([ xX])]\\s+(.*)$")

    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = source.replace("\r\n", "\n").split("\n")
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            // 围栏代码块：整段吃掉，内部不再解析
            if (trimmed.startsWith("```")) {
                val language = trimmed.removePrefix("```").trim()
                val body = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    body.appendLine(lines[index])
                    index++
                }
                index++ // 跳过收尾的 ```
                blocks += Block.Code(language, body.toString().trimEnd())
                continue
            }

            when {
                trimmed.isEmpty() -> blocks += Block.Blank

                trimmed == "---" || trimmed == "***" || trimmed == "___" -> blocks += Block.Divider

                headingRegex.matches(trimmed) -> {
                    val match = headingRegex.find(trimmed)!!
                    blocks += Block.Heading(
                        level = match.groupValues[1].length,
                        text = match.groupValues[2].trim()
                    )
                }

                trimmed.startsWith(">") -> blocks += Block.Quote(
                    trimmed.removePrefix(">").trim()
                )

                bulletRegex.matches(line) -> {
                    val match = bulletRegex.find(line)!!
                    val indent = match.groupValues[1].length / 2
                    val content = match.groupValues[2]
                    val todo = todoRegex.find(content)
                    blocks += if (todo != null) {
                        Block.Bullet(
                            text = todo.groupValues[2],
                            indent = indent,
                            checked = todo.groupValues[1].equals("x", ignoreCase = true)
                        )
                    } else {
                        Block.Bullet(content, indent, null)
                    }
                }

                orderedRegex.matches(line) -> {
                    val match = orderedRegex.find(line)!!
                    blocks += Block.Ordered(
                        text = match.groupValues[3],
                        indent = match.groupValues[1].length / 2,
                        number = match.groupValues[2]
                    )
                }

                else -> blocks += Block.Paragraph(trimmed)
            }
            index++
        }
        return blocks
    }

    /**
     * 行内解析：**粗体**、*斜体*、`代码`、~~删除线~~、[文字](链接)。
     * 用「先找标记、再递归处理内部」的朴素做法，够用且没有回溯风险。
     */
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        appendInline(this, text)
    }

    private fun appendInline(builder: AnnotatedString.Builder, text: String) {
        var index = 0
        while (index < text.length) {
            val rest = text.substring(index)
            // 命中的既要有正则结果，也要有它对应的样式，所以成对带出来
            val found = INLINE_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.regex.find(rest)?.takeIf { it.range.first == 0 }?.let { pattern to it }
            }
            if (found == null) {
                // 没有更多标记：把剩下的普通文本一次性追加，避免逐字符 append
                builder.append(text.substring(index))
                return
            }
            val (pattern, match) = found
            builder.withStyle(pattern.style) {
                appendInline(this, match.groupValues[1])
            }
            index += match.value.length
        }
    }

    private class InlinePattern(
        val regex: Regex,
        val style: SpanStyle
    )

    private val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    private val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    private val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)

    /** 顺序重要：先匹配长的标记（** 要在 * 前面） */
    private val INLINE_PATTERNS: List<InlinePattern> by lazy {
        val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)
        listOf(
            InlinePattern(Regex("^\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL), boldStyle),
            InlinePattern(Regex("^__(.+?)__", RegexOption.DOT_MATCHES_ALL), boldStyle),
            InlinePattern(Regex("^`([^`]+)`"), codeStyle),
            InlinePattern(Regex("^~~(.+?)~~", RegexOption.DOT_MATCHES_ALL), strikeStyle),
            InlinePattern(Regex("^\\*(.+?)\\*", RegexOption.DOT_MATCHES_ALL), italicStyle),
            InlinePattern(Regex("^_(.+?)_", RegexOption.DOT_MATCHES_ALL), italicStyle),
            InlinePattern(Regex("^\\[([^]]+)]\\(([^)]+)\\)"), boldStyle)
        )
    }

}

/** 把 Markdown 源码渲染成一列 Compose 组件 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownParser.Block.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = MarkdownParser.inline(block.text),
                        style = style,
                        color = colors.onSurface,
                        modifier = Modifier.padding(
                            top = if (block.level <= 2) 10.dp else 6.dp,
                            bottom = 2.dp
                        )
                    )
                }

                is MarkdownParser.Block.Bullet -> MarkdownRow(
                    marker = when (block.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> "•"
                    },
                    indent = block.indent,
                    text = block.text,
                )

                is MarkdownParser.Block.Ordered -> MarkdownRow(
                    marker = "${block.number}.",
                    indent = block.indent,
                    text = block.text,
                )

                is MarkdownParser.Block.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp, end = 8.dp)
                            .width(3.dp)
                            .background(colors.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                            .padding(top = 14.dp)
                    )
                    Text(
                        text = MarkdownParser.inline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }

                is MarkdownParser.Block.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp)
                ) {
                    Text(
                        text = block.body,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurface,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }

                is MarkdownParser.Block.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = colors.outlineVariant
                )

                is MarkdownParser.Block.Paragraph -> Text(
                    text = MarkdownParser.inline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                MarkdownParser.Block.Blank -> Box(Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun MarkdownRow(
    marker: String,
    indent: Int,
    text: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 14).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = MarkdownParser.inline(text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
