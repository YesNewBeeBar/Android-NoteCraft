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
import java.util.Locale

/**
 * 用系统自带的 TTS 念笔记。不接任何在线模型，离线也能用。
 *
 * 系统 TTS 没有「暂停」这个 API（只有 stop），所以这里按句子切分，
 * 停的时候记住念到第几句，「继续」就从那句接着念 —— 听感上就是暂停/继续。
 */
class SpeechReader(context: Context) {

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
        tts?.stop()
        _state.value = _state.value.copy(playing = false, paused = true)
    }

    fun resume() {
        if (sentences.isEmpty()) return
        _state.value = _state.value.copy(playing = true, paused = false)
        speakFrom(_state.value.index)
    }

    fun stop() {
        tts?.stop()
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

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

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
