package com.dsh.notes.ai

import com.dsh.notes.data.AppSettings

/**
 * 提示词中心。
 *
 * 这里其实有**两套完全不同的提示词**，界面上也分开写，因为它们的机制不一样：
 *
 *  1. **转写提示词**（[DEFAULT_ASR_PROMPT]）：不是给模型的指令，而是「术语表 / 上下文线索」。
 *     Whisper 这类模型只把它当上文参考，用来减少专有名词听错（比如「向量数据库」被听成
 *     「限量数据库」）。长度通常几十字，有的服务商直接忽略这个参数。
 *
 *  2. **整理提示词**（[DEFAULT_SYSTEM_PROMPT]）：真正的系统指令，决定怎么把转写文字
 *     整理成结构化 JSON。这里可以写几百字，效果差异也最大。
 *
 * 两套都支持「内置默认 / 用户自定义 / 模板库」三层，[resolve] 负责挑出最终用的那份。
 */
object PromptBuilder {

    // ------------------------------------------------------------ 转写提示词

    /**
     * 转写的默认「术语线索」。刻意写得很短：
     * Whisper 的 prompt 有长度上限（约 224 token），太长会被截断甚至起反作用。
     */
    const val DEFAULT_ASR_PROMPT = "以下是一段中文语音，可能夹杂英文技术词汇。请如实转写，保留数字、人名与专业术语。"

    // ------------------------------------------------------------ 整理提示词

    const val DEFAULT_SYSTEM_PROMPT = """你是一个中文笔记整理助手。用户会给你一段由语音转写得到的原始文字（可能有错别字、口语重复、缺少标点）。

你的任务：把它整理成一份干净、准确、可以直接归档的 Markdown 笔记。

必须遵守：
1. 只依据原文内容整理，**绝对不要编造**原文没有的信息。听不清或明显断裂的地方，写 [听不清] 或按上下文谨慎补全后标注。
2. 修正明显的同音错字、去掉「嗯/啊/那个」这类口头语和重复句，但不要改变原意。
3. 数字、日期、人名、地名、专业术语必须原样保留，不要换算、不要猜测。
4. 如果原文是会议/课堂记录，按议题或知识点分段；如果是随笔，按自然段落整理。
5. 输出语言与原文一致（中文就用中文）。

严格只输出一个 JSON 对象，字段如下：
{
  "title": "不超过 20 字的标题，概括主题",
  "summary": "一到两句话的摘要",
  "key_points": ["要点1", "要点2"],
  "action_items": ["待办1", "待办2"],
  "tags": ["标签1", "标签2"],
  "markdown": "整理后的完整正文，用 Markdown 语法：## 小标题、- 列表、**加粗** 关键词"
}

字段说明：
- key_points：3~8 条，覆盖主要信息，每条一句话。
- action_items：只放明确要做的事（谁、做什么、什么时候）；没有就给空数组。
- tags：2~5 个短标签，用于以后检索。
- markdown：这是笔记正文，要完整、可读、有自己的结构，不要只是照抄原文。"""

