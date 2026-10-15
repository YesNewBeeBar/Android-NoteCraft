package com.dsh.notes.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析 AI 返回内容的小工具。
 *
 * 用 kotlinx.serialization 的 JsonElement 树、而不是给每家 API 定义 data class：
 * 各家的返回字段总有出入（有的少 usage、有的多 id），树形解析对缺字段最宽容。
 */
object AiTextCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 从 Chat Completions 响应里取出正文 */
    fun extractChatContent(raw: String): String {
        val root = parseObject(raw)
        val choices = root["choices"] as? JsonArray
            ?: throw AiException("返回格式不认识：没有 choices 字段", hint = raw.take(200))
        val first = choices.firstOrNull() as? JsonObject
            ?: throw AiException("返回内容为空（choices 是空数组）")
        val message = first["message"] as? JsonObject
            ?: throw AiException("返回格式不认识：没有 message 字段")

        return when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            // 少数服务端把 content 拆成数组，这里只取其中的 text 片段
            is JsonArray -> content.mapNotNull { element ->
                (element as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("")
            else -> ""
        }.trim()
    }

    /** 取 token 用量，用于在界面上显示「这次花了多少」 */
    fun extractUsage(raw: String): Usage? = runCatching {
        val usage = parseObject(raw)["usage"] as? JsonObject ?: return null
        Usage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        )
    }.getOrNull()

    /** 尽力从一段可能是 JSON 的文本里找出对象：模型爱加 ```json 围栏和前后废话 */
    fun findJsonObject(text: String): JsonObject? {
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let { return it }

        Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { fenced ->
                runCatching { json.parseToJsonElement(fenced.trim()) as? JsonObject }
                    .getOrNull()
                    ?.let { return it }
            }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start in 0 until end) {
            runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    fun stringOf(obj: JsonObject?, key: String): String =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun stringListOf(obj: JsonObject?, key: String): List<String> {
        return when (val element = obj?.get(key)) {
            is JsonArray -> element.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            }

            is JsonPrimitive -> element.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let { listOf(it) }
                ?: emptyList()

            else -> emptyList()
        }
    }

    private fun parseObject(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            ?: throw AiException("返回的不是合法 JSON", hint = raw.take(200))

    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    ) {
        val display: String
            get() = "输入 $promptTokens / 输出 $completionTokens tokens"
    }
}
