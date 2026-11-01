package io.github.audxius.loopkeep.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
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

/**
 * Moves a video file from the phone into the archive and attaches it to an existing note: for posts
 * TikTok won't hand out anonymously, such as your own "Only me" videos saved with TikTok's "Save video".
 * The original is deleted only after the copy and the note are written.
 * The note keeps its dates (posted comes from the post ID), caption, notes and collections.
 */
class LocalVideoImport(private val context: Context, private val indexer: Indexer) {

    suspend fun attach(storage: VaultStorage, item: VideoEntity, source: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val ext = videoExtension(name, resolver.getType(source))
        val dir = File(context.cacheDir, "attach").apply { deleteRecursively(); mkdirs() }
        val video = File(dir, FileNames.videoFile(item.tiktokId, ext))
        try {
            (resolver.openInputStream(source) ?: throw IOException("Cannot open the file")).use { input ->
                video.outputStream().use { input.copyTo(it, 256 * 1024) }
            }

            var durationSeconds: Int? = null
            var frame: Bitmap? = null
            MediaMetadataRetriever().apply {
                try {
                    setDataSource(video.absolutePath)
                    if (extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                        throw IOException("That file isn't a video")
                    }
                    durationSeconds = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?.let { ((it + 500) / 1000).toInt() }?.takeIf { it > 0 }
                    // Keep TikTok's own cover if the note has one; otherwise a frame from the video.
                    if (item.coverUri == null) frame = getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    release()
                }
            }

            val mediaDir = storage.mediaDir()
            val videoName = FileNames.videoFile(item.tiktokId, ext)
            storage.importFile(video, mediaDir, videoName)
            val coverName = frame?.let { bmp ->
                val cover = File(dir, FileNames.coverFile(item.tiktokId))
                cover.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle()
                FileNames.coverFile(item.tiktokId).also { storage.importFile(cover, mediaDir, it) }
            }

            val text = storage.readText(item.noteDocId)
            val meta = VideoMeta(
                tiktokId = item.tiktokId,
                sourceUrl = item.sourceUrl,
                authorHandle = item.authorHandle.ifBlank { null },
                posted = TikTokUrls.postedFromId(item.tiktokId),
                saved = LocalDate.now(), // only used if the note has no saved date
                status = NoteStatus.FULL,
                type = PostType.VIDEO,
                durationSeconds = durationSeconds,
                videoFile = videoName,
                coverFile = coverName,
            )
            storage.writeText(item.noteDocId, NoteWriter.merge(text, meta))
            storage.info(item.noteDocId)?.let { indexer.indexNote(storage, it) }

            // Move, not copy: now that the video is safely in media/, remove the original (e.g. the copy
            // TikTok's "Save video" put in the gallery) so it isn't kept twice.
            val removed = runCatching { DocumentsContract.deleteDocument(resolver, source) }.getOrDefault(false)
            if (removed) {
                "Video moved into your archive"
            } else {
                "Video added to your archive. The original${name?.let { " ()" } ?: ""} couldn't be removed; delete it yourself if you like."
            }
        } finally {
            dir.deleteRecursively()
        }
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
