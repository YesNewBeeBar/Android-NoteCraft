package com.dsh.notes.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.audio.RecorderPhase
import com.dsh.notes.data.ThemeMode
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.RecordingViewModel
import com.dsh.notes.ui.components.ActivityBars
import com.dsh.notes.ui.components.LevelMeter
import com.dsh.notes.ui.components.ThemeToggleButton
import com.dsh.notes.ui.theme.recordColor
import com.dsh.notes.util.Permissions
import com.dsh.notes.util.formatDuration

/**
 * 录音页。
 *
 * 交互刻意做成「一屏一件事」：一个巨大的按钮，其余信息都是辅助。
 * 录音时最怕的是按错键，所以次要操作（静音、取消）都放远一点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    viewModelFactory: NoteCraftViewModelFactory,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNote: (Long) -> Unit
) {
    val viewModel: RecordingViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.recorderState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hasPermission = remember { mutableStateOf(Permissions.hasAudio(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    /**
     * 转写没配好时拦下这次录音。
     * 录完才发现转写不了，等于白录一条 —— 这是最气人的失败方式，所以宁可不让他开始。
     */
    var configBlocker by remember { mutableStateOf<String?>(null) }
    var lastFinishedNoteId by remember { mutableStateOf(0L) }

    // 录音结束、笔记落库后，给一个「去看这条笔记」的入口
    LaunchedEffect(state.phase, state.noteId) {
        if (state.isRecording || state.phase == RecorderPhase.PROCESSING) {
            lastFinishedNoteId = state.noteId
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("笔记整理器") },
                actions = {
                    if (settings.liveCaptionEnabled && !settings.cloudAsrReady && state.isRecording) {
                        Text(
                            text = "实时上屏需配置云转写",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                    ThemeToggleButton(themeMode = themeMode, onToggle = onToggleTheme)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            StatusHeader(
                phase = state.phase,
                hint = state.hint,
                elapsedMs = state.elapsedMs,
                error = state.error
            )

            // 格式徽标：让用户随时知道这次录的是 m4a 还是 WAV，别猜
            if (state.isRecording) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatBadge(
                        text = if (state.compressed) "m4a · 省空间" else "WAV · 高兼容",
                        highlight = state.compressed
                    )
                    if (state.liveCaptionActive) {
                        FormatBadge(text = "实时上屏中", highlight = true)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 音量条：录音时给一个「麦克风在工作」的即时反馈
            if (state.isRecording) {
                LevelMeter(
                    level = state.level,
                    muted = state.muted,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
            }

            // 实时上屏：开着且配好了云转写才显示
            if (state.isRecording && settings.liveCaptionEnabled) {
                LiveCaptionPanel(
                    finalized = state.finalizedText,
                    partial = state.partialText
                )
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(8.dp))

            RecordButton(
                phase = state.phase,
                onClick = {
                    when (state.phase) {
                        RecorderPhase.IDLE -> {
                            val blocker = settings.transcribeBlocker
                            when {
                                !Permissions.hasAudio(context) -> showPermissionDialog = true
                                blocker != null -> configBlocker = blocker
                                else -> viewModel.startRecording()
                            }
                        }

                        RecorderPhase.RECORDING, RecorderPhase.PAUSED -> viewModel.stopRecording()
                        RecorderPhase.PROCESSING -> Unit
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            if (state.isRecording) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SecondaryAction(
                        icon = if (state.phase == RecorderPhase.PAUSED) {
                            Icons.Default.PlayArrow
                        } else {
                            Icons.Default.Pause
                        },
                        label = if (state.phase == RecorderPhase.PAUSED) "继续" else "暂停",
                        onClick = {
                            if (state.phase == RecorderPhase.PAUSED) {
                                viewModel.resumeRecording()
                            } else {
                                viewModel.pauseRecording()
                            }
                        }
                    )
                    SecondaryAction(
                        icon = if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        label = when {
                            state.muted -> "已静音"
                            !state.muteSupported -> "静音不可用"
                            else -> "静音"
                        },
                        enabled = state.muteSupported,
                        onClick = { viewModel.toggleMute() }
                    )
                    SecondaryAction(
                        icon = Icons.Default.Close,
                        label = "放弃",
                        danger = true,
                        onClick = { viewModel.cancelRecording() }
                    )
                }
            }

            if (state.phase == RecorderPhase.PROCESSING) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActivityBars()
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "正在转写与整理，可以先去做别的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (lastFinishedNoteId > 0 && state.phase == RecorderPhase.IDLE) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = { onOpenNote(lastFinishedNoteId) }) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("查看刚录的笔记")
                }
            }

            Spacer(Modifier.height(24.dp))
            Tips(settings.liveCaptionEnabled, settings.asrEngine.label)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("需要麦克风权限") },
            text = { Text("录音必须要有麦克风权限。点「去授权」后允许即可。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    (context as? Activity)?.let { activity ->
                        activity.requestPermissions(Permissions.recordingPermissions(), REQUEST_CODE)
                    }
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("取消") }
            }
        )
    }

    // 防呆：转写没配好就别让录 —— 录完转不了，这条录音基本就废了
    configBlocker?.let { reason ->
        AlertDialog(
            onDismissRequest = { configBlocker = null },
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null) },
            title = { Text("还不能开始录音") },
            text = {
                Text(
                    buildString {
                        appendLine(reason)
                        appendLine()
                        appendLine("转写引擎现在是「${settings.asrEngine.label}」。")
                        append("去设置里把缺的填上，或者改用「系统离线识别」——那条路不需要 Key。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    configBlocker = null
                    onOpenSettings()
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { configBlocker = null }) { Text("取消") }
            }
        )
    }
}

