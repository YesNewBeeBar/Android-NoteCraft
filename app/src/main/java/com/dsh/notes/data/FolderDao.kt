package com.dsh.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Folder>>

    @Insert
    suspend fun insert(folder: Folder): Long

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun findById(id: Long): Folder?

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删文件夹时把里面的笔记归到「未分类」，笔记本身一条都不删 */
    @Query("UPDATE notes SET folderId = NULL WHERE folderId = :folderId")
    suspend fun detachNotes(folderId: Long)
}
