package io.github.audxius.loopkeep.pipeline

import android.content.Context
import androidx.room.withTransaction
import io.github.audxius.loopkeep.core.ExportEntry
import io.github.audxius.loopkeep.core.ExportSource
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.QueuePolicy
import io.github.audxius.loopkeep.core.QueuePolicy.Decision
import io.github.audxius.loopkeep.core.TikTokUrls
import io.github.audxius.loopkeep.data.AppDatabase
import io.github.audxius.loopkeep.data.QueueItem
import io.github.audxius.loopkeep.data.QueueSource
import io.github.audxius.loopkeep.data.QueueState

data class EnqueueResult(
    val added: Int,
    val alreadySaved: Int,
    val alreadyQueued: Int,
    val skipped: Int,
    /** Already-saved posts queued only to add them to a collection (no download). */
    val collectionUpdates: Int = 0,
) {
    val summary: String
        get() = buildList {
            add("$added queued")
            if (collectionUpdates > 0) add("$collectionUpdates added to collection")
            if (alreadySaved > 0) add("$alreadySaved already saved")
            if (alreadyQueued > 0) add("$alreadyQueued already in queue")
            if (skipped > 0) add("$skipped skipped")
        }.joinToString(", ")
}

class QueueRepository(private val context: Context, private val db: AppDatabase) {

    private data class Request(
        val url: String,
        val id: String?,
        val source: String,
        val date: String?,
        val force: Boolean,
        val collections: List<String> = emptyList(),
    )

    /** Post links and collection links, e.g. from the share sheet. */
    suspend fun enqueueUrls(urls: List<String>, source: String = QueueSource.SHARE): EnqueueResult =
        enqueue(
            urls.filter { TikTokUrls.isPostUrl(it) || TikTokUrls.isCollectionUrl(it) }.map {
                if (TikTokUrls.isCollectionUrl(it)) {
                    // Sharing a collection again should pick up posts added since.
                    Request(it, null, QueueSource.COLLECTION, null, force = true)
                } else {
                    Request(it, TikTokUrls.extractVideoId(it), source, null, false)
                }
            },
        )

    suspend fun enqueueExport(entries: List<ExportEntry>, includeLiked: Boolean): EnqueueResult =
        enqueue(
            entries.filter { it.source == ExportSource.SAVED || (includeLiked && it.source == ExportSource.LIKED) }
                // Oldest first, so the queue roughly follows the order they were saved.
                .sortedBy { it.date }
                .map {
                    val src = if (it.source == ExportSource.SAVED) QueueSource.EXPORT_SAVED else QueueSource.EXPORT_LIKED
                    Request(it.url, it.videoId, src, it.date?.toString(), false, it.collections)
                },
        )

    /** The posts of a shared collection link, after yt-dlp listed them. */
    suspend fun enqueueCollection(urls: List<String>, name: String): EnqueueResult =
        enqueue(urls.map { Request(it, TikTokUrls.extractVideoId(it), QueueSource.COLLECTION, null, false, listOf(name)) })

    /** Re-download an item (e.g. a text-only note), keeping the note and its edits. */
    suspend fun retry(tiktokId: String, url: String): EnqueueResult =
        enqueue(listOf(Request(url.ifBlank { TikTokUrls.canonicalUrl(tiktokId) }, tiktokId, QueueSource.RETRY, null, true)))

    private suspend fun enqueue(requests: List<Request>): EnqueueResult {
        var added = 0; var saved = 0; var queued = 0; var skipped = 0; var collectionUpdates = 0
        val now = System.currentTimeMillis()
        db.withTransaction {
            val merged = requests.groupBy { it.id ?: it.url }.values.map { group ->
                group.first().copy(collections = group.flatMap { it.collections }.distinct())
            }
            for (r in merged) {
                val note = r.id?.let { db.videos().byTiktokId(it) }
                val existing = db.queue().findExisting(r.id, r.url)
                val collections = r.collections.joinToString("\n").ifEmpty { null }
                when (QueuePolicy.decide(note?.status?.let(NoteStatus::from), existing?.state, r.force)) {
                    Decision.ADD -> {
                        db.queue().insert(
                            QueueItem(url = r.url, tiktokId = r.id, source = r.source, savedDate = r.date, force = r.force, collections = collections),
                        )
                        added++
                    }
                    Decision.REQUEUE -> {
                        db.queue().update(
                            existing!!.copy(
                                state = QueueState.PENDING, attempts = 0, nextAttemptAt = 0, lastError = null,
                                force = r.force, updatedAt = now, savedDate = existing.savedDate ?: r.date,
                                collections = union(existing.collections, r.collections),
                            ),
                        )
                        added++
                    }
                    Decision.SKIP_IN_QUEUE -> {
                        if (r.collections.isNotEmpty()) {
                            db.queue().update(existing!!.copy(collections = union(existing.collections, r.collections), updatedAt = now))
                        }
                        queued++
                    }
                    Decision.SKIP_ALREADY_SAVED, Decision.SKIP_ALREADY_TRIED -> {
                        val missing = r.collections.filter { it !in note?.collectionList.orEmpty() }
                        if (note != null && missing.isNotEmpty()) {
                            // Saved already: the worker only adds the collection to the note.
                            db.queue().insert(
                                QueueItem(url = r.url, tiktokId = r.id, source = QueueSource.COLLECTION, savedDate = r.date, collections = missing.joinToString("\n")),
                            )
                            collectionUpdates++
                        } else if (note?.status == NoteStatus.FULL.value) saved++ else skipped++
                    }
                }
            }
        }
        if (added > 0 || collectionUpdates > 0) QueueWorker.start(context)
        return EnqueueResult(added, saved, queued, skipped, collectionUpdates)
    }

    private fun union(existing: String?, more: List<String>): String? =
        (existing.orEmpty().split('\n') + more).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString("\n").ifEmpty { null }

    /** Queues a creator-comments and profile-link refresh for saved posts. Nothing is re-downloaded. */
    suspend fun refreshCreatorInfo(posts: List<io.github.audxius.loopkeep.data.VideoEntity>): Int {
        var added = 0
        db.withTransaction {
            for (p in posts) {
                val existing = db.queue().findExisting(p.tiktokId, p.sourceUrl)
                if (existing?.state == QueueState.PENDING || existing?.state == QueueState.RUNNING) continue
                db.queue().insert(QueueItem(url = p.sourceUrl.ifBlank { TikTokUrls.canonicalUrl(p.tiktokId) }, tiktokId = p.tiktokId, source = QueueSource.REFRESH, savedDate = null))
                added++
            }
        }
        if (added > 0) QueueWorker.start(context)
        return added
    }

    suspend fun retryFailed(): Int = db.queue().retryFailed(System.currentTimeMillis()).also { if (it > 0) QueueWorker.start(context) }

    suspend fun retryWaitingNow() {
        db.queue().retryWaitingNow(System.currentTimeMillis())
        QueueWorker.start(context)
    }

    suspend fun clearDone() = db.queue().clearDone()

    suspend fun remove(id: Long) = db.queue().delete(id)
}
