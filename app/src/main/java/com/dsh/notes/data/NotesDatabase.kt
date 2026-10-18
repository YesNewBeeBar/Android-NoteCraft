package com.dsh.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    entities = [Note::class, Folder::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(NoteConverters::class)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    abstract fun folderDao(): FolderDao

    companion object {
        private const val DB_NAME = "notecraft.db"

        /**
         * v1 -> v2：加文件夹。
         *
         * 以前配的是 fallbackToDestructiveMigration（结构变了就重建表）。
         * 1.x 已经发出去、用户手里有真实笔记，再靠重建就等于替他们把数据删了。
         * 所以从这一版起写真正的迁移，并去掉 destructive 兜底 ——
         * 以后真要改结构，也得补一条迁移，而不是悄悄清库。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN folderId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS folders (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        @Volatile
        private var instance: NotesDatabase? = null

        fun build(context: Context): NotesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
