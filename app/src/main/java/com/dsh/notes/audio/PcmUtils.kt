package com.dsh.notes.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 和 WAV / PCM 有关的小工具 */
object PcmUtils {

    /** PCM16 小端字节 → 归一化 FloatArray（-1f..1f） */
    fun bytesToFloats(pcm: ByteArray, length: Int): FloatArray {
        val samples = length / 2
        val out = FloatArray(samples)
        val buffer = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < samples && buffer.remaining() >= 2) {
            out[i] = buffer.short / 32768f
            i++
        }
        return out
    }

    /** 粗略判断「这一段有没有声音」，避免把纯静音送去转写白花钱 */
    fun rms(pcm: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        val buffer = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0
        while (buffer.remaining() >= 2) {
            val sample = buffer.short.toDouble()
            sum += sample * sample
            count++
        }
        if (count == 0) return 0.0
        return Math.sqrt(sum / count) / 32768.0
    }

    /** 读 WAV 头算时长；头坏了就按文件长度估（16k / 单声道 / 16bit） */
    fun wavDurationMs(file: File, fallbackSampleRate: Int = 16_000): Long {
        if (!file.exists()) return 0L
        val fromHeader = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < WavWriter.HEADER_SIZE) return@use 0L
                raf.seek(22)
                val channels = raf.readShortLE().toInt().coerceAtLeast(1)
                raf.seek(24)
                val sampleRate = raf.readIntLE()
                raf.seek(34)
                val bits = raf.readShortLE().toInt().coerceAtLeast(8)
                raf.seek(40)
                val dataSize = raf.readIntLE().toLong()
                val bytesPerSecond = sampleRate.toLong() * channels * bits / 8
                if (bytesPerSecond <= 0L) 0L else dataSize * 1000L / bytesPerSecond
            }
        }.getOrDefault(0L)

        if (fromHeader > 0L) return fromHeader
        val bytesPerSecond = fallbackSampleRate * 2L
        return ((file.length() - WavWriter.HEADER_SIZE).coerceAtLeast(0L)) * 1000L / bytesPerSecond
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val bytes = ByteArray(4)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun RandomAccessFile.readShortLE(): Short {
        val bytes = ByteArray(2)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short
    }
}

/**
 * 时长探测入口：WAV 走头部解析，其它格式（m4a/mp3）用 MediaMetadataRetriever。
 * 放在这里而不是用 MediaPlayer，是因为它不会占用解码器、开销也小。
 */
fun probeDurationMs(file: File, sampleRate: Int = 16_000): Long {
    if (!file.exists()) return 0L
    if (file.extension.equals("wav", ignoreCase = true)) {
        return PcmUtils.wavDurationMs(file, sampleRate)
    }
    return runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)
}
