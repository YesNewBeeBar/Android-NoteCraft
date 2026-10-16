package com.dsh.notes.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.notes.BuildConfig
import com.dsh.notes.update.UpdateState

/**
 * 新版本提示窗。
 *
 * 只在「发现新版本」和「正在下载」两种状态下出现 ——
 * 检查中、已是最新、检查失败都各有各的去处（snackbar 或干脆静默），
 * 不该用一个弹窗去承担所有反馈。
 *
 * 下载过程中不给关：进度条走完会自动交棒给系统安装器，中途关掉只会让人以为没反应。
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onOpenRelease: (String) -> Unit
) {
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        else -> null
    } ?: return

    val downloading = state as? UpdateState.Downloading

    AlertDialog(
        onDismissRequest = { if (downloading == null) onLater() },
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column {
                Text(
                    text = "当前版本 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (downloading != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("正在下载更新包… ${downloading.percent}%")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloading.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            if (downloading == null) {
                val hasApk = info.apkUrl != null
                TextButton(
                    onClick = { if (hasApk) onInstall() else onOpenRelease(info.releaseUrl) }
                ) {
                    Text(if (hasApk) "立即更新" else "去发布页")
                }
            }
        },
        dismissButton = {
            if (downloading == null) {
                TextButton(onClick = onLater) { Text("稍后") }
            } else {
                TextButton(onClick = { onOpenRelease(info.releaseUrl) }) { Text("打不开？去发布页") }
            }
        }
    )
}
