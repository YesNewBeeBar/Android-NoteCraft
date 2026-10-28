package com.dsh.notes.ai

import com.dsh.notes.data.Note
import com.dsh.notes.data.keyPoints

/**
 * 「大肥鱼」的系统提示词。
 *
 * 设计原则：
 *  1. 只基于参考笔记 + 通用知识，不编造；
 *  2. 不知道就直说 —— 用户明确要求「不确定的不瞎编」；
 *  3. 重点做三件事：讲清笔记、串知识点、补背景。
 */
object FishPrompt {

    /** 参考内容的字符上限：全部笔记走摘要，选中笔记走正文 */
    const val ALL_NOTES_LIMIT = 30_000
    const val SELECTED_LIMIT = 60_000

    fun systemPrompt(aiName: String): String = """
        你是「$aiName」，一个中文学习助手，服务对象是上课、开会、读书做笔记的人。

        你的能力边界（必须严格遵守）：
        - 你只能基于这次提供的「参考笔记」和通用的公开知识回答。
        - 你**没有联网能力**，也拿不到实时信息。凡是需要最新数据、确切出处、具体页码、
          论文编号、法条条款、网址这类内容，而参考笔记里又没有的，
          必须直接说「这点我不确定」或「笔记里没有提到」，**不要编造**。
        - 绝不虚构书名、作者、论文、链接。宁可少说，也不能给一个像模像样的假信息。

        回答方式：
        1. **先给结论**：开头一两句话回答核心问题，再展开。
        2. **串知识点**：优先指出笔记之间的联系 —— 同一个概念的两种说法、前后递进、
           互相矛盾的地方，用「对应」「共同点」「冲突」这类小标题组织。
        3. **补背景**：需要时补充「为什么会这样」的理论或历史背景，
           并明确标注这是补充知识，不是笔记原话。
        4. **可落地**：如果问题是「怎么复习 / 怎么整理 / 考什么」，给出有顺序的清单。
        5. **篇幅**：默认 300~600 字；用户要求详细时再展开，别用空话凑字数。

        格式：用 Markdown（小标题、要点列表、必要时用表格）。
        数学公式用普通文本写，不要用 LaTeX 语法。
        回答用中文，除非用户用别的语言提问。
    """.trimIndent()

    /** 把选中的笔记拼成参考材料；超长时按整篇截断并注明 */
    fun buildReference(notes: List<Note>, limit: Int, fullText: Boolean): String {
        if (notes.isEmpty()) return ""

        val builder = StringBuilder()
        var used = 0
        var truncated = 0

        for (note in notes) {
            val block = buildString {
                appendLine("### ${note.title.ifBlank { "未命名笔记" }}")
                if (note.summary.isNotBlank()) {
                    appendLine("摘要：${note.summary}")
                }
                val points = note.keyPoints()
                if (points.isNotEmpty()) {
                    appendLine("要点：")
                    points.forEach { appendLine("- $it") }
                }
                if (fullText) {
                    val body = note.refinedMarkdown.ifBlank { "" }.trim()
                    if (body.isNotBlank()) {
                        appendLine()
                        appendLine(body)
                    }
                }
                appendLine()
            }

            if (used + block.length > limit) {
                truncated++
                if (used == 0 && block.length > limit) {
                    builder.append(block.take(limit))
                    used = limit
                }
                continue
            }
            builder.append(block)
            used += block.length
        }

        if (truncated > 0) {
            builder.appendLine()
            builder.appendLine("（还有 $truncated 篇笔记因为长度限制没有放进来）")
        }
        return builder.toString().trim()
    }
}
