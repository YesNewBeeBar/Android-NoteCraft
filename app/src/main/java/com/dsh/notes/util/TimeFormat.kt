package com.dsh.notes.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间显示。
 *
 * 列表里刻意用「今天 14:30 / 昨天 09:12 / 04-05 14:30」这种相对写法：
 * 笔记大多集中在最近几天，绝对日期反而不容易扫读。
 */
private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
private val monthDayFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

fun formatDateTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)

    return when {
        sameYear && dayDiff == 0 -> "今天 ${timeFormat.format(Date(timestamp))}"
        sameYear && dayDiff == 1 -> "昨天 ${timeFormat.format(Date(timestamp))}"
        sameYear -> monthDayFormat.format(Date(timestamp))
        else -> fullFormat.format(Date(timestamp))
    }
}

fun formatFullDateTime(timestamp: Long): String = fullFormat.format(Date(timestamp))

/** 00:00 / 1:02:03 */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 1.2 MB / 830 KB */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 长文本截断，保留结尾（用于显示路径） */
fun String.ellipsizeMiddle(max: Int = 48): String {
    if (length <= max) return this
    val head = max / 2 - 2
    val tail = max - head - 3
    return take(head) + "…" + takeLast(tail)
}
