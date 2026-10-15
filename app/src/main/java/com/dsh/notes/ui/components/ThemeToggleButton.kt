package com.dsh.notes.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.dsh.notes.data.ThemeMode

/**
 * 标题栏右上角的深浅色开关。
 *
 * 点一下轮到下一档：跟随系统 → 浅色 → 深色 → 跟随系统。
 * 图标本身就是当前状态，一眼能看出现在锁在哪一档。
 */
@Composable
fun ThemeToggleButton(
    themeMode: ThemeMode,
    onToggle: () -> Unit
) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = when (themeMode) {
                ThemeMode.FOLLOW_SYSTEM -> Icons.Default.BrightnessAuto
                ThemeMode.LIGHT -> Icons.Default.LightMode
                ThemeMode.DARK -> Icons.Default.DarkMode
            },
            contentDescription = "主题：${themeMode.label}（点击切换）"
        )
    }
}
