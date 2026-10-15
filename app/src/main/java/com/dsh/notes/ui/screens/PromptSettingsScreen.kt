package com.dsh.notes.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.SettingsViewModel

/**
 * 提示词页（设置页右上角进入）。
 *
 * 刻意把两套提示词放在同一页但分成两块，因为它们的机制完全不同：
 *  - 上面是**转写提示词**：本质是给 Whisper 的「术语线索」，很短，影响听写准确率；
 *  - 下面是**整理提示词**：真正的系统指令，较长，决定笔记长什么样。
 *
 * 用户很容易把两者当成一回事，所以每块都写了用途说明、长度建议和内置默认全文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptSettingsScreen(
    viewModelFactory: NoteCraftViewModelFactory,
    onBack: () -> Unit
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提示词") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ==================== 转写提示词 ====================
            SectionCard(
                icon = Icons.Default.Mic,
                title = "转写提示词",
                subtitle = "给转写模型的「术语线索」，不是命令。用来减少专有名词听错"
            ) {
                Text(
                    text = "怎么写才有用：列出这段录音里可能出现的专业词、人名、缩写、英文词，" +
                        "例如「涉及 Kotlin、Compose、Room、协程」。模型会把它当上下文参考。\n" +
                        "写「请认真转写」这类话没有作用。建议 50 字以内。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                DraftTextField(
                    label = "术语线索（留空则用内置默认）",
                    placeholder = viewModel.defaultAsrPrompt,
                    externalValue = settings.asrPrompt,
                    minLines = 3,
                    onSave = viewModel::setAsrPrompt,
                    onFillDefault = { viewModel.setAsrPrompt("") }
                )

                InfoLine("内置默认：${viewModel.defaultAsrPrompt}")
                InfoLine("不是所有转写服务商都支持这个参数，不支持的会直接忽略它（不影响转写本身）。")
            }

            // ==================== 整理提示词 ====================
            SectionCard(
                icon = Icons.Default.AutoAwesome,
                title = "整理提示词",
                subtitle = "真正的系统指令，决定转写文字被整理成什么样子"
            ) {
                Text(
                    text = "当前生效：${viewModel.activePromptSource(settings)}\n" +
                        "优先级：你手写的内容 > 选中的模板 > 内置默认。\n" +
                        "无论写什么，App 都会自动附加 JSON 字段约定（标题/摘要/要点/待办/标签/正文），" +
                        "所以不用自己操心输出格式。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "模板库（点一下套用，再点一下取消）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.templates.take(2).forEach { template ->
                        FilterChip(
                            selected = settings.activePromptTemplate == template.id,
                            onClick = { viewModel.selectTemplate(template.id) },
                            label = { Text(template.name) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.templates.drop(2).forEach { template ->
                        FilterChip(
                            selected = settings.activePromptTemplate == template.id,
                            onClick = { viewModel.selectTemplate(template.id) },
                            label = { Text(template.name) }
                        )
                    }
                }

                // 选中的模板内容摊开给用户看，别让他猜
                TemplateDetail(settings.activePromptTemplate, viewModel)

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))

                DraftTextField(
                    label = "自定义整理要求（留空则用模板或内置默认）",
                    placeholder = "例如：按课堂板书的结构整理；保留所有公式；用简体中文",
                    externalValue = settings.customSystemPrompt,
                    minLines = 8,
                    onSave = viewModel::setOrganizePrompt,
                    onFillDefault = { viewModel.setOrganizePrompt("") }
                )

                InfoLine("手写内容一旦保存就会覆盖模板选择；想回到模板，把这里清空再保存。")
            }

            // ==================== 内置默认全文 ====================
            SectionCard(
                icon = Icons.Default.Restore,
                title = "内置默认整理提示词（全文）",
                subtitle = "想改就整段复制走，改成自己的版本"
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = viewModel.defaultSystemPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { viewModel.setOrganizePrompt(viewModel.defaultSystemPrompt) }
                    ) { Text("用这份内置默认作为我的自定义") }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 带「保存 / 还原」的草稿输入框。
 *
 * 关键点：外部值变化时**只在用户没有未保存修改的情况下**同步到草稿。
 * 上一版用 `remember(externalValue)` 会在点「填入默认」之后
 * 把用户正在输入的内容直接冲掉——这类 bug 在编辑器里最招人烦。
 */
@Composable
private fun DraftTextField(
    label: String,
    placeholder: String,
    externalValue: String,
    minLines: Int,
    onSave: (String) -> Unit,
    onFillDefault: () -> Unit
) {
    var text by remember { mutableStateOf(externalValue) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(externalValue) {
        if (!dirty && text != externalValue) {
            text = externalValue
            dirty = false
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            dirty = true
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = {
            onFillDefault()
            text = ""
            dirty = false
        }) {
            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("清空")
        }
        Spacer(Modifier.size(8.dp))
        Button(
            onClick = {
                onSave(text.trim())
                dirty = false
            },
            enabled = dirty
        ) { Text("保存") }
    }
}

/** 把选中模板的说明与正文摊开 */
@Composable
private fun TemplateDetail(templateId: String, viewModel: SettingsViewModel) {
    val template = viewModel.templates.firstOrNull { it.id == templateId } ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${template.name} · ${template.description}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = template.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}
