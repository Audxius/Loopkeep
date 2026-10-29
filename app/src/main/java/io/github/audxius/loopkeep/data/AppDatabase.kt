package io.github.audxius.loopkeep.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [VideoEntity::class, VideoFts::class, QueueItem::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videos(): VideoDao
    abstract fun queue(): QueueDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "loopkeep.db")
                // The video index is rebuildable from the folder, so a destructive migration only
                // costs a rescan. The queue would be lost, which is acceptable pre-1.0.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
