package com.dsh.notes.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * 本地朗读：sherpa-onnx + Kokoro。
 *
 * 模型是 Kokoro 多语言 int8 版，[modelDir] 下直接平铺着
 * model.int8.onnx / voices.bin / tokens.txt / lexicon-*.txt。
 *
 * 初始化要一两秒（加载 100MB 的 onnx），所以做成懒加载单例：
 * 第一次朗读时才构造，之后常驻。
 */
object SherpaTts {

    @Volatile
    private var engine: OfflineTts? = null
    private var loadedDir: String? = null
    private var player: AudioTrack? = null

    /** 模型文件可能在 app 目录里，也可能随安装包放在 assets 里 */
    fun modelDir(filesDir: File): File = File(File(filesDir, "tts"), "kokoro-int8-multi-lang-v1_0")

    fun isReady(filesDir: File): Boolean {
        val dir = modelDir(filesDir)
        return findModel(dir) != null && File(dir, "tokens.txt").exists()
    }

    private fun findModel(dir: File): File? = dir.listFiles()?.firstOrNull {
        it.isFile && it.name.startsWith("model") && it.name.endsWith(".onnx") && it.length() > 0
    }

    @Synchronized
    private fun ensureEngine(dir: File): OfflineTts {
        engine?.let { if (loadedDir == dir.absolutePath) return it }
        engine?.release()
        engine = null

        val model = findModel(dir)
            ?: throw IllegalStateException("模型目录里没有 model*.onnx")
        val lexicons = listOf("lexicon-zh.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt")
            .map { File(dir, it) }
            .filter { it.exists() }
            .joinToString(",") { it.absolutePath }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    lexicon = lexicons,
                    lang = "zh"
                ),
                numThreads = 2
            ),
            maxNumSentences = 2,
            silenceScale = 0.2f
        )
        val created = OfflineTts(config = config)
        engine = created
        loadedDir = dir.absolutePath
        return created
    }

    /** 合成一段文字；返回采样与采样率 */
    fun synthesize(
        filesDir: File,
        text: String,
        speed: Float
    ): Pair<FloatArray, Int> {
        val dir = modelDir(filesDir)
        val tts = ensureEngine(dir)
        val audio = tts.generate(text, sid = 0, speed = speed.coerceIn(0.5f, 2.0f))
        return audio.samples to audio.sampleRate
    }

    /** 用 AudioTrack 播放 PCM，支持暂停/继续/停止 */
    fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        player = track
    }

    fun pause() {
        runCatching { player?.pause() }
    }

    fun resume() {
        runCatching { player?.play() }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    fun release() {
        stop()
        runCatching { engine?.release() }
        engine = null
        loadedDir = null
    }
}
