package com.dsh.notes.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.data.AudioSource
import com.dsh.notes.data.tags
import com.dsh.notes.pipeline.AudioFileState
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.NoteDetailViewModel
import com.dsh.notes.ui.components.StatusChip
import com.dsh.notes.ui.components.WaveformView
import com.dsh.notes.ui.markdown.MarkdownText
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

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    var bodyDraft by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
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
                        DropdownMenuItem(
                            text = { Text("重拟标题") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                viewModel.regenerateTitle()
                            }
                        )
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
                        DropdownMenuItem(
                            text = { Text("复制正文") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                note?.let { copyToClipboard(context, "笔记", it.displayBody) }
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
