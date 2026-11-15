package com.dsh.notes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 的语音接口（2026-11 开放）。
 *
 * 识别一次最多 120 秒、8 MiB，所以只用于实时上屏的小切片，
 * 不适合直接转写整场录音。
 */
object DeepSeekVoice {

    const val BASE_URL = "https://api.deepseek.com"

    /** 预设音色；接口本身只有这两个 */
    val VOICES = listOf("stella" to "海星（女声）", "tide" to "暗潮（男声）")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 转写一段 WAV（16bit PCM，单声道）；返回识别出的文字 */
    suspend fun transcribe(apiKey: String, wav: ByteArray): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("还没有填 DeepSeek 的 API Key")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "segment.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/v1/tools/asr")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw AiException("连不上 DeepSeek：${io.message ?: "网络错误"}")
        }

        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AiException("DeepSeek 识别失败（HTTP ${resp.code}）", hint = explain(resp.code))
            }
            runCatching { JSONObject(raw).optString("text") }.getOrDefault("")
        }
    }

    /** 合成整段语音，返回 WAV 字节 */
    suspend fun synthesize(
        apiKey: String,
        text: String,
        voiceId: String = "stella"
    ): ByteArray = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("还没有填 DeepSeek 的 API Key")
        if (text.isBlank()) throw AiException("没有可朗读的文字")

        val payload = JSONObject().apply {
            put("text", text.take(9000))
            put("voice_id", voiceId.ifBlank { "stella" })
            // format / filter_markdown 都有默认值（wav、去掉 Markdown），
            // 不显式传，少一个可能被拒的参数
        }
        val request = Request.Builder()
            .url("$BASE_URL/v1/tools/tts")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw AiException("连不上 DeepSeek：${io.message ?: "网络错误"}")
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                // 把服务端给的原因带出来，否则界面上只有一句「HTTP 400」，没法查
                val detail = runCatching {
                    val json = JSONObject(raw)
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("message")
                        ?: json.optString("detail")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw AiException(
                    "DeepSeek 合成失败（HTTP ${resp.code}）" +
                        if (detail != null) "：$detail" else "",
                    hint = when {
                        raw.contains("Content Exists Risk") -> "文本没有通过内容审核，换一段试试"
                        else -> explain(resp.code)
                    }
                )
            }
            resp.body?.bytes() ?: throw AiException("DeepSeek 没有返回音频")
        }
    }

    private fun explain(code: Int): String = when (code) {
        400 -> "音频格式不对，或请求里缺了必填内容"
        401, 403 -> "API Key 无效，去 DeepSeek 后台确认一下"
        413 -> "音频太大，超过 8 MB"
        429 -> "请求太密，稍等一下再试"
        503, 504 -> "DeepSeek 那边正忙，稍后重试"
        else -> "检查网络和 API Key"
    }
}
