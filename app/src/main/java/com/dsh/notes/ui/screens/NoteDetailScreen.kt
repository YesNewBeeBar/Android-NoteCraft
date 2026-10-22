package com.dsh.notes.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.audio.SpeechReader
import com.dsh.notes.data.AudioSource
import com.dsh.notes.data.tags
import com.dsh.notes.pipeline.AudioFileState
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.NoteDetailViewModel
import com.dsh.notes.ui.Suspect
import com.dsh.notes.ui.components.KeepScreenOn
import com.dsh.notes.ui.components.StatusChip
import com.dsh.notes.ui.components.WaveformView
import com.dsh.notes.ui.markdown.MarkdownText
import com.dsh.notes.util.NoteSharing
import com.dsh.notes.util.copyToClipboard
import com.dsh.notes.util.formatDateTime
import com.dsh.notes.util.formatDuration
import com.dsh.notes.util.formatSize
import kotlinx.coroutines.delay

/**
 * 笔记详情页：波形播放 + 正文（Markdown 渲染）+ 三种重跑操作。
 *
 * 「保存位置」在这一页可以临时改：导出时弹一次目录选择器，
 * 用户既可以选择「只这一次」，也可以勾选「设为默认」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    noteId: Long,
    viewModelFactory: NoteCraftViewModelFactory,
    onBack: () -> Unit
) {
    val viewModel: NoteDetailViewModel = viewModel(
        factory = viewModelFactory.forNote(noteId),
        key = "note-$noteId"
    )
    val note by viewModel.note.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val peaks by viewModel.peaks.collectAsStateWithLifecycle()
    val audioState by viewModel.audioState.collectAsStateWithLifecycle()
    val exportedPath by viewModel.exportedPath.collectAsStateWithLifecycle()
    val speech by viewModel.speech.collectAsStateWithLifecycle()
    val suspects by viewModel.suspects.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // 朗读时别息屏：听长篇时屏幕老黑掉很烦
    KeepScreenOn(speech.playing)

    var editing by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    var bodyDraft by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showToolsDialog by remember { mutableStateOf(false) }
    var pendingTreeUri by remember { mutableStateOf<String?>(null) }
    var showTranscript by remember { mutableStateOf(false) }

    // ---- 音频播放（状态放在页面层，组件重组不会丢） ----
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progressRatio by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                player?.stop()
                player?.release()
            }
            player = null
        }
    }

    LaunchedEffect(playing, player) {
        val current = player
        if (!playing || current == null) return@LaunchedEffect
        while (playing) {
            runCatching {
                val total = current.duration
                if (total > 0) {
                    progressRatio = (current.currentPosition.toFloat() / total).coerceIn(0f, 1f)
                }
                if (!current.isPlaying) {
                    playing = false
                    progressRatio = 0f
                }
            }
            delay(200)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(exportedPath) {
        exportedPath?.let {
            snackbar.showSnackbar("已保存到 $it")
            viewModel.clearExported()
        }
    }

    val pickDirectory = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { pendingTreeUri = it.toString() } }

    // 导出音频是「一次性动作」，不用问要不要设成默认位置，所以它单独用一个选择器
    val pickAudioDirectory = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.exportAudioTo(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = note?.title?.ifBlank { "未命名笔记" } ?: "笔记",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val current = note ?: return@IconButton
                        if (editing) {
                            editing = false
                        } else {
                            titleDraft = current.title
                            bodyDraft = current.displayBody
                            editing = true
                        }
                    }) {
                        Icon(
                            imageVector = if (editing) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = if (editing) "预览" else "编辑"
                        )
                    }
                    IconButton(onClick = {
                        val state = speech
                        when {
                            state.playing -> viewModel.pauseSpeech()
                            state.paused -> viewModel.resumeSpeech()
                            else -> viewModel.speakBody()
                        }
                    }) {
                        Icon(
                            imageVector = when {
                                speech.playing -> Icons.Default.PauseCircle
                                speech.paused -> Icons.Default.PlayCircle
                                else -> Icons.Default.VolumeUp
                            },
                            contentDescription = if (speech.playing) "暂停朗读" else "朗读笔记"
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // 常用：整理 / 转写
                        DropdownMenuItem(
                            text = { Text("重新整理（用 DeepSeek）") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                viewModel.summarize()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("重新转写音频") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                viewModel.transcribe()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("转写 + 整理全流程") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                viewModel.processAll()
                            }
                        )
                        // 常用：保存
                        DropdownMenuItem(
                            text = { Text("保存为 Markdown") },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                viewModel.exportToDefault()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("另存到其他位置…") },
                            leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                pickDirectory.launch(null)
                            }
                        )
                        // 不常用的收进二级，但删除必须留在外面
                        DropdownMenuItem(
                            text = { Text("更多工具") },
                            leadingIcon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showToolsDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除这条笔记") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val current = note
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- 朗读控制条（开始念之后才出现） ----
            if (speech.total > 0 || speech.error != null) {
                SpeechBar(
                    state = speech,
                    onPause = viewModel::pauseSpeech,
                    onResume = viewModel::resumeSpeech,
                    onStop = viewModel::stopSpeech,
                    onSpeed = viewModel::setSpeechSpeed
                )
                Spacer(Modifier.height(10.dp))
            }

            // ---- 元信息 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = current.status)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = formatDateTime(current.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (current.audioDurationMs > 0) {
                    Text(
                        text = " · ${formatDuration(current.audioDurationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            current.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ---- 音频播放 ----
            if (audioState is AudioFileState.Available) {
                AudioSection(
                    filePath = current.audioPath,
                    peaks = peaks,
                    sizeBytes = (audioState as AudioFileState.Available).sizeBytes,
                    durationMs = current.audioDurationMs,
                    playing = playing,
                    progressRatio = progressRatio,
                    onToggle = {
                        val path = current.audioPath
                        if (path.isNullOrBlank()) return@AudioSection
                        if (playing) {
                            runCatching { player?.pause() }
                            playing = false
                        } else {
                            var active = player
                            if (active == null) {
                                active = runCatching {
                                    MediaPlayer().apply {
                                        setDataSource(path)
                                        prepare()
                                    }
                                }.getOrNull()
                                player = active
                            }
                            active?.let {
                                runCatching { it.start() }
                                playing = true
                            }
                        }
                    }
                )
            } else if (current.audioSource == AudioSource.RECORDED) {
                Text(
                    text = "录音文件已不在（可能被清理过），但转写文字仍然保留。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            if (progress?.isWorking == true) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = progress?.message.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (current.tags().isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    current.tags().take(6).forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) })
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 正文 / 编辑 ----
            if (editing) {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bodyDraft,
                    onValueChange = { bodyDraft = it },
                    label = { Text("正文（Markdown）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        viewModel.updateTitle(titleDraft.trim())
                        viewModel.updateBody(bodyDraft)
                        editing = false
                    }) { Text("保存") }
                    TextButton(onClick = { editing = false }) { Text("取消") }
                }
            } else {
                val body = current.displayBody
                if (body.isBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "这条笔记还没有内容",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "点右上角菜单里的「转写 + 整理全流程」试试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    if (current.refinedMarkdown.isBlank() && current.transcript.isNotBlank()) {
                        Text(
                            text = "原始转写（尚未整理）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    MarkdownText(markdown = body, modifier = Modifier.fillMaxWidth())
                }

                // 整理结果之外，把原始转写折叠起来备查
                if (current.refinedMarkdown.isNotBlank() && current.transcript.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = { showTranscript = !showTranscript }) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (showTranscript) "收起原始转写" else "查看原始转写")
                    }
                    if (showTranscript) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Text(
                                text = current.transcript,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            // 模型与引擎来源，方便回看「这条是怎么来的」
            val provenance = buildString {
                current.asrEngine?.takeIf { it.isNotBlank() }?.let { append("转写：$it") }
                current.llmModel?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("整理：$it")
                }
                if (current.exportRelativePath != null) {
                    if (isNotEmpty()) append(" · ")
                    append("已导出：${current.exportRelativePath}")
                }
            }
            if (provenance.isNotBlank()) {
                Text(
                    text = provenance,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 18.dp)
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除这条笔记？") },
            text = { Text("笔记与录音都会被删除，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete { onBack() }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    pendingTreeUri?.let { uriText ->
        AlertDialog(
            onDismissRequest = { pendingTreeUri = null },
            title = { Text("保存到选择的位置") },
            text = { Text("要不要把这个位置设为以后所有笔记的默认保存位置？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.exportTo(Uri.parse(uriText), saveAsDefault = true)
                    pendingTreeUri = null
                }) { Text("设为默认并保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.exportTo(Uri.parse(uriText), saveAsDefault = false)
                    pendingTreeUri = null
                }) { Text("只这一次") }
            }
        )
    }

    // ---- 二级菜单：不常用的工具 ----
    if (showToolsDialog) {
        AlertDialog(
            onDismissRequest = { showToolsDialog = false },
            title = { Text("更多工具") },
            text = {
                Column {
                    ToolRow("重拟标题", Icons.Default.AutoAwesome) {
                        showToolsDialog = false
                        viewModel.regenerateTitle()
                    }
                    ToolRow("复制正文", Icons.Default.ContentCopy) {
                        showToolsDialog = false
                        note?.let { copyToClipboard(context, "笔记", it.displayBody) }
                    }
                    ToolRow("复制摘要 + 待办", Icons.Default.Checklist) {
                        showToolsDialog = false
                        note?.let {
                            copyToClipboard(context, "摘要 + 待办", NoteSharing.summaryAndTodo(it))
                        }
                    }
                    ToolRow("导出音频文件…", Icons.Default.GraphicEq) {
                        showToolsDialog = false
                        pickAudioDirectory.launch(null)
                    }
                    ToolRow("检查疑似错误", Icons.Default.Spellcheck) {
                        showToolsDialog = false
                        viewModel.checkSuspects()
                    }
                    if (checking) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "正在检查…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToolsDialog = false }) { Text("关闭") }
            }
        )
    }

    // ---- 可疑词逐条确认 ----
    if (suspects.isNotEmpty()) {
        SuspectsDialog(
            suspects = suspects,
            onApply = viewModel::applyCorrection,
            onKeep = viewModel::dismissSuspect,
            onClose = viewModel::clearSuspects
        )
    }
}

/** 二级菜单里的一行 */
@Composable
private fun ToolRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

