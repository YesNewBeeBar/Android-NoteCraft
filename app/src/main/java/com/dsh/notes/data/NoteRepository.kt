package com.dsh.notes.data

import kotlinx.coroutines.flow.Flow

/**
 * 笔记仓储：只做数据搬运，业务逻辑（转写、整理）放在 pipeline 里。
 */
class NoteRepository(private val dao: NoteDao) {

    fun observeAll(): Flow<List<Note>> = dao.observeAll()

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
