package io.github.audxius.loopkeep.pipeline

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import java.io.File

/**
 * Video files of the user's own posts, downloaded inside the signed-in TikTok page and handed over
 * in base64 chunks (see `assets/collection_sync.js`). They wait in the cache until the queue has
 * turned them into notes; [Processor] picks them up through a `file:` media URL and deletes them.
 */
class OwnVideoFiles(context: Context) {
    private val dir = File(context.cacheDir, "own-videos")
    private val ids = Regex("""^\d{6,25}$""")

    fun file(id: String): File? = if (ids.matches(id)) File(dir, "$id.mp4") else null

    /** The finished file for [id], if the page delivered one. */
    fun ready(id: String): File? = file(id)?.takeIf { it.length() > 0 && !File(dir, "$id.part").exists() }

    fun start(id: String): Boolean {
        val f = file(id) ?: return false
        dir.mkdirs()
        f.delete()
        File(dir, "$id.part").writeText("")
        return true
    }

    fun append(id: String, base64: String): Boolean = runCatching {
        val f = file(id) ?: return false
        f.appendBytes(Base64.decode(base64, Base64.DEFAULT))
        true
    }.getOrDefault(false)

    /** Returns null when the file is a playable video, else the reason (and the file is dropped). */
    fun finish(id: String): String? {
        val f = file(id) ?: return "bad id"
        File(dir, "$id.part").delete()
        val isVideo = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(f.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                } finally {
                    release()
                }
            }
        }.getOrDefault(false)
        if (isVideo) return null
        f.delete()
        return "TikTok sent something that isn't a video"
    }
}
