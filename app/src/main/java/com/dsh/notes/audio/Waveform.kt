package com.dsh.notes.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 从 WAV 里抽波形，给笔记详情页画「声波图」。
 *
 * 做法很朴素：把整段 PCM 分成 N 段，每段取采样点的峰值。
 * 一小时的 16k 单声道录音是 115MB —— 读一遍大约零点几秒，
 * 所以放在 IO 线程里跑一次、结果存内存就够了，不需要额外缓存层。
 */
object Waveform {

    /**
     * @param bars 想要多少个柱
     * @return 每个柱的峰值（0f..1f）；文件不可读时返回空列表
     */
    fun peaks(file: File, bars: Int, sampleRate: Int = 16_000): List<Float> {
        if (bars <= 0 || !file.exists() || file.length() <= WavWriter.HEADER_SIZE) return emptyList()

        return runCatching {
            // 采样步长：每段大概 20ms，避免逐点扫描太慢
            val step = maxOf(1, sampleRate / 50) * 2
            val result = FloatArray(bars)
            var minSample = 0
            var maxSample = 0

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(WavWriter.HEADER_SIZE)
                val totalSamples = ((raf.length() - WavWriter.HEADER_SIZE) / 2).toInt()
                if (totalSamples <= 0) return emptyList()

                // 第一遍：找整体峰值，用于归一化（不同录音音量差异很大）
                val scanBuffer = ByteArray(step * 64)
                var offset = 0L
                raf.seek(WavWriter.HEADER_SIZE)
                while (offset < totalSamples.toLong()) {
                    val read = raf.read(scanBuffer)
                    if (read <= 0) break
                    val buffer = ByteBuffer.wrap(scanBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                    while (buffer.remaining() >= 2) {
                        val value = buffer.short.toInt()
                        if (value < minSample) minSample = value
                        if (value > maxSample) maxSample = value
                    }
                    offset += read / 2
                }
                val amplitude = maxOf(maxSample, -minSample).coerceAtLeast(1)

                // 第二遍：分成 bars 段取峰值
                val samplesPerBar = maxOf(1, totalSamples / bars)
                val sampleBuffer = ByteArray(step * 32)
                for (bar in 0 until bars) {
                    val startSample = bar.toLong() * samplesPerBar
                    if (startSample >= totalSamples) break
                    raf.seek(WavWriter.HEADER_SIZE + startSample * 2)
                    var remaining = samplesPerBar.toLong()
                    var peak = 0
                    while (remaining > 0) {
                        val want = minOf(sampleBuffer.size.toLong(), remaining * 2).toInt()
                        val read = raf.read(sampleBuffer, 0, want)
                        if (read <= 0) break
                        val buffer = ByteBuffer.wrap(sampleBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                        while (buffer.remaining() >= 2) {
                            val value = buffer.short.toInt()
                            val magnitude = if (value < 0) -value else value
                            if (magnitude > peak) peak = magnitude
                        }
                        remaining -= read / 2
                    }
                    result[bar] = (peak.toFloat() / amplitude).coerceIn(0f, 1f)
                }
            }
            result.toList()
        }.getOrDefault(emptyList())
    }
}
