package com.dsh.notes.pipeline

import com.dsh.notes.data.NoteRepository
import com.dsh.notes.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「转写 + 整理」的处理队列。
 *
 * 为什么需要它：录音一次只能一条（麦克风独占），但**处理**没有这个限制。
 * 以前前面的笔记还在转写时再录一条，结束键会被上一单的处理吞掉，录音停不下来。
 * 现在录音归录音、处理归处理：录完就往队尾一放，用户立刻可以开下一次。
 *
 * 并发固定为 1 —— 一个 worker 协程从 channel 里取，天然串行。理由有两条：
 *  - 两条笔记同时上传会打爆服务商的限流；
 *  - 手机的上行带宽本来就不够分。
 *
 * （实时上屏是另一条链路，不受这里约束：它必须边说边传，串起来就不叫实时了。）
 */
class ProcessingQueue(
    private val pipeline: NotePipeline,
    private val repository: NoteRepository,
    private val settingsStore: SettingsStore,
    scope: CoroutineScope
) {

    /**
     * @param currentNoteId 正在处理的那条；0 表示空闲
     * @param waitingIds    排队等着处理的；按先来后到
     */
    data class QueueState(
        val currentNoteId: Long = 0L,
        val currentProgress: PipelineProgress? = null,
        val waitingIds: List<Long> = emptyList()
    ) {
        /** 「正在处理 + 排队」的总条数 */
        val pendingCount: Int get() = waitingIds.size + if (currentNoteId > 0L) 1 else 0

        val isBusy: Boolean get() = pendingCount > 0
    }

    private val lock = Any()
    private var current = 0L
    private var progress: PipelineProgress? = null
    private val waiting = ArrayDeque<Long>()

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val channel = Channel<Long>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (noteId in channel) {
                synchronized(lock) {
                    current = noteId
                    waiting.remove(noteId)
                    progress = null
                }
                publish()

                runCatching {
                    // forceTranscribe = false：已有转写的（比如整理失败重排的）不会重复花钱转一遍
                    pipeline.process(noteId, forceTranscribe = false) { update ->
                        synchronized(lock) { progress = update }
                        publish()
                    }
                }

                synchronized(lock) {
                    current = 0L
                    progress = null
                }
                publish()
            }
        }
    }

    /**
     * 排一条笔记进去。
     * @return 真的排进去了才返回 true；已经在队里或在处理中会返回 false（去重）
     */
    fun enqueue(noteId: Long): Boolean {
        if (noteId <= 0L) return false
        synchronized(lock) {
            if (noteId == current || waiting.contains(noteId)) return false
            waiting.addLast(noteId)
        }
        publish()
        channel.trySend(noteId)
        return true
    }

    /**
     * App 启动时把积压的笔记重新排上。
     *
     * 覆盖三种状态：SAVED（待处理）、TRANSCRIBING / SUMMARIZING（上次被系统杀掉留下的半截）。
     * 这样排队不会因为进程被杀而永久卡住 —— 每次启动都自愈一次。
     *
     * @param respectAutoProcess 为 true 时，用户关掉了「自动处理」就不续跑（那是他的明确选择）
     */
    suspend fun resumePending(respectAutoProcess: Boolean = true) {
        if (respectAutoProcess && !settingsStore.current().autoProcess) return

        val resumable = runCatching {
            repository.listResumable().sortedBy { it.createdAt }
        }.getOrDefault(emptyList())

        resumable.forEach { enqueue(it.id) }
    }

    private fun publish() {
        _state.value = synchronized(lock) {
            QueueState(
                currentNoteId = current,
                currentProgress = progress,
                waitingIds = waiting.toList()
            )
        }
    }
}
