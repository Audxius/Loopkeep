package io.github.audxius.loopkeep.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Index row for one note. Rebuildable at any time from the folder; the markdown file is the
 * source of truth.
 */
@Entity(
    tableName = "videos",
    indices = [Index(value = ["tiktokId"], unique = true), Index("noteDocId"), Index("savedSort")],
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tiktokId: String,
    val noteDocId: String,
    val noteName: String,
    val noteLastModified: Long,
    val noteSize: Long,
    val sourceUrl: String,
    val author: String,
    val authorHandle: String,
    val caption: String,
    val notes: String,
    /** Space-separated with leading/trailing spaces (" a b "), so `LIKE '% a %'` matches whole tags. */
    val tags: String,
    val status: String,
    val type: String,
    val posted: String?,
    val saved: String?,
    /** Epoch day of [saved] (or [posted]) for sorting. */
    val savedSort: Long,
    val durationSeconds: Int?,
    val videoName: String?,
    val coverName: String?,
    val audioName: String?,
    /** Newline-separated slideshow image names. */
    val imageNames: String,
    val videoUri: String?,
    val coverUri: String?,
    val audioUri: String?,
    /** Newline-separated content URIs, same order as [imageNames]. */
    val imageUris: String,
    /** Collection names, newline-separated with a leading and trailing newline ("\nA\nB\n"). */
    @ColumnInfo(defaultValue = "") val collections: String = "",
) {
    val tagList: List<String> get() = tags.trim().split(' ').filter { it.isNotEmpty() }
    val imageUriList: List<String> get() = imageUris.split('\n').filter { it.isNotEmpty() }
    val collectionList: List<String> get() = collections.split('\n').filter { it.isNotEmpty() }
}

@Fts4(contentEntity = VideoEntity::class)
@Entity(tableName = "videos_fts")
data class VideoFts(
    val caption: String,
    val notes: String,
    val tags: String,
    val author: String,
    val authorHandle: String,
)

object QueueState {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
}

object QueueSource {
    const val EXPORT_SAVED = "export-saved"
    const val EXPORT_LIKED = "export-liked"
    const val SHARE = "share"
    const val MANUAL = "manual"
    const val RETRY = "retry"
    const val COLLECTION = "collection"
}

@Entity(tableName = "queue", indices = [Index("tiktokId"), Index("url"), Index("state", "nextAttemptAt")])
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val tiktokId: String?,
    val source: String,
    /** yyyy-MM-dd from the export, if known. */
    val savedDate: String?,
    val state: String = QueueState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val nextAttemptAt: Long = 0,
    /** Note status produced when done (full / text-only / unavailable). */
    val resultStatus: String? = null,
    /** Re-download even if a full note already exists. */
    val force: Boolean = false,
    /** Newline-separated collection names to put the post in. */
    val collections: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

data class IndexStamp(val id: Long, val noteDocId: String, val noteLastModified: Long, val noteSize: Long)

data class StatusCount(val status: String, val count: Int)

data class StateCount(val state: String, val count: Int)

data class NamedCount(val name: String, val count: Int)
