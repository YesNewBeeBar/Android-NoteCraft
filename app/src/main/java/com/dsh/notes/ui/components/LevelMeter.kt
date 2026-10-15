package com.dsh.notes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 实时音量条：录音时给用户一个「麦克风真的在工作」的反馈。
 *
 * 刻意做成一条随音量伸缩的进度条，而不是跳动波形：
 * 前者一眼能看出「有声音/没声音」，后者好看但看不出问题。
 */
@Composable
fun LevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    muted: Boolean = false
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = when {
        muted -> MaterialTheme.colorScheme.outline
        level > 0.75f -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            drawRoundRect(
                color = trackColor,
                size = Size(size.width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            )
            val width = size.width * level.coerceIn(0f, 1f)
            if (width > 0f) {
                drawRoundRect(
                    color = activeColor,
                    size = Size(width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                )
            }
        }
    }
}

/**
 * 静态波形图：笔记详情页用它展示音频的响度分布。
 * [peaks] 里每个值 0f..1f，长度就是柱子数量。
 */
@Composable
fun WaveformView(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    val idleColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        if (peaks.isEmpty()) return@Canvas
        val barWidth = size.width / (peaks.size * 1.6f)
        val gap = barWidth * 0.6f
        val centerY = size.height / 2f
        val playedCount = (peaks.size * progress.coerceIn(0f, 1f)).toInt()

        peaks.forEachIndexed { index, peak ->
            val height = (peak.coerceIn(0.02f, 1f)) * size.height * 0.9f
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = if (index <= playedCount) barColor else idleColor,
                topLeft = Offset(x, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
        }
    }
}

