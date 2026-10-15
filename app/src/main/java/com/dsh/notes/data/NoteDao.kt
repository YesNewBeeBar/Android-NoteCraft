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
