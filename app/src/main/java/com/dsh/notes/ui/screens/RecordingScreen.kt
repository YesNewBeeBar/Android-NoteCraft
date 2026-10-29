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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.audio.RecorderPhase
import com.dsh.notes.data.ConfigArea
import com.dsh.notes.data.ConfigProblem
import com.dsh.notes.data.ThemeMode
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.RecordingViewModel
import com.dsh.notes.ui.components.ActivityBars
import com.dsh.notes.ui.components.KeepScreenOn
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
    /** 配置不齐时把人送到设置页；参数是全部问题，第一项用来决定滑到哪一节 */
    onOpenSettings: (List<ConfigProblem>) -> Unit,
    onOpenNote: (Long) -> Unit
) {
    val viewModel: RecordingViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.recorderState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hasPermission = remember { mutableStateOf(Permissions.hasAudio(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // 录音期间别让屏幕睡着：盯着实时上屏的人不该被息屏打断
    KeepScreenOn(state.isRecording)

    var lastFinishedNoteId by remember { mutableStateOf(0L) }

    // 配置体检。用 remember 缓存，免得每次重组都重算一遍
    val problems = remember(settings, viewModel.localEngineAvailable) {
        viewModel.configProblems(settings)
    }

    /**
     * 能不能「暂不整理」。
     *
     * 只有整理（DeepSeek）那边没配好、而转写是好的时候才给这条路 ——
     * 转写本身不可用时录下来只是一段听不了也转不了的音频，没有意义。
     */
    val canSkipOrganize = problems.isNotEmpty() && problems.none { it.area == ConfigArea.ASR }

    // 录音结束、笔记落库后，给一个「去看这条笔记」的入口
    LaunchedEffect(state.phase, state.noteId) {
        if (state.isRecording || state.phase == RecorderPhase.PROCESSING) {
            lastFinishedNoteId = state.noteId
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NoteCraft") },
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
            Spacer(Modifier.height(6.dp))

            StatusHeader(
                phase = state.phase,
                hint = state.hint,
                elapsedMs = state.elapsedMs,
                error = state.error
            )

            /*
             * 徽标区。
             *  - 录音中：显示这次到底录的 m4a 还是 WAV，别让用户猜
             *  - 空闲时：显示实时上屏开关 —— 以前只能去设置页关，
             *    而且它一开就强制 WAV，用户却找不到地方关掉
             */
            if (state.isRecording) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
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
            } else if (state.phase != RecorderPhase.PROCESSING) {
                LiveCaptionToggle(
                    enabled = settings.liveCaptionEnabled,
                    cloudReady = settings.cloudAsrReady,
                    onToggle = viewModel::setLiveCaption
                )
            }

            Spacer(Modifier.height(10.dp))

            // 音量条：录音时给一个「麦克风在工作」的即时反馈
            if (state.isRecording) {
                LevelMeter(
                    level = state.level,
                    muted = state.muted,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
            }

            // 实时上屏：录音中、开着、而且这次确实是 PCM（m4a 拿不到流，没得上屏）
            if (state.isRecording && settings.liveCaptionEnabled && !state.compressed) {
                LiveCaptionPanel(
                    finalized = state.finalizedText,
                    partial = state.partialText,
                    paused = state.phase == RecorderPhase.PAUSED,
                    onStop = { viewModel.stopRecording() },
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                    onAbandon = { viewModel.cancelRecording() }
                )
                Spacer(Modifier.height(10.dp))
            }

            RecordButton(
                phase = state.phase,
                onClick = {
                    when (state.phase) {
                        // PROCESSING 也允许开录：处理在队列里排队，不该拦着用户录下一条
                        RecorderPhase.IDLE, RecorderPhase.PROCESSING -> {
                            when {
                                !Permissions.hasAudio(context) -> showPermissionDialog = true

                                // 转写和整理任一方没配好，这条录音都是废的：
                                // 直接把人送到设置页出问题的那个框前面，不让他白说一段话
                                else -> {
                                    if (problems.isEmpty()) {
                                        viewModel.startRecording()
                                    } else {
                                        onOpenSettings(problems)
                                    }
                                }
                            }
                        }

                        RecorderPhase.RECORDING, RecorderPhase.PAUSED -> viewModel.stopRecording()
                    }
                }
            )

            Spacer(Modifier.height(10.dp))

            // 整理那边没配好时，给一条「先录着」的退路：
            // 转写照跑，笔记先停在待处理，等补上 API 再重新整理就行
            if (state.phase == RecorderPhase.IDLE && canSkipOrganize) {
                TextButton(onClick = { viewModel.startRecording() }) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("暂不整理")
                }
                Text(
                    text = "先只做录音和转写。之后补上整理用的 API，在笔记详情里点「重新整理」即可。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActivityBars()
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "正在转写与整理，休息一下吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 队列情况说清楚：还有几条在等、当前这条在做什么
                val queueDetail = buildString {
                    queueState.currentProgress?.message?.takeIf { it.isNotBlank() }?.let {
                        append(it)
                    }
                    if (queueState.waitingIds.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("后面还有 ${queueState.waitingIds.size} 条排队")
                    }
                }
                if (queueDetail.isNotBlank()) {
                    Text(
                        text = queueDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Text(
                    text = "现在照样可以按上面的按钮录下一条，录完自动排到队尾。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (lastFinishedNoteId > 0 && state.phase == RecorderPhase.IDLE) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = { onOpenNote(lastFinishedNoteId) }) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("查看刚录的笔记")
                }
            }

            // 使用提示只在空闲时显示：录音/处理时它纯占地方，
            // 而那几个按钮更需要屏幕空间
            if (state.phase == RecorderPhase.IDLE) {
                Spacer(Modifier.height(16.dp))
                Tips(settings.liveCaptionEnabled, settings.asrEngine.label)
            }
            Spacer(Modifier.height(24.dp))
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
    // 处理中也要能按：新的录音会排进队列，处理在后台继续
    val container = if (isRecording) {
        MaterialTheme.colorScheme.recordColor
    } else {
        MaterialTheme.colorScheme.primary
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
            enabled = true,
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

/**
 * 实时上屏开关。
 *
 * 以前这个开关只存在于设置页，而且它一开就强制录成 WAV ——
 * 用户看到格式变了却找不到地方关。现在放在录音页最显眼的位置。
 */
@Composable
private fun LiveCaptionToggle(
    enabled: Boolean,
    cloudReady: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilterChip(
            selected = enabled,
            onClick = { onToggle(!enabled) },
            label = { Text(if (enabled) "实时上屏：开" else "实时上屏：关") },
            leadingIcon = {
                Icon(
                    imageVector = if (enabled) Icons.Default.GraphicEq else Icons.Default.VolumeOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        Text(
            text = when {
                !enabled -> "使用 m4a 格式，省空间（1 小时约 14MB）"
                !cloudReady -> "已开启，但云转写还没配好，暂时不会生效"
                else -> "开启后必须录成 WAV（切片要原始音频流）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun LiveCaptionPanel(
    finalized: String,
    partial: String,
    paused: Boolean,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
) {
    val text = when {
        finalized.isBlank() && partial.isBlank() -> "开始说话后，这里会实时出现文字…"
        else -> finalized + partial
    }
    var fullScreen by remember { mutableStateOf(false) }
    var confirmAbandon by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityBars(barCount = 3)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "实时上屏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { fullScreen = true }) {
                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription = "全屏查看转写",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            AutoFollowText(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LIVE_CAPTION_HEIGHT)
                    .padding(end = 8.dp)
            )
        }
    }

    if (fullScreen) {
        // 真·全屏覆盖：这一段文字经常是唯一想盯着看的东西
        Dialog(
            onDismissRequest = { fullScreen = false },
            // decorFitsSystemWindows = false：让对话框也边到边绘制，
            // 这样里面的 safeDrawingPadding() 才能拿到真实的导航栏高度。
            // 只设 usePlatformDefaultWidth 的话，Android 15 上有内容会跑到
            // 导航栏下面 —— 右下角那个圆钮就被切掉一半。
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // targetSdk 35 在 Android 15 上强制 edge-to-edge，
                        // Dialog 不自己处理 inset，内容会画到导航栏下面 —— 最后一行就看不见了
                        .safeDrawingPadding()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "实时上屏",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { fullScreen = false }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AutoFollowText(text = text, modifier = Modifier.fillMaxSize())

                        // 右下角圆钮：点开从左边依次伸出三个操作
                        val rotation by animateFloatAsState(
                            targetValue = if (expanded) 45f else 0f,
                            animationSpec = tween(220) , label = "caption-fab-rotation"
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf(
                                Triple("结束", Icons.Default.Stop) {
                                    onStop()
                                    fullScreen = false
                                },
                                Triple(if (paused) "继续" else "暂停", if (paused) Icons.Default.PlayArrow else Icons.Default.Pause) {
                                    if (paused) onResume() else onPause()
                                },
                                Triple("放弃", Icons.Default.Delete) {
                                    confirmAbandon = true
                                }
                            ).forEachIndexed { index, (label, icon, action) ->
                                AnimatedVisibility(
                                    visible = expanded,
                                    enter = fadeIn(tween(120, delayMillis = index * 45)) +
                                        slideInHorizontally(tween(220, delayMillis = index * 45)) { it / 2 },
                                    exit = fadeOut(tween(70)) +
                                        slideOutHorizontally(tween(120)) { it / 2 }
                                ) {
                                    ExtendedFloatingActionButton(
                                        onClick = {
                                            expanded = false
                                            action()
                                        },
                                        icon = { Icon(icon, contentDescription = null) },
                                        text = { Text(label) },
                                        containerColor = if (label == "放弃") {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        contentColor = if (label == "放弃") {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            FloatingActionButton(
                                onClick = { expanded = !expanded },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = if (expanded) "收起操作" else "展开操作",
                                    modifier = Modifier.rotate(rotation)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmAbandon) {
        AlertDialog(
            onDismissRequest = { confirmAbandon = false },
            title = { Text("放弃这次录音？") },
            text = { Text("已录内容与上屏文字都会被丢弃，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAbandon = false
                    onAbandon()
                    fullScreen = false
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAbandon = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 会自己跟着最新文字走的文本区。
 *
 * 只在「用户本来就贴着底部」时才自动滚 —— 否则他往上翻看前文时，
 * 每来一句新话就被拽回底部，等于永远读不了历史。
 */
@Composable
private fun AutoFollowText(text: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    var followTail by remember { mutableStateOf(true) }

    // 只有用户自己拖动时才判断他是不是往上翻了；程序自身的滚动不算
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }.collect { dragging ->
            if (dragging) {
                followTail = scrollState.maxValue - scrollState.value <= TAIL_SLACK_PX
            }
        }
    }

    // 监听 maxValue 而不是 text：文字变长后布局才算完，这时 maxValue 才是新的
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect {
            if (followTail) scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.verticalScroll(scrollState)
    )
}

/** 转写栏折叠态高度 */
private val LIVE_CAPTION_HEIGHT = 120.dp

/** 离底部这么近就算「贴着底」，单位是像素 */
private const val TAIL_SLACK_PX = 24

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
