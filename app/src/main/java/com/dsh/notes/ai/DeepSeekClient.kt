package com.dsh.notes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 整理结果：标题 / 摘要 / 正文 Markdown / 结构化数据 */
data class NoteDigest(
    val title: String,
    val summary: String,
    val markdown: String,
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val structuredJson: String? = null,
    val usage: AiTextCodec.Usage? = null
)

/**
 * DeepSeek 客户端（POST /chat/completions，OpenAI 兼容）。
 *
 * 需求里只要求 DeepSeek，但 BaseURL 与模型名做成可配置之后，
 * Kimi / 通义 / 本地 Ollama 也顺带能接 —— 白送的能力，不额外增加复杂度。
 */
class DeepSeekClient(private val client: OkHttpClient) {

    /**
     * 把转写原文整理成结构化笔记。
     *
     * 优先请求 JSON 输出（response_format=json_object）；少数部署不支持这个参数会返回 400，
     * 这时自动退回「纯 Markdown」模式再试一次，用户不会看到这次失败。
     */
    suspend fun summarize(
        baseUrl: String,
        apiKey: String,
        model: String,
        transcript: String,
        systemPrompt: String,
        temperature: Float = 0.3f,
        extraInstruction: String? = null
    ): NoteDigest = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw AiException("还没填 DeepSeek API Key", hint = "去「设置 → 笔记整理」里填写")
        }
        if (transcript.isBlank()) throw AiException("没有可整理的文字内容")

        val userPrompt = buildString {
            appendLine("下面是一段录音转写出来的原始文字，请整理成结构化笔记。")
            if (!extraInstruction.isNullOrBlank()) {
                appendLine()
                appendLine("额外要求：$extraInstruction")
            }
            appendLine()
            appendLine("----- 原始转写开始 -----")
            appendLine(transcript.trim())
            appendLine("----- 原始转写结束 -----")
        }

        val raw = try {
            callOnce(baseUrl, apiKey, model, systemPrompt, userPrompt, temperature, jsonMode = true)
        } catch (error: AiException) {
            val blob = (error.message.orEmpty() + " " + error.hint.orEmpty()).lowercase()
            // 只有「服务端不认 json_object」才值得重试；Key 错、限流之类重试也是白搭
            if ("response_format" in blob || "json_object" in blob) {
                callOnce(baseUrl, apiKey, model, systemPrompt, userPrompt, temperature, jsonMode = false)
            } else {
                throw error
            }
        }

        parseDigest(raw, transcript)
    }

    /** 直接问一句话：用于「按内容重拟标题」这类小功能 */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float = 0.3f
    ): String = withContext(Dispatchers.IO) {
        val raw = callOnce(baseUrl, apiKey, model, systemPrompt, userPrompt, temperature, jsonMode = false)
        AiTextCodec.extractChatContent(raw)
    }

    /**
     * 流式对话（SSE）：每来一段就回调一次，最后返回完整文本。
     *
     * 大肥鱼必须用流式：一次回答经常要十几秒，非流式的话界面会一直空白，
     * 用户不知道是在思考还是卡死了。
     */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Float = 0.6f,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw AiException(
                "还没填 API Key",
                hint = "去「设置 → 大肥鱼」填一个，或让它沿用笔记整理那套"
            )
        }
        if (messages.isEmpty()) throw AiException("没有要发送的内容")

        val payload = buildJsonObject {
            put("model", model.ifBlank { "deepseek-chat" })
            put("temperature", temperature)
            put("stream", true)
            put("messages", buildJsonArray {
                messages.forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", content)
                    })
                }
            })
        }

        val request = Request.Builder()
            .url(endpointOf(baseUrl))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw AiException(
                "连不上 AI 服务：${io.message ?: "网络错误"}",
                io,
                hint = "检查网络，以及 BaseURL 是否写对（DeepSeek 是 https://api.deepseek.com）"
            )
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val raw = resp.body?.string().orEmpty()
                throw AiException(
                    "AI 服务返回 HTTP ${resp.code}",
                    hint = explainHttpError(resp.code, raw)
                )
            }
            val source = resp.body?.source() ?: throw AiException("AI 服务没有返回内容")

            val builder = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break

                val delta = runCatching {
                    Json.parseToJsonElement(data).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty()

                if (delta.isNotEmpty()) {
                    builder.append(delta)
                    onDelta(delta)
                }
            }
            builder.toString()
        }
    }

    private fun callOnce(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        jsonMode: Boolean
    ): String {
        if (apiKey.isBlank()) throw AiException("还没填 DeepSeek API Key")

        val payload = buildJsonObject {
            put("model", model.ifBlank { "deepseek-chat" })
            put("temperature", temperature)
            put("stream", false)
            put("messages", buildJsonArray {
                if (systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            if (jsonMode) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }

        val request = Request.Builder()
            .url(endpointOf(baseUrl))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw AiException(
                "连不上整理服务：${io.message ?: "网络错误"}",
                io,
                hint = "检查网络，以及 BaseURL 是否写对（DeepSeek 是 https://api.deepseek.com）"
            )
        }

        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AiException(
                    "整理服务返回 HTTP ${resp.code}",
                    hint = explainHttpError(resp.code, raw)
                )
            }
            return raw
        }
    }

    private fun parseDigest(raw: String, transcript: String): NoteDigest {
        val content = AiTextCodec.extractChatContent(raw)
        val usage = AiTextCodec.extractUsage(raw)
        val obj = AiTextCodec.findJsonObject(content)

        if (obj == null) {
            // 模型没给 JSON 也不该整体失败：把整段当正文，标题取首个非空行
            val title = content.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.replace(Regex("^#+\\s*"), "")
                ?.trim()
                ?.take(40)
                ?.takeIf { it.isNotBlank() }
                ?: "未命名笔记"
            return NoteDigest(
                title = title,
                summary = content.replace(Regex("\\s+"), " ").trim().take(120),
                markdown = content,
                usage = usage
            )
        }

        val keyPoints = AiTextCodec.stringListOf(obj, "key_points")
        val actionItems = AiTextCodec.stringListOf(obj, "action_items")
        val tags = AiTextCodec.stringListOf(obj, "tags")
        val markdown = AiTextCodec.stringOf(obj, "markdown")
            .ifBlank { AiTextCodec.stringOf(obj, "content") }
            .ifBlank { AiTextCodec.stringOf(obj, "notes") }
            .ifBlank { content }

        val structured = buildJsonObject {
            put("key_points", buildJsonArray { keyPoints.forEach { add(JsonPrimitive(it)) } })
            put("action_items", buildJsonArray { actionItems.forEach { add(JsonPrimitive(it)) } })
            put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
        }.toString()

        return NoteDigest(
            title = AiTextCodec.stringOf(obj, "title").trim().take(60).ifBlank { "未命名笔记" },
            summary = AiTextCodec.stringOf(obj, "summary").trim().take(200),
            markdown = markdown,
            keyPoints = keyPoints,
            actionItems = actionItems,
            tags = tags,
            structuredJson = structured,
            usage = usage
        )
    }

    private fun explainHttpError(code: Int, raw: String): String = when (code) {
        400 -> "请求被拒：${raw.take(200)}"
        401, 403 -> "API Key 无效或余额不足：${raw.take(200)}"
        404 -> "接口不存在，BaseURL 可能写错（DeepSeek 是 https://api.deepseek.com）"
        422 -> "参数不被接受：${raw.take(200)}"
        429 -> "限流或余额不足：${raw.take(200)}"
        in 500..599 -> "服务端错误（$code），稍后重试"
        else -> raw.take(200)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 整理长文本可能比较慢，给足读超时 */
        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(360, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun endpointOf(baseUrl: String): String =
            OpenAiCompatibleAsr.joinUrl(baseUrl, "chat/completions")
    }
}
