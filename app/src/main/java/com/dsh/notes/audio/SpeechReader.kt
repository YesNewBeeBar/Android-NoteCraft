package com.dsh.notes.audio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale

/**
 * 用系统自带的 TTS 念笔记。不接任何在线模型，离线也能用。
 *
 * 系统 TTS 没有「暂停」这个 API（只有 stop），所以这里按句子切分，
 * 停的时候记住念到第几句，「继续」就从那句接着念 —— 听感上就是暂停/继续。
 */
class SpeechReader(context: Context) {

    private val appContext = context.applicationContext

    data class State(
        val ready: Boolean = false,
        val playing: Boolean = false,
        val paused: Boolean = false,
        /** 当前正在念第几句（0 起） */
        val index: Int = 0,
        val total: Int = 0,
        val speed: Float = 1.0f,
        /** 念的是哪一段：摘要 / 正文 */
        val source: String = "",
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var tts: TextToSpeech? = null

    /** 云端合成的配置；非空就走云端，不看系统引擎 */
    private var cloud: CloudTtsConfig? = null
    private var player: android.media.MediaPlayer? = null
    private var localPlayer: android.media.MediaPlayer? = null
    private var localJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class CloudTtsConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val voice: String
    )

    /** 切换朗读通道：传 null 用系统引擎 */
    fun setCloudMode(config: CloudTtsConfig?) {
        cloud = config
        if (config != null) localMode = false
    }

    private var localMode = false

