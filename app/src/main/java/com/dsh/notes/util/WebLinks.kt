package com.dsh.notes.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 应用里会往外跳的几个网址。
 *
 * 集中放一处，免得「申请 Key」的地址散落在各个界面里各写各的。
 */
object WebLinks {

    const val DEEPSEEK_API_KEYS = "https://platform.deepseek.com/api_keys"

    const val SILICONFLOW_API_KEYS = "https://cloud.siliconflow.cn/me/account/ak"

    const val OPENAI_API_KEYS = "https://platform.openai.com/api-keys"

    const val GROQ_API_KEYS = "https://console.groq.com/keys"

    /**
     * 按用户填的 BaseURL 猜「该去哪家申请 Key」。
     *
     * 认不出来就返回 null（比如自建的 vLLM），界面据此把按钮藏起来 ——
     * 硬塞一个硅基流动的链接给自建用户，只会让人点进去一脸茫然。
     */
    fun asrKeyUrl(baseUrl: String): String? = when {
        baseUrl.isBlank() -> SILICONFLOW_API_KEYS
        baseUrl.contains("siliconflow", ignoreCase = true) -> SILICONFLOW_API_KEYS
        baseUrl.contains("openai.com", ignoreCase = true) -> OPENAI_API_KEYS
        baseUrl.contains("groq.com", ignoreCase = true) -> GROQ_API_KEYS
        else -> null
    }

    /** 丢给默认浏览器打开。用户按返回就回到 App，不需要额外处理。 */
    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
