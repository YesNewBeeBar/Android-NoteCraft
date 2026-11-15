package com.dsh.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Note>>

    /**
     * 关键词搜索。用一条 SQL 同时匹配标题/转写/整理结果，
     * 笔记量在几千条以内完全够用，不需要上 FTS。
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE :keyword = ''
           OR title LIKE '%' || :keyword || '%'
           OR transcript LIKE '%' || :keyword || '%'
           OR refinedMarkdown LIKE '%' || :keyword || '%'
        ORDER BY createdAt DESC
        """
    )
    fun observeSearch(keyword: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun findById(id: Long): Note?

    @Query("SELECT * FROM notes WHERE status = 'RECORDING' ORDER BY createdAt DESC")
    suspend fun findByStatusRecording(): List<Note>

    /**
     * 还没处理完的笔记：上次被系统杀掉时卡在「转写中 / 整理中」的半截，
     * 以及**有音频但还没出整理结果**的。
     *
     * 这里刻意排除没有音频的笔记 —— 从 Markdown 导入的笔记本身就是成品，
     * 之前把它们也当成待处理，启动时会拿去跑整理，然后报「没有可整理的文字内容」。
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE status IN ('TRANSCRIBING', 'SUMMARIZING')
           OR (status = 'SAVED' AND audioPath IS NOT NULL AND refinedMarkdown = '')
        ORDER BY createdAt ASC
        """
    )
    suspend fun findResumable(): List<Note>

    @Query("SELECT COUNT(*) FROM notes")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}
