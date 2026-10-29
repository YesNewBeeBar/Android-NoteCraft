package com.dsh.notes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dsh.notes.util.copyToClipboard

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
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data class Paragraph(val text: String) : Block
        data object Divider : Block
        data object Blank : Block
    }

    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val todoRegex = Regex("^\\[([ xX])]\\s+(.*)$")

    /** 表格的分隔行：|---|:--:|---| */
    private val tableSeparator = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

    private fun isTableRow(line: String): Boolean = line.trim().startsWith("|")

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }


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

            // 表格：当前行是 |...| 且下一行是分隔行
            if (isTableRow(trimmed) &&
                index + 1 < lines.size &&
                tableSeparator.matches(lines[index + 1].trim())
            ) {
                val header = splitRow(trimmed)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && isTableRow(lines[index])) {
                    rows += splitRow(lines[index])
                    index++
                }
                blocks += Block.Table(header, rows)
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
    /** true 时整段可选词复制（笔记详情、大肥鱼回答用） */
    selectable: Boolean = false,
    /** 代码块「下载」交给调用方决定存哪 */
    onDownloadSnippet: ((language: String, body: String) -> Unit)? = null,
    /** 选中一段文字后点「AI 提问」把文字交给调用方 */
    onAskSelection: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    if (selectable) {
        SelectionActionHost(onAsk = onAskSelection) {
            SelectionContainer(modifier = modifier) {
                MarkdownBlocks(blocks = blocks, onDownloadSnippet = onDownloadSnippet)
            }
        }
    } else {
        MarkdownBlocks(blocks = blocks, onDownloadSnippet = onDownloadSnippet, modifier = modifier)
    }
}

@Composable
private fun MarkdownBlocks(
    blocks: List<MarkdownParser.Block>,
    onDownloadSnippet: ((language: String, body: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
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

                is MarkdownParser.Block.Code -> CodeBlock(
                    language = block.language,
                    body = block.body,
                    onDownload = onDownloadSnippet
                )

                is MarkdownParser.Block.Table -> TableBlock(block)

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
) {    Row(
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

/** 代码块：头部条（语言 + 复制 + 下载）+ 轻量高亮 */
@Composable
private fun CodeBlock(
    language: String,
    body: String,
    onDownload: ((String, String) -> Unit)?
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val highlighted = remember(body, colors) { highlightCode(body, colors) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant.copy(alpha = 0.75f))
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.ifBlank { "text" },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { copyToClipboard(context, "代码", body) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("复制", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = { onDownload?.invoke(language, body) },
                enabled = onDownload != null,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("下载", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            text = highlighted,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = colors.onSurface,
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(12.dp)
        )
    }
}

/** 表格：整表横向滚动，外边框 + 行分隔线 + 表头底色 */
@Composable
private fun TableBlock(block: MarkdownParser.Block.Table) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val columnCount = maxOf(
        block.header.size,
        block.rows.maxOfOrNull { it.size } ?: 0
    )
    if (columnCount == 0) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp))
        ) {
            TableCells(block.header, columnCount, header = true)
            block.rows.forEachIndexed { index, row ->
                HorizontalDivider(color = colors.outlineVariant)
                TableCells(row, columnCount, header = false, striped = index % 2 == 1)
            }
        }
    }
}

@Composable
private fun TableCells(
    cells: List<String>,
    columnCount: Int,
    header: Boolean,
    striped: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.background(
            when {
                header -> colors.surfaceVariant.copy(alpha = 0.7f)
                striped -> colors.surfaceVariant.copy(alpha = 0.22f)
                else -> Color.Transparent
            }
        )
    ) {
        for (index in 0 until columnCount) {
            val text = cells.getOrElse(index) { "" }
            Text(
                text = MarkdownParser.inline(text),
                style = if (header) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = colors.onSurface,
                fontWeight = if (header) FontWeight.SemiBold else null,
                modifier = Modifier
                    .widthIn(min = 92.dp, max = 220.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * 极简高亮：注释 / 字符串 / 数字 / 关键字。
 * 不做语法树，够读就行；颜色全部取自主题，深浅色都跟着变。
 */
private fun highlightCode(code: String, colors: androidx.compose.material3.ColorScheme): AnnotatedString {
    val keywordStyle = SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)
    val stringStyle = SpanStyle(color = colors.tertiary)
    val commentStyle = SpanStyle(
        color = colors.onSurfaceVariant.copy(alpha = 0.75f),
        fontStyle = FontStyle.Italic
    )
    val numberStyle = SpanStyle(color = colors.secondary)

    val pattern = Regex(
        "(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/)" +
            "|(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')" +
            "|\\b(\\d+(?:\\.\\d+)?)\\b" +
            "|\\b($KEYWORDS)\\b"
    )

    return buildAnnotatedString {
        var cursor = 0
        pattern.findAll(code).forEach { match ->
            if (match.range.first > cursor) {
                append(code.substring(cursor, match.range.first))
            }
            val style = when {
                match.groups[1] != null -> commentStyle
                match.groups[2] != null -> stringStyle
                match.groups[3] != null -> numberStyle
                else -> keywordStyle
            }
            withStyle(style) { append(match.value) }
            cursor = match.range.last + 1
        }
        if (cursor < code.length) append(code.substring(cursor))
    }
}

private const val KEYWORDS =
    "fun|val|var|if|else|for|while|return|class|object|interface|import|package|true|false|null|" +
        "this|when|is|in|new|public|private|protected|static|final|void|int|long|float|double|" +
        "boolean|def|let|const|function|async|await|try|catch|finally|throw|break|continue|" +
        "switch|case|default|struct|enum|impl|fn|mut|pub|use|mod"
