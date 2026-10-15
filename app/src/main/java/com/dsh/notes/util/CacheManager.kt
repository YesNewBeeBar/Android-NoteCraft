package com.dsh.notes.util

import android.content.Context
import com.dsh.notes.data.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存占用情况。
 *
 * @param tempBytes     cacheDir 里的临时文件（导入暂存、实时上屏切出来的 wav 片段）
 * @param orphanCount   已经没有笔记引用的音频文件个数
 * @param orphanBytes   这些孤儿音频占的空间
 */
data class CacheUsage(
    val tempBytes: Long = 0L,
    val orphanCount: Int = 0,
    val orphanBytes: Long = 0L
) {
    val totalBytes: Long get() = tempBytes + orphanBytes

    val isEmpty: Boolean get() = totalBytes == 0L && orphanCount == 0
}

/**
 * 缓存统计与清理。
 *
 * 「缓存」在这里只指两类东西：
 *  1) `cacheDir` 下的临时文件 —— 导入时的暂存、实时上屏切片；
 *  2) `files/audio` 里**已经没有任何笔记引用**的音频 —— 笔记被删掉、
 *     或导入半路失败留下的孤儿文件。
 *
 * 仍被笔记引用的录音一律不动：那是用户的数据，不是缓存。
 */
class CacheManager(
    private val context: Context,
    private val repository: NoteRepository
) {

    suspend fun scan(): CacheUsage = withContext(Dispatchers.IO) {
        val orphans = orphanAudio()
        CacheUsage(
            tempBytes = directorySize(cacheDir()),
            orphanCount = orphans.size,
            orphanBytes = orphans.sumOf { it.length() }
        )
    }

    /**
     * 清掉上面两类东西。
     * @return 释放掉的字节数
     */
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        val orphans = orphanAudio()
        // 先算账再删：删完再 length() 就恒为 0 了
        val freed = directorySize(cacheDir()) + orphans.sumOf { it.length() }

        cacheDir().listFiles()?.forEach { it.deleteRecursively() }
        orphans.forEach { it.delete() }

        freed
    }

    private fun cacheDir(): File = context.cacheDir

    private fun audioDir(): File = File(context.filesDir, "audio")

    private fun directorySize(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isDirectory) directorySize(it) else it.length() } ?: 0L

    /**
     * 找出没被任何笔记引用的音频。
     *
     * 只认 `note-<id>.<ext>` 和 `import-<id>.<ext>` 这两种命名：能从文件名反解出 id、
     * 且数据库里没有这条笔记，才算孤儿。**认不出来的文件一律留着** —— 宁可少清，不可误删。
     *
     * 另外给刚写下去的文件留一个 10 分钟的保护期：录音过程中文件先落盘、路径后回填，
     * 中间那一小段窗口里它看起来就是「没人引用」。
     */
    private suspend fun orphanAudio(): List<File> {
        val files = audioDir().listFiles()?.filter { it.isFile } ?: return emptyList()
        if (files.isEmpty()) return emptyList()

        // 查不到笔记列表就什么都不清 —— 拿不准的时候宁可不动手
        val aliveIds = runCatching { repository.observeAll().first().map { it.id }.toSet() }
            .getOrElse { return emptyList() }

        val now = System.currentTimeMillis()
        return files.filter { file ->
            val id = noteIdOf(file.name) ?: return@filter false
            if (id in aliveIds) return@filter false
            now - file.lastModified() > FRESH_GUARD_MS
        }
    }

    private fun noteIdOf(fileName: String): Long? =
        FILE_ID_PATTERN.find(fileName)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        /** note-12.m4a / import-12.wav */
        val FILE_ID_PATTERN = Regex("^(?:note|import)-(\\d+)\\.[A-Za-z0-9]+$")

        /** 这段时间内写过的文件当作「可能还在用」 */
        const val FRESH_GUARD_MS = 10 * 60 * 1000L
    }
}