    /** 用本地 sherpa-onnx 模型（Kokoro）朗读 */
    fun setLocalMode(enabled: Boolean) {
        localMode = enabled
        if (enabled) cloud = null
    }
    private var sentences: List<String> = emptyList()
    private var pendingText: String? = null
    private var pendingSpeed = 1.0f
    private var pendingSource = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        val engine = TextToSpeech(context.applicationContext) { status ->
            // 回调可能在构造函数还没返回时就触发（甚至来自 Binder 线程），
            // 那时 tts 还是 null。统一 post 到主线程，等赋值完成后处理。
            mainHandler.post { applyInit(status) }
        }
        tts = engine
    }

    private fun applyInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            _state.value = _state.value.copy(
                ready = false,
                error = "系统语音引擎初始化失败，请在系统设置里确认已安装并选用 TTS 引擎"
            )
            return
        }

        engine.setOnUtteranceProgressListener(listener)

        val preferred = Locale.SIMPLIFIED_CHINESE
        val availability = engine.isLanguageAvailable(preferred)
        when {
            availability == TextToSpeech.LANG_MISSING_DATA ||
                availability == TextToSpeech.LANG_NOT_SUPPORTED -> {
                // 中文数据没装：先退回系统默认语言，至少能念；同时如实告诉用户
                engine.language = Locale.getDefault()
                _state.value = _state.value.copy(
                    ready = true,
                    error = "系统语音引擎缺少中文数据，已退回默认语言"
                )
            }

            else -> {
                engine.language = preferred
                _state.value = _state.value.copy(ready = true, error = null)
            }
        }

        pendingText?.let { text ->
            val source = pendingSource
            val speed = pendingSpeed
            pendingText = null
            start(text, source, speed)
        }
    }

    /** 开始念一段文字；已经在念的话会先停下重来 */
    fun speak(text: String, source: String, speed: Float = _state.value.speed) {
        if (!_state.value.ready) {
            // 引擎还在初始化：先把活排上，初始化完自动开始
            pendingText = text
            pendingSource = source
            pendingSpeed = speed
            return
        }
        start(text, source, speed)
    }

    /** 暂停：记住念到第几句，之后从这句继续 */
    fun pause() {
        if (cloud != null) {
            runCatching { player?.pause() }
            _state.value = _state.value.copy(playing = false, paused = true)
            return
        }
        if (localMode) {
            runCatching { localPlayer?.pause() }
            _state.value = _state.value.copy(playing = false, paused = true)
            return
        }
        tts?.stop()
        _state.value = _state.value.copy(playing = false, paused = true)
    }

    fun resume() {
        if (cloud != null) {
            runCatching { player?.start() }
            _state.value = _state.value.copy(playing = true, paused = false)
            return
        }
        if (localMode) {
            runCatching { localPlayer?.start() }
            _state.value = _state.value.copy(playing = true, paused = false)
            return
        }
        if (sentences.isEmpty()) return
        _state.value = _state.value.copy(playing = true, paused = false)
        speakFrom(_state.value.index)
    }

    fun stop() {
        tts?.stop()
        localJob?.cancel()
        localJob = null
        runCatching { localPlayer?.release() }
        localPlayer = null
        SherpaTts.stop()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        sentences = emptyList()
        _state.value = _state.value.copy(
            playing = false,
            paused = false,
            index = 0,
            total = 0
        )
    }

    /** 调速。正在念的话从当前这句重新念起，避免语速要等到下一句才生效 */
    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        if (_state.value.playing) speakFrom(_state.value.index)
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        sentences = emptyList()
        _state.value = State()
    }

    // ---------------------------------------------------------------- 内部

    private fun start(text: String, source: String, speed: Float) {
        val config = cloud
        if (config != null) {
            startCloud(config, text, source, speed)
            return
        }
        if (localMode) {
            startLocal(text, source, speed)
            return
        }
        sentences = splitSentences(text)
        if (sentences.isEmpty()) {
            _state.value = _state.value.copy(error = "这段内容没有可以朗读的文字")
            return
        }
        _state.value = _state.value.copy(
            playing = true,
            paused = false,
            index = 0,
            total = sentences.size,
            speed = speed,
            source = source,
            error = null
        )
        speakFrom(0)
    }

    private fun speakFrom(index: Int) {
        val sentence = sentences.getOrNull(index) ?: run {
            stop()
            return
        }
        _state.value = _state.value.copy(index = index, playing = true, paused = false)
        val engine = tts ?: return
        engine.setSpeechRate(_state.value.speed)
        val result = engine.speak(
            sentence,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "notecraft-tts-$index"
        )
        if (result != TextToSpeech.SUCCESS) {
            _state.value = _state.value.copy(error = "朗读启动失败", playing = false)
        }
    }

    /**
     * 云端朗读：整段丢给 OpenAI 兼容的 `/audio/speech`，拿回音频用 MediaPlayer 播。
     *
     * 不按句分段请求 —— 那样每句一次 HTTP，延迟和费用都会翻倍；
     * 代价是没有逐句高亮，进度条按一段显示。
     */
    private fun startCloud(config: CloudTtsConfig, text: String, source: String, speed: Float) {
        if (config.apiKey.isBlank()) {
            _state.value = _state.value.copy(error = "还没有填 TTS API Key", playing = false)
            return
        }
        stop()
        _state.value = _state.value.copy(
            playing = true,
            paused = false,
            index = 0,
            total = 1,
            speed = speed,
            source = source,
            error = null
        )
        scope.launch {
            val file = runCatching { synthesize(config, text, speed) }.getOrElse { error ->
                _state.value = _state.value.copy(
                    playing = false,
                    error = "云端合成失败：${error.message ?: "网络错误"}"
                )
                return@launch
            }
            playFile(file)
        }
    }

    private suspend fun synthesize(
        config: CloudTtsConfig,
        text: String,
        speed: Float
    ): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val plain = text.replace(Regex("[*_`>#|]"), " ").replace(Regex("\\s+"), " ").trim()
        if (plain.isBlank()) throw IllegalStateException("没有可以朗读的文字")

        val base = config.baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val payload = org.json.JSONObject().apply {
            put("model", config.model.ifBlank { "tts-1" })
            put("input", plain.take(4000))
            put("voice", config.voice.ifBlank { "alloy" })
            put("speed", speed.toDouble().coerceIn(0.5, 2.0))
            put("response_format", "mp3")
        }
        val request = okhttp3.Request.Builder()
            .url("$base/audio/speech")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body ?: throw IllegalStateException("响应为空")
            if (!response.isSuccessful) {
                val msg = runCatching { body.string().take(200) }.getOrDefault("")
                throw IllegalStateException("HTTP ${response.code} $msg")
            }
            val dir = File(appContext.cacheDir, "tts").apply { if (!exists()) mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "speech-${System.currentTimeMillis()}.mp3")
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            target
        }
    }

    private fun playFile(file: File) {
        runCatching {
            player?.release()
            player = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { stop() }
                start()
            }
            _state.value = _state.value.copy(playing = true, paused = false)
        }.onFailure { error ->
            _state.value = _state.value.copy(
                playing = false,
                error = "播放失败：${error.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 本地朗读：sherpa-onnx 合成，逐句播放。
     *
     * 之前把整篇合成进一个 FloatArray 再塞给 AudioTrack 的静态缓冲 ——
     * 长文本会超出缓冲上限，write() 返回负值，play() 自然没声音，
     * 而且整条链路没有返回值检查，所以「不报错也不出声」。
     * 现在每句合成成一个 wav，用 MediaPlayer 播完再下一句。
     */
    private fun startLocal(text: String, source: String, speed: Float) {
        if (!SherpaTts.isReady(appContext.filesDir)) {
            _state.value = _state.value.copy(
                error = "本地模型没有安装，已改用系统语音",
                playing = false
            )
            localMode = false
            start(text, source, speed)
            return
        }
        val chunks = splitSentences(text).let { list ->
            if (list.isEmpty()) listOf(text) else list
        }
        if (chunks.isEmpty()) {
            _state.value = _state.value.copy(error = "这段内容没有可以朗读的文字")
            return
        }
        stop()
        _state.value = _state.value.copy(
            playing = true,
            paused = false,
            index = 0,
            total = chunks.size,
            speed = speed,
            source = source,
            error = null
        )
        localJob = scope.launch {
            for ((index, chunk) in chunks.withIndex()) {
                // stop() 会把 playing 置 false，同时 cancel 这个 job
                if (!_state.value.playing) break
                _state.value = _state.value.copy(index = index)
                val wav = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        synthesizeWav(chunk, speed)
                    }
                }.getOrElse { error ->
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "本地合成失败：${error.message ?: "未知错误"}"
                    )
                    return@launch
                }
                val finished = playWavAndAwait(wav)
                runCatching { wav.delete() }
                if (!finished) break
            }
            if (_state.value.playing) stop()
        }
    }

    /** 合成一句话，写成 16bit 单声道 wav —— MediaPlayer 能直接播 */
    private fun synthesizeWav(text: String, speed: Float): File {
        val (samples, sampleRate) = SherpaTts.synthesize(appContext.filesDir, text, speed)
        val dir = File(appContext.cacheDir, "tts-local").apply { if (!exists()) mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "line-${System.currentTimeMillis()}.wav")

        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val dataBytes = pcm.size * 2
        target.outputStream().buffered().use { out ->
            fun le32(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte()
            )
            fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

            out.write("RIFF".toByteArray())
            out.write(le32(36 + dataBytes))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1))          // PCM
            out.write(le16(1))          // 单声道
            out.write(le32(sampleRate))
            out.write(le32(sampleRate * 2))
            out.write(le16(2))
            out.write(le16(16))         // 位深
            out.write("data".toByteArray())
            out.write(le32(dataBytes))
            val bytes = ByteArray(dataBytes)
            for (i in pcm.indices) {
                bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
        return target
    }

    /** 播一段 wav，播完/出错/被取消才返回 */
    private suspend fun playWavAndAwait(file: File): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var resumed = false
            val mp = android.media.MediaPlayer()
            localPlayer = mp
            runCatching {
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    if (!resumed) {
                        resumed = true
                        runCatching { mp.release() }
                        cont.resumeWith(Result.success(true))
                    }
                }
                mp.setOnErrorListener { _, _, _ ->
                    if (!resumed) {
                        resumed = true
                        runCatching { mp.release() }
                        cont.resumeWith(Result.success(false))
                    }
                    true
                }
                mp.prepare()
                mp.start()
            }.onFailure { error ->
                if (!resumed) {
                    resumed = true
                    runCatching { mp.release() }
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "播放失败：${error.message}"
                    )
                    cont.resumeWith(Result.success(false))
                }
            }
            cont.invokeOnCancellation {
                if (!resumed) {
                    resumed = true
                    runCatching { mp.release() }
                }
            }
        }

    private val listener = object : UtteranceProgressListener() {        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            // 回调在后台线程；StateFlow 本身线程安全
            val next = _state.value.index + 1
            if (next >= sentences.size) {
                stop()
            } else {
                speakFrom(next)
            }
        }

        @Deprecated("旧 API 的签名，仍需要实现")
        override fun onError(utteranceId: String?) {
            _state.value = _state.value.copy(error = "朗读出错", playing = false)
        }
    }

    /**
     * 按句切分，顺便把 Markdown 记号去掉 —— 否则会听见「井号井号」「星号」。
     */
    private fun splitSentences(text: String): List<String> {
        val cleaned = text
            .replace(Regex("^\\s*[-*+]\\s+\\[.\\]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*#{1,6}\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_`>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isBlank()) return emptyList()

        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in cleaned) {
            buffer.append(ch)
            if (ch in "。！？!?；;．.") {
                val piece = buffer.toString().trim()
                if (piece.isNotBlank()) parts += piece
                buffer.clear()
            }
        }
        if (buffer.isNotBlank()) parts += buffer.toString().trim()
        return parts.filter { it.isNotBlank() }
    }

    companion object {
        /**
         * 尽力打开系统的「文字转语音」设置页。
         *
         * 各家 ROM 的入口不一样，所以按顺序试：
         *  1. TTS_SETTINGS（很多机型有这个系统 Activity，没公开常量，用字符串）
         *  2. 无障碍设置（绝大多数机器都列着 TTS 入口）
         *  3. 应用详情页（最后的兜底）
         */
        fun openEngineSettings(context: Context) {
            val candidates = listOf(
                Intent("com.android.settings.TTS_SETTINGS"),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            candidates.forEach { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return
            }
        }
    }
}
