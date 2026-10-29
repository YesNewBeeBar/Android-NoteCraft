package com.dsh.notes.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dsh.notes.R
import com.dsh.notes.data.ChatMessage
import com.dsh.notes.data.ChatSession
import com.dsh.notes.data.Folder
import com.dsh.notes.data.Note
import com.dsh.notes.ui.FishAiViewModel
import com.dsh.notes.ui.FishReference
import com.dsh.notes.ui.markdown.MarkdownBody
import com.dsh.notes.util.formatDateTime
import com.dsh.notes.util.openWebSearch

/**
 * 大肥鱼问答页。
 *
 * 视觉参考：中间一个助手标志 + 名字 + 能力说明 + 几个示例问题，底部输入条。
 * 颜色全部走主题，跟着深浅色模式走，不额外配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FishAiScreen(
    viewModel: FishAiViewModel,
    onClose: () -> Unit,
    /** 半屏面板里传入：点它升到全屏（同一个会话） */
    onExpand: (() -> Unit)? = null,
    /** 半屏面板里高度有限，隐藏示例问题区 */
    compact: Boolean = false
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val reference by viewModel.reference.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val allNotes by viewModel.allNotes.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var showReference by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    /*
     * 像实时上屏那样「贴着底」。
     *
     * 之前用的是 animateScrollToItem(最后一项)，那是把最后一项的**顶部**对到屏幕顶部，
     * 流式输出时气泡越来越长，于是内容一直往上顶、底部永远看不全。
     * 现在滚到「最后一项 + 足够大的偏移」，等价于贴住列表底部；
     * 而且只有用户本来就贴着底时才跟随，他往上翻历史时不会被拽回来。
     */
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) followTail = !listState.canScrollForward
        }
    }
    LaunchedEffect(messages.size, streaming?.length) {
        if (!followTail) return@LaunchedEffect
        val count = messages.size + if (streaming != null) 1 else 0
        // 用一个足够大的偏移把最后一项滚到"完全可见"，等价于贴住列表底部
        if (count > 0) runCatching { listState.scrollToItem(count - 1, 100_000) }
    }

    val fishName = settings.fishName

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回笔记")
                    }
                },
                title = { Text(fishName) },
                actions = {
                    if (onExpand != null) {
                        IconButton(onClick = onExpand) {
                            Icon(Icons.Default.OpenInFull, contentDescription = "全屏继续")
                        }
                    }
                    IconButton(onClick = { showHistory = true }, enabled = !sending) {
                        Icon(Icons.Default.History, contentDescription = "历史对话")
                    }
                    IconButton(onClick = { viewModel.newSession() }, enabled = !sending) {
                        Icon(Icons.Default.AddComment, contentDescription = "新对话")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty() && streaming == null && !compact) {
                FishEmptyState(
                    name = fishName,
                    modifier = Modifier.weight(1f),
                    onPickPrompt = { input = it }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        // 搜索要用「用户当时问的那句话」，而不是 AI 的回答：
                        // 回答里全是 Markdown 标题，拿去搜索只会搜到一堆 # 号
                        val query = if (message.fromUser) {
                            ""
                        } else {
                            messages
                                .takeWhile { it.id != message.id }
                                .lastOrNull { it.fromUser }
                                ?.content
                                .orEmpty()
                        }
                        MessageBubble(message = message, name = fishName, searchQuery = query)
                    }
                    if (streaming != null) {
                        item(key = "streaming") {
                            StreamingBubble(text = streaming.orEmpty(), name = fishName)
                        }
                    }
                }
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                sending = sending,
                reference = reference,
                onOpenReference = { showReference = true },
                onSend = {
                    val text = input
                    input = ""
                    viewModel.send(text)
                },
                onStop = { viewModel.stop() }
            )
        }
    }

    if (showReference) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showReference = false },
            sheetState = sheetState
        ) {
            ReferencePicker(
                notes = allNotes,
                folders = folders,
                current = reference,
                onApply = {
                    viewModel.setReference(it)
                    showReference = false
                }
            )
        }
    }

    if (showHistory) {
        val historySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = historySheetState
        ) {
            HistorySheet(
                sessions = sessions,
                onOpen = { id ->
                    viewModel.openSession(id)
                    showHistory = false
                },
                onDelete = { id -> viewModel.deleteSession(id) },
                onNew = {
                    viewModel.newSession()
                    showHistory = false
                }
            )
        }
    }
}

