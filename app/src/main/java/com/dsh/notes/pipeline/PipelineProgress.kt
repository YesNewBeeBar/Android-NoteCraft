package com.dsh.notes.pipeline

/** 处理阶段 */
enum class PipelineStage {
    IDLE,
    TRANSCRIBING,
    SUMMARIZING,
    EXPORTING,
    DONE,
    FAILED
}

/**
 * 处理进度。前台服务把它同步到通知栏，界面把它显示成一行状态文字。
 */
data class PipelineProgress(
    val noteId: Long = 0L,
    val stage: PipelineStage = PipelineStage.IDLE,
    val message: String = "",
    val error: String? = null
) {
    val isWorking: Boolean
        get() = stage == PipelineStage.TRANSCRIBING ||
            stage == PipelineStage.SUMMARIZING ||
            stage == PipelineStage.EXPORTING

    companion object {
        fun of(noteId: Long, stage: PipelineStage, message: String) =
            PipelineProgress(noteId = noteId, stage = stage, message = message)
    }
}
