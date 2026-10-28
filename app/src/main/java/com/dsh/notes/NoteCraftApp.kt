package com.dsh.notes

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.dsh.notes.ai.AiClients
import com.dsh.notes.audio.AppVisibility
import com.dsh.notes.audio.RecordingService
import com.dsh.notes.data.NoteRepository
import com.dsh.notes.data.NoteStatus
import com.dsh.notes.data.NotesDatabase
import com.dsh.notes.data.SettingsStore
import com.dsh.notes.export.NotesExporter
import com.dsh.notes.pipeline.NotePipeline
import com.dsh.notes.pipeline.ProcessingQueue
import com.dsh.notes.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手工依赖容器。
 *
 * 刻意不用 Hilt/Dagger：目标是「用 Android Studio 打开就能编」，
 * 少一个注解处理器就少一类版本不匹配的坑。所有依赖在这里创建，
 * 通过 [NoteCraftApp.container] 取用。
 */
class AppContainer(private val app: Application) {

    /** Application 级 Context：前台服务、SAF、通知都要用；持有 Application 不会泄漏 */
    val context: Application get() = app

    /** 与进程同生命周期的 IO 作用域：转写/整理这类长任务不能被界面销毁打断 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: NotesDatabase by lazy { NotesDatabase.build(app) }

    val settings: SettingsStore by lazy { SettingsStore(app) }

    val repository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), database.folderDao())
    }

    val ai: AiClients by lazy { AiClients(settings) }

    /** 大肥鱼的对话历史 */
    val chatDao get() = database.chatDao()

    val exporter: NotesExporter by lazy { NotesExporter(app, settings, repository) }

    val pipeline: NotePipeline by lazy { NotePipeline(app, repository, settings, ai, exporter) }

    /**
     * 「转写 + 整理」的处理队列。串行、并发 1。
     *
     * 放在容器里做单例：录音结束后台服务要往里排，启动时的自动续跑也要往里排，
     * 两处必须是同一个队列。
     */
    val queue: ProcessingQueue by lazy {
        ProcessingQueue(pipeline, repository, settings, appScope)
    }

    /**
     * 应用内自更新。做成单例是有意的：启动时的自动检查和设置页的「检查更新」
     * 必须共用同一份状态，否则两边会各弹各的窗。
     */
    val updater: UpdateManager by lazy { UpdateManager(app, settings) }

    /** 当前正在录音的那条笔记 id；前台服务结束时要用 */
    @Volatile
    var activeNoteId: Long = -1L
        private set

    fun beginNote(id: Long) {
        activeNoteId = id
    }

    fun clearActiveNote() {
        activeNoteId = -1L
    }

    init {
        // 设备重启 / 应用更新后，把上次异常中断留下的「录音中」状态收尾
        appScope.launch {
            runCatching {
                val stale = repository.listRecording()
                stale.forEach { note ->
                    repository.patch(note.id) {
                        it.copy(
                            status = NoteStatus.FAILED,
                            errorMessage = "上次录音被系统中断（断电或强制停止），音频可能不完整"
                        )
                    }
                }
                if (stale.isNotEmpty()) {
                    RecordingService.clearPersistedState(app)
                }
            }

            // 再把积压的「待处理」重新排上队。
            // 这样即使上次是被强杀的，队列也会自愈，而不是永久卡住。
            runCatching { queue.resumePending() }
        }
    }
}

class NoteCraftApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppVisibility.onActivityStarted()

            override fun onActivityStopped(activity: Activity) = AppVisibility.onActivityStopped()

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        /** 少数需要 Context 的静态场景（通知、广播）会用到 */
        lateinit var instance: NoteCraftApp
            private set
    }
}
