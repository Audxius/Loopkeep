package io.github.audxius.loopkeep.pipeline

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Thin wrapper around youtubedl-android (an on-device yt-dlp with its own Python). */
class YtDlp(private val context: Context) {

    private val initMutex = Mutex()
    @Volatile private var initialized = false

    data class Result(val dir: File, val infoJson: String?, val mediaFile: File?)

    suspend fun ensureInit() = initMutex.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) { YoutubeDL.getInstance().init(context.applicationContext) }
        initialized = true
    }

    fun version(): String? = runCatching { YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()

    /** Updates the yt-dlp script itself (not the app). Returns a short human-readable status. */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        ensureInit()
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel._STABLE)
        val v = version() ?: "unknown"
        when (status) {
            YoutubeDL.UpdateStatus.DONE -> "Updated yt-dlp to $v"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is up to date ($v)"
            else -> "yt-dlp: $v"
        }
    }

    /**
     * Downloads [url] into a fresh [dir]: the best single-file format (H.264 preferred for
     * compatibility, no ffmpeg needed) plus yt-dlp's info JSON. Throws on failure.
     */
    suspend fun download(url: String, dir: File, processId: String): Result = withContext(Dispatchers.IO) {
        ensureInit()
        dir.deleteRecursively()
        dir.mkdirs()
        val request = YoutubeDLRequest(url).apply {
            addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
            addOption("--write-info-json")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-progress")
            addOption("-f", "b[vcodec!=none]/b")
            addOption("-S", "vcodec:h264,res:1080")
            addOption("--retries", 3)
            addOption("--socket-timeout", 30)
        }
        val response = YoutubeDL.getInstance().execute(request, processId)
        if (response.exitCode != 0) throw RuntimeException(response.err.ifBlank { "yt-dlp exited with ${response.exitCode}" })
        collect(dir)
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    private fun collect(dir: File): Result {
        val files = dir.listFiles().orEmpty()
        val info = files.firstOrNull { it.name.endsWith(".info.json") }?.readText()
        val media = files.filter { !it.name.endsWith(".json") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .maxByOrNull { it.length() }
        Log.d("YtDlp", "Downloaded ${media?.name} (${media?.length()} bytes)")
        return Result(dir, info, media)
    }
}
