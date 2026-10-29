package io.github.audxius.loopkeep.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @RawQuery(observedEntities = [VideoEntity::class, VideoFts::class])
    fun observe(query: SupportSQLiteQuery): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE tiktokId = :tiktokId")
    suspend fun byTiktokId(tiktokId: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE tiktokId = :tiktokId")
    fun observeByTiktokId(tiktokId: String): Flow<VideoEntity?>

    @Query("SELECT id, noteDocId, noteLastModified, noteSize FROM videos")
    suspend fun stamps(): List<IndexStamp>

    @Query("SELECT * FROM videos")
    suspend fun all(): List<VideoEntity>

    @Insert
    suspend fun insert(entity: VideoEntity): Long

    @Update
    suspend fun update(entity: VideoEntity)

    @Update
    suspend fun updateAll(entities: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM videos")
    suspend fun clear()

    @Transaction
    suspend fun upsert(entity: VideoEntity) {
        val existing = byTiktokId(entity.tiktokId)
        if (existing == null) insert(entity) else update(entity.copy(id = existing.id))
    }

    @Query("SELECT status, COUNT(*) AS count FROM videos GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT authorHandle AS name, COUNT(*) AS count FROM videos WHERE authorHandle != '' GROUP BY authorHandle ORDER BY count DESC, name")
    fun observeAuthors(): Flow<List<NamedCount>>

    @Query("SELECT tags FROM videos WHERE tags != ' ' AND tags != ''")
    fun observeTagStrings(): Flow<List<String>>

    @Query("SELECT tags FROM videos WHERE tags != ' ' AND tags != ''")
    suspend fun tagStrings(): List<String>
}

@Dao
interface QueueDao {
    @Insert
    suspend fun insert(item: QueueItem): Long

    @Update
    suspend fun update(item: QueueItem)

    @Query("SELECT * FROM queue WHERE state = 'pending' AND nextAttemptAt <= :now ORDER BY nextAttemptAt, id LIMIT 1")
    suspend fun nextReady(now: Long): QueueItem?

    @Query("SELECT MIN(nextAttemptAt) FROM queue WHERE state = 'pending'")
    suspend fun earliestPending(): Long?

    @Query("SELECT * FROM queue WHERE (tiktokId = :tiktokId AND :tiktokId IS NOT NULL) OR url = :url ORDER BY id DESC LIMIT 1")
    suspend fun findExisting(tiktokId: String?, url: String): QueueItem?

    @Query("SELECT * FROM queue ORDER BY CASE state WHEN 'running' THEN 0 WHEN 'pending' THEN 1 WHEN 'failed' THEN 2 ELSE 3 END, updatedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<QueueItem>>

    @Query("SELECT state, COUNT(*) AS count FROM queue GROUP BY state")
    fun observeStateCounts(): Flow<List<StateCount>>

    @Query("SELECT COUNT(*) FROM queue WHERE state IN ('pending', 'running')")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM queue WHERE state = 'done' AND updatedAt >= :since")
    suspend fun doneSince(since: Long): Int

    @Query("UPDATE queue SET state = 'pending', updatedAt = :now WHERE state = 'running'")
    suspend fun resetInterrupted(now: Long)

    @Query("UPDATE queue SET state = 'pending', attempts = 0, nextAttemptAt = 0, lastError = NULL, updatedAt = :now WHERE state = 'failed'")
    suspend fun retryFailed(now: Long): Int

    @Query("UPDATE queue SET state = 'pending', nextAttemptAt = 0, updatedAt = :now WHERE state = 'pending'")
    suspend fun retryWaitingNow(now: Long)

    @Query("DELETE FROM queue WHERE state = 'done'")
    suspend fun clearDone()

    @Query("DELETE FROM queue WHERE id = :id")
    suspend fun delete(id: Long)
}
