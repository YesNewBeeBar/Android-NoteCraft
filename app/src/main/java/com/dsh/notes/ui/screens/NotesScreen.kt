package com.dsh.notes.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.data.Note
import com.dsh.notes.data.ThemeMode
import com.dsh.notes.ui.FolderFilter
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.NotesViewModel
import com.dsh.notes.ui.components.NoteCard
import com.dsh.notes.ui.components.ActivityBars
import com.dsh.notes.ui.components.SwipeAction
import com.dsh.notes.ui.components.SwipeActionRow
import com.dsh.notes.ui.components.ThemeToggleButton
import com.dsh.notes.util.NoteSharing

/**
 * 笔记列表。
 *
 * 搜索是「边打边筛」而不是打完再按回车：本地 SQLite 的 LIKE 查询在几千条量级
 * 完全跟得上打字速度，即时反馈比省那点 CPU 更值。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(
    viewModelFactory: NoteCraftViewModelFactory,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onOpenNote: (Long) -> Unit,
    sharedAudioUris: List<Uri> = emptyList(),
    onSharedAudioConsumed: () -> Unit = {}
) {
    val viewModel: NotesViewModel = viewModel(factory = viewModelFactory)
    val allNotes by viewModel.visibleNotes.collectAsStateWithLifecycle()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val folderFilter by viewModel.folderFilter.collectAsStateWithLifecycle()
    val mergeState by viewModel.mergeState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val notes = allNotes
    val context = LocalContext.current

    // ---- 多选 ----
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 同一时刻只允许一行滑开
    var openRowId by remember { mutableStateOf<Long?>(null) }
    var moveTargetIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Note?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Note?>(null) }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 从系统「分享音频」进来时自动导入
    LaunchedEffect(sharedAudioUris) {
        if (sharedAudioUris.isNotEmpty()) {
            sharedAudioUris.forEach { uri -> viewModel.importAudio(uri) }
            onSharedAudioConsumed()
        }
    }

    // 导入完成并且产出了笔记时，直接跳进详情页
    LaunchedEffect(importState.noteId, importState.running) {
        if (!importState.running && importState.noteId > 0L && importState.error == null) {
            val target = importState.noteId
            viewModel.clearImportState()
            onOpenNote(target)
        }
    }

    val pickAudio = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importAudio(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("已选 ${selected.size} 条") },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                moveTargetIds = selected.toList()
                            },
                            enabled = selected.isNotEmpty()
                        ) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "移到文件夹")
                        }
                        IconButton(
                            onClick = {
                                val ids = selected.toList()
                                // 合并会新建一条笔记，选完就退出多选，避免误操作
                                exitSelection()
                                viewModel.mergeNotes(ids) { newId -> onOpenNote(newId) }
                            },
                            enabled = selected.size >= 2 && mergeState == null
                        ) {
                            Icon(Icons.Default.MergeType, contentDescription = "合并整理")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("我的笔记") },
                    actions = { ThemeToggleButton(themeMode = themeMode, onToggle = onToggleTheme) }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { pickAudio.launch(arrayOf("audio/*")) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("导入音频") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = viewModel::search,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索标题或内容") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true
            )

            FolderBar(
                folders = folders,
                filter = folderFilter,
                onSelect = viewModel::selectFolder,
                onNewFolder = {
                    newFolderName = ""
                    showNewFolderDialog = true
                },
                onDeleteFolder = viewModel::deleteFolder
            )

            mergeState?.let { text ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActivityBars()
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (importState.running || importState.error != null) {
                ImportBanner(
                    running = importState.running,
                    fileName = importState.fileName,
                    message = importState.message,
                    error = importState.error,
                    onDismiss = viewModel::clearImportState
                )
            }

            if (notes.isEmpty()) {
                EmptyNotes(hasKeyword = keyword.isNotBlank())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = notes, key = { it.id }) { note ->
                        val isOpen = openRowId == note.id
                        SwipeActionRow(
                            actions = swipeActionsFor(
                                note = note,
                                context = context,
                                requestMove = { moveTargetIds = listOf(note.id) },
                                requestRename = {
                                    renameDraft = note.title
                                    renameTarget = note
                                },
                                requestExport = { viewModel.exportMarkdown(note) },
                                requestDelete = { deleteTarget = note }
                            ),
                            expanded = isOpen,
                            onExpandedChange = { openRowId = if (it) note.id else null }
                        ) {
                            NoteCard(
                                note = note,
                                onClick = {
                                    when {
                                        // 划开的状态下点卡片先收起来，避免误进详情
                                        isOpen -> openRowId = null
                                        selectionMode -> {
                                            selected = if (note.id in selected) {
                                                selected - note.id
                                            } else {
                                                selected + note.id
                                            }
                                        }
                                        else -> onOpenNote(note.id)
                                    }
                                },
                                modifier = Modifier.animateItem(),
                                queued = note.id == queueState.currentNoteId ||
                                    note.id in queueState.waitingIds,
                                selectionMode = selectionMode,
                                selected = note.id in selected,
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selected = setOf(note.id)
                                    }
                                }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    // 新建文件夹
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("文件夹名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(newFolderName)
                        showNewFolderDialog = false
                    },
                    enabled = newFolderName.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("取消") }
            }
        )
    }

    // 移到文件夹（来自多选或单条右滑）
    if (moveTargetIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { moveTargetIds = emptyList() },
            title = {
                Text(
                    if (moveTargetIds.size == 1) "把这条笔记移到…" else "把 ${moveTargetIds.size} 条笔记移到…"
                )
            },
            text = {
                Column {
                    FolderPickRow("未分类") {
                        viewModel.moveToFolder(moveTargetIds, null)
                        moveTargetIds = emptyList()
                        exitSelection()
                    }
                    folders.forEach { folder ->
                        FolderPickRow(folder.name) {
                            viewModel.moveToFolder(moveTargetIds, folder.id)
                            moveTargetIds = emptyList()
                            exitSelection()
                        }
                    }
                    if (folders.isEmpty()) {
                        Text(
                            text = "还没有文件夹，先在上面点「新建」建一个。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveTargetIds = emptyList() }) { Text("取消") }
            }
        )
    }

    // 改名
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名标题") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("标题") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(target, renameDraft)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条笔记？") },
            text = { Text("笔记与录音都会被删除，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

/** 右滑露出的五个操作。标签都压到两个字，五个刚好排得下 */
@Composable
private fun swipeActionsFor(
    note: Note,
    context: android.content.Context,
    requestMove: () -> Unit,
    requestRename: () -> Unit,
    requestExport: () -> Unit,
    requestDelete: () -> Unit
): List<SwipeAction> = listOf(
    SwipeAction(
        label = "分类",
        icon = Icons.Default.Folder,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = requestMove
    ),
    SwipeAction(
        label = "改名",
        icon = Icons.Default.DriveFileRenameOutline,
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = requestRename
    ),
    SwipeAction(
        label = "保存",
        icon = Icons.Default.Save,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        onClick = requestExport
    ),
    SwipeAction(
        label = "分享",
        icon = Icons.Default.Share,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = { NoteSharing.share(context, note) }
    ),
    SwipeAction(
        label = "删除",
        icon = Icons.Default.Delete,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        onClick = requestDelete
    )
)

