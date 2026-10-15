package com.dsh.notes.ai

import android.content.Context
import android.net.Uri
import com.dsh.notes.audio.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的音频转写客户端。
 *
 * 为什么只做「OpenAI 兼容」这一种协议：`/v1/audio/transcriptions` 的 multipart 格式
 * 已经是事实标准 —— 硅基流动、DashScope 兼容模式、Groq、本地 vLLM、OpenAI 官方都认。
 * 一套代码全覆盖，换供应商只要在设置里改 BaseURL + 模型名。
 *
 * 注意：DeepSeek 官方**没有**音频接口（只有文本），所以转写必须配置另一家。
 */
class OpenAiCompatibleAsr(private val client: OkHttpClient) {

    /**
     * 上传并转写一个音频文件。
     *
     * @param file     本地文件（自己录的 wav，或导入后复制到缓存的文件）
     * @param filename 上报给服务端的文件名，后缀会影响服务端选哪个解码器
     */
    suspend fun transcribeFile(
        baseUrl: String,
        apiKey: String,
        model: String,
        file: File,
        filename: String = file.name,
        language: String? = null,
        prompt: String? = null
    ): AsrResult = withContext(Dispatchers.IO) {
        if (!file.exists()) throw AiException("音频文件不存在：${file.absolutePath}")
        if (apiKey.isBlank()) {
            throw AiException("还没填云转写的 API Key", hint = "去「设置 → 音频转写」里填写")
        }
        if (model.isBlank()) {
            throw AiException(
                "还没填转写模型名",
                hint = "例如 whisper-1 或 FunAudioLLM/SenseVoiceSmall"
            )
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", filename, FileRequestBody(file))
            .apply {
                // 只有明确指定才带 language：有些服务端对不支持的语言会直接报错
                language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) }
                prompt?.takeIf { it.isNotBlank() }?.let { addFormDataPart("prompt", it) }
            }
            .build()

        val request = Request.Builder()
            .url(joinUrl(baseUrl, "audio/transcriptions"))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val started = System.currentTimeMillis()
        val response = try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw AiException(
                "连不上转写服务：${io.message ?: "网络错误"}",
                io,
                hint = "检查手机网络，以及 BaseURL 是否写对（一般要带 /v1）"
            )
        }

        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AiException(
                    "转写服务返回 HTTP ${resp.code}",
                    hint = explainHttpError(resp.code, raw)
                )
            }
            val text = parseText(raw)
            if (text.isBlank()) {
                throw AiException("转写服务返回了空结果", hint = "音频可能全是静音，或模型名不对")
            }
            AsrResult(
                text = text,
                engineLabel = "云端 · $model",
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    /**
     * 转写内存里的一小段 PCM（实时上屏用）。
     * 会被包上一层合法 WAV 头再上传，免得服务端不认裸 PCM。
     */
    suspend fun transcribePcmSegment(
        baseUrl: String,
        apiKey: String,
        model: String,
        pcm: ByteArray,
        sampleRate: Int,
        language: String? = null,
        segmentIndex: Int = 0
    ): String {
        val temp = File.createTempFile("live-$segmentIndex-", ".wav")
        return try {
            withContext(Dispatchers.IO) {
                temp.outputStream().use { out ->
                    out.write(wavHeader(pcm.size, sampleRate))
                    out.write(pcm)
                }
            }
            transcribeFile(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                file = temp,
                filename = "segment-$segmentIndex.wav",
                language = language
            ).text.trim()
        } finally {
            runCatching { temp.delete() }
        }
    }

    /**
     * 把导入的音频复制到缓存目录。
     * ContentResolver 给的 Uri 不能直接当 File 用，而且有些 Uri 只允许读一次。
     */
    suspend fun copyToCache(context: Context, uri: Uri, displayName: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "import").apply { if (!exists()) mkdirs() }
            val safeName = displayName.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_")
            val target = File(dir, safeName.ifBlank { "import-${System.currentTimeMillis()}.bin" })

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw AiException("读取不到这个文件，可能没有访问权限")

            if (target.extension.equals("wav", ignoreCase = true)) {
                // 外部 wav 的头经常是坏的，不修的话服务端会解出 0 秒
                runCatching { WavWriter.repairHeaderIfNeeded(target) }
            }
            target
        }

    /** 流式上传请求体：大文件也不会把整个音频读进内存 */
    private class FileRequestBody(private val file: File) : RequestBody() {

        override fun contentType() = guessMediaType(file.name)

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { input -> sink.writeAll(input.source()) }
        }
    }

    private fun parseText(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val json = JSONObject(raw)
            json.optString("text").ifBlank {
                json.optString("result").ifBlank { json.optString("transcript") }
            }
        }.getOrElse {
            // 万一返回的不是 JSON（有些自建服务就这样），把原文当结果
            raw
        }.trim()
    }

    private fun explainHttpError(code: Int, raw: String): String = when (code) {
        400 -> "请求被拒，通常是模型名写错或音频格式不支持：${raw.take(160)}"
        401, 403 -> "API Key 无效或没有权限：${raw.take(160)}"
        404 -> "接口路径不存在，BaseURL 可能少写了 /v1"
        413 -> "音频太大，服务端拒绝接收"
        429 -> "触发限流或余额不足：${raw.take(160)}"
        in 500..599 -> "服务端错误（$code），稍后重试：${raw.take(160)}"
        else -> raw.take(200)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300L

        /** 大文件上传 + 服务端排队，超时要给够 */
        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_TIMEOUT_SECONDS + 60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun joinUrl(base: String, path: String): String =
            "${base.trim().trimEnd('/')}/${path.trimStart('/')}"

        fun guessMediaType(fileName: String) = when (fileName.substringAfterLast('.', "").lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "3gp" -> "audio/3gpp"
            "amr" -> "audio/amr"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }.toMediaType()

        private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val buffer = ByteBuffer.allocate(WavWriter.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(36 + dataBytes)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16)
            buffer.putShort(1)
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort((channels * bitsPerSample / 8).toShort())
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(dataBytes)
            return buffer.array()
        }
    }
}
