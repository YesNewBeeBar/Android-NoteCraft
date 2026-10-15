package com.dsh.notes.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 系统离线语音识别（Android 原生 [SpeechRecognizer]）。
 *
 * 特点：音频不出手机、完全离线；但**必须独占麦克风**，所以不能和 AudioRecord 并行。
 *
 * 因此它在本项目里有两个用途：
 *  1) 设置里把转写引擎选成「系统离线」时，作为整段录音的转写器；
 *  2) 云端接口失败且开启了自动回落时，作为兜底。
 *
 * 代价要说清楚：这条路上不产出音频文件（要识别就得占麦克风）。
 * 所以选它时录完只有文字、没有音频，界面上会提示。
 */
class LocalTranscriber(private val context: Context) {

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Android 13+ 是否装了设备端离线识别包（没装则走在线识别） */
    val isOnDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun engineLabel(): String = when {
        isOnDeviceAvailable -> "系统离线识别（设备端）"
        isAvailable -> "系统语音识别"
        else -> "不可用"
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 跑一次「说一段话 → 出文字」。
     * 熄屏或长时间无人说话由 [timeoutMs] 兜底结束，不会永远挂着。
     */
    suspend fun transcribeOnce(
        languageTag: String = "zh-CN",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String {
        if (!hasPermission()) throw IllegalStateException("没有录音权限，无法使用系统离线识别")
        if (!isAvailable) throw IllegalStateException("这台设备没有可用的系统语音识别服务")

        val result = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    startListening(languageTag, continuation)
                }
            }
        }
        return result.orEmpty()
    }

    /** 必须在主线程调用（SpeechRecognizer 的硬性要求） */
    private fun startListening(
        languageTag: String,
        continuation: CancellableContinuation<String>
    ) {
        val main = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null

        // 回调里直接 destroy 会让某些厂商实现崩溃，统一挪到下一拍主线程执行
        fun cleanupAndResume(block: () -> Unit) {
            val target = recognizer
            recognizer = null
            main.post {
                runCatching { target?.destroy() }
                if (continuation.isActive) block()
            }
        }

        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                cleanupAndResume { continuation.resume(text) }
            }

            override fun onError(error: Int) {
                val message = describe(error)
                cleanupAndResume { continuation.resumeWithException(IllegalStateException(message)) }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        try {
            val engine = createRecognizer()
            recognizer = engine
            engine.setRecognitionListener(listener)
            engine.startListening(buildIntent(languageTag))
        } catch (error: Exception) {
            cleanupAndResume {
                continuation.resumeWithException(error)
            }
        }

        continuation.invokeOnCancellation { main.post { runCatching { recognizer?.destroy() } } }
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isOnDeviceAvailable) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
                .getOrElse { SpeechRecognizer.createSpeechRecognizer(context) }
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun buildIntent(languageTag: String) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_LENGTH_MS)
        }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "识别时音频出错（麦克风可能被占用）"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "识别需要联网，当前网络不可用"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清内容"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务端返回错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
        else -> "识别失败（错误码 $error）"
    }

    companion object {
        private const val TAG = "LocalTranscriber"
        private const val COMPLETE_SILENCE_MS = 1_200L
        private const val MIN_LENGTH_MS = 3_000L

        /** 离线识别最长等 10 分钟：足够覆盖一段较长的独白 */
        const val DEFAULT_TIMEOUT_MS = 600_000L

        fun describeEngine(context: Context): String {
            val label = LocalTranscriber(context).engineLabel()
            Log.d(TAG, "本地识别引擎：$label")
            return label
        }
    }
}
