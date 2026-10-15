package com.dsh.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.audio.RecorderState
import com.dsh.notes.audio.RecordingService
import com.dsh.notes.data.AppSettings
import com.dsh.notes.pipeline.PipelineProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

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

    fun publishProgress(progress: PipelineProgress?) {
        _progress.value = progress
    }

    fun notify(message: String) {
        _toast.value = message
    }

    fun clearToast() {
        _toast.value = null
    }
}
