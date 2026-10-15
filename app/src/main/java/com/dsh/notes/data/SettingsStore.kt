package com.dsh.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 音频转写走哪里 */
/** 录音落盘格式 */
enum class StorageFormat {
    /** m4a / AAC 32kbps，约 0.24MB/分钟。省空间，但拿不到 PCM，做不了实时上屏 */
    M4A,

    /** 16kHz 单声道 WAV，约 1.92MB/分钟。大 8 倍，但支持实时上屏与静音 */
    WAV;

    val label: String
        get() = when (this) {
            M4A -> "m4a（省空间）"
            WAV -> "WAV（支持实时上屏）"
        }

    val approximateBytesPerMinute: Long
        get() = when (this) {
            M4A -> 240_000L
            WAV -> 1_920_000L
        }
}

enum class AsrEngine {
    /** 云端 OpenAI 兼容接口（Whisper / SenseVoice 等），准确率高，音频要上传 */
    CLOUD,

    /** 系统离线语音识别，音频不出手机，适合短句、网络差 */
    LOCAL;

    val label: String
        get() = when (this) {
            CLOUD -> "云端 API"
            LOCAL -> "系统离线识别"
        }
}

/** 录音 / 转写 / 整理的全部可调项 */
data class AppSettings(
    // ---------- DeepSeek（笔记整理） ----------
    val deepseekBaseUrl: String = DEFAULT_DEEPSEEK_BASE_URL,
    val deepseekApiKey: String = "",
    val deepseekModel: String = DEFAULT_DEEPSEEK_MODEL,
    val temperature: Float = 0.3f,
    /** 整理提示词：空串表示用内置默认那套 */
    val customSystemPrompt: String = "",

    /** 用户从模板库里选的那套；为空表示没选，直接用内置默认或自定义 */
    val activePromptTemplate: String = "",

    // ---------- 音频转写 ----------
    val asrEngine: AsrEngine = AsrEngine.CLOUD,
    val asrBaseUrl: String = DEFAULT_ASR_BASE_URL,
    val asrApiKey: String = "",
    val asrModel: String = DEFAULT_ASR_MODEL,
    val localAsrLanguage: String = "zh-CN",

    /**
     * 传给转写接口的 prompt：不是给人看的指令，而是「术语表 / 上下文线索」，
     * Whisper 一类模型会用它来减少专有名词的听错率（有的服务商忽略这个参数）。
     */
    val asrPrompt: String = "",

    // ---------- 录音 ----------
    val sampleRate: Int = 16000,

    /** 落盘格式。实时上屏开着时会被强制成 WAV（切片需要 PCM） */
    val storageFormat: StorageFormat = StorageFormat.M4A,
    /** 实时上屏总开关 */
    val liveCaptionEnabled: Boolean = true,
    val liveCaptionChunkMs: Int = 8000,
    /** 录音结束后自动跑「转写 + 整理」 */
    val autoProcess: Boolean = true,
    /** 云转写失败时自动回落系统离线识别 */
    val autoFallbackToLocal: Boolean = true,

    // ---------- 导出 ----------
    val exportTreeUri: String = "",
    val exportSubDir: String = "NoteCraft",
    val autoExport: Boolean = false,
    val fileNamePattern: String = "{date}-{time}-{title}"
) {
    val deepseekReady: Boolean
        get() = deepseekApiKey.isNotBlank()

    val cloudAsrReady: Boolean
        get() = asrApiKey.isNotBlank() && asrBaseUrl.isNotBlank() && asrModel.isNotBlank()

    val hasExportDir: Boolean
        get() = exportTreeUri.isNotBlank()

    /** 当前引擎是否具备转写条件 */
    val canTranscribe: Boolean
        get() = when (asrEngine) {
            AsrEngine.CLOUD -> cloudAsrReady
            AsrEngine.LOCAL -> true
        }

    /** 实时上屏只能靠云接口：本地识别要独占麦克风，无法与录音并行 */
    val canLiveCaption: Boolean
        get() = liveCaptionEnabled && cloudAsrReady

    companion object {
        const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"

        /**
         * 默认走硅基流动的 SenseVoice：国内可直连、便宜、中文准。
         * 换成 OpenAI 官方就是 https://api.openai.com/v1 + whisper-1。
         */
        const val DEFAULT_ASR_BASE_URL = "https://api.siliconflow.cn/v1"
        const val DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
    }
}

