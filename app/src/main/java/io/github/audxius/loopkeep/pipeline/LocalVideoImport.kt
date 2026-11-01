package io.github.audxius.loopkeep.pipeline

import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.audxius.loopkeep.core.FileNames
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.NoteWriter
import io.github.audxius.loopkeep.core.PostType
import io.github.audxius.loopkeep.core.TikTokUrls
import io.github.audxius.loopkeep.core.VideoMeta
import io.github.audxius.loopkeep.data.VideoEntity
import io.github.audxius.loopkeep.index.Indexer
import io.github.audxius.loopkeep.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Moves video files from the phone into the archive: either attached to an existing note (a post
 * TikTok won't hand out), or as a new post (your own "Only me" videos, which have no link).
 * The original is deleted only after the copy and the note are written.
 */
class LocalVideoImport(private val context: Context, private val indexer: Indexer) {

    private class Probed(val name: String?, val ext: String, val file: File, val durationSeconds: Int?, val frame: Bitmap?)

    /** Attaches to an existing note. Its dates (posted comes from the post ID), caption, notes and collections stay. */
    suspend fun attach(storage: VaultStorage, item: VideoEntity, source: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "attach").apply { deleteRecursively(); mkdirs() }
        try {
            val probed = copyAndProbe(source, dir, item.tiktokId, wantFrame = item.coverUri == null)
            val (videoName, coverName) = store(storage, item.tiktokId, probed, dir)
            val text = storage.readText(item.noteDocId)
            val meta = VideoMeta(
                tiktokId = item.tiktokId,
                sourceUrl = item.sourceUrl,
                authorHandle = item.authorHandle.ifBlank { null },
                posted = TikTokUrls.postedFromId(item.tiktokId),
                saved = LocalDate.now(), // only used if the note has no saved date
                status = NoteStatus.FULL,
                type = PostType.VIDEO,
                durationSeconds = probed.durationSeconds,
                videoFile = videoName,
                coverFile = coverName,
            )
            storage.writeText(item.noteDocId, NoteWriter.merge(text, meta))
            storage.info(item.noteDocId)?.let { indexer.indexNote(storage, it) }
            finishMove(source, probed.name, "Video moved into your archive")
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A new post from a file on the phone, for videos without a TikTok link (TikTok offers no link
     * for "Only me" posts). [posted] is the date the user picked; it also orders the post.
     */
    suspend fun addNew(storage: VaultStorage, source: Uri, posted: LocalDate, caption: String, handle: String?): String =
        withContext(Dispatchers.IO) {
            val id = "own-%s-%04x".format(posted.toString().replace("-", ""), (source.toString().hashCode() and 0xffff))
            val dir = File(context.cacheDir, "attach").apply { deleteRecursively(); mkdirs() }
            try {
                val probed = copyAndProbe(source, dir, id, wantFrame = true)
                val (videoName, coverName) = store(storage, id, probed, dir)
                val meta = VideoMeta(
                    tiktokId = id,
                    sourceUrl = "",
                    author = handle,
                    authorHandle = handle,
                    posted = posted,
                    saved = posted,
                    status = NoteStatus.FULL,
                    durationSeconds = probed.durationSeconds,
                    videoFile = videoName,
                    coverFile = coverName,
                    caption = caption.trim(),
                )
                val notesDir = storage.notesDir()
                val taken = storage.listChildren(notesDir).map { it.name }.toSet()
                val name = FileNames.unique(FileNames.noteFileName(handle, caption, id)) { it in taken }
                val doc = storage.createText(notesDir, name, NoteWriter.newNote(meta))
                indexer.indexNote(storage, doc)
                finishMove(source, probed.name, "Added to your archive as ${doc.name}")
            } finally {
                dir.deleteRecursively()
            }
        }

    /** When the video was taken (gallery) or last modified (files), as a starting point for the date picker. */
    fun fileDate(source: Uri): LocalDate? =
        listOf(MediaStore.MediaColumns.DATE_TAKEN, DocumentsContract.Document.COLUMN_LAST_MODIFIED).firstNotNullOfOrNull { column ->
            runCatching {
                context.contentResolver.query(source, arrayOf(column), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0) && c.getLong(0) > 0) {
                        java.time.Instant.ofEpochMilli(c.getLong(0)).atZone(ZoneId.systemDefault()).toLocalDate()
                    } else null
                }
            }.getOrNull()
        }

    @Volatile private var deleteRequest: IntentSender? = null

    /**
     * Android's "Allow Loopkeep to delete this video?" prompt for an original picked from the
     * gallery, which the picker only lends read access to. Taken once by the UI, which shows it.
     */
    fun takeDeleteRequest(): IntentSender? = deleteRequest.also { deleteRequest = null }

    /** The gallery item behind a photo-picker link (its last segment is the media ID), on Android 11+. */
    private fun galleryDeleteRequest(source: Uri): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val media = if (source.authority == MediaStore.AUTHORITY && source.pathSegments.firstOrNull() == "picker") {
            source.lastPathSegment?.toLongOrNull()?.let { android.content.ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), it) }
        } else if (source.authority == MediaStore.AUTHORITY) source else null
        return media?.let { runCatching { MediaStore.createDeleteRequest(context.contentResolver, listOf(it)).intentSender }.getOrNull() }
    }

    private fun copyAndProbe(source: Uri, dir: File, id: String, wantFrame: Boolean): Probed {
        val resolver = context.contentResolver
        val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val ext = videoExtension(name, resolver.getType(source))
        val file = File(dir, FileNames.videoFile(id, ext))
        (resolver.openInputStream(source) ?: throw IOException("Cannot open the file")).use { input ->
            file.outputStream().use { input.copyTo(it, 256 * 1024) }
        }
        var duration: Int? = null
        var frame: Bitmap? = null
        MediaMetadataRetriever().apply {
            try {
                setDataSource(file.absolutePath)
                if (extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") throw IOException("That file isn't a video")
                duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.let { ((it + 500) / 1000).toInt() }?.takeIf { it > 0 }
                if (wantFrame) frame = getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                release()
            }
        }
        return Probed(name, ext, file, duration, frame)
    }

    /** Copies the video (and a cover frame, if any) into media/. Returns their file names. */
    private fun store(storage: VaultStorage, id: String, probed: Probed, dir: File): Pair<String, String?> {
        val mediaDir = storage.mediaDir()
        val videoName = FileNames.videoFile(id, probed.ext)
        storage.importFile(probed.file, mediaDir, videoName)
        val coverName = probed.frame?.let { bmp ->
            val cover = File(dir, FileNames.coverFile(id))
            cover.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            FileNames.coverFile(id).also { storage.importFile(cover, mediaDir, it) }
        }
        return videoName to coverName
    }

    /**
     * Move, not copy: now that the video is safely in media/, remove the original (e.g. the copy
     * TikTok's "Save video" put in the gallery) so it isn't kept twice.
     */
    private fun finishMove(source: Uri, name: String?, success: String): String {
        val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, source) }.getOrDefault(false)
        if (!removed) galleryDeleteRequest(source)?.let {
            deleteRequest = it
            return success
        }
        return if (removed) success else "$success. The original${name?.let { " ($it)" } ?: ""} couldn't be removed; delete it yourself if you like."
    }

    private fun videoExtension(name: String?, mime: String?): String {
        val fromName = name?.substringAfterLast('.', "")?.lowercase()
        if (fromName in setOf("mp4", "mov", "webm", "mkv", "m4v", "3gp")) return fromName!!
        return when (mime) {
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/3gpp" -> "3gp"
            else -> "mp4"
        }
    }
}
