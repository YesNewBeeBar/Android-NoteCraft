package com.dsh.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 笔记文件夹。只做一层，不做嵌套 —— 笔记本来就是「一叠纸」，
 * 二级三级目录只会让人纠结放哪，反而不整理了。
 *
 * 文件夹与笔记的关系记在 [Note.folderId] 上（一条笔记最多属于一个文件夹）。
 */
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