/** 文件夹筛选条：全部 / 未分类 / 各文件夹 / 新建 */
@Composable
private fun FolderBar(
    folders: List<com.dsh.notes.data.Folder>,
    filter: FolderFilter,
    onSelect: (FolderFilter) -> Unit,
    onNewFolder: () -> Unit,
    onDeleteFolder: (Long) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<com.dsh.notes.data.Folder?>(null) }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = filter is FolderFilter.All,
                onClick = { onSelect(FolderFilter.All) },
                label = { Text("全部") }
            )
        }
        item {
            FilterChip(
                selected = filter is FolderFilter.Unfiled,
                onClick = { onSelect(FolderFilter.Unfiled) },
                label = { Text("未分类") }
            )
        }
        items(items = folders, key = { it.id }) { folder ->
            FilterChip(
                selected = filter is FolderFilter.In && filter.id == folder.id,
                onClick = { onSelect(FolderFilter.In(folder.id)) },
                label = { Text(folder.name) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除文件夹「${folder.name}」",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { pendingDelete = folder }
                    )
                }
            )
        }
        item {
            AssistChip(
                onClick = onNewFolder,
                label = { Text("新建") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
    }

    pendingDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件夹「${folder.name}」？") },
            text = { Text("里面的笔记不会被删除，只会回到「未分类」。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFolder(folder.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FolderPickRow(name: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(name)
    }
}

@Composable
private fun ImportBanner(
    running: Boolean,
    fileName: String,
    message: String,
    error: String?,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    ActivityBars(color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.size(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            error != null -> "导入失败"
                            else -> "正在导入 ${fileName.take(28)}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (error != null) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    Text(
                        text = error ?: message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error != null) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                }
                if (!running) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Clear, contentDescription = "关闭")
                    }
                }
            }
            if (running) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyNotes(hasKeyword: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = if (hasKeyword) Icons.Default.Search else Icons.Default.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (hasKeyword) "没有匹配的笔记" else "还没有笔记",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (hasKeyword) {
                    "换个关键词试试"
                } else {
                    "去「录音」页录一段，或者点右下角导入一个音频文件"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
