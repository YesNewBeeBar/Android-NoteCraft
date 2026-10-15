package com.dsh.notes.ai

import com.dsh.notes.data.SettingsStore

/**
 * 两个 HTTP 客户端在这里统一持有：
 * 转写要传大文件、整理要长等待，各自的超时策略不同，但底层连接池可以共用。
 *
 * 注意：这里**没有**任何密钥缓存 —— 每次调用都从 [SettingsStore] 现取，
 * 用户改完设置立刻生效，不用重启 App。
 */
class AiClients(private val settings: SettingsStore) {

    private val asrHttp = OpenAiCompatibleAsr.buildClient()
    private val llmHttp = DeepSeekClient.buildClient()

    val asr: OpenAiCompatibleAsr = OpenAiCompatibleAsr(asrHttp)

    val deepseek: DeepSeekClient = DeepSeekClient(llmHttp)

    suspend fun currentSettings() = settings.current()
}
