package com.dsh.notes.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.notes.data.AppSettings
import com.dsh.notes.data.AsrEngine
import com.dsh.notes.data.ConfigArea
import com.dsh.notes.data.ConfigField
import com.dsh.notes.data.ConfigValidator
import com.dsh.notes.data.StorageFormat
import com.dsh.notes.data.ThemeMode
import com.dsh.notes.ui.NoteCraftViewModelFactory
import com.dsh.notes.ui.SettingsViewModel
import com.dsh.notes.ui.components.ThemeToggleButton
import com.dsh.notes.util.WebLinks
import com.dsh.notes.util.ellipsizeMiddle
import com.dsh.notes.util.formatSize
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 每个输入框都做成「失焦/点保存才写库」：DataStore 是异步的，
 * 如果每敲一个字符就写一次，光标会跳、还会出现闪回旧值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModelFactory: NoteCraftViewModelFactory,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    /** 从录音页被拦下时带过来的区块（deepseek / asr）；平时是空串 */
    focusArea: String = "",
    onOpenPrompts: () -> Unit
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val locationLabel by viewModel.locationLabel.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val cacheUsage by viewModel.cacheUsage.collectAsStateWithLifecycle()

    var showClearCacheDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // 配置体检：哪个框有问题就标红并说明原因。用的是和录音页同一份判定，
    // 所以用户改好一个字段，红标立刻消失，不会两边说法不一致。
    val problems = remember(settings, viewModel.localEngineAvailable) {
        ConfigValidator.validate(settings, localEngineUsable = viewModel.localEngineAvailable)
    }
    val problemsByField = remember(problems) { problems.associateBy { it.field } }

    fun fieldError(field: ConfigField): String? = problemsByField[field]?.message

    /*
     * 自动滚到出问题的那一节。
     *
     * 位置只在第一次布局时记一次（那时滚动量还是 0）：用「节的顶 - 滚动容器的顶」
     * 得到它在内容里的偏移。之后滚动时这两个值会一起变，差值不变，所以不必反复更新。
     */
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var contentTop by remember { mutableStateOf(-1f) }
    var deepseekTop by remember { mutableStateOf(-1f) }
    var asrTop by remember { mutableStateOf(-1f) }

    LaunchedEffect(focusArea, contentTop, deepseekTop, asrTop) {
        if (contentTop < 0f) return@LaunchedEffect
        val sectionTop = when (focusArea) {
            ConfigArea.DEEPSEEK.routeKey -> deepseekTop
            ConfigArea.ASR.routeKey -> asrTop
            else -> return@LaunchedEffect
        }
        if (sectionTop < 0f) return@LaunchedEffect
        // 往上留一点余量，免得目标节的标题正好贴着标题栏
        val target = (sectionTop - contentTop - 12f).coerceAtLeast(0f)
        scope.launch { scrollState.animateScrollTo(target.toInt()) }
    }

    // 进设置页就把占用算一遍，让用户点之前心里有数
    LaunchedEffect(Unit) { viewModel.refreshCacheUsage() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val pickDirectory = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onDirectoryPicked) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                actions = { ThemeToggleButton(themeMode = themeMode, onToggle = onToggleTheme) }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .onGloballyPositioned { if (contentTop < 0f) contentTop = it.positionInRoot().y }
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ================= 笔记整理（DeepSeek） =================
            SettingsSection(
                icon = Icons.Default.Psychology,
                title = "笔记整理（DeepSeek）",
                subtitle = "把转写文字整理成结构化 Markdown 笔记",
                modifier = Modifier.onGloballyPositioned {
                    if (deepseekTop < 0f) deepseekTop = it.positionInRoot().y
                }
            ) {
                CommittedTextField(
                    label = "API Key",
                    initial = settings.deepseekApiKey,
                    placeholder = "sk-…",
                    secret = true,
                    supporting = "只存在本机，用 Android Keystore 加密（${viewModel.describeKey(settings.deepseekApiKey)}）",
                    error = fieldError(ConfigField.DEEPSEEK_KEY),
                    onCommit = viewModel::setDeepseekApiKey
                )
                OutlinedButton(
                    onClick = { WebLinks.open(context, WebLinks.DEEPSEEK_API_KEYS) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("去 DeepSeek 申请 API Key")
                }
                CommittedTextField(
                    label = "BaseURL",
                    initial = settings.deepseekBaseUrl,
                    placeholder = AppSettings.DEFAULT_DEEPSEEK_BASE_URL,
                    supporting = "官方就是 https://api.deepseek.com；填别的 OpenAI 兼容地址也能用",
                    error = fieldError(ConfigField.DEEPSEEK_BASE_URL),
                    onCommit = viewModel::setDeepseekBaseUrl
                )
                CommittedTextField(
                    label = "模型",
                    initial = settings.deepseekModel,
                    placeholder = AppSettings.DEFAULT_DEEPSEEK_MODEL,
                    supporting = "deepseek-chat（通用）或 deepseek-reasoner（更强但更慢更贵）",
                    error = fieldError(ConfigField.DEEPSEEK_MODEL),
                    onCommit = viewModel::setDeepseekModel
                )

                TemperatureRow(
                    value = settings.temperature,
                    onChange = viewModel::setTemperature
                )

                OutlinedButton(
                    onClick = onOpenPrompts,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("自定义提示词与模板库")
                }
            }

            // ================= 音频转写 =================
            SettingsSection(
                icon = Icons.Default.Mic,
                title = "音频转写",
                subtitle = "把录音变成文字。DeepSeek 没有音频接口，所以这里要单独配一家",
                modifier = Modifier.onGloballyPositioned {
                    if (asrTop < 0f) asrTop = it.positionInRoot().y
                }
            ) {
                Text(
                    text = "转写方式",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.asrEngine == AsrEngine.CLOUD,
                        onClick = { viewModel.setAsrEngine(AsrEngine.CLOUD) },
                        label = { Text("云端 API（推荐）") }
                    )
                    FilterChip(
                        selected = settings.asrEngine == AsrEngine.LOCAL,
                        onClick = { viewModel.setAsrEngine(AsrEngine.LOCAL) },
                        label = { Text("系统离线识别") }
                    )
                }

                if (settings.asrEngine == AsrEngine.LOCAL) {
                    InfoCard(
                        text = buildString {
                            appendLine("当前设备：${viewModel.localEngineLabel}")
                            appendLine()
                            if (viewModel.localEngineAvailable) {
                                appendLine("· 音频完全不出手机，不花钱。")
                                appendLine("· 但离线识别必须**实时**占用麦克风：选它时录完只有文字，没有音频文件。")
                                appendLine("· 也不能做「实时上屏」，因为麦克风被录音占着。")
                                append("· 长句和方言的效果明显不如云端模型。")
                            } else {
                                append("这台设备没有可用的系统语音识别服务，请改用云端 API。")
                            }
                        }
                    )
                } else {
                    CommittedTextField(
                        label = "转写 API Key",
                        initial = settings.asrApiKey,
                        placeholder = "sk-…",
                        secret = true,
                        supporting = "硅基流动 / OpenAI / Groq 的 Key 都填这里（${viewModel.describeKey(settings.asrApiKey)}）",
                        error = fieldError(ConfigField.ASR_KEY),
                        onCommit = viewModel::setAsrApiKey
                    )
                    // 按填的 BaseURL 猜该去哪家申请；自建地址认不出来就干脆不显示，免得误导
                    WebLinks.asrKeyUrl(settings.asrBaseUrl)?.let { url ->
                        OutlinedButton(
                            onClick = { WebLinks.open(context, url) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("去申请转写 API Key")
                        }
                    }
                    CommittedTextField(
                        label = "转写 BaseURL",
                        initial = settings.asrBaseUrl,
                        placeholder = AppSettings.DEFAULT_ASR_BASE_URL,
                        supporting = "OpenAI 官方填 https://api.openai.com/v1",
                        error = fieldError(ConfigField.ASR_BASE_URL),
                        onCommit = viewModel::setAsrBaseUrl
                    )
                    CommittedTextField(
                        label = "转写模型",
                        initial = settings.asrModel,
                        placeholder = AppSettings.DEFAULT_ASR_MODEL,
                        supporting = "whisper-1 / FunAudioLLM/SenseVoiceSmall 等",
                        error = fieldError(ConfigField.ASR_MODEL),
                        onCommit = viewModel::setAsrModel
                    )
                }

                CommittedTextField(
                    label = "识别语言",
                    initial = settings.localAsrLanguage,
                    placeholder = "zh-CN",
                    supporting = "填 auto 或留空表示交给服务端自动判断",
                    onCommit = viewModel::setLocalLanguage
                )

                SwitchRow(
                    title = "录音时实时上屏",
                    subtitle = if (settings.cloudAsrReady) {
                        "边录边出字。需要云转写，会把每段音频实时上传"
                    } else {
                        "已开启，但还没配云转写 Key，暂时不会生效"
                    },
                    checked = settings.liveCaptionEnabled,
                    onCheckedChange = viewModel::setLiveCaption
                )

                if (settings.liveCaptionEnabled && settings.cloudAsrReady) {
                    SliceRow(
                        title = "上屏切片长度：${settings.liveCaptionChunkMs / 1000} 秒",
                        subtitle = "越短越实时，但请求次数和费用越高",
                        value = settings.liveCaptionChunkMs.toFloat(),
                        range = 4000f..20000f,
                        steps = 3,
                        onChange = { viewModel.setLiveCaptionChunk(it.toInt()) }
                    )
                }
            }

            // ================= 录音与处理 =================
            SettingsSection(
                icon = Icons.Default.Save,
                title = "录音与处理",
                subtitle = "录完自动做什么"
            ) {
                SwitchRow(
                    title = "录完自动转写并整理",
                    subtitle = "关闭后录音只落盘，需要手动在详情页点「转写 + 整理」",
                    checked = settings.autoProcess,
                    onCheckedChange = viewModel::setAutoProcess
                )

                Text(
                    text = "录音格式",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StorageFormat.entries.forEach { format ->
                        FilterChip(
                            selected = settings.storageFormat == format,
                            onClick = { viewModel.setStorageFormat(format) },
                            label = { Text(format.label) }
                        )
                    }
                }
                InfoCard(
                    text = buildString {
                        appendLine("· m4a：约 0.24MB/分钟（1 小时 ≈ 14MB），但不能做实时上屏、不能静音。")
                        appendLine("· WAV：约 1.92MB/分钟（1 小时 ≈ 115MB），支持实时上屏与静音。")
                        if (settings.liveCaptionEnabled && settings.cloudAsrReady) {
                            append("· 你现在开着实时上屏，所以录音会强制用 WAV 才能切片。想要省空间就先关掉实时上屏。")
                        } else {
                            append("· 录音时会按上面选的格式落盘。")
                        }
                    }
                )
                SwitchRow(
                    title = "云转写失败时回落离线识别",
                    subtitle = "更稳，但离线识别需要你对着手机再讲一遍",
                    checked = settings.autoFallbackToLocal,
                    onCheckedChange = viewModel::setAutoFallback
                )
                CommittedTextField(
                    label = "采样率",
                    initial = settings.sampleRate.toString(),
                    placeholder = "16000",
                    supporting = "16000 是语音识别的最佳选择，不要随便调高（文件会大三倍）",
                    onCommit = { text -> text.toIntOrNull()?.let(viewModel::setSampleRate) }
                )
            }

            // ================= Markdown 存放位置 =================
            SettingsSection(
                icon = Icons.Default.Folder,
                title = "Markdown 存放位置",
                subtitle = "选一个文件夹，之后导出的 .md 都放进去"
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "当前：${locationLabel.ifBlank { "未设置" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = settings.exportTreeUri.ellipsizeMiddle(60).ifBlank { "（还没有授权任何目录）" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickDirectory.launch(null) }) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("选择文件夹")
                    }
                    if (settings.hasExportDir) {
                        OutlinedButton(onClick = viewModel::clearExportDir) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("清除")
                        }
                    }
                }
                CommittedTextField(
                    label = "子目录名",
                    initial = settings.exportSubDir,
                    placeholder = "NoteCraft",
                    supporting = "会在你选的目录下再建这层子目录，避免文件散落",
                    onCommit = viewModel::setExportSubDir
                )
                CommittedTextField(
                    label = "文件名模板",
                    initial = settings.fileNamePattern,
                    placeholder = "{date}-{time}-{title}",
                    supporting = "可用占位符：{date} {time} {datetime} {title} {id}",
                    onCommit = viewModel::setFileNamePattern
                )
                SwitchRow(
                    title = "处理完自动导出",
                    subtitle = "每次转写整理完成后直接写入 .md，不用手动点",
                    checked = settings.autoExport,
                    onCheckedChange = viewModel::setAutoExport
                )
            }

            // ================= 存储与缓存 =================
            SettingsSection(
                icon = Icons.Default.CleaningServices,
                title = "存储与缓存",
                subtitle = "临时文件，以及已经没有笔记引用的音频"
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        UsageRow("临时文件", formatSize(cacheUsage.tempBytes))
                        Spacer(Modifier.height(6.dp))
                        UsageRow(
                            label = "无主音频",
                            value = if (cacheUsage.orphanCount == 0) {
                                "无"
                            } else {
                                "${cacheUsage.orphanCount} 个 · ${formatSize(cacheUsage.orphanBytes)}"
                            }
                        )
                    }
                }
                Text(
                    text = "「无主音频」是笔记已被删除、却没有跟着清掉的录音。" +
                        "仍被笔记引用的录音不会动 —— 那是你的数据，不是缓存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
                )
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    enabled = !cacheUsage.isEmpty
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (cacheUsage.isEmpty) "没有可清除的缓存" else "清除缓存")
                }
            }

            // ================= 关于 =================
            SettingsSection(
                icon = Icons.Default.Info,
                title = "关于与自检",
                subtitle = "排查用"
            ) {
                Text(
                    text = buildString {
                        appendLine("· 转写与整理都走 HTTP 接口，音频和文字会发送给你配置的服务商。")
                        appendLine("· API Key 用 Android Keystore 加密后存在本机，不参与云备份。")
                        appendLine("· 录音文件存在 App 私有目录，卸载 App 会一起删掉，重要录音请先导出。")
                        append("· 转写引擎当前选择：${settings.asrEngine.label}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::selfTestCrypto) {
                    Text("运行密钥存储自检")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::checkUpdate) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("检查更新（当前 v${viewModel.currentVersion}）")
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清除缓存？") },
            text = {
                Text(
                    buildString {
                        appendLine("将要清掉：")
                        appendLine("· 临时文件 ${formatSize(cacheUsage.tempBytes)}")
                        appendLine(
                            "· 无主音频 ${cacheUsage.orphanCount} 个" +
                                "（${formatSize(cacheUsage.orphanBytes)}）"
                        )
                        appendLine()
                        append("笔记里还能播放的录音不会被删除。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    viewModel.clearCache()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
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

/** 失焦或点「保存」才提交的文本框 */
@Composable
private fun CommittedTextField(
    label: String,
    initial: String,
    placeholder: String = "",
    supporting: String = "",
    /** 非空表示这个框当前有问题：框会标红，下面用红字说明原因 */
    error: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    onCommit: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var revealed by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    // 外部值变了（比如从云同步回来）就刷新；用户正在编辑时不打断
    LaunchedEffect(initial) {
        if (!dirty && initial != text) text = initial
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            dirty = true
        },
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = singleLine,
        minLines = minLines,
        isError = error != null,
        visualTransformation = if (secret && !revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = {
            if (secret) {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed) "隐藏" else "显示"
                    )
                }
            }
        },
        supportingText = if (error != null || supporting.isNotBlank()) {
            {
                Column {
                    // 颜色显式指定：isError 会把整块 supportingText 都染红，
                    // 但那行说明文字是中性提示，不该跟着变红
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (supporting.isNotBlank()) {
                        Text(
                            text = supporting,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    )

    if (dirty && text != initial) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                text = initial
                dirty = false
            }) { Text("还原") }
            Button(onClick = {
                onCommit(text)
                dirty = false
            }) { Text("保存") }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliceRow(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun TemperatureRow(value: Float, onChange: (Float) -> Unit) {
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "整理温度：${"%.1f".format(local)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "越低越忠实原文，越高越会「发挥」。整理笔记建议 0.2~0.4",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = 0f..1f,
            steps = 9
        )
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}
