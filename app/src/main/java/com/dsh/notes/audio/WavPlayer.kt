package com.dsh.notes.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 自己解析 WAV 并用 AudioTrack 播放。
 *
 * 为什么不用 MediaPlayer：它走系统解码器，遇到纯 PCM 的 wav 偶尔会
 * `Prepare failed: status=0x1`（DeepSeek 返回的就是这种），而这本来
 * 就是最不需要解码的格式。自己读 RIFF 头、按块写 AudioTrack 更稳。
 */
class WavPlayer {

    private var track: AudioTrack? = null
    @Volatile
    private var cancelled = false

    /** 播完返回 true；被停止或格式不支持返回 false */
    suspend fun play(file: File): Boolean = withContext(Dispatchers.IO) {
        cancelled = false
        val header = readHeader(file) ?: return@withContext false

        val channelMask =
            if (header.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(
            header.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return@withContext false

        val audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(header.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, 64 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audio

        val totalFrames = header.dataSize / (header.channels * 2)
        val done = CompletableDeferred<Unit>()
        audio.setNotificationMarkerPosition(totalFrames.toInt().coerceAtLeast(1))
        audio.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                if (!done.isCompleted) done.complete(Unit)
            }

            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })

        try {
            audio.play()
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(header.dataOffset)
                var remaining = header.dataSize
                val buffer = ByteArray(maxOf(minBuffer, 32 * 1024))
                while (remaining > 0 && !cancelled) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, want)
                    if (read <= 0) break
                    val written = audio.write(buffer, 0, read)
                    if (written < 0) break
                    remaining -= written
                }
            }
            if (cancelled) return@withContext false
            // 写完不代表放完，等播放头走到尾巴（最长等 30 分钟）
            kotlinx.coroutines.withTimeoutOrNull(
                (header.durationMs + 5000).coerceAtLeast(5000)
            ) { done.await() }
            !cancelled
        } catch (error: Exception) {
            android.util.Log.w("WavPlayer", "播放失败", error)
            false
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.release() }
            track = null
        }
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    fun resume() {
        runCatching { track?.play() }
    }

    fun stop() {
        cancelled = true
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    private data class Header(
        val sampleRate: Int,
        val channels: Int,
        val dataOffset: Long,
        val dataSize: Long
    ) {
        val durationMs: Long
            get() = if (sampleRate > 0) dataSize * 1000 / (sampleRate * channels * 2) else 0
    }

    /** 只认 16bit PCM；其它格式返回 null，交给调用方处理 */
    private fun readHeader(file: File): Header? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null

            var sampleRate = 0
            var channels = 0
            var bits = 0
            val chunk = ByteArray(8)
            while (raf.filePointer < raf.length() - 8) {
                raf.readFully(chunk)
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt())
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        channels = bb.getShort(2).toInt()
                        sampleRate = bb.getInt(4)
                        bits = bb.getShort(14).toInt()
                        if (bits != 16) return null
                    }

                    "data" -> {
                        val offset = raf.filePointer
                        val available = minOf(size, raf.length() - offset)
                        if (sampleRate <= 0 || channels <= 0) return null
                        return Header(sampleRate, channels, offset, available)
                    }

                    else -> raf.seek(raf.filePointer + size + (size % 2))
                }
            }
            null
        }
    }.getOrNull()
}