    /**
     * 常用模板库：一键换风格，不用每次重写提示词。
     *
     * 每个模板只写「和默认不同的部分 + 输出字段约定」，
     * 因为 [resolve] 会把它和「JSON 输出格式要求」拼在一起，
     * 用户不必自己操心模型能不能被解析。
     */
    val TEMPLATES: List<PromptTemplate> = listOf(
        PromptTemplate(
            id = "meeting",
            name = "会议纪要",
            description = "按议题分段，突出决议、负责人、截止时间",
            prompt = """你在整理一份会议录音的转写稿。请输出会议纪要。

要求：
1. 按议题分成若干小节，每节用 `## 议题名` 作标题。
2. 明确区分三类内容：**已决定的事项**、**待讨论的分歧**、**下一步行动**。
3. 发言人若在原文中出现名字或身份（如「张工」「产品那边」），保留并标注；不要凭空指认。
4. 时间、金额、版本号、人名等关键信息必须原样保留。
5. 原文里没提到的决议不要补，宁可留空。"""
        ),
        PromptTemplate(
            id = "lecture",
            name = "课堂笔记",
            description = "按知识点组织，公式与定义单独标出",
            prompt = """你在整理一份课堂 / 讲座录音的转写稿。请输出便于复习的课堂笔记。

要求：
1. 按知识点组织，用 `## 知识点` 作小标题；有先后依赖关系的按顺序排。
2. **定义、公式、定理**用引用块或加粗单独标出，方便快速定位。
3. 老师举的例子放在对应知识点下面，标注「例：」。
4. 保留所有术语、公式、数字、年份、文献名，不要改写或简化。
5. 听不清的地方标 [听不清]，不要用猜测填补。"""
        ),
        PromptTemplate(
            id = "idea",
            name = "灵感速记",
            description = "保留跳跃的原始思路，只做归纳不重排",
            prompt = """你在整理一段灵感 / 头脑风暴的语音转写。请输出简洁的灵感记录。

要求：
1. **保留原始思路的跳跃感**，不要把想法强行归并成一条逻辑线。
2. 每个独立想法一条，用短句表达，不要扩写成段落。
3. 只去除口头语和重复，不要润色、不要补充原文没有的推论。
4. 如果几个想法明显属于同一主题，用 `## 主题` 分组，主题名从内容里提炼。"""
        ),
        PromptTemplate(
            id = "todo",
            name = "待办提取",
            description = "只抽取可执行事项，其余内容一笔带过",
            prompt = """你在从一段语音转写里抽取待办事项。

要求：
1. `action_items` 是这个模板的重点：尽量抽全，每条写清「做什么」，原文提到负责人或时间就一并写上。
2. `markdown` 里用 `- [ ] 事项` 的复选框列表列出全部待办，按原文出现顺序。
3. 非待办的背景信息压缩成一句话放在 markdown 开头即可，不要展开。
4. 不要脑补原文没提的任务；不确定是不是任务的，放进去并在句末标「（待确认）」。"""
        )
    )

    data class PromptTemplate(
        val id: String,
        val name: String,
        val description: String,
        val prompt: String
    )

    fun templateById(id: String): PromptTemplate? =
        TEMPLATES.firstOrNull { it.id == id }

    /**
     * 挑出最终要发给模型的整理提示词。优先级：
     * 自定义 > 选中模板 > 内置默认。
     * 自定义放最高，是因为用户既然手写了，就不该被模板覆盖。
     */
    fun resolve(settings: AppSettings): String {
        val custom = settings.customSystemPrompt.trim()
        if (custom.isNotBlank()) return withJsonContract(custom)

        val template = templateById(settings.activePromptTemplate)
        if (template != null) return withJsonContract(template.prompt)

        return DEFAULT_SYSTEM_PROMPT
    }

    /** 最终用的是哪一套，给界面显示用 */
    fun activeSourceLabel(settings: AppSettings): String = when {
        settings.customSystemPrompt.isNotBlank() -> "自定义"
        templateById(settings.activePromptTemplate) != null ->
            "模板：${templateById(settings.activePromptTemplate)?.name}"

        else -> "内置默认"
    }

    /** 转写提示词：同样支持自定义，空则用默认 */
    fun asrPrompt(settings: AppSettings): String? =
        settings.asrPrompt.trim().ifBlank { DEFAULT_ASR_PROMPT }

    /** 「按内容重拟标题」用的轻量提示词 */
    fun titlePrompt(): String =
        "请为下面这段笔记内容起一个不超过 20 字的中文标题，只输出标题本身，不要引号、不要标点结尾。"

    /**
     * 不论用户写了什么，都补上 JSON 字段约定 ——
     * 否则模型可能回一段漂亮的 Markdown，而 App 拿不到标题/要点/待办。
     */
    private fun withJsonContract(base: String): String = buildString {
        appendLine(base.trim())
        appendLine()
        appendLine("无论上面的要求如何，最终都必须只输出一个 JSON 对象，字段为：")
        appendLine("title（不超过 20 字）、summary（一到两句）、key_points（字符串数组）、")
        appendLine("action_items（字符串数组，没有就给空数组）、tags（字符串数组）、markdown（完整正文）。")
    }
}
