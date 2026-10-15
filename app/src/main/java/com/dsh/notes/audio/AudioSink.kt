package com.dsh.notes.audio

/**
 * 录音引擎的统一接口。
 *
 * 有两个实现，因为「省空间」和「实时上屏」在安卓上没法同时满足：
 *
 *  - [WavAudioRecorder]：AudioRecord 采原始 PCM，自己写 WAV。
 *    文件大（16k 单声道约 1.92MB/分钟），但**只在拿到 PCM 流时才能按时间切片**，
 *    所以「实时上屏」必须用它。
 *
 *  - [M4aAudioRecorder]：MediaRecorder 直接录成 m4a（AAC 32kbps）。
 *    约 0.24MB/分钟，比 WAV 小 8 倍，但拿不到 PCM，切不了片。
 *
 * 界面层根据「实时上屏开关 + 用户选的格式」挑一个，两者对上层完全同构。
 */
import java.io.File

interface AudioRecorder {

    /** 是否正在录音（暂停也算在录） */
    val isRecording: Boolean

    val isPaused: Boolean

    val isMuted: Boolean

    /** 到目前为止的录音时长（毫秒），暂停期间不计入 */
    val elapsedMs: Long

    /**
     * 开始录音。
     * @param onPcm 仅 PCM 引擎会回调；MediaRecorder 引擎不产生 PCM，永远不调用
     */
    fun start(onPcm: ((buffer: ShortArray, length: Int) -> Unit)? = null)

    fun pause()

    fun resume()

    fun setMuted(muted: Boolean)

    /**
     * 停止并释放，把文件收尾（回填 WAV 头 / 写完 m4a 的 moov）。必须调用，
     * 否则 m4a 会因为缺 moov box 而完全播不了。
     */
    fun stop()

    /** 磁盘上的文件；start 之前是 null */
    val file: File?
}
