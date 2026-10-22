package com.dsh.notes.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.view.WindowManager

/**
 * 指定期间保持屏幕常亮。
 *
 * 用的是窗口级 [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] ——
 * 之前用 `View.keepScreenOn` 实测不生效：那个属性要等 ViewRootImpl 下一次
 * 遍历才会同步到窗口标志上，而录音页进入录音后未必还有下一帧重绘，
 * 于是标志根本没被加过。窗口标志是即时生效的，不依赖重绘。
 *
 * 不需要任何权限，离开这段组合时自动清掉。
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(enabled, activity) {
        val window = activity?.window
        if (window != null) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            runCatching { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}

/** 从可能的 ContextWrapper 里捞出 Activity */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
