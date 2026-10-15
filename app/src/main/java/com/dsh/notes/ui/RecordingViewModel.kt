package com.dsh.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.audio.RecorderState
import com.dsh.notes.audio.RecordingService
import com.dsh.notes.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 录音页的状态。
 *
 * 这里几乎不存东西 —— 录音的真正状态在 [com.dsh.notes.audio.RecordingService] 里，
 * 因为用户随时可能锁屏、退到后台，界面只是它的一个观察者。
 */
class RecordingViewModel(private val container: AppContainer) : ViewModel() {

    val recorderState: StateFlow<RecorderState> = RecordingService.state

    val settings: StateFlow<AppSettings> = container.settings.flow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    // 录音控制都转给前台服务：界面随时可能被销毁，真正的录音状态不在这里
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

    private val _permissionHint = MutableStateFlow<String?>(null)

    /** 权限被拒之类的提示 */
    val permissionHint: StateFlow<String?> = _permissionHint.asStateFlow()

    fun reportPermissionDenied() {
        _permissionHint.value = "没有麦克风权限就没法录音，请在系统设置里允许「录音」权限"
    }

    fun clearHint() {
        _permissionHint.value = null
    }
}
