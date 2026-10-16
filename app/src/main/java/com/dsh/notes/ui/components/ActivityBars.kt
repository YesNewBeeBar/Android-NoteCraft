package com.dsh.notes.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 三根上下跳动的柱子，表示「正在处理」。
 *
 * 用真实的无限动画而不是静态图形：转写可能跑十几秒，
 * 一个不动的图标会让人以为卡死了。
 *
 * 注意容器高度必须写死：柱子本身在 5dp↔17dp 之间跳，如果外层用 wrap content，
 * 整个组件的高度会跟着抖 12dp —— 上面的卡片、下面的按钮全都会一起上下跳。
 */
@Composable
fun ActivityBars(
    modifier: Modifier = Modifier,
    barCount: Int = 3,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "activity")
    Row(
        modifier = modifier.height(BAR_BOX_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(barCount) { index ->
            val height by transition.animateFloat(
                initialValue = 5f,
                targetValue = 17f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 130),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$index"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/** 柱子最高 17dp，留一点余量把容器钉死在这 */
private val BAR_BOX_HEIGHT = 18.dp
