package com.dsh.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class NoteConverters {

    @TypeConverter
    fun fromStatus(value: NoteStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): NoteStatus =
        runCatching { NoteStatus.valueOf(value) }.getOrDefault(NoteStatus.SAVED)

    @TypeConverter
    fun fromSource(value: AudioSource): String = value.name

    @TypeConverter
    fun toSource(value: String): AudioSource =
        runCatching { AudioSource.valueOf(value) }.getOrDefault(AudioSource.RECORDED)
}

@Database(
    entities = [Note::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(NoteConverters::class)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        private const val DB_NAME = "notecraft.db"

        @Volatile
        private var instance: NotesDatabase? = null

        fun build(context: Context): NotesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    DB_NAME
                )
                    // 自用 App：结构变更直接重建表，不做迁移；真要保数据再加 Migration
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
