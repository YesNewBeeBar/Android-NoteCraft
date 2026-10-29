package com.dsh.notes.ui.markdown

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * 带「下载代码块」能力的 Markdown 正文。
 *
 * 下载走系统的「另存为」，由用户自己选位置 —— 不依赖「设置 → 导出目录」，
 * 免得为了存一段代码还要求你先配置导出位置。
 */
@Composable
fun MarkdownBody(
    markdown: String,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val payload = pending
        pending = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(payload.second.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    MarkdownText(
        markdown = markdown,
        modifier = modifier,
        selectable = selectable,
        onDownloadSnippet = { language, body ->
            pending = language to body
            launcher.launch("snippet.${extensionOf(language)}")
        }
    )
}

private fun extensionOf(language: String): String = when (language.lowercase().trim()) {
    "kotlin", "kt" -> "kt"
    "java" -> "java"
    "python", "py" -> "py"
    "javascript", "js" -> "js"
    "typescript", "ts" -> "ts"
    "json" -> "json"
    "xml", "html" -> "xml"
    "yaml", "yml" -> "yml"
    "sql" -> "sql"
    "bash", "sh", "shell" -> "sh"
    "css" -> "css"
    else -> "txt"
}