private val Context.settingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notecraft_settings"
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val deepseekBaseUrl = stringPreferencesKey("deepseek_base_url")
        val deepseekApiKey = stringPreferencesKey("deepseek_api_key_enc")
        val deepseekModel = stringPreferencesKey("deepseek_model")
        val temperature = stringPreferencesKey("temperature")
        val customSystemPrompt = stringPreferencesKey("custom_system_prompt")
        val activePromptTemplate = stringPreferencesKey("active_prompt_template")

        val asrEngine = stringPreferencesKey("asr_engine")
        val asrBaseUrl = stringPreferencesKey("asr_base_url")
        val asrApiKey = stringPreferencesKey("asr_api_key_enc")
        val asrModel = stringPreferencesKey("asr_model")
        val localAsrLanguage = stringPreferencesKey("local_asr_language")
        val asrPrompt = stringPreferencesKey("asr_prompt")

        val sampleRate = intPreferencesKey("sample_rate")
        val storageFormat = stringPreferencesKey("storage_format")
        val liveCaptionEnabled = booleanPreferencesKey("live_caption_enabled")
        val liveCaptionChunkMs = intPreferencesKey("live_caption_chunk_ms")
        val autoProcess = booleanPreferencesKey("auto_process")
        val autoFallbackToLocal = booleanPreferencesKey("auto_fallback_local")

        val exportTreeUri = stringPreferencesKey("export_tree_uri")
        val exportSubDir = stringPreferencesKey("export_sub_dir")
        val autoExport = booleanPreferencesKey("auto_export")
        val fileNamePattern = stringPreferencesKey("file_name_pattern")
    }

    val flow: Flow<AppSettings> = context.settingDataStore.data.map { prefs ->
        AppSettings(
            deepseekBaseUrl = prefs[Keys.deepseekBaseUrl] ?: AppSettings.DEFAULT_DEEPSEEK_BASE_URL,
            deepseekApiKey = CryptoUtils.decrypt(prefs[Keys.deepseekApiKey] ?: ""),
            deepseekModel = prefs[Keys.deepseekModel] ?: AppSettings.DEFAULT_DEEPSEEK_MODEL,
            temperature = (prefs[Keys.temperature] ?: "0.3").toFloatOrNull() ?: 0.3f,
            customSystemPrompt = prefs[Keys.customSystemPrompt] ?: "",
            activePromptTemplate = prefs[Keys.activePromptTemplate] ?: "",
            asrEngine = runCatching {
                AsrEngine.valueOf(prefs[Keys.asrEngine] ?: AsrEngine.CLOUD.name)
            }.getOrDefault(AsrEngine.CLOUD),
            asrBaseUrl = prefs[Keys.asrBaseUrl] ?: AppSettings.DEFAULT_ASR_BASE_URL,
            asrApiKey = CryptoUtils.decrypt(prefs[Keys.asrApiKey] ?: ""),
            asrModel = prefs[Keys.asrModel] ?: AppSettings.DEFAULT_ASR_MODEL,
            localAsrLanguage = prefs[Keys.localAsrLanguage] ?: "zh-CN",
            asrPrompt = prefs[Keys.asrPrompt] ?: "",
            sampleRate = prefs[Keys.sampleRate] ?: 16000,
            storageFormat = runCatching {
                StorageFormat.valueOf(prefs[Keys.storageFormat] ?: StorageFormat.M4A.name)
            }.getOrDefault(StorageFormat.M4A),
            liveCaptionEnabled = prefs[Keys.liveCaptionEnabled] ?: true,
            liveCaptionChunkMs = prefs[Keys.liveCaptionChunkMs] ?: 8000,
            autoProcess = prefs[Keys.autoProcess] ?: true,
            autoFallbackToLocal = prefs[Keys.autoFallbackToLocal] ?: true,
            exportTreeUri = prefs[Keys.exportTreeUri] ?: "",
            exportSubDir = prefs[Keys.exportSubDir] ?: "NoteCraft",
            autoExport = prefs[Keys.autoExport] ?: false,
            fileNamePattern = prefs[Keys.fileNamePattern] ?: "{date}-{time}-{title}"
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun setDeepseekBaseUrl(value: String) = put(Keys.deepseekBaseUrl, value.trim())

    suspend fun setDeepseekApiKey(value: String) =
        put(Keys.deepseekApiKey, CryptoUtils.encrypt(value.trim()))

    suspend fun setDeepseekModel(value: String) = put(Keys.deepseekModel, value.trim())

    suspend fun setTemperature(value: Float) = put(Keys.temperature, value.toString())

    suspend fun setCustomSystemPrompt(value: String) = put(Keys.customSystemPrompt, value)

    suspend fun setAsrEngine(value: AsrEngine) = put(Keys.asrEngine, value.name)

    suspend fun setAsrBaseUrl(value: String) = put(Keys.asrBaseUrl, value.trim())

    suspend fun setAsrApiKey(value: String) = put(Keys.asrApiKey, CryptoUtils.encrypt(value.trim()))

    suspend fun setAsrModel(value: String) = put(Keys.asrModel, value.trim())

    suspend fun setLocalAsrLanguage(value: String) = put(Keys.localAsrLanguage, value.trim())

    suspend fun setAsrPrompt(value: String) = put(Keys.asrPrompt, value)

    suspend fun setStorageFormat(value: StorageFormat) = put(Keys.storageFormat, value.name)

    suspend fun setActivePromptTemplate(value: String) = put(Keys.activePromptTemplate, value)

    suspend fun setSampleRate(value: Int) = put(Keys.sampleRate, value)

    suspend fun setLiveCaptionEnabled(value: Boolean) = put(Keys.liveCaptionEnabled, value)

    suspend fun setLiveCaptionChunkMs(value: Int) = put(Keys.liveCaptionChunkMs, value)

    suspend fun setAutoProcess(value: Boolean) = put(Keys.autoProcess, value)

    suspend fun setAutoFallbackToLocal(value: Boolean) = put(Keys.autoFallbackToLocal, value)

    suspend fun setExportTreeUri(value: String) = put(Keys.exportTreeUri, value)

    suspend fun setExportSubDir(value: String) = put(Keys.exportSubDir, value.trim())

    suspend fun setAutoExport(value: Boolean) = put(Keys.autoExport, value)

    suspend fun setFileNamePattern(value: String) = put(Keys.fileNamePattern, value.trim())

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingDataStore.edit { it[key] = value }
    }
}
