package com.dsh.notes.data

/** 出问题的是哪一块配置；用来把用户送到设置页的正确位置 */
enum class ConfigArea(val routeKey: String) {
    DEEPSEEK("deepseek"),
    ASR("asr")
}

/** 具体到哪一个输入框，界面据此把对应的框标红 */
enum class ConfigField {
    DEEPSEEK_KEY,
    DEEPSEEK_BASE_URL,
    DEEPSEEK_MODEL,
    ASR_KEY,
    ASR_BASE_URL,
    ASR_MODEL,
    ASR_ENGINE
}

/**
 * 一条配置问题：哪一块、哪个框、为什么。
 *
 * 界面拿 [field] 把输入框标红并在下面显示 [message]，
 * 拿 [area] 决定自动滑到哪一节。
 */
data class ConfigProblem(
    val area: ConfigArea,
    val field: ConfigField,
    val message: String
)

/**
 * 录音前的配置体检。
 *
 * 为什么要有这一层：转写或整理任一方没配好，这一条录音就是废的 ——
 * 录完才发现，用户已经白说了十分钟。所以宁可在按下录音键的那一刻就拦住，
 * 把人直接送到出问题的那个输入框前面。
 *
 * 刻意**不检查 Key 的前缀**（sk- / gsk_ 之类）：自建网关用什么都行，
 * 按前缀判会误伤。只挡「空着」「带空格/换行」「短得明显不完整」这三种真问题。
 */
object ConfigValidator {

    fun validate(
        settings: AppSettings,
        /** 这台设备上系统离线识别到底能不能用；只有选了它才需要 */
        localEngineUsable: Boolean = true
    ): List<ConfigProblem> = buildList {
        // ---------- 笔记整理（DeepSeek） ----------
        checkKey(settings.deepseekApiKey, ConfigArea.DEEPSEEK, ConfigField.DEEPSEEK_KEY, "整理")
            ?.let(::add)
        checkBaseUrl(settings.deepseekBaseUrl, ConfigArea.DEEPSEEK, ConfigField.DEEPSEEK_BASE_URL, "整理")
            ?.let(::add)
        checkModel(settings.deepseekModel, ConfigArea.DEEPSEEK, ConfigField.DEEPSEEK_MODEL, "整理")
            ?.let(::add)

        // ---------- 音频转写 ----------
        when (settings.asrEngine) {
            AsrEngine.LOCAL -> if (!localEngineUsable) {
                add(
                    ConfigProblem(
                        ConfigArea.ASR,
                        ConfigField.ASR_ENGINE,
                        "这台设备没有可用的系统语音识别服务，请改用云端 API"
                    )
                )
            }

            AsrEngine.CLOUD -> {
                checkKey(settings.asrApiKey, ConfigArea.ASR, ConfigField.ASR_KEY, "转写")?.let(::add)
                checkBaseUrl(settings.asrBaseUrl, ConfigArea.ASR, ConfigField.ASR_BASE_URL, "转写")?.let(::add)
                checkModel(settings.asrModel, ConfigArea.ASR, ConfigField.ASR_MODEL, "转写")?.let(::add)
            }
        }
    }

    private fun checkKey(
        value: String,
        area: ConfigArea,
        field: ConfigField,
        label: String
    ): ConfigProblem? = when {
        value.isBlank() -> ConfigProblem(area, field, "${label}的 API Key 还没填")
        value.any { it.isWhitespace() } ->
            ConfigProblem(area, field, "${label}的 API Key 里有空格或换行，粘贴时可能多带了字符")
        value.length < 8 -> ConfigProblem(area, field, "${label}的 API Key 太短，看起来不完整")
        else -> null
    }

    private fun checkBaseUrl(
        value: String,
        area: ConfigArea,
        field: ConfigField,
        label: String
    ): ConfigProblem? {
        val trimmed = value.trim()
        // 自己拆主机名，不引 android.net.Uri：这层保持纯 Kotlin，方便单独测
        val host = trimmed.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')

        return when {
            trimmed.isBlank() -> ConfigProblem(area, field, "${label}的 BaseURL 还没填")

            !trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true) ->
                ConfigProblem(area, field, "${label}的 BaseURL 要以 http:// 或 https:// 开头")

            trimmed.any { it.isWhitespace() } ->
                ConfigProblem(area, field, "${label}的 BaseURL 里有空格")

            host.isBlank() ->
                ConfigProblem(area, field, "${label}的 BaseURL 缺少主机名，例如 https://api.deepseek.com")

            else -> null
        }
    }

    private fun checkModel(
        value: String,
        area: ConfigArea,
        field: ConfigField,
        label: String
    ): ConfigProblem? = when {
        value.isBlank() -> ConfigProblem(area, field, "${label}还没填模型名")
        value.any { it.isWhitespace() } ->
            ConfigProblem(area, field, "${label}的模型名里有空格，模型名本身不该带空格")
        else -> null
    }
}