/** 历史对话列表 */
@Composable
private fun HistorySheet(
    sessions: List<ChatSession>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "历史对话",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNew) { Text("新对话") }
        }

        if (sessions.isEmpty()) {
            Text(
                text = "还没有历史对话。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(items = sessions, key = { it.id }) { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(session.id) }
                            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatDateTime(session.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDelete(session.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除这段对话")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 首屏：标志 + 名字 + 能力说明 + 示例问题 */
@Composable
private fun FishEmptyState(
    name: String,
    modifier: Modifier = Modifier,
    onPickPrompt: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fish_robot),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "会读你的笔记，帮你串知识点、找关联、补背景",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        listOf(
            "把这几节课的知识点串成一条线",
            "这个概念的背景是什么，为什么要提它",
            "把最近一周的笔记整理成复习提纲",
            "我笔记里前后矛盾的地方有哪些"
        ).forEach { prompt ->
            AssistChip(
                onClick = { onPickPrompt(prompt) },
                label = { Text(prompt) },
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "回答可能不准确，重要内容请回原始笔记核对。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, name: String, searchQuery: String) {
    if (message.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    MarkdownBody(markdown = message.content)
                    BrowserSearchRow(query = searchQuery)
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, name: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (text.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在翻你的笔记…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    MarkdownBody(markdown = text)
                }
            }
        }
    }
}

/** 每条回答下面给一个「去浏览器搜」的出口 —— 没联网能力时这是最诚实的选择 */
@Composable
private fun BrowserSearchRow(query: String) {
    val context = LocalContext.current
    // 去掉 Markdown 标题行，只留正文里的话
    val clean = query.lineSequence()
        .filterNot { it.trimStart().startsWith("#") }
        .joinToString(" ")
        .trim()
    if (clean.isBlank()) return

    TextButton(
        onClick = { openWebSearch(context, clean.take(80)) },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Text("在浏览器里搜索这个问题", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    reference: FishReference,
    onOpenReference: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // 左下角：选参考范围
                IconButton(onClick = onOpenReference) {
                    Box {
                        Icon(
                            imageVector = Icons.Default.Checklist,
                            contentDescription = "选择参考笔记",
                            tint = if (reference.isEmpty) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问点什么") },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (sending) onStop() else onSend() },
                    enabled = sending || value.isNotBlank()
                ) {
                    Icon(
                        imageVector = if (sending) Icons.Default.Stop else Icons.Default.Send,
                        contentDescription = if (sending) "停止" else "发送"
                    )
                }
            }
            if (!reference.isEmpty) {
                Text(
                    text = "参考：${reference.count} 项已选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * 参考范围选择。
 *
 * 第一层直接列分类（没有分类的笔记归到「未分类」），不用一路往下滑；
 * 单点打勾，双击（或点右边的箭头）进分类看具体笔记。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReferencePicker(
    notes: List<Note>,
    folders: List<Folder>,
    current: FishReference,
    onApply: (FishReference) -> Unit
) {
    var noteIds by remember { mutableStateOf(current.noteIds) }
    var folderIds by remember { mutableStateOf(current.folderIds) }
    var openedFolder by remember { mutableStateOf<Long?>(null) }

    val openedName = openedFolder?.let { id ->
        if (id == FishReference.UNFILED_ID) "未分类" else folders.firstOrNull { it.id == id }?.name ?: "分类"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (openedFolder != null) {
                IconButton(onClick = { openedFolder = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回分类")
                }
            }
            Text(
                text = openedName ?: "参考范围",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                noteIds = emptySet()
                folderIds = emptySet()
            }) { Text("清空") }
        }
        Text(
            text = if (openedFolder == null) {
                "单击打勾，双击（或点右侧箭头）查看分类里的笔记。不勾选时默认参考全部笔记。"
            } else {
                "勾选这个分类里的具体笔记。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        val folderId = openedFolder
        if (folderId == null) {
            val entries = buildList {
                folders.forEach { folder ->
                    add(Triple(folder.id, folder.name, notes.count { it.folderId == folder.id }))
                }
                add(
                    Triple(
                        FishReference.UNFILED_ID,
                        "未分类",
                        notes.count { it.folderId == null }
                    )
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(items = entries, key = { it.first }) { entry ->
                    FolderPickRow(
                        name = entry.second,
                        count = entry.third,
                        checked = entry.first in folderIds,
                        onToggle = { folderIds = toggle(folderIds, entry.first) },
                        onOpen = { openedFolder = entry.first }
                    )
                }
            }
        } else {
            val list = notes.filter {
                if (folderId == FishReference.UNFILED_ID) it.folderId == null
                else it.folderId == folderId
            }
            if (list.isEmpty()) {
                Text(
                    text = "这个分类里还没有笔记。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(items = list, key = { it.id }) { note ->
                        NotePickRow(
                            name = note.title.ifBlank { "未命名笔记" },
                            hint = formatDateTime(note.createdAt),
                            checked = note.id in noteIds,
                            onToggle = { noteIds = toggle(noteIds, note.id) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (noteIds.isEmpty() && folderIds.isEmpty()) {
                    "当前：全部笔记"
                } else {
                    "已选 ${noteIds.size} 篇笔记、${folderIds.size} 个分类"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onApply(FishReference(noteIds, folderIds)) }) {
                Text("完成")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun <T> toggle(set: Set<T>, value: T): Set<T> =
    if (value in set) set - value else set + value

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPickRow(
    name: String,
    count: Int,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onDoubleClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$count 篇",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.Default.ChevronRight, contentDescription = "查看这个分类里的笔记")
        }
    }
}

@Composable
private fun NotePickRow(
    name: String,
    hint: String?,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
