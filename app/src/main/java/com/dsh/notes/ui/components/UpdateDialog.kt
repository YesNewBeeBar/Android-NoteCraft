package com.dsh.notes.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    /** 这次先不更：本次进入不再提示，下次启动照常提示 */
    onLater: () -> Unit,
    /** 跳过这个版本：记下版本号，之后同一版本不再提示，出现更新版本才会再提示 */
    onSkipVersion: () -> Unit,
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
                    // 更新日志可能很长：限定高度并可上下滑动，避免显示不全
                    Text(
                        text = info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }

                // 常驻的发布页入口：内嵌更新偶尔会因为网络或证书装不上，
                // 留一个能自己去下载的口子，不用等它失败才出现
                TextButton(
                    onClick = { onOpenRelease(info.releaseUrl) },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("打不开？去发布页", style = MaterialTheme.typography.labelMedium)
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
                Row {
                    TextButton(onClick = onSkipVersion) { Text("跳过此版本") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onLater) { Text("暂不更新") }
                }
            }
        }
    )
}
