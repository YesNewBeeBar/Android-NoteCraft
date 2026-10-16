package com.dsh.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.audio.LocalTranscriber
import com.dsh.notes.audio.RecorderState
import com.dsh.notes.audio.RecordingService
import com.dsh.notes.data.AppSettings
import com.dsh.notes.data.ConfigProblem
import com.dsh.notes.data.ConfigValidator
import com.dsh.notes.data.StorageFormat
import com.dsh.notes.pipeline.ProcessingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 录音页的状态。
 *
 * 这里几乎不存东西 —— 录音的真正状态在 [com.dsh.notes.audio.RecordingService] 里，
 * 因为用户随时可能锁屏、退到后台，界面只是它的一个观察者。
 */
class RecordingViewModel(private val container: AppContainer) : ViewModel() {

    val recorderState: StateFlow<RecorderState> = RecordingService.state

    /** 「转写 + 整理」的处理队列：界面用它显示排队条数与当前进度 */
    val queueState: StateFlow<ProcessingQueue.QueueState> = container.queue.state

    /** 录音页那个实时上屏开关，和设置页是同一个设置 */
    fun setLiveCaption(enabled: Boolean) = viewModelScope.launch {
        container.settings.setLiveCaptionEnabled(enabled)
        // 打开实时上屏就必须用 WAV（切片要原始 PCM），
        // 顺手把格式也改掉，免得设置里写着 m4a、界面上却录的是 WAV
        if (enabled && container.settings.current().storageFormat != StorageFormat.WAV) {
            container.settings.setStorageFormat(StorageFormat.WAV)
        }
    }

    /** 选 m4a 就自动关掉实时上屏 —— m4a 拿不到 PCM，开着它也没用 */
    fun setStorageFormat(format: StorageFormat) = viewModelScope.launch {
        container.settings.setStorageFormat(format)
        if (format == StorageFormat.M4A && container.settings.current().liveCaptionEnabled) {
            container.settings.setLiveCaptionEnabled(false)
        }
    }

    val settings: StateFlow<AppSettings> = container.settings.flow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    /** 这台设备上系统离线识别能不能用；选云端时用不到，但配置校验要拿它判断 */
    val localEngineAvailable: Boolean = LocalTranscriber(container.context).isAvailable

    /**
     * 录音前的配置体检。
     * @return 空列表表示可以录；否则每一项都指出是哪一块的哪个框、为什么
     */
    fun configProblems(settings: AppSettings): List<ConfigProblem> =
        ConfigValidator.validate(settings, localEngineUsable = localEngineAvailable)

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
