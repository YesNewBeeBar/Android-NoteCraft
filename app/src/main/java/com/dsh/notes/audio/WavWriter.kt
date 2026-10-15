package com.dsh.notes.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简 WAV 写入器：16-bit PCM、单声道、小端序。
 *
 * 为什么不用 MediaRecorder 录 m4a：
 *  - 各家云转写接口对 16k 单声道 wav 的支持最稳，省掉转码依赖；
 *  - 「实时上屏」需要按时间切片拿原始 PCM，MediaRecorder 只给文件、不给流。
 *
 * 头部两个长度字段先写 0，结束时用 [finish] 回填。这样即使进程被杀，
 * 文件里也已经有大半数据，可以按最大长度硬解析抢救出来。
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {

    private val output = FileOutputStream(file, false)
    private var dataBytes: Long = 0
    private var finished = false

    init {
        output.write(header(dataBytes))
        output.flush()
    }

    /** 写入一段 PCM，返回累计写入的字节数 */
    fun write(pcm: ByteArray, length: Int): Long {
        output.write(pcm, 0, length)
        dataBytes += length
        return dataBytes
    }

    /** 已写入的时长（毫秒） */
    fun durationMs(): Long {
        val bytesPerSecond = (sampleRate * channels * bitsPerSample / 8).toLong()
        return if (bytesPerSecond == 0L) 0L else dataBytes * 1000L / bytesPerSecond
    }

    /** 刷新并回填头部；调用后不可再写 */
    fun finish(): Long {
        if (finished) return dataBytes
        finished = true
        runCatching {
            output.flush()
            output.close()
        }
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header(dataBytes))
            }
        }
        return dataBytes
    }

    /** 异常路径：尽量把已写入的数据保住 */
    fun abort() {
        runCatching { finish() }
    }

    private fun header(dataBytes: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((36 + dataBytes).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)                                  // fmt chunk 大小
        buffer.putShort(1)                                 // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes.toInt())

        return buffer.array()
    }

    companion object {
        const val HEADER_SIZE = 44

        /**
         * 修复外部录音的坏头：把长度按「文件实际长度 - 44」回填。
         * 手机自带录音机 / 下载来的 wav 经常在 CRASH 后留下残头，
         * 不修的话云转写会解出 0 秒。
         */
        fun repairHeaderIfNeeded(file: File, sampleRate: Int = 16_000): Boolean {
            if (!file.exists() || file.length() <= HEADER_SIZE) return false
            return runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    val expectedData = (file.length() - HEADER_SIZE).toInt()
                    raf.seek(4)
                    val riffSize = raf.readIntLE()
                    raf.seek(40)
                    val dataSize = raf.readIntLE()
                    if (dataSize == expectedData && riffSize == expectedData + 36) return@use false

                    raf.seek(4)
                    raf.writeIntLE(expectedData + 36)
                    raf.seek(22)
                    raf.writeShortLE(1)          // 单声道
                    raf.seek(24)
                    raf.writeIntLE(sampleRate)
                    raf.seek(28)
                    raf.writeIntLE(sampleRate * 2)   // byteRate
                    raf.writeShortLE(2)              // blockAlign
                    raf.writeShortLE(16)             // bitsPerSample
                    raf.seek(40)
                    raf.writeIntLE(expectedData)
                    true
                }
            }.getOrDefault(false)
        }

        private fun RandomAccessFile.readIntLE(): Int {
            val bytes = ByteArray(4)
            readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
        }

        private fun RandomAccessFile.writeIntLE(value: Int) {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        private fun RandomAccessFile.writeShortLE(value: Int) {
            write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        }
    }
}
