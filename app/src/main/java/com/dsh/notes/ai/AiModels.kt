package com.dsh.notes.ai

/** 云转写结果 */
data class AsrResult(
    val text: String,
    val engineLabel: String,
    val elapsedMs: Long = 0L
)

/**
 * 统一的上层错误类型：界面直接展示 message，[hint] 是「该怎么修」的提示。
 * 这样调用方不用去猜 HTTP 状态码的含义。
 */
class AiException(
    message: String,
    cause: Throwable? = null,
    val hint: String? = null
) : Exception(message, cause) {

    /** 给界面用的一行完整说明 */
    val display: String
        get() = if (hint.isNullOrBlank()) message.orEmpty() else "${message.orEmpty()}\n$hint"
}
