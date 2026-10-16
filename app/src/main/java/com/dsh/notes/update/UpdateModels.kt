package com.dsh.notes.update

import java.io.File

/**
 * 从 GitHub Releases 读到的一条版本信息。
 *
 * @param versionName 去掉前缀 v 的版本号，例如 "1.1.0"
 * @param apkUrl      这一版附带的 .apk 资产地址；没有资产时为 null
 * @param releaseUrl  发布页地址，作为「没有 apk 资产」时的兜底出口
 */
data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String?,
    val apkSizeBytes: Long,
    val releaseUrl: String
)

/** 更新流程的状态。界面只在 [Available] 与 [Downloading] 时弹窗。 */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    /** 带着 info 一起走：下载过程中弹窗还要显示「在更新到哪个版本」 */
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState

    data class Installer(val file: File) : UpdateState
}

/**
 * 比较版本号大小。只比数字段，忽略 `v` 前缀和 `-beta` 这类后缀。
 *
 * 故意的简单实现：这个项目的版本号就是 `主.次.修订`，上语义化版本那套重家伙没必要。
 */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    fun parts(value: String) = value.trim()
        .removePrefix("v").removePrefix("V")
        .substringBefore('-')
        .split('.')
        .map { it.trim().toIntOrNull() ?: 0 }

    val r = parts(remote)
    val c = parts(current)
    for (i in 0 until maxOf(r.size, c.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = c.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}
