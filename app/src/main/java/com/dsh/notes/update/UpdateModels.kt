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