/** 朗读控制条：播放/暂停、停止、调速 */
@Composable
private fun SpeechBar(
    state: com.dsh.notes.audio.SpeechReader.State,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSpeed: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        state.error != null -> state.error
                        state.playing -> "正在朗读${if (state.source.isNotBlank()) " · ${state.source}" else ""}"
                        state.paused -> "已暂停"
                        else -> "朗读结束"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f)
                )
                if (state.total > 0) {
                    Text(
                        text = "${(state.index + 1).coerceAtMost(state.total)}/${state.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.error != null) {
                    // 引擎没配好时给一条出路，而不是只报错
                    val ctx = LocalContext.current
                    TextButton(onClick = { SpeechReader.openEngineSettings(ctx) }) {
                        Text("去设置")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { if (state.playing) onPause() else onResume() }) {
                    Icon(
                        imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "继续"
                    )
                }
                Spacer(Modifier.size(8.dp))
                FilledIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
                Spacer(Modifier.weight(1f))
                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                        onClick = { onSpeed(speed) },
                        label = { Text("${speed}x") },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/** 可疑词逐条确认：点候选即替换，也可以自己填，点「保留」就跳过 */
@Composable
private fun SuspectsDialog(
    suspects: List<Suspect>,
    onApply: (String, String) -> Unit,
    onKeep: (String) -> Unit,
    onClose: () -> Unit
) {
    // 每个可疑词各自的「自己填写」草稿
    var drafts by remember { mutableStateOf(mapOf<String, String>()) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("检查疑似错误") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "下面这些词在上下文里可能听错了。点候选即可替换，也可以自己填；" +
                        "候选多的话左右滑一下就能看全。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                suspects.forEach { suspect ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = suspect.word,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (suspect.reason.isNotBlank()) {
                        Text(
                            text = suspect.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    // 候选区可横向滚动，「保留」固定在右边 —— 候选再长也挤不掉它
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(items = suspect.suggestions) { candidate ->
                                AssistChip(
                                    onClick = { onApply(suspect.word, candidate) },
                                    label = { Text(candidate) }
                                )
                            }
                            if (suspect.suggestions.isEmpty()) {
                                item {
                                    Text(
                                        text = "没有候选，可以直接在下面填写",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(4.dp))
                        TextButton(onClick = { onKeep(suspect.word) }) { Text("保留") }
                    }

                    // 自己填写：输入框 + 替换
                    val draft = drafts[suspect.word].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { drafts = drafts + (suspect.word to it) },
                            placeholder = { Text("自己填写正确的写法") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                onApply(suspect.word, draft)
                                drafts = drafts - suspect.word
                            },
                            enabled = draft.isNotBlank() && draft != suspect.word
                        ) { Text("替换") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("完成") }
        }
    )
}

/**
 * 播放器 + 波形。
 *
 * 用最基础的 MediaPlayer：这里只需要「播放/暂停 + 进度」，
 * 引入 Media3/ExoPlayer 会让冷启动和体积都变大，不划算。
 */
@Composable
private fun AudioSection(
    filePath: String?,
    peaks: List<Float>,
    sizeBytes: Long,
    durationMs: Long,
    playing: Boolean,
    progressRatio: Float,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onToggle,
                enabled = !filePath.isNullOrBlank()
            ) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放"
                )
            }

            Column(modifier = Modifier.padding(start = 12.dp)) {
                WaveformView(peaks = peaks, progress = progressRatio)
                Text(
                    text = "${formatDuration(durationMs)} · ${formatSize(sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
