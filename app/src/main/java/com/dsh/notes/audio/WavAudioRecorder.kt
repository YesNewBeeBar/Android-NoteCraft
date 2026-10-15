package com.dsh.notes.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * PCM 引擎：AudioRecord 采原始数据，[WavWriter] 落成 WAV。
 *
 * 用它的唯一理由是**能拿到 PCM 流**——只有拿到流才能按时间切片做实时上屏，
 * 也只有它能在采样时就实现静音（写等长零数据）。
 *
 * 文件大小要说清楚：16kHz 单声道 16bit = 1.92MB/分钟，
 * 1 小时约 115MB。不想这么大就用 [M4aAudioRecorder]。
 */
class WavAudioRecorder(
    private val sampleRate: Int,
    private val channelCount: Int = 1,
    private val targetFile: File
) : AudioRecorder {

    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    private var thread: Thread? = null
    private var onPcm: ((ShortArray, Int) -> Unit)? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var muted = false

    /** 麦克风真实采到的采样点（暂停期间不计） */
    @Volatile
    private var samplesCaptured: Long = 0L

    override val isRecording: Boolean get() = running

    override val isPaused: Boolean get() = paused

    override val isMuted: Boolean get() = muted

    override val elapsedMs: Long
        get() = if (sampleRate <= 0) 0L else samplesCaptured * 1000L / sampleRate

    override val file: File = targetFile

    @SuppressLint("MissingPermission")
    override fun start(onPcm: ((buffer: ShortArray, length: Int) -> Unit)?) {
        check(!running) { "录音器已经在运行" }
        this.onPcm = onPcm

        val channelMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException(
                "设备不支持 ${sampleRate}Hz 单声道 PCM 录音（minBufferSize=$minBuffer）"
            )
        }
        // 给足缓冲，避免锁屏 / 后台调度抖动导致丢帧
        val bufferSize = maxOf(minBuffer * 4, sampleRate * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("麦克风初始化失败：可能被其它应用占用，或权限被拒")
        }

        writer = WavWriter(targetFile, sampleRate)
        record = recorder
        running = true
        paused = false
        muted = false
        samplesCaptured = 0L

        recorder.startRecording()

        thread = Thread({ loop(recorder) }, "notecraft-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop(recorder: AudioRecord) {
        val chunk = ShortArray(sampleRate / 5) // 200ms
        val sink = writer ?: return

        while (running) {
            val read = recorder.read(chunk, 0, chunk.size)
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "AudioRecord.read 返回错误码 $read，结束采集")
                    break
                }
                continue
            }
            if (paused) continue

            samplesCaptured += read

            // 静音：写入等长零数据，时长照算，但文件里听不到内容
            val data = if (muted) ShortArray(read) else chunk
            val bytes = toLittleEndianBytes(data, read)

            runCatching { sink.write(bytes, bytes.size) }
                .onFailure { Log.e(TAG, "写 WAV 失败", it) }

            onPcm?.let { callback ->
                runCatching { callback(data, read) }
                    .onFailure { Log.e(TAG, "PCM 消费失败", it) }
            }
        }
    }

    private fun toLittleEndianBytes(buffer: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        var index = 0
        for (i in 0 until length) {
            val value = buffer[i].toInt()
            bytes[index++] = (value and 0xFF).toByte()
            bytes[index++] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    override fun pause() {
        if (!running) return
        paused = true
        runCatching { record?.stop() }
    }

    override fun resume() {
        if (!running) return
        paused = false
        runCatching { record?.startRecording() }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun stop() {
        running = false
        val recorder = record
        record = null
        runCatching { recorder?.stop() }
        runCatching { thread?.join(1_500) }
        runCatching { recorder?.release() }
        thread = null
        // 回填 WAV 头部的长度字段，否则播放器会以为文件是 0 秒
        runCatching { writer?.finish() }
        writer = null
    }

    private companion object {
        const val TAG = "WavAudioRecorder"
    }
}