private const val REQUEST_CODE = 4001

@Composable
private fun StatusHeader(
    phase: RecorderPhase,
    hint: String,
    elapsedMs: Long,
    error: String?
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDuration(elapsedMs),
            style = MaterialTheme.typography.displayMedium,
            color = if (phase == RecorderPhase.RECORDING) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun RecordButton(
    phase: RecorderPhase,
    onClick: () -> Unit
) {
    val isRecording = phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED
    val container = when {
        phase == RecorderPhase.PROCESSING -> MaterialTheme.colorScheme.surfaceVariant
        isRecording -> MaterialTheme.colorScheme.recordColor
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(CircleShape)
            .background(container.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = phase != RecorderPhase.PROCESSING,
            modifier = Modifier.size(114.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container)
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "结束录音" else "开始录音",
                modifier = Modifier.size(46.dp)
            )
        }
    }
}

@Composable
private fun SecondaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        danger -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        danger -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(containerColor)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun LiveCaptionPanel(finalized: String, partial: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityBars(barCount = 3)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "实时上屏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    finalized.isBlank() && partial.isBlank() -> "开始说话后，这里会实时出现文字…"
                    else -> finalized + partial
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.height(120.dp).verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun Tips(liveCaption: Boolean, engine: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "使用提示",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    appendLine("· 录音会以常驻通知的形式在后台进行，熄屏也不会中断。")
                    appendLine("· 转写引擎：$engine${if (liveCaption) "（实时上屏已开启）" else ""}")
                    appendLine(
                        if (liveCaption) {
                            "· 实时上屏需要原始音频流，所以这次会录成 WAV（文件较大）。"
                        } else {
                            "· 格式在「设置 → 录音与处理」里选：m4a 省空间，WAV 支持静音与实时上屏。"
                        }
                    )
                    append("· 说完点红色按钮结束，之后可以自动转写并整理成 Markdown。")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 格式徽标：小圆角药丸，一眼看清当前录音的参数 */
@Composable
private fun FormatBadge(text: String, highlight: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (highlight) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                }
            )
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
