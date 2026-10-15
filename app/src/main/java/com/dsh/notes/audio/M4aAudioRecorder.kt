package com.dsh.notes.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * 压缩引擎：MediaRecorder 直接录成 m4a（AAC）。
 *
 * 为什么用它当默认：
 *  - 32kbps 单声道 = **0.24MB/分钟**，1 小时约 14MB，比 WAV 小 8 倍；
 *  - AAC/m4a 是各家转写接口都收的格式，包括 OpenAI 与国内的硅基流动；
 *  - 安卓原生封装，不需要自己写容器（这也是我没选 webm 的原因：
 *    MediaMuxer 不支持 WebM，且 Opus 编码器不是每台机器都有）。
 *
 * 代价：拿不到 PCM 流，所以**不能做实时上屏**，也不能在采样时就静音。
 * 静音功能这里用「暂停 + 跳过」模拟不了，只能整体禁用（界面会说明）。
 */
class M4aAudioRecorder(
    private val context: Context,
    private val targetFile: File,
    private val bitRate: Int = DEFAULT_BIT_RATE,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE
) : AudioRecorder {

    private var recorder: MediaRecorder? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    /** 累计录音时长：MediaRecorder 不吐采样点，只能自己掐表 */
    @Volatile
    private var accumulatedMs: Long = 0L

    @Volatile
    private var segmentStartedAt: Long = 0L

    override val isRecording: Boolean get() = running

    override val isPaused: Boolean get() = paused

    /** MediaRecorder 无法在采样层静音，这里恒为 false */
    override val isMuted: Boolean get() = false

    override val elapsedMs: Long
        get() {
            val live = if (running && !paused) SystemClock.elapsedRealtime() - segmentStartedAt else 0L
            return accumulatedMs + live
        }

    override val file: File get() = targetFile

    @SuppressLint("MissingPermission")
    override fun start(onPcm: ((buffer: ShortArray, length: Int) -> Unit)?) {
        check(!running) { "录音器已经在运行" }
        if (onPcm != null) {
            // 上层如果指望这里给 PCM，那一定会静默地拿不到数据，早点报出来
            Log.w(TAG, "MediaRecorder 引擎不产生 PCM，实时上屏不可用")
        }

        // API 31 起 MediaRecorder(Context) 是推荐写法；旧版本只能走无参构造
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            media.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioSamplingRate(sampleRate)
            media.setAudioEncodingBitRate(bitRate)
            media.setOutputFile(targetFile.absolutePath)
            media.prepare()
            media.start()
        } catch (error: Exception) {
            runCatching { media.release() }
            throw IllegalStateException("初始化 m4a 录音失败：${error.message}", error)
        }

        recorder = media
        running = true
        paused = false
        accumulatedMs = 0L
        segmentStartedAt = SystemClock.elapsedRealtime()
    }

    override fun pause() {
        if (!running || paused) return
        val media = recorder ?: return
        runCatching { media.pause() }.onFailure {
            Log.w(TAG, "MediaRecorder.pause 不被支持，改用停止结束当前段", it)
            runCatching { media.stop() }
        }
        accumulatedMs += SystemClock.elapsedRealtime() - segmentStartedAt
        paused = true
    }

    override fun resume() {
        if (!running || !paused) return
        val media = recorder ?: return
        val resumed = runCatching { media.resume() }.isSuccess
        if (!resumed) {
            // 极少数机器不支持 pause/resume：这里只能提示用户，不伪造成功
            Log.w(TAG, "这台设备不支持 m4a 继续录音")
            return
        }
        paused = false
        segmentStartedAt = SystemClock.elapsedRealtime()
    }

    override fun setMuted(muted: Boolean) {
        // 采样层静音对 MediaRecorder 不可行；上层会据此禁用静音按钮
        if (muted) Log.i(TAG, "m4a 引擎不支持静音，忽略")
    }

    override fun stop() {
        if (!running && recorder == null) return
        val media = recorder
        recorder = null
        running = false
        if (!paused) {
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStartedAt
        }
        // stop() 会写完 moov box；不调用的话文件根本播不了
        runCatching { media?.stop() }
            .onFailure { error ->
                Log.e(TAG, "停止 m4a 录音失败（文件可能不可用）", error)
                runCatching { targetFile.delete() }
            }
        runCatching { media?.release() }
        paused = false
    }

    companion object {
        private const val TAG = "M4aAudioRecorder"

        /** 语音用 32kbps 完全够；想更清楚可以调到 48k */
        const val DEFAULT_BIT_RATE = 32_000
        const val DEFAULT_SAMPLE_RATE = 16_000
    }
}
