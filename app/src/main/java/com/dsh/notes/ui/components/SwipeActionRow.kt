package com.dsh.notes.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 滑动露出的一个操作 */
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
    val onClick: () -> Unit
)

/**
 * 向左拖动卡片、从右侧露出一排操作。
 *
 * 为什么是「左拖露出右侧」而不是「右拖露出左侧」：五个操作一共要占掉两百多 dp，
 * 如果把卡片往右推，卡片几乎整张被推出屏幕，看着像被撕掉。
 * 往左推则卡片还剩大半张，视觉上更稳。
 *
 * 同一时刻只允许一行展开：展开状态由调用方用一个 id 托管（[expanded]）。
 */
@Composable
fun SwipeActionRow(
    actions: List<SwipeAction>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val actionWidth = 52.dp
    val panelWidth = actionWidth * actions.size
    val panelPx = with(LocalDensity.current) { panelWidth.toPx() }

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 外部收起（比如另一行被打开了）
    LaunchedEffect(expanded, panelPx) {
        offsetX.animateTo(
            targetValue = if (expanded) -panelPx else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessLow)
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 背面：操作
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                ActionCell(action = action, modifier = Modifier.width(actionWidth))
            }
        }

        // 正面：卡片本身，可以横向拖动
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-panelPx, 0f))
                        }
                    },
                    onDragStopped = { velocity ->
                        val open = -offsetX.value > panelPx * 0.4f || velocity < -900f
                        onExpandedChange(open)
                        offsetX.animateTo(
                            targetValue = if (open) -panelPx else 0f,
                            animationSpec = spring(stiffness = Spring.StiffnessLow)
                        )
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun ActionCell(action: SwipeAction, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        Surface(
            onClick = action.onClick,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp)),
            color = action.container
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.label,
                    tint = action.content,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = action.content,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}
