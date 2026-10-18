package com.dsh.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dsh.notes.ai.AiTextCodec
import kotlinx.serialization.json.JsonObject

/** 一条笔记的一生：录音中 → 已保存 → 转写中 → 整理中 → 完成 / 失败 */
enum class NoteStatus {
    RECORDING,
    SAVED,
    TRANSCRIBING,
    SUMMARIZING,
    DONE,
    FAILED;

    val isBusy: Boolean
        get() = this == RECORDING || this == TRANSCRIBING || this == SUMMARIZING

    val label: String
        get() = when (this) {
            RECORDING -> "录音中"
            SAVED -> "待处理"
            TRANSCRIBING -> "转写中"
            SUMMARIZING -> "整理中"
            DONE -> "已完成"
            FAILED -> "出错"
        }
}

/** 音频来源：现场录的 / 从文件导入的 */
enum class AudioSource {
    RECORDED,
    IMPORTED
}

@Entity(
    tableName = "notes",
    indices = [Index("createdAt"), Index("status")]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val title: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    /** 音频绝对路径；文件可能被用户清理掉，UI 需要容错 */
    val audioPath: String? = null,
    val audioSource: AudioSource = AudioSource.RECORDED,
    val audioDurationMs: Long = 0L,
    val audioSizeBytes: Long = 0L,

    /** 转写原文（未加工） */
    val transcript: String = "",

    /** AI 整理后的 Markdown 正文 */
    val refinedMarkdown: String = "",

    /** 一句话摘要，列表预览用 */
    val summary: String = "",

    val status: NoteStatus = NoteStatus.RECORDING,
    val errorMessage: String? = null,

    /** 本条笔记单独指定的导出目录；为空则用全局默认目录 */
    val exportTreeUri: String? = null,
    val exportRelativePath: String? = null,
    val exportDocumentUri: String? = null,
    val exportedAt: Long? = null,

    /** 转写用的引擎 / 整理用的模型，便于回看「这条是怎么来的」 */
    val asrEngine: String? = null,
    val llmModel: String? = null,

    /**
     * 结构化数据（要点、待办、标签）存成 JSON，不做多张表：
     * schema 保持简单，迁移成本最低，代价只是查询时要在内存里解析。
     */
    val structuredJson: String? = null,

    /** 录音过程中实时上屏的文字；完整转写完成后会被替换 */
    @ColumnInfo(defaultValue = "")
    val liveDraft: String = "",

    /** 所属文件夹；null 表示未分类 */
    val folderId: Long? = null
) {
    /** 列表/详情里显示的正文：优先整理结果，其次原始转写 */
    val displayBody: String
        get() = refinedMarkdown.ifBlank { transcript }

    val previewText: String
        get() {
            val base = summary.ifBlank { displayBody }
            return base.replace(Regex("\\s+"), " ").trim().take(120)
        }

    val durationText: String
        get() {
            val total = (audioDurationMs / 1000).toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
}

/**
 * 结构化数据（要点 / 待办 / 标签）的读取入口。
 *
 * 存的时候是一个 JSON 字符串，这里按需解析。解析失败不抛异常，
 * 只是返回空列表 —— 笔记正文仍然完好，不该因为一个附加字段打不开。
 */
private inline fun <T> Note.parseStructured(default: T, block: (String) -> T): T {
    val raw = structuredJson?.takeIf { it.isNotBlank() } ?: return default
    return runCatching { block(raw) }.getOrDefault(default)
}

fun Note.keyPoints(): List<String> = parseStructured(emptyList()) { raw ->
    AiTextCodec.stringListOf(AiTextCodec.json.parseToJsonElement(raw) as? JsonObject, "key_points")
}

fun Note.actionItems(): List<String> = parseStructured(emptyList()) { raw ->
    AiTextCodec.stringListOf(AiTextCodec.json.parseToJsonElement(raw) as? JsonObject, "action_items")
}

fun Note.tags(): List<String> = parseStructured(emptyList()) { raw ->
    AiTextCodec.stringListOf(AiTextCodec.json.parseToJsonElement(raw) as? JsonObject, "tags")
}
