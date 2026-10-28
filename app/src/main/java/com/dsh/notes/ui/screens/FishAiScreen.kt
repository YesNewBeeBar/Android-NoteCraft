package com.dsh.notes.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
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
import com.dsh.notes.data.Folder
import com.dsh.notes.data.Note
import com.dsh.notes.ui.FishAiViewModel
import com.dsh.notes.ui.FishReference
import com.dsh.notes.ui.markdown.MarkdownText
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
    onClose: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val reference by viewModel.reference.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val allNotes by viewModel.allNotes.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var showReference by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // 新消息 / 流式增量都跟着滚到底
    LaunchedEffect(messages.size, streaming?.length) {
        val count = messages.size + if (streaming != null) 1 else 0
        if (count > 0) runCatching { listState.animateScrollToItem(count - 1) }
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
            if (messages.isEmpty() && streaming == null) {
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
                        MessageBubble(message = message, name = fishName)
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
private fun MessageBubble(message: ChatMessage, name: String) {
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
                    MarkdownText(markdown = message.content)
                    BrowserSearchRow(question = message.content)
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
                    MarkdownText(markdown = text)
                }
            }
        }
    }
}

/** 每条回答下面给一个「去浏览器搜」的出口 —— 没联网能力时这是最诚实的选择 */
@Composable
private fun BrowserSearchRow(question: String) {
    val context = LocalContext.current
    TextButton(
        onClick = { openWebSearch(context, question.take(80)) },
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
                    placeholder = { Text(if (reference.isEmpty) "问点什么…（默认参考全部笔记）" else "问点什么…") },
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

/** 参考范围选择：分类 + 笔记，两类可以混选 */
@Composable
private fun ReferencePicker(
    notes: List<Note>,
    folders: List<Folder>,
    current: FishReference,
    onApply: (FishReference) -> Unit
) {
    var noteIds by remember { mutableStateOf(current.noteIds) }
    var folderIds by remember { mutableStateOf(current.folderIds) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "参考范围",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                noteIds = emptySet()
                folderIds = emptySet()
            }) { Text("清空") }
        }
        Text(
            text = "不选任何项时，默认参考全部笔记（只发摘要，更省长度）。" +
                "勾选后只发这些笔记整理后的正文。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            if (folders.isNotEmpty()) {
                item { SectionTitle("按分类") }
                items(items = folders, key = { "f${it.id}" }) { folder ->
                    val count = notes.count { it.folderId == folder.id }
                    PickRow(
                        label = folder.name,
                        hint = "$count 篇",
                        checked = folder.id in folderIds,
                        onToggle = {
                            folderIds = if (folder.id in folderIds) {
                                folderIds - folder.id
                            } else {
                                folderIds + folder.id
                            }
                        }
                    )
                }
            }
            item { SectionTitle("按笔记") }
            items(items = notes, key = { "n${it.id}" }) { note ->
                PickRow(
                    label = note.title.ifBlank { "未命名笔记" },
                    hint = if (note.folderId != null) null else "未分类",
                    checked = note.id in noteIds,
                    onToggle = {
                        noteIds = if (note.id in noteIds) {
                            noteIds - note.id
                        } else {
                            noteIds + note.id
                        }
                    }
                )
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
            IconButton(onClick = { onApply(FishReference()) }) {
                Icon(Icons.Default.Close, contentDescription = "重置")
            }
            TextButton(onClick = { onApply(FishReference(noteIds, folderIds)) }) {
                Text("完成")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun PickRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
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
