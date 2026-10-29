package io.github.audxius.loopkeep.pipeline

import android.content.Context
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
import androidx.room.withTransaction

data class EnqueueResult(val added: Int, val alreadySaved: Int, val alreadyQueued: Int, val skipped: Int) {
    val summary: String
        get() = buildList {
            add("$added queued")
            if (alreadySaved > 0) add("$alreadySaved already saved")
            if (alreadyQueued > 0) add("$alreadyQueued already in queue")
            if (skipped > 0) add("$skipped skipped")
        }.joinToString(", ")
}

class QueueRepository(private val context: Context, private val db: AppDatabase) {

    private data class Request(val url: String, val id: String?, val source: String, val date: String?, val force: Boolean)

    suspend fun enqueueUrls(urls: List<String>, source: String = QueueSource.SHARE): EnqueueResult =
        enqueue(urls.filter { TikTokUrls.isPostUrl(it) }.map { Request(it, TikTokUrls.extractVideoId(it), source, null, false) })

    suspend fun enqueueExport(entries: List<ExportEntry>, includeLiked: Boolean): EnqueueResult =
        enqueue(
            entries.filter { it.source == ExportSource.SAVED || (includeLiked && it.source == ExportSource.LIKED) }
                // Oldest first, so the queue roughly follows the order they were saved.
                .sortedBy { it.date }
                .map {
                    val src = if (it.source == ExportSource.SAVED) QueueSource.EXPORT_SAVED else QueueSource.EXPORT_LIKED
                    Request(it.url, it.videoId, src, it.date?.toString(), false)
                },
        )

    /** Re-download an item (e.g. a text-only note), keeping the note and its edits. */
    suspend fun retry(tiktokId: String, url: String): EnqueueResult =
        enqueue(listOf(Request(url.ifBlank { TikTokUrls.canonicalUrl(tiktokId) }, tiktokId, QueueSource.RETRY, null, true)))

    private suspend fun enqueue(requests: List<Request>): EnqueueResult {
        var added = 0; var saved = 0; var queued = 0; var skipped = 0
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (r in requests.distinctBy { it.id ?: it.url }) {
                val note = r.id?.let { db.videos().byTiktokId(it) }
                val existing = db.queue().findExisting(r.id, r.url)
                when (QueuePolicy.decide(note?.status?.let(NoteStatus::from), existing?.state, r.force)) {
                    Decision.ADD -> {
                        db.queue().insert(QueueItem(url = r.url, tiktokId = r.id, source = r.source, savedDate = r.date, force = r.force))
                        added++
                    }
                    Decision.REQUEUE -> {
                        db.queue().update(
                            existing!!.copy(
                                state = QueueState.PENDING, attempts = 0, nextAttemptAt = 0, lastError = null,
                                force = r.force, updatedAt = now, savedDate = existing.savedDate ?: r.date,
                            ),
                        )
                        added++
                    }
                    Decision.SKIP_ALREADY_SAVED -> saved++
                    Decision.SKIP_IN_QUEUE -> queued++
                    Decision.SKIP_ALREADY_TRIED -> skipped++
                }
            }
        }
        if (added > 0) QueueWorker.start(context)
        return EnqueueResult(added, saved, queued, skipped)
    }

    suspend fun retryFailed(): Int = db.queue().retryFailed(System.currentTimeMillis()).also { if (it > 0) QueueWorker.start(context) }

    suspend fun retryWaitingNow() {
        db.queue().retryWaitingNow(System.currentTimeMillis())
        QueueWorker.start(context)
    }

    suspend fun clearDone() = db.queue().clearDone()

    suspend fun remove(id: Long) = db.queue().delete(id)
}
