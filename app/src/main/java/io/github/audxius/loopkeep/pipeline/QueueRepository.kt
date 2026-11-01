package io.github.audxius.loopkeep.pipeline

import android.content.Context
import androidx.room.withTransaction
import io.github.audxius.loopkeep.core.ExportEntry
import io.github.audxius.loopkeep.core.ExportSource
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.QueuePolicy
import io.github.audxius.loopkeep.core.QueuePolicy.Decision
import io.github.audxius.loopkeep.core.SyncedOwnPost
import io.github.audxius.loopkeep.core.TikTokUrls
import io.github.audxius.loopkeep.data.AppDatabase
import io.github.audxius.loopkeep.data.QueueItem
import io.github.audxius.loopkeep.data.QueueSource
import io.github.audxius.loopkeep.data.QueueState
import java.time.Instant
import java.time.ZoneId

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
            if (skipped > 0) add("$skipped kept as partial or gone (open one and tap Retry)")
        }.joinToString(", ")
}

class QueueRepository(private val context: Context, private val db: AppDatabase) {

    private companion object {
        val OWN_SOURCES = setOf(QueueSource.PROFILE, QueueSource.EXPORT_OWN)
    }

    private data class Request(
        val url: String,
        val id: String?,
        val source: String,
        val date: String?,
        val force: Boolean,
        val collections: List<String> = emptyList(),
        val mediaUrl: String? = null,
        val caption: String? = null,
        val authorHandle: String? = null,
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

    suspend fun enqueueExport(entries: List<ExportEntry>, includeLiked: Boolean, includeOwn: Boolean = false, ownerHandle: String? = null): EnqueueResult =
        enqueue(
            entries.filter { it.source == ExportSource.SAVED || (includeLiked && it.source == ExportSource.LIKED) || (includeOwn && it.source == ExportSource.OWN) }
                // Oldest first, so the queue roughly follows the order they were saved.
                .sortedBy { it.date }
                .map {
                    when (it.source) {
                        ExportSource.OWN -> Request(
                            it.url, it.videoId ?: ownId(it), QueueSource.EXPORT_OWN, it.date?.toString(), false, it.collections,
                            mediaUrl = it.mediaUrl, caption = it.caption, authorHandle = ownerHandle,
                        )
                        ExportSource.SAVED -> Request(it.url, it.videoId, QueueSource.EXPORT_SAVED, it.date?.toString(), false, it.collections)
                        else -> Request(it.url, it.videoId, QueueSource.EXPORT_LIKED, it.date?.toString(), false, it.collections)
                    }
                },
        )

    /** Key for an own post that has no TikTok link: its post time, or a hash of the file link. */
    private fun ownId(e: ExportEntry): String =
        "own-" + (e.timestamp ?: Integer.toHexString(e.url.substringBefore('?').hashCode()))

    /** The posts of a shared collection link, after yt-dlp listed them. */
    suspend fun enqueueCollection(urls: List<String>, name: String): EnqueueResult =
        enqueue(urls.map { Request(it, TikTokUrls.extractVideoId(it), QueueSource.COLLECTION, null, false, listOf(name)) })

    /**
     * The user's own posts, listed from their profile while signed in. Each carries TikTok's file
     * link, so private ones can be downloaded too. Posts that were only partly saved (or failed)
     * are fetched again with the fresh link; fully saved ones are left alone.
     */
    suspend fun enqueueProfile(posts: List<SyncedOwnPost>, handle: String?, localFile: (String) -> java.io.File? = { null }): EnqueueResult =
        enqueue(
            posts.sortedBy { it.createTime }.map { p ->
                val note = db.videos().byTiktokId(p.id)
                Request(
                    TikTokUrls.canonicalUrl(p.id, handle), p.id, QueueSource.PROFILE,
                    p.createTime.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() },
                    force = note != null && note.status != NoteStatus.FULL.value,
                    // A file already downloaded in the signed-in page beats TikTok's link, which needs the session.
                    mediaUrl = localFile(p.id)?.let { android.net.Uri.fromFile(it).toString() } ?: p.videoUrl,
                    caption = p.caption.ifBlank { null }, authorHandle = handle,
                )
            },
        )

    /**
     * Re-download an item (e.g. a text-only note), keeping the note and its edits. [own]: one of your
     * own posts, fetched with your TikTok login so private ones work too.
     */
    suspend fun retry(tiktokId: String, url: String, own: Boolean = false): EnqueueResult =
        enqueue(listOf(Request(url.ifBlank { TikTokUrls.canonicalUrl(tiktokId) }, tiktokId, if (own) QueueSource.PROFILE else QueueSource.RETRY, null, true)))

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
                            QueueItem(
                                url = r.url, tiktokId = r.id, source = r.source, savedDate = r.date, force = r.force, collections = collections,
                                mediaUrl = r.mediaUrl, caption = r.caption, authorHandle = r.authorHandle,
                            ),
                        )
                        added++
                    }
                    Decision.REQUEUE -> {
                        db.queue().update(
                            existing!!.copy(
                                state = QueueState.PENDING, attempts = 0, nextAttemptAt = 0, lastError = null,
                                // Your own posts keep their source, so they're still fetched with your login.
                                source = if (existing.source in OWN_SOURCES) existing.source else r.source,
                                force = r.force, updatedAt = now, savedDate = existing.savedDate ?: r.date,
                                collections = union(existing.collections, r.collections),
                                // A file downloaded in the TikTok page is deleted once saved; don't point at it again.
                                mediaUrl = r.mediaUrl ?: existing.mediaUrl?.takeUnless { stale(it) }, caption = existing.caption ?: r.caption,
                                authorHandle = existing.authorHandle ?: r.authorHandle,
                            ),
                        )
                        added++
                    }
                    Decision.SKIP_IN_QUEUE -> {
                        if (r.source == QueueSource.PROFILE && r.mediaUrl != null && existing!!.state == QueueState.PENDING) {
                            // Waiting on a retry: take the fresh link (or downloaded file) and run it now.
                            db.queue().update(
                                existing.copy(
                                    source = QueueSource.PROFILE, mediaUrl = r.mediaUrl, attempts = 0, nextAttemptAt = 0, lastError = null,
                                    caption = existing.caption ?: r.caption, authorHandle = existing.authorHandle ?: r.authorHandle,
                                    savedDate = existing.savedDate ?: r.date, updatedAt = now,
                                ),
                            )
                            added++
                        } else if (r.collections.isNotEmpty()) {
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

    private fun stale(mediaUrl: String): Boolean =
        mediaUrl.startsWith("file:") && android.net.Uri.parse(mediaUrl).path?.let { java.io.File(it).isFile } != true

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
