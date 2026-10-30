package com.dsh.notes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 笔记仓储：只做数据搬运，业务逻辑（转写、整理）放在 pipeline 里。
 */
class NoteRepository(
    private val dao: NoteDao,
    private val folderDao: FolderDao
) {

    // ---------------------------------------------------------------- 文件夹

    fun observeFolders(): Flow<List<Folder>> = folderDao.observeAll()

    suspend fun createFolder(name: String): Long =
        folderDao.insert(Folder(name = name.trim()))

    /** 文件夹名；找不到返回 null（比如文件夹刚被删掉） */
    suspend fun folderName(id: Long): String? = folderDao.findById(id)?.name

    /** 按名字找文件夹，找不到就建一个。导入 .md 时用来还原目录结构 */
    suspend fun folderIdOrCreate(name: String): Long =
        folderDao.findByName(name.trim())?.id ?: createFolder(name)

    suspend fun renameFolder(id: Long, name: String) = folderDao.rename(id, name.trim())

    /** 删文件夹不删笔记：里面的笔记统统回到「未分类」 */
    suspend fun deleteFolder(id: Long) {
        folderDao.detachNotes(id)
        folderDao.delete(id)
    }

    suspend fun moveNotesToFolder(noteIds: List<Long>, folderId: Long?) {
        noteIds.forEach { id -> patch(id) { it.copy(folderId = folderId) } }
    }

    // ---------------------------------------------------------------- 笔记

    fun observeAll(): Flow<List<Note>> = dao.observeAll()

    /** 导出算「相关笔记」时要一次性拿到全部笔记，不能只取一帧就 Subscribe */
    suspend fun listAllOnce(): List<Note> = dao.observeAll().first()

    fun observeSearch(keyword: String): Flow<List<Note>> {
        val trimmed = keyword.trim()
        return if (trimmed.isEmpty()) dao.observeAll() else dao.observeSearch(trimmed)
    }

    fun observeById(id: Long): Flow<Note?> = dao.observeById(id)

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun get(id: Long): Note? = dao.findById(id)

    suspend fun create(note: Note): Long = dao.insert(note)

    suspend fun update(note: Note) = dao.update(note.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(note: Note) = dao.delete(note)

    suspend fun deleteById(id: Long) {
        dao.findById(id)?.let { dao.delete(it) }
    }

    suspend fun listRecording(): List<Note> = dao.findByStatusRecording()

    /** 还没处理完的（待处理 / 卡住的转写中与整理中），启动时用来重建队列 */
    suspend fun listResumable(): List<Note> = dao.findResumable()

    /** 只改字段的一小部分，避免整行覆盖时把并发写入的内容冲掉 */
    suspend fun patch(id: Long, block: (Note) -> Note) {
        val current = dao.findById(id) ?: return
        dao.update(block(current).copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun patchStatus(id: Long, status: NoteStatus, error: String? = null) =
        patch(id) { it.copy(status = status, errorMessage = error) }
}
