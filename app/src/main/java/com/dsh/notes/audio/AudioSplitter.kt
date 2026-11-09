package com.dsh.notes.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 长音频切段。
 *
 * 硅基流动一类的云端转写对单次请求有时长和体积上限（大约 1 小时 / 几十 MB），
 * 超了就整条失败。这里在**上传前**把长录音切成若干段，分别转写后按顺序拼回文字，
 * 用户体验上仍然是一次转写、一篇笔记。
 *
 * 两种处理方式：
 *  · wav —— 直接按字节切 PCM，不动头（重新算一次头，保证每段能独立播放）
 *  · m4a/mp4/aac —— MediaExtractor + MediaMuxer 按音频采样重新封装
 */
object AudioSplitter {

    /** 每段 45 分钟：小于常见的 1 小时上限，留出上传和转写的时间余量 */
    const val SEGMENT_MS = 45 * 60 * 1000L

    /** 超过 55 分钟就切 */
    const val THRESHOLD_MS = 55 * 60 * 1000L

    /** 超过 45MB 也切：很多服务按体积拒绝 */
    const val THRESHOLD_BYTES = 45L * 1024 * 1024

    data class Segment(val file: File, val startMs: Long, val endMs: Long)

    fun shouldSplit(file: File, durationMs: Long): Boolean =
        durationMs > THRESHOLD_MS || file.length() > THRESHOLD_BYTES

    /**
     * 切分入口。返回按时间顺序排列的分段；文件不需要切时返回单段。
     * 调用方负责在用完后删除这些临时文件。
     */
    fun split(file: File, outDir: File, segmentMs: Long = SEGMENT_MS): List<Segment> {
        outDir.deleteRecursively()
        outDir.mkdirs()
        return if (file.extension.equals("wav", true)) {
            splitWav(file, outDir, segmentMs)
        } else {
            splitContainer(file, outDir, segmentMs)
        }
    }

    // ---------------------------------------------------------------- wav

    private fun splitWav(file: File, outDir: File, segmentMs: Long): List<Segment> {
        val raf = RandomAccessFile(file, "r")
        try {
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                // 不是标准 wav，退回按体积硬切
                return splitRaw(file, outDir, segmentMs)
            }

            var sampleRate = 16000
            var channels = 1
            var bits = 16
            var dataOffset = -1L
            var dataSize = 0L

            val chunkHeader = ByteArray(8)
            while (raf.filePointer < raf.length() - 8) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fmt = ByteArray(size.toInt())
                    raf.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    channels = bb.getShort(2).toInt()
                    sampleRate = bb.getInt(4)
                    bits = bb.getShort(14).toInt()
                } else if (id == "data") {
                    dataOffset = raf.filePointer
                    dataSize = minOf(size, raf.length() - dataOffset)
                    break
                } else {
                    raf.seek(raf.filePointer + size + (size % 2))
                }
            }
            if (dataOffset < 0) return splitRaw(file, outDir, segmentMs)

            val bytesPerMs = (sampleRate.toLong() * channels * (bits / 8)) / 1000L
            if (bytesPerMs <= 0) return splitRaw(file, outDir, segmentMs)

            val segmentBytes = (bytesPerMs * segmentMs).toLong()
                .coerceAtLeast(1024L * 1024L)
                .toInt()
            // 对齐到采样帧，避免切开半个采样点
            val frameBytes = channels * (bits / 8)
            val alignedSegment = (segmentBytes / frameBytes) * frameBytes

            val result = mutableListOf<Segment>()
            var offset = 0L
            var index = 0
            while (offset < dataSize) {
                val length = minOf(alignedSegment.toLong(), dataSize - offset).toInt()
                val target = File(outDir, "seg-${index.toString().padStart(3, '0')}.wav")
                val buffer = ByteArray(length)
                raf.seek(dataOffset + offset)
                raf.readFully(buffer)
                writeWav(target, buffer, sampleRate, channels, bits)
                result += Segment(
                    file = target,
                    startMs = offset / bytesPerMs,
                    endMs = (offset + length) / bytesPerMs
                )
                offset += length
                index++
            }
            return result.ifEmpty { listOf(Segment(file, 0, 0)) }
        } finally {
            runCatching { raf.close() }
        }
    }

    private fun writeWav(target: File, pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int) {
        target.outputStream().buffered().use { out ->
            fun le32(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
            )
            fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
            val byteRate = sampleRate * channels * (bits / 8)
            out.write("RIFF".toByteArray())
            out.write(le32(36 + pcm.size))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1))
            out.write(le16(channels))
            out.write(le32(sampleRate))
            out.write(le32(byteRate))
            out.write(le16(channels * (bits / 8)))
            out.write(le16(bits))
            out.write("data".toByteArray())
            out.write(le32(pcm.size))
            out.write(pcm)
        }
    }

    /** 兜底：不管格式，按体积均分（切出来的段可能无法直接播放，但能上传） */
    private fun splitRaw(file: File, outDir: File, segmentMs: Long): List<Segment> {
        val chunk = (file.length() / maxOf(1, (file.length() / (segmentMs * 32)))).coerceAtLeast(1024L * 1024L)
        val result = mutableListOf<Segment>()
        file.inputStream().buffered().use { input ->
            var index = 0
            while (true) {
                val buffer = ByteArray(chunk.toInt())
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 0) break
                val target = File(outDir, "seg-${index.toString().padStart(3, '0')}.${file.extension}")
                target.outputStream().use { it.write(buffer, 0, read) }
                result += Segment(target, 0, 0)
                index++
                if (read < buffer.size) break
            }
        }
        return result.ifEmpty { listOf(Segment(file, 0, 0)) }
    }

    // ---------------------------------------------------------------- 容器格式

    private fun splitContainer(file: File, outDir: File, segmentMs: Long): List<Segment> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) return splitRaw(file, outDir, segmentMs)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            val segmentUs = segmentMs * 1000L
            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()

            val result = mutableListOf<Segment>()
            var muxer: MediaMuxer? = null
            var outTrack = -1
            var segStartUs = 0L
            var currentFile: File? = null

            fun closeCurrent() {
                muxer?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                muxer = null
            }

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime

                if (muxer == null || timeUs - segStartUs >= segmentUs) {
                    closeCurrent()
                    val target = File(outDir, "seg-${result.size.toString().padStart(3, '0')}.m4a")
                    muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    outTrack = muxer!!.addTrack(format)
                    muxer!!.start()
                    segStartUs = timeUs
                    currentFile = target
                    result += Segment(target, timeUs / 1000, 0)
                }

                buffer.position(0)
                buffer.limit(size)
                info.offset = 0
                info.size = size
                info.presentationTimeUs = timeUs - segStartUs
                info.flags = extractor.sampleFlags
                runCatching { muxer?.writeSampleData(outTrack, buffer, info) }
                extractor.advance()
            }
            closeCurrent()

            if (result.isEmpty()) return listOf(Segment(file, 0, 0))
            return result.mapIndexed { index, segment ->
                segment.copy(
                    endMs = result.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
                )
            }
        } catch (error: Exception) {
            android.util.Log.w("AudioSplitter", "容器切分失败，退回按体积切", error)
            return splitRaw(file, outDir, segmentMs)
        } finally {
            runCatching { extractor.release() }
        }
    }
}
