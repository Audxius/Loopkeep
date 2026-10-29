package io.github.audxius.loopkeep.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.StatFs
import android.util.Log
import io.github.audxius.loopkeep.core.FileNames
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.NoteWriter
import io.github.audxius.loopkeep.core.OEmbedInfo
import io.github.audxius.loopkeep.core.OEmbedParser
import io.github.audxius.loopkeep.core.PageInfo
import io.github.audxius.loopkeep.core.PageParser
import io.github.audxius.loopkeep.core.PlaylistParser
import io.github.audxius.loopkeep.core.PostType
import io.github.audxius.loopkeep.core.TagSuggester
import io.github.audxius.loopkeep.core.TikTokUrls
import io.github.audxius.loopkeep.core.VideoMeta
import io.github.audxius.loopkeep.core.YtDlpInfo
import io.github.audxius.loopkeep.core.YtDlpInfoParser
import io.github.audxius.loopkeep.data.AppDatabase
import io.github.audxius.loopkeep.data.QueueItem
import io.github.audxius.loopkeep.index.Indexer
import io.github.audxius.loopkeep.storage.VaultStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate

sealed class Outcome {
    data class Done(val status: NoteStatus, val noteName: String?, val message: String? = null) : Outcome()
    /** Temporary problem (network, storage); try again later. */
    data class Retry(val reason: String, val minDelayMs: Long = 0) : Outcome()
    /** Will not work no matter how often we try. */
    data class Fail(val reason: String) : Outcome()
    /** A collection link: these posts should be queued into collection [name]. */
    data class Expanded(val urls: List<String>, val name: String) : Outcome()
}

/**
 * Processes one queue item: resolve link → yt-dlp (video + info JSON) → cover → note.
 * Falls back to TikTok's page data, then oEmbed, for a text-only note when yt-dlp fails.
 */
