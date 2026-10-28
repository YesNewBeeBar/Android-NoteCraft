package com.dsh.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.audio.RecorderState
import com.dsh.notes.audio.RecordingService
import com.dsh.notes.data.AppSettings
import com.dsh.notes.data.ThemeMode
import com.dsh.notes.pipeline.PipelineProgress
import com.dsh.notes.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局状态：录音机状态、设置、以及最近一次处理进度。
 *
 * 之所以要一个跨界面共享的 ViewModel：转写与整理可能跑几十秒，
 * 用户会中途切页甚至退出 App，状态必须活得比单个界面更久。
 * 真正的「哪条笔记在忙」由数据库里的 status 字段说话，
 * 这里只保留一行给人看的进度文字。
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    val recorderState: StateFlow<RecorderState> = RecordingService.state

    val settings: StateFlow<AppSettings> = container.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _progress = MutableStateFlow<PipelineProgress?>(null)

    /** 最近一次处理进度（null 表示没有活动任务） */
    val progress: StateFlow<PipelineProgress?> = _progress.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)

    /** 一行提示；界面消费后调 [clearToast] */
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // ---------------------------------------------------------------- 应用内更新

    /** 更新流程状态；根界面拿它决定要不要弹更新窗 */
    val updateState: StateFlow<UpdateState> = container.updater.state

    /**
     * 查一次新版本。
     * @param manual 手动点的：失败或无更新都会给一句话；自动检查则静默且限流
     */
    fun checkForUpdate(manual: Boolean) {
        viewModelScope.launch {
            container.updater.check(manual)?.let { _toast.value = it }
        }
    }

    /**
     * 下载更新包并拉起系统安装器。
     *
     * 放在 appScope 而不是 viewModelScope：下载常常要跑几十秒，
     * 用户很可能按 Home 键切后台、甚至把界面划掉。挂在 ViewModel 上的话
     * Activity 一销毁下载就被取消，表现就是「放后台就失败」。
     */
    fun installUpdate() {
        container.appScope.launch {
            container.updater.downloadAndInstall()?.let { _toast.value = it }
        }
    }

    /** 「暂不更新」：这次进入不再打扰，下次启动照常提示 */
    fun postponeUpdate() {
        container.updater.postponeForSession()
    }

    /** 「跳过此版本」：记下版本号，同版本以后不再提示，出现新版本才再提示 */
    fun skipUpdateVersion() {
        val info = (container.updater.state.value as? UpdateState.Available)?.info ?: return
        viewModelScope.launch { container.updater.dismiss(info) }
    }

    fun startRecording() {
        RecordingService.start(
            context = container.context,
            sampleRate = settings.value.sampleRate
        )
    }

    fun stopRecording() = RecordingService.stop(container.context)

    fun pauseRecording() = RecordingService.pause(container.context)

    fun resumeRecording() = RecordingService.resume(container.context)

    fun toggleMute() = RecordingService.toggleMute(container.context)

    fun cancelRecording() = RecordingService.cancel(container.context)

    /**
     * 深浅色手动切换：跟随系统 → 浅色 → 深色 → 跟随系统。
     * 结果写进 DataStore，重启后还是用户选的那一档。
     */
    fun cycleThemeMode() {
        val next = settings.value.themeMode.next()
        container.appScope.launch { container.settings.setThemeMode(next) }
        _toast.value = "主题：${next.label}"
    }

    fun publishProgress(progress: PipelineProgress?) {
        _progress.value = progress
    }

    // ---------------------------------------------------------------- 大肥鱼

    private val _fishOpen = MutableStateFlow(false)

    /** 问答页是否展开。只能由笔记页的机器人球打开 */
    val fishOpen: StateFlow<Boolean> = _fishOpen.asStateFlow()

    fun openFish() {
        _fishOpen.value = true
    }

    fun closeFish() {
        _fishOpen.value = false
    }

    fun notify(message: String) {
        _toast.value = message
    }

    fun clearToast() {
        _toast.value = null
    }
}
