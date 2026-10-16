package com.dsh.notes.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.data.ThemeMode
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.NotesViewModel
import com.dsh.notes.ui.components.NoteCard
import com.dsh.notes.ui.components.ActivityBars
import com.dsh.notes.ui.components.ThemeToggleButton

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
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()


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
        topBar = {
            TopAppBar(
                title = { Text("我的笔记") },
                actions = { ThemeToggleButton(themeMode = themeMode, onToggle = onToggleTheme) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickAudio.launch(arrayOf("audio/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入音频") }
            )
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
                        NoteCard(
                            note = note,
                            onClick = { onOpenNote(note.id) },
                            modifier = Modifier.animateItem(),
                            queued = note.id == queueState.currentNoteId ||
                                note.id in queueState.waitingIds
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
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
