package com.dsh.notes.export

import com.dsh.notes.data.Note
import com.dsh.notes.data.actionItems
import com.dsh.notes.data.keyPoints
import com.dsh.notes.data.tags
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 算「哪几条笔记和这条最像」。
 *
 * 中文没有空格，按词切分需要词典，这里退一步用**字二元组**（「文学」→ 文学），
 * 配合 TF-IDF 余弦相似度。不需要模型、不联网，几毫秒就算完，
 * 结果直接变成导出的 `[[双链]]`。
 */
object RelatedNotes {

    /** 标题在相似度里权重更高：重复几遍等于加权 */
    private const val TITLE_REPEAT = 3

    private val latinWord = Regex("[a-z0-9]{2,}")

    fun rank(target: Note, candidates: List<Note>, limit: Int = 5): List<Note> {
        val others = candidates.filter { it.id != target.id }
        if (others.isEmpty()) return emptyList()

        val targetTokens = tokens(textOf(target))
        if (targetTokens.size < 6) return emptyList()

        val docs = others.map { it to tokens(textOf(it)) }
        val df = HashMap<String, Int>()
        docs.forEach { (_, tk) -> tk.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val docCount = docs.size.toDouble()

        val targetVector = vector(targetTokens, df, docCount)

        return docs.mapNotNull { (note, tk) ->
            if (tk.size < 6) return@mapNotNull null
            val score = cosine(targetVector, vector(tk, df, docCount))
            // 太低的多半只是共享了「今天」「笔记」这类高频词，不往导出里塞
            if (score >= 0.08) note to score else null
        }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun textOf(note: Note): String = buildString {
        val title = note.title.ifBlank { "" }
        repeat(TITLE_REPEAT) {
            if (title.isNotBlank()) append(title).append(' ')
        }
        append(note.summary).append(' ')
        note.keyPoints().forEach { append(it).append(' ') }
        note.tags().forEach { append(it).append(' ') }
        note.actionItems().forEach { append(it).append(' ') }
        append(note.refinedMarkdown)
    }

    /** 英文按词，中文按相邻两字 */
    private fun tokens(text: String): List<String> {
        val lower = text.lowercase()
        val out = ArrayList<String>(lower.length / 2 + 8)
        latinWord.findAll(lower).forEach { out += it.value }
        val cjk = lower.filter { it in '\u4e00'..'\u9fa5' }
        for (i in 0 until cjk.length - 1) {
            out += cjk.substring(i, i + 2)
        }
        return out
    }

    private fun idf(token: String, df: Map<String, Int>, docCount: Double): Double {
        val d = df[token] ?: 0
        return ln((docCount + 1.0) / (d + 1.0)) + 1.0
    }

    private fun vector(
        tokens: List<String>,
        df: Map<String, Int>,
        docCount: Double
    ): Map<String, Double> {
        val tf = HashMap<String, Int>()
        tokens.forEach { tf[it] = (tf[it] ?: 0) + 1 }
        val total = tokens.size.toDouble().coerceAtLeast(1.0)
        return tf.mapValues { (token, count) ->
            (count / total) * idf(token, df, docCount)
        }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        a.forEach { (k, v) ->
            normA += v * v
            b[k]?.let { dot += v * it }
        }
        b.values.forEach { normB += it * it }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator <= 0.0) 0.0 else dot / denominator
    }
}
