package io.github.audxius.loopkeep.index

import android.util.Log
import io.github.audxius.loopkeep.core.NoteDocument
import io.github.audxius.loopkeep.core.NoteKeys
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.PostType
import io.github.audxius.loopkeep.core.unwrapWikiLink
import io.github.audxius.loopkeep.data.VideoDao
import io.github.audxius.loopkeep.data.VideoEntity
import io.github.audxius.loopkeep.storage.DocInfo
import io.github.audxius.loopkeep.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class ScanResult(val notes: Int, val updated: Int, val removed: Int, val skipped: Int)

/** Keeps the Room index in step with the markdown files in the folder. */
class Indexer(private val dao: VideoDao) {

    private val mutex = Mutex()

    /**
     * Incremental by default: only notes whose mtime or size changed are re-read, so edits made
     * in Obsidian show up quickly. [full] clears the index and re-reads everything.
     */
    suspend fun rescan(storage: VaultStorage, full: Boolean = false): ScanResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (full) dao.clear()
            val notes = storage.listChildren(storage.notesDir()).filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            val media = mediaMap(storage)
            val stamps = dao.stamps().associateBy { it.noteDocId }
            var updated = 0
            var skipped = 0
            val seenIds = HashSet<String>()
            val keptRows = HashSet<Long>()

            for (note in notes) {
                val stamp = stamps[note.documentId]
                if (stamp != null && stamp.noteLastModified == note.lastModified && stamp.noteSize == note.size) {
                    keptRows += stamp.id
                    continue
                }
                val entity = runCatching { parse(storage.readText(note.documentId), note, media) }
                    .onFailure { Log.w(TAG, "Cannot read ${note.name}", it) }
                    .getOrNull()
                if (entity == null || !seenIds.add(entity.tiktokId)) {
                    // Not one of ours (no tiktok_id), unreadable, or a duplicate copy of another note.
                    skipped++
                    continue
                }
                dao.upsert(entity)
                dao.byTiktokId(entity.tiktokId)?.let { keptRows += it.id }
                updated++
            }

            val removed = dao.stamps().filter { it.id !in keptRows }.map { it.id }
            if (removed.isNotEmpty()) removed.chunked(500).forEach { dao.deleteIds(it) }

            // Media may arrive or disappear without the note changing; refresh resolved URIs.
            val stale = dao.all().mapNotNull { e -> withMedia(e, media).takeIf { it != e } }
            if (stale.isNotEmpty()) dao.updateAll(stale)

            ScanResult(notes.size, updated, removed.size, skipped)
        }
    }

    /** Re-indexes a single note right after the app wrote it. */
    suspend fun indexNote(storage: VaultStorage, note: DocInfo) = withContext(Dispatchers.IO) {
        val entity = parse(storage.readText(note.documentId), note, mediaMap(storage)) ?: return@withContext
        dao.upsert(entity)
    }

    /** Media file name → content URI. */
    private fun mediaMap(storage: VaultStorage): Map<String, String> {
        val children = storage.listChildren(storage.mediaDir())
        val now = System.currentTimeMillis()
        // Leftovers from interrupted copies.
        children.filter { it.name.endsWith(".part") && now - it.lastModified > 6 * 3600_000L }
            .forEach { storage.delete(it.documentId) }
        return children.filter { !it.name.endsWith(".part") }.associate { it.name to storage.uriOf(it.documentId).toString() }
    }

    private fun withMedia(e: VideoEntity, media: Map<String, String>): VideoEntity {
        fun uri(name: String?) = name?.let { media[it] }
        return e.copy(
            videoUri = uri(e.videoName),
            coverUri = uri(e.coverName),
            audioUri = uri(e.audioName),
            imageUris = e.imageNames.split('\n').filter { it.isNotEmpty() }.mapNotNull { uri(it) }.joinToString("\n"),
        )
    }

    private fun parse(text: String, note: DocInfo, media: Map<String, String>): VideoEntity? {
        val doc = NoteDocument.parse(text)
        val fm = doc.frontmatter
        val id = doc.tiktokId ?: return null
        val saved = fm.getString(NoteKeys.SAVED)
        val posted = fm.getString(NoteKeys.POSTED)
        val sortDate = listOf(saved, posted).firstNotNullOfOrNull { d ->
            d?.take(10)?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
        } ?: (note.lastModified / 86_400_000L)
        val images = fm.getList(NoteKeys.IMAGES).mapNotNull { unwrapWikiLink(it) }
        val base = VideoEntity(
            tiktokId = id,
            noteDocId = note.documentId,
            noteName = note.name,
            noteLastModified = note.lastModified,
            noteSize = note.size,
            sourceUrl = fm.getString(NoteKeys.SOURCE_URL).orEmpty(),
            author = fm.getString(NoteKeys.AUTHOR).orEmpty(),
            authorHandle = fm.getString(NoteKeys.AUTHOR_HANDLE).orEmpty(),
            caption = doc.caption(),
            notes = doc.notes(),
            tags = " " + fm.getList(NoteKeys.TAGS).map { it.trim().trimStart('#').replace(' ', '_') }
                .filter { it.isNotEmpty() }.joinToString(" ") + " ",
            status = NoteStatus.from(fm.getString(NoteKeys.STATUS)).value,
            type = PostType.from(fm.getString(NoteKeys.TYPE)).value,
            posted = posted,
            saved = saved,
            savedSort = sortDate,
            durationSeconds = fm.getString(NoteKeys.DURATION)?.toDoubleOrNull()?.toInt(),
            videoName = unwrapWikiLink(fm.getString(NoteKeys.VIDEO)),
            coverName = unwrapWikiLink(fm.getString(NoteKeys.COVER)),
            audioName = unwrapWikiLink(fm.getString(NoteKeys.AUDIO)),
            imageNames = images.joinToString("\n"),
            creatorComments = doc.creatorComments(),
            authorLink = fm.getString(NoteKeys.AUTHOR_LINK)?.ifBlank { null },
            collections = fm.getList(NoteKeys.COLLECTIONS).map { it.trim() }.filter { it.isNotEmpty() }
                .let { if (it.isEmpty()) "" else it.joinToString("\n", prefix = "\n", postfix = "\n") },
            videoUri = null, coverUri = null, audioUri = null, imageUris = "",
        )
        return withMedia(base, media)
    }

    private companion object {
        const val TAG = "Indexer"
    }
}
