package io.github.audxius.loopkeep

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.github.audxius.loopkeep.data.AppDatabase
import io.github.audxius.loopkeep.data.Settings
import io.github.audxius.loopkeep.index.Indexer
import io.github.audxius.loopkeep.pipeline.Processor
import io.github.audxius.loopkeep.pipeline.QueueRepository
import io.github.audxius.loopkeep.pipeline.YtDlp
import io.github.audxius.loopkeep.storage.VaultStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Hand-rolled dependency graph; the app is small enough not to need a DI framework. */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val db = AppDatabase.create(app)
    val indexer = Indexer(db.videos())
    val ytDlp = YtDlp(app)
    val processor = Processor(app, db, indexer, ytDlp)
    val queue = QueueRepository(app, db)

    private var cachedStorage: VaultStorage? = null

    /** The chosen folder, or null if none is set or access was lost. */
    @Synchronized fun storage(): VaultStorage? {
        val uri = settings.folder.value ?: return null
        cachedStorage?.takeIf { it.treeUri == uri }?.let { return it }
        val hasPermission = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!hasPermission) return null
        return VaultStorage(app.contentResolver, uri).also { cachedStorage = it }
    }

    fun setFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(uri, flags)
        settings.folder.value?.takeIf { it != uri }?.let { old ->
            runCatching { app.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        synchronized(this) { cachedStorage = null }
        settings.setFolder(uri)
    }

    /** yt-dlp breaks whenever TikTok changes things; keep it fresh without bothering the user. */
    suspend fun maybeAutoUpdateYtDlp() {
        if (!settings.autoUpdateYtDlp) return
        val now = System.currentTimeMillis()
        if (now - settings.lastYtDlpUpdateCheck < 3 * 24 * 3600_000L) return
        settings.lastYtDlpUpdateCheck = now
        runCatching { ytDlp.update() }
    }
}
