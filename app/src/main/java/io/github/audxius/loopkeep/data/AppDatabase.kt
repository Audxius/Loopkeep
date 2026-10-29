package io.github.audxius.loopkeep.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [VideoEntity::class, VideoFts::class, QueueItem::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videos(): VideoDao
    abstract fun queue(): QueueDao

    companion object {
        // v2: collections.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN collections TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE queue ADD COLUMN collections TEXT")
                // Re-read every note on the next scan so collections already in the files get indexed.
                db.execSQL("UPDATE videos SET noteLastModified = 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "loopkeep.db")
                // The video index is rebuildable from the folder, so a destructive migration only
                // costs a rescan. The queue would be lost, which is acceptable pre-1.0.
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