class Processor(
    private val context: Context,
    private val db: AppDatabase,
    private val indexer: Indexer,
    private val ytDlp: YtDlp,
) {

    suspend fun process(item: QueueItem, storage: VaultStorage, noteNames: MutableSet<String>): Outcome = withContext(Dispatchers.IO) {
        lowStorageReason()?.let { return@withContext Outcome.Retry(it, minDelayMs = 30 * 60_000L) }

        // 1. Resolve to an ID and canonical URL.
        var url = item.url.trim()
        var id = item.tiktokId ?: TikTokUrls.extractVideoId(url)
        if (id == null && TikTokUrls.isShortLink(url)) {
            url = try {
                Http.resolveRedirects(url) { TikTokUrls.extractVideoId(it) != null || TikTokUrls.isCollectionUrl(it) }
            } catch (e: IOException) {
                return@withContext Outcome.Retry("Could not resolve short link: ${e.message}")
            }
            id = TikTokUrls.extractVideoId(url)
        }
        if (id == null && TikTokUrls.isCollectionUrl(url)) return@withContext expandCollection(url)
        if (id == null) return@withContext Outcome.Fail("Not a TikTok post link (or the short link has expired)")

        val collections = item.collections.orEmpty().split('\n').filter { it.isNotBlank() }
        val existing = db.videos().byTiktokId(id)
        if (existing != null && existing.status == NoteStatus.FULL.value && !item.force) {
            if (collections.isNotEmpty()) addCollections(storage, existing.noteDocId, collections)
            return@withContext Outcome.Done(NoteStatus.FULL, existing.noteName, "Already saved")
        }

        val isPhoto = TikTokUrls.isPhotoUrl(url)
        val handleHint = TikTokUrls.extractHandle(url) ?: existing?.authorHandle?.ifBlank { null }
        // yt-dlp and TikTok's page only work with /video/ URLs, also for photo posts: yt-dlp rejects
        // /photo/ ("Unsupported URL") and the /photo/ page has no post data. Checked live 2026-10-29.
        val canonical = TikTokUrls.canonicalUrl(id, handleHint, photo = false)
        val tmp = File(context.cacheDir, "dl/$id")

        try {
            // 2. yt-dlp.
            var ytError: String? = null
            val yt = try {
                download(canonical, tmp, "item-${item.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ytError = e.message?.lineSequence()?.lastOrNull { it.contains("ERROR") } ?: e.message
                Log.w(TAG, "yt-dlp failed for $id: $ytError")
                null
            }
            val info = yt?.infoJson?.let { YtDlpInfoParser.parse(it) }
            val videoFile = yt?.mediaFile?.takeIf { info?.hasVideo != false && it.extension.lowercase() in VIDEO_EXTS }
            val audioFile = yt?.mediaFile?.takeIf { videoFile == null && it.extension.lowercase() in AUDIO_EXTS }

            // 3. Page data: needed for slideshows and as the fallback when yt-dlp failed.
            var pageError: String? = null
            val page: PageInfo? = if (videoFile == null || isPhoto) {
                try {
                    PageParser.parse(Http.getText(TikTokUrls.canonicalUrl(id, handleHint ?: info?.authorHandle, photo = false)))
                        ?.takeIf { !it.isAvailable || it.caption != null || it.covers.isNotEmpty() || it.imageUrls.isNotEmpty() }
                } catch (e: IOException) {
                    pageError = e.message
                    null
                }
            } else null

            val oembed: OEmbedInfo? = if (videoFile == null && page == null && info == null) {
                runCatching { OEmbedParser.parse(Http.getText("https://www.tiktok.com/oembed?url=" + Http.encode(canonical))) }.getOrNull()
                    ?.takeIf { it.caption != null || it.thumbnailUrl != null }
            } else null

            // 4. Decide what we have.
            val images = page?.imageUrls.orEmpty()
            val type = if (images.isNotEmpty() || (videoFile == null && audioFile != null)) PostType.SLIDESHOW else PostType.VIDEO
            val status = when {
                videoFile != null -> NoteStatus.FULL
                images.isNotEmpty() -> NoteStatus.FULL
                page != null && !page.isAvailable -> NoteStatus.UNAVAILABLE
                // Text-only needs at least a caption or a cover; otherwise try again later.
                info != null || page != null || oembed != null -> NoteStatus.TEXT_ONLY
                else -> {
                    val reason = listOfNotNull(ytError, pageError).joinToString("; ").ifBlank { "No data from TikTok" }
                    return@withContext Outcome.Retry(reason)
                }
            }
            // 5. Media into the folder.
            val mediaDir = storage.mediaDir()
            val coverName = FileNames.coverFile(id)
            val coverOk = fetchCover(info, page, oembed, videoFile, File(tmp, "cover.jpg"))?.let {
                storage.importFile(it, mediaDir, coverName); true
            } ?: false
            val videoName = videoFile?.let { f ->
                FileNames.videoFile(id, f.extension.lowercase()).also { storage.importFile(f, mediaDir, it) }
            }
            val audioName = audioFile?.takeIf { images.isNotEmpty() }?.let { f ->
                FileNames.videoFile(id, f.extension.lowercase()).also { storage.importFile(f, mediaDir, it) }
            }
            val imageNames = images.mapIndexedNotNull { i, imageUrl ->
                val jpg = File(tmp, "img_$i.jpg")
                val ok = runCatching { downloadAsJpeg(imageUrl, jpg) }.getOrDefault(false)
                if (!ok) null else FileNames.imageFile(id, i).also { storage.importFile(jpg, mediaDir, it) }
            }

            // 6. The note.
            val caption = info?.caption ?: page?.caption ?: oembed?.caption ?: ""
            val handle = info?.authorHandle ?: page?.authorHandle ?: oembed?.authorHandle ?: handleHint
            val existingTags = db.videos().tagStrings().flatMap { it.trim().split(' ') }.filter { it.isNotEmpty() }.toSet()
            val meta = VideoMeta(
                tiktokId = id,
                sourceUrl = TikTokUrls.canonicalUrl(id, handle, isPhoto || images.isNotEmpty()),
                author = info?.author ?: page?.author ?: oembed?.author,
                authorHandle = handle,
                posted = info?.posted ?: page?.posted,
                saved = item.savedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
                tags = TagSuggester.suggest(caption, existingTags, limit = 6),
                collections = collections,
                status = status,
                type = type,
                durationSeconds = info?.durationSeconds ?: page?.durationSeconds,
                videoFile = videoName,
                coverFile = if (coverOk) coverName else null,
                audioFile = audioName,
                imageFiles = imageNames,
                caption = caption,
            )
            val noteName = writeNote(storage, meta, existing?.noteDocId, noteNames)
            val message = when (status) {
                NoteStatus.TEXT_ONLY -> "Text only: ${ytError ?: "no video file"}"
                NoteStatus.UNAVAILABLE -> "Unavailable: ${page?.statusMessage ?: "removed or private"}"
                NoteStatus.FULL -> null
            }
            Outcome.Done(status, noteName, message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "I/O error for $id", e)
            if (isNoSpace(e)) Outcome.Retry("Storage full", minDelayMs = 30 * 60_000L) else Outcome.Retry(e.message ?: e.toString())
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Lists a collection's posts with yt-dlp. TikTok's collection API often returns nothing to
     * unsigned requests (seen 2026-10-29 even for public collections), so an empty result is
     * reported as a failure the user can read, not silently accepted.
     */
    private suspend fun expandCollection(url: String): Outcome {
        val name = TikTokUrls.collectionName(url) ?: "Collection"
        val urls = try {
            PlaylistParser.videoUrls(ytDlp.listPlaylist(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Retry("Could not list collection: ${e.message}")
        }
        if (urls.isEmpty()) {
            return Outcome.Fail(
                "TikTok returned no videos for collection \"$name\". Make sure it is public. " +
                    "If it still fails, TikTok is blocking collection listing; add the videos individually.",
            )
        }
        return Outcome.Expanded(urls, name)
    }

    /** Adds collections to an existing note without downloading anything. */
    private suspend fun addCollections(storage: VaultStorage, noteDocId: String, collections: List<String>) {
        val text = runCatching { storage.readText(noteDocId) }.getOrNull() ?: return
        val doc = io.github.audxius.loopkeep.core.NoteDocument.parse(text)
        if (!NoteWriter.addCollections(doc.frontmatter, collections)) return
        storage.writeText(noteDocId, doc.render())
        storage.info(noteDocId)?.let { indexer.indexNote(storage, it) }
    }

    /** Runs yt-dlp so that cancelling the coroutine also kills the yt-dlp process. */
    private suspend fun download(url: String, dir: File, processId: String): YtDlp.Result = coroutineScope {
        val job = async(Dispatchers.IO) { ytDlp.download(url, dir, processId) }
        try {
            job.await()
        } catch (e: CancellationException) {
            ytDlp.cancel(processId)
            throw e
        }
    }

    private suspend fun writeNote(storage: VaultStorage, meta: VideoMeta, existingDocId: String?, noteNames: MutableSet<String>): String {
        val notesDir = storage.notesDir()
        if (existingDocId != null) {
            val current = runCatching { storage.readText(existingDocId) }.getOrNull()
            if (current != null) {
                storage.writeText(existingDocId, NoteWriter.merge(current, meta))
                val info = storage.info(existingDocId) ?: throw IOException("Note vanished while writing")
                indexer.indexNote(storage, info)
                return info.name
            }
        }
        val name = FileNames.unique(FileNames.noteFileName(meta.authorHandle, meta.caption, meta.tiktokId)) { it in noteNames }
        val doc = storage.createText(notesDir, name, NoteWriter.newNote(meta))
        noteNames += doc.name
        indexer.indexNote(storage, doc)
        return doc.name
    }

    /**
     * TikTok's own cover first (yt-dlp thumbnails, then page data, then oEmbed). Only if none can
     * be fetched, a frame from the downloaded video.
     */
    private fun fetchCover(info: YtDlpInfo?, page: PageInfo?, oembed: OEmbedInfo?, video: File?, target: File): File? {
        val candidates = (info?.thumbnails.orEmpty() + page?.covers.orEmpty() + listOfNotNull(oembed?.thumbnailUrl)).distinct()
        for (url in candidates) {
            if (runCatching { downloadAsJpeg(url, target) }.getOrDefault(false)) return target
        }
        if (video != null) {
            val frame = runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(video.absolutePath)
                        getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        release()
                    }
                }
            }.getOrNull()
            if (frame != null) {
                target.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                frame.recycle()
                return target
            }
        }
        return null
    }

    /** Downloads an image and re-encodes it as JPEG (TikTok serves WebP/HEIC too; the note says .jpg). */
    private fun downloadAsJpeg(url: String, target: File): Boolean {
        target.parentFile?.mkdirs()
        val raw = File(target.parentFile, target.name + ".raw")
        try {
            Http.download(url, raw)
            val bytes = raw.readBytes()
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                raw.copyTo(target, overwrite = true)
                return true
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            target.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            return true
        } finally {
            raw.delete()
        }
    }

    private fun lowStorageReason(): String? {
        val free = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        return if (free < MIN_FREE_BYTES) "Low storage: ${free / (1024 * 1024)} MB free" else null
    }

    private fun isNoSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { (it.message ?: "").contains("ENOSPC") || (it.message ?: "").contains("No space left") }

    private companion object {
        const val TAG = "Processor"
        const val MIN_FREE_BYTES = 300L * 1024 * 1024
        val VIDEO_EXTS = setOf("mp4", "mov", "webm", "mkv", "m4v")
        val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "opus", "ogg")
    }
}
