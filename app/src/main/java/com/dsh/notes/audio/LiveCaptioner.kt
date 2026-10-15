package com.dsh.notes.audio

import android.util.Log
import com.dsh.notes.ai.OpenAiCompatibleAsr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 录音过程中的「实时上屏」。
 *
 * 实现方式：把 AudioRecord 交出来的 PCM 按固定时长切成小段，逐段丢给云转写接口，
 * 拿回文字按顺序追加。用**一条串行队列**处理这些段，原因有两个：
 *  - 并发发请求会被服务端限流；
 *  - 段与段之间必须保持顺序，否则文字会乱。
 *
 * 为什么本地离线识别做不了实时上屏：SpeechRecognizer 要独占麦克风，
 * 没法在录音的同时跑。所以上屏只有云接口这一条路，界面上会讲清楚。
 *
 * @param chunkMs 每段时长；越小越「实时」但请求越多，默认 8 秒
 * @param onSegment 每段识别完成后的回调（在主线程外调用）
 */
class LiveCaptioner(
    private val scope: CoroutineScope,
    private val asr: OpenAiCompatibleAsr,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val sampleRate: Int,
    private val chunkMs: Int,
    private val onSegment: (text: String) -> Unit,
    private val onError: (message: String) -> Unit
) {

    /** 低于这个 RMS 视作静音，不发请求（省流量也省额度） */
    private val silenceThreshold = 0.006

    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pending = ByteArrayOutputStream()
    private val segmentIndex = AtomicInteger(0)
    private val bytesPerChunk = sampleRate * 2 * chunkMs / 1000

    @Volatile
    private var finished = false

    private var worker: Job? = null

    val running: Boolean get() = !finished

    fun start() {
        worker = scope.launch {
            for (pcm in queue) {
                val index = segmentIndex.getAndIncrement()
                val text = runCatching {
                    asr.transcribePcmSegment(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        pcm = pcm,
                        sampleRate = sampleRate,
                        language = language,
                        segmentIndex = index
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "第 $index 段实时转写失败", error)
                    onError(error.message ?: "实时转写失败")
                    ""
                }
                if (text.isNotBlank()) onSegment(text)
            }
        }
    }

    /**
     * 喂入录音线程交出来的 PCM。注意：这个方法在**录音线程**上被调用，
     * 只做内存拷贝和一次 channel trySend，绝不阻塞。
     */
    fun feed(buffer: ShortArray, length: Int) {
        if (finished) return
        // ShortArray → 小端字节
        val bytes = ByteArray(length * 2)
        var index = 0
        for (i in 0 until length) {
            val value = buffer[i].toInt()
            bytes[index++] = (value and 0xFF).toByte()
            bytes[index++] = ((value shr 8) and 0xFF).toByte()
        }
        synchronized(pending) {
            pending.write(bytes, 0, bytes.size)
            while (pending.size() >= bytesPerChunk) {
                val chunk = takeChunk(bytesPerChunk)
                enqueue(chunk)
            }
        }
    }

    private fun takeChunk(size: Int): ByteArray {
        val all = pending.toByteArray()
        val chunk = all.copyOfRange(0, size)
        pending.reset()
        if (all.size > size) pending.write(all, size, all.size - size)
        return chunk
    }

    private fun enqueue(chunk: ByteArray) {
        if (PcmUtils.rms(chunk, chunk.size) < silenceThreshold) return
        queue.trySend(chunk)
    }

    /**
     * 结束：把不足一段的尾巴也发出去，然后等队列排空。
     * 记得在停止录音之后调用，否则最后几秒的话会丢。
     */
    suspend fun finishAndDrain() {
        if (finished) return
        finished = true
        synchronized(pending) {
            if (pending.size() >= sampleRate) { // 至少 0.5 秒才值得发
                enqueue(pending.toByteArray())
            }
            pending.reset()
        }
        queue.close()
        runCatching { worker?.join() }
    }

    /** 异常路径：直接丢弃剩余数据 */
    fun cancel() {
        finished = true
        queue.close()
        worker?.cancel()
    }

    companion object {
        private const val TAG = "LiveCaptioner"
    }
}
