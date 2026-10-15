package com.dsh.notes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dsh.notes.data.NoteStatus

/** 状态徽标：完成 / 转写中 / 失败… 一眼能分清 */
@Composable
fun StatusChip(
    status: NoteStatus,
    modifier: Modifier = Modifier
) {
    val (background, foreground) = statusColors(status)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(11.dp)
                    .padding(end = 0.dp),
                strokeWidth = 1.5.dp,
                color = foreground
            )
            Box(Modifier.size(5.dp))
        }
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground
        )
    }
}

@Composable
private fun statusColors(status: NoteStatus): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        NoteStatus.DONE -> scheme.primaryContainer to scheme.onPrimaryContainer
        NoteStatus.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        NoteStatus.RECORDING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        NoteStatus.SAVED -> scheme.surfaceVariant to scheme.onSurfaceVariant
        NoteStatus.TRANSCRIBING, NoteStatus.SUMMARIZING ->
            scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
}
