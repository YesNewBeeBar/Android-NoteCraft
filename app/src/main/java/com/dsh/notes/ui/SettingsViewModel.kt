package com.dsh.notes.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.ai.PromptBuilder
import com.dsh.notes.audio.FloatingBallService
import com.dsh.notes.audio.LocalTranscriber
import com.dsh.notes.data.AppSettings
import com.dsh.notes.data.AsrEngine
import com.dsh.notes.data.CryptoUtils
import com.dsh.notes.util.CacheManager
import com.dsh.notes.util.CacheUsage
import com.dsh.notes.util.formatSize

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _locationLabel = MutableStateFlow("")

    /** 导出目录的可读名字，例如「Documents/NoteCraft」 */
    val locationLabel: StateFlow<String> = _locationLabel.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    /** 系统离线识别在这台设备上到底能不能用 */
    val localEngineLabel: String = LocalTranscriber(container.context).engineLabel()

    val localEngineAvailable: Boolean = LocalTranscriber(container.context).isAvailable

    // ---------------------------------------------------------------- 悬浮球

    fun setFloatingBallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setFloatingBallEnabled(enabled)
            if (enabled) {
                if (FloatingBallService.canShow(container.context)) {
                    FloatingBallService.show(container.context)
                } else {
                    _message.value = "还没有「显示在其他应用上层」权限"
                }
            } else {
                FloatingBallService.hide(container.context)
            }
        }
    }

    fun setFloatingBallSize(dp: Int) {
        viewModelScope.launch { container.settings.setFloatingBallSize(dp) }
    }

    fun setFloatingBallAlpha(alpha: Float) {
        viewModelScope.launch { container.settings.setFloatingBallAlpha(alpha) }
    }

    /** 大小/透明度/图案改完后重建一次悬浮窗，让改动立刻可见 */
    fun restartFloatingBall() {
        viewModelScope.launch {
            val enabled = container.settings.current().floatingBallEnabled
            if (enabled) FloatingBallService.restart(container.context)
        }
    }

    /** 相册里挑的图存进 App 私有目录，只留一张，避免越攒越多 */
    fun setFloatingBallIcon(uri: Uri) {
        viewModelScope.launch {
            try {
                val target = File(container.context.filesDir, FLOATING_ICON_NAME)
                container.context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("读不到这张图")

                container.settings.setFloatingBallIconPath(target.absolutePath)
                FloatingBallService.restart(container.context)
            } catch (error: Exception) {
                _message.value = error.message ?: "设置图案失败"
            }
        }
    }

    fun resetFloatingBallIcon() {
        viewModelScope.launch {
            runCatching { File(container.context.filesDir, FLOATING_ICON_NAME).delete() }
            container.settings.setFloatingBallIconPath("")
            FloatingBallService.restart(container.context)
        }
    }

    companion object {
        private const val FLOATING_ICON_NAME = "floating_ball_icon.png"
    }

    val defaultSystemPrompt: String = PromptBuilder.DEFAULT_SYSTEM_PROMPT

    val defaultAsrPrompt: String = PromptBuilder.DEFAULT_ASR_PROMPT

    val templates: List<PromptBuilder.PromptTemplate> = PromptBuilder.TEMPLATES

    private val cacheManager = CacheManager(container.context, container.repository)

    private val _cacheUsage = MutableStateFlow(CacheUsage())

    /** 缓存占用；进设置页时算一次，清理后再算一次 */
    val cacheUsage: StateFlow<CacheUsage> = _cacheUsage.asStateFlow()

    fun refreshCacheUsage() {
        viewModelScope.launch { _cacheUsage.value = cacheManager.scan() }
    }

    /** 清临时文件 + 无主音频；被笔记引用的录音不会动 */
    fun clearCache() {
        viewModelScope.launch {
            val freed = cacheManager.clear()
            _cacheUsage.value = cacheManager.scan()
            _message.value =
                if (freed > 0) "已清除 ${formatSize(freed)}" else "没有可清除的缓存"
        }
    }

    // ---- 更新 ----

    /** 当前版本号，显示在「检查更新」那一行 */
    val currentVersion: String = com.dsh.notes.BuildConfig.VERSION_NAME

    /**
     * 手动检查更新。先清掉「稍后」记录 ——
     * 用户都主动点了，就不该再被上次的忽略挡住。
     */
    fun checkUpdate() {
        viewModelScope.launch {
            container.updater.forgetIgnored()
            container.updater.check(manual = true)?.let { _message.value = it }
        }
    }

    /** 当前生效的是哪一套（自定义 / 模板：xx / 内置默认） */
    fun activePromptSource(settings: AppSettings): String = PromptBuilder.activeSourceLabel(settings)

    init {
        viewModelScope.launch {
            container.settings.flow.collect { current ->
                _locationLabel.value = if (current.exportTreeUri.isBlank()) {
                    "未设置"
                } else {
                    container.exporter.describeLocation(current.exportTreeUri)
                }
            }
        }
    }

    // ---- DeepSeek ----
    fun setDeepseekBaseUrl(value: String) = launch { container.settings.setDeepseekBaseUrl(value) }
    fun setDeepseekApiKey(value: String) = launch { container.settings.setDeepseekApiKey(value) }
    fun setDeepseekModel(value: String) = launch { container.settings.setDeepseekModel(value) }
    fun setTemperature(value: Float) = launch { container.settings.setTemperature(value) }
    fun setSystemPrompt(value: String) = launch { container.settings.setCustomSystemPrompt(value) }

    // ---- 转写 ----
    fun setAsrEngine(value: AsrEngine) = launch { container.settings.setAsrEngine(value) }
    fun setAsrBaseUrl(value: String) = launch { container.settings.setAsrBaseUrl(value) }
    fun setAsrApiKey(value: String) = launch { container.settings.setAsrApiKey(value) }
    fun setAsrModel(value: String) = launch { container.settings.setAsrModel(value) }
    fun setLocalLanguage(value: String) = launch { container.settings.setLocalAsrLanguage(value) }

    // ---- 提示词 ----
    fun setAsrPrompt(value: String) = launch { container.settings.setAsrPrompt(value) }

    fun setOrganizePrompt(value: String) = launch { container.settings.setCustomSystemPrompt(value) }

    /** 选模板会自动让自定义提示词让位，避免两套设置打架 */
    fun selectTemplate(id: String) = launch {
        val current = container.settings.current()
        val next = if (current.activePromptTemplate == id) "" else id
        container.settings.setActivePromptTemplate(next)
        if (next.isNotBlank()) container.settings.setCustomSystemPrompt("")
    }

    fun setStorageFormat(value: com.dsh.notes.data.StorageFormat) =
        launch { container.settings.setStorageFormat(value) }

    // ---- 录音 ----
    fun setSampleRate(value: Int) = launch { container.settings.setSampleRate(value) }
    fun setLiveCaption(value: Boolean) = launch { container.settings.setLiveCaptionEnabled(value) }
    fun setLiveCaptionChunk(value: Int) = launch { container.settings.setLiveCaptionChunkMs(value) }
    fun setAutoProcess(value: Boolean) = launch { container.settings.setAutoProcess(value) }
    fun setAutoFallback(value: Boolean) = launch { container.settings.setAutoFallbackToLocal(value) }

    // ---- 导出 ----
    fun setExportSubDir(value: String) = launch { container.settings.setExportSubDir(value) }
    fun setAutoExport(value: Boolean) = launch { container.settings.setAutoExport(value) }
    fun setFileNamePattern(value: String) = launch { container.settings.setFileNamePattern(value) }

    fun onDirectoryPicked(uri: Uri) {
        viewModelScope.launch {
            container.exporter.persistPermission(uri)
            container.settings.setExportTreeUri(uri.toString())
            _message.value = "保存位置已更新"
        }
    }

    fun clearExportDir() {
        viewModelScope.launch {
            container.settings.setExportTreeUri("")
            _message.value = "已清除保存位置"
        }
    }

    /** 看看 Key 到底存成什么样：只显示前几位与总长度，避免明文外泄 */
    fun describeKey(raw: String): String = when {
        raw.isBlank() -> "未填写"
        raw.length <= 8 -> "已填写（${raw.length} 位）"
        else -> "${raw.take(4)}…${raw.takeLast(4)}（${raw.length} 位）"
    }

    /** 自检：Key 的加解密链路是否可用 */
    fun selfTestCrypto() {
        val sample = "sk-selftest-123456"
        val ok = CryptoUtils.decrypt(CryptoUtils.encrypt(sample)) == sample
        _message.value = if (ok) "密钥存储自检通过（Keystore 加解密正常）" else "密钥存储自检失败！"
    }

    /** 把内置提示词弹出来看看：用户要自定义时至少知道默认长什么样 */

    fun clearMessage() {
        _message.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
