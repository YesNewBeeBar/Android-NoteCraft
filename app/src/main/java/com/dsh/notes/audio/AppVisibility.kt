package com.dsh.notes.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 当前是不是在前台。
 *
 * 悬浮球需要它：在 App 内没必要挡着自家界面，切到别的应用才该出现。
 * 用 ActivityLifecycleCallbacks 数「已 start 的 Activity 个数」来判断，
 * 不额外引 lifecycle-process 依赖，也不需要任何权限。
 */
object AppVisibility {

    private val _foreground = MutableStateFlow(false)

    /** true = App 正在前台 */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private var startedCount = 0

    fun onActivityStarted() {
        startedCount++
        _foreground.value = true
    }

    fun onActivityStopped() {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0) _foreground.value = false
    }
}
