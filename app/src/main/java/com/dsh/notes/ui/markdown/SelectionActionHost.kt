package com.dsh.notes.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.dsh.notes.util.openWebSearch

private data class SelectionMenu(
    val rect: Rect,
    val onCopy: (() -> Unit)?
)

/**
 * 给可选文字加「AI 提问 / 浏览器搜索」。
 *
 * Compose 的自定义 TextToolbar 只能拿到「复制」这个动作，**拿不到选中的文字**。
 * 所以这里的做法是：备份剪贴板 → 触发一次复制 → 读出文字 → 立刻还原。
 * 这是目前唯一能拿到选中文本的路径；副作用是剪贴板会被覆盖极短一瞬再复原。
 */
@Composable
fun SelectionActionHost(
    onAsk: ((String) -> Unit)?,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf<SelectionMenu?>(null) }

    val toolbar = remember {
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = if (menu != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                menu = SelectionMenu(rect, onCopyRequested)
            }

            override fun hide() {
                menu = null
            }
        }
    }

    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        content()
    }

    val current = menu
    if (current != null) {
        Popup(
            popupPositionProvider = remember(current) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val x = current.rect.left.toInt()
                            .coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
                        val y = (current.rect.top.toInt() - popupContentSize.height - 12)
                            .coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
                        return IntOffset(x, y)
                    }
                }
            },
            onDismissRequest = { menu = null }
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 6.dp
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    TextButton(onClick = {
                        current.onCopy?.invoke()
                        menu = null
                    }) { Text("复制") }

                    if (onAsk != null) {
                        TextButton(onClick = {
                            val copy = current.onCopy
                            menu = null
                            extractSelection(context, copy) { text ->
                                if (text.isNotBlank()) onAsk(text)
                            }
                        }) { Text("AI 提问") }
                    }

                    TextButton(onClick = {
                        val copy = current.onCopy
                        menu = null
                        extractSelection(context, copy) { text ->
                            if (text.isNotBlank()) openWebSearch(context, text.take(80))
                        }
                    }) { Text("搜索") }
                }
            }
        }
    }
}

/**
 * 备份剪贴板 → 触发复制 → 读出文字 → 还原。
 * 还原失败也不影响主流程，最坏情况只是剪贴板里留了这段选中文字。
 */
private fun extractSelection(
    context: Context,
    copy: (() -> Unit)?,
    onText: (String) -> Unit
) {
    val manager = context.getSystemService(ClipboardManager::class.java)
    if (manager == null || copy == null) {
        onText("")
        return
    }

    val backup = runCatching {
        manager.primaryClip?.let { clip ->
            if (clip.itemCount == 0) {
                null
            } else {
                ClipData(clip.description, clip.getItemAt(0)).apply {
                    for (index in 1 until clip.itemCount) {
                        addItem(clip.getItemAt(index))
                    }
                }
            }
        }
    }.getOrNull()

    runCatching { copy() }
    val text = runCatching {
        manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }.getOrDefault("")

    runCatching { backup?.let { manager.setPrimaryClip(it) } }
    onText(text)
}
