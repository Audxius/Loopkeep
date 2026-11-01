package io.github.audxius.loopkeep.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.audxius.loopkeep.App
import io.github.audxius.loopkeep.core.CollectionSync
import io.github.audxius.loopkeep.core.ExportParser
import io.github.audxius.loopkeep.core.ExportScan
import io.github.audxius.loopkeep.core.NoteDocument
import io.github.audxius.loopkeep.core.NoteKeys
import io.github.audxius.loopkeep.core.TagSuggester
import io.github.audxius.loopkeep.core.TikTokUrls
import io.github.audxius.loopkeep.data.FeedOrder
import io.github.audxius.loopkeep.data.LibraryFilter
import io.github.audxius.loopkeep.data.QueueItem
import io.github.audxius.loopkeep.data.QueueSource
import io.github.audxius.loopkeep.data.QueueState
import io.github.audxius.loopkeep.data.VideoEntity
import io.github.audxius.loopkeep.pipeline.QueueWorker
import io.github.audxius.loopkeep.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Library : Screen
    data class Feed(val startTiktokId: String?) : Screen
    data object Queue : Screen
    data object Settings : Screen
    data object TikTokSync : Screen
}

data class StorageInfo(val folder: String, val notes: Int, val mediaFiles: Int, val bytes: Long)

data class PendingImport(val fileName: String, val scan: ExportScan)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as App).graph
    private val videos = graph.db.videos()

    val folder: StateFlow<Uri?> = graph.settings.folder

    private val _filter = MutableStateFlow(LibraryFilter(order = graph.settings.feedOrder, shuffleSeed = System.currentTimeMillis()))
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    val items: StateFlow<List<VideoEntity>?> = _filter
        .flatMapLatest { videos.observe(it.toQuery()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val statusCounts = videos.observeStatusCounts()
        .map { list -> list.associate { it.status to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val authors = videos.observeAuthors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<Pair<String, Int>>> = videos.observeTagStrings()
        .map { strings ->
            strings.flatMap { it.trim().split(' ') }.filter { it.isNotEmpty() }
                .groupingBy { it }.eachCount().toList().sortedWith(compareBy({ -it.second }, { it.first }))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val knownCollections = MutableStateFlow(graph.settings.knownCollections)

    /** Collection name → number of posts, most used first; known but still empty ones last. */
    val collections: StateFlow<List<Pair<String, Int>>> = combine(videos.observeCollectionStrings(), knownCollections) { strings, known ->
        val used = strings.flatMap { it.split('\n') }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().toList().sortedWith(compareBy({ -it.second }, { it.first.lowercase() }))
        val names = used.map { it.first }.toSet()
        used + known.filter { it !in names }.map { it to 0 }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uncategorizedCount: StateFlow<Int> = videos.observe(LibraryFilter(uncategorized = true).toQuery())
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val queueItems: StateFlow<List<QueueItem>> = graph.db.queue().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every saved post by ID, for labelling queue rows with the creator and caption. */
    val allById: StateFlow<Map<String, VideoEntity>> = videos.observe(LibraryFilter().toQuery())
        .map { list -> list.associateBy { it.tiktokId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val queueCounts = graph.db.queue().observeStateCounts()
        .map { list -> list.associate { it.state to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _screens = MutableStateFlow<List<Screen>>(listOf(Screen.Library))
    val screen = _screens.map { it.last() }.stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Library)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()

    private val _ytDlpVersion = MutableStateFlow<String?>(null)
    val ytDlpVersion: StateFlow<String?> = _ytDlpVersion.asStateFlow()

    var muted: Boolean
        get() = graph.settings.muted
        set(value) { graph.settings.muted = value }

    val autoUpdateYtDlp get() = graph.settings.autoUpdateYtDlp

    init {
        onAppStart()
    }

    private fun onAppStart() = viewModelScope.launch {
        val storage = graph.storage() ?: return@launch
        // Incremental rescan so edits made in Obsidian show up.
        runCatching { graph.indexer.rescan(storage) }
            .onFailure { _message.value = "Could not read the folder: ${it.message}" }
        repairEmptyNotes()
        removeAutoTags()
        runCatching { graph.applyGalleryVisibility() }
        if (graph.db.queue().activeCount() > 0) QueueWorker.start(getApplication())
    }

    /**
     * Up to v0.2, new notes got tags copied from the caption's hashtags. Those are noise, so they
     * are removed once, but only from notes whose tags are *all* such hashtags; any note with a
     * tag the user added is left alone.
     */
    private suspend fun removeAutoTags() {
        if (graph.settings.autoTagsCleaned) return
        val storage = graph.storage() ?: return
        var cleaned = 0
        withContext(Dispatchers.IO) {
            for (v in videos.all()) {
                if (!TagSuggester.allFromHashtags(v.tagList, v.caption)) continue
                runCatching {
                    val text = storage.readText(v.noteDocId)
                    val doc = NoteDocument.parse(text)
                    doc.frontmatter.setList(NoteKeys.TAGS, emptyList())
                    storage.writeText(v.noteDocId, doc.render())
                    storage.info(v.noteDocId)?.let { graph.indexer.indexNote(storage, it) }
                    cleaned++
                }
            }
        }
        graph.settings.autoTagsCleaned = true
        if (cleaned > 0) _message.value = "Removed hashtag tags from $cleaned notes"
    }

    /** Re-queues notes that have no caption and no cover (left by v0.1.1 for photo posts). */
    private suspend fun repairEmptyNotes() {
        for (e in videos.emptyTextOnly()) {
            val last = graph.db.queue().findExisting(e.tiktokId, e.sourceUrl)
            if (last?.state == QueueState.FAILED) continue // already tried and gave up; the user can retry
            graph.queue.retry(e.tiktokId, e.sourceUrl)
        }
    }

    // --- navigation -------------------------------------------------------------------------

    fun open(screen: Screen) { _screens.value = _screens.value + screen }

    fun back(): Boolean {
        if (_screens.value.size <= 1) return false
        _screens.value = _screens.value.dropLast(1)
        return true
    }

    fun consumeMessage() { _message.value = null }
    fun showMessage(text: String) { _message.value = text }

    // --- filters ----------------------------------------------------------------------------

    fun setSearch(text: String) = _filter.update { it.copy(search = text) }
    fun setStatus(status: String?) = _filter.update { it.copy(status = status) }
    fun toggleStatus(status: String) = _filter.update { it.copy(status = if (it.status == status) null else status) }
    fun setTag(tag: String?) = _filter.update { it.copy(tag = tag) }
    fun setAuthor(handle: String?) = _filter.update { it.copy(authorHandle = handle) }
    fun setCollection(name: String?) = _filter.update { it.copy(collection = name, uncategorized = false) }
    fun showUncategorized() = _filter.update { it.copy(collection = null, uncategorized = true) }
    fun clearFilters() = _filter.update { LibraryFilter(order = it.order, shuffleSeed = it.shuffleSeed) }

    fun setOrder(order: FeedOrder) {
        graph.settings.feedOrder = order
        _filter.update { it.copy(order = order, shuffleSeed = if (order == FeedOrder.SHUFFLE) System.currentTimeMillis() else it.shuffleSeed) }
    }

    private inline fun MutableStateFlow<LibraryFilter>.update(f: (LibraryFilter) -> LibraryFilter) { value = f(value) }

    // --- folder -----------------------------------------------------------------------------

    fun setFolder(uri: Uri) = viewModelScope.launch {
        try {
            graph.setFolder(uri)
            val storage = graph.storage() ?: error("No access to the folder")
            withBusy("Reading folder…") {
                withContext(Dispatchers.IO) { storage.notesDir(); storage.mediaDir() }
                val r = graph.indexer.rescan(storage, full = true)
                runCatching { graph.applyGalleryVisibility() }
                _message.value = if (r.notes > 0) "Found ${r.notes - r.skipped} saved videos in this folder" else "Folder ready"
            }
        } catch (e: Exception) {
            _message.value = "Could not use that folder: ${e.message}"
        }
    }

    fun rescan(full: Boolean) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        withBusy("Rescanning folder…") {
            val r = graph.indexer.rescan(storage, full)
            _message.value = "Rescanned: ${r.notes} notes, ${r.updated} read, ${r.removed} removed" +
                if (r.skipped > 0) ", ${r.skipped} skipped (no tiktok_id or duplicate)" else ""
            refreshStorageInfo()
        }
    }

    private fun storageOrComplain(): VaultStorage? = graph.storage().also {
        if (it == null) _message.value = "No folder access. Pick the folder again in Settings."
    }

    // --- intake -----------------------------------------------------------------------------

    fun readExport(uri: Uri) = viewModelScope.launch {
        withBusy("Reading export…") {
            val app = getApplication<Application>()
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "export"
            val scan = withContext(Dispatchers.IO) {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                ExportParser.parse(bytes, name)
            }
            if (scan.entries.isEmpty() && scan.collectionNames.isNotEmpty()) {
                rememberCollections(scan.collectionNames)
                _message.value = "No videos in that file, but added your ${scan.collectionNames.size} collections"
            } else if (scan.entries.isEmpty()) {
                _message.value = "No TikTok video links found in that file. Try the JSON export, or the ZIP as downloaded."
            } else {
                _pendingImport.value = PendingImport(name, scan)
            }
        }
    }

    fun confirmImport(includeLiked: Boolean, includeOwn: Boolean = false) = viewModelScope.launch {
        val pending = _pendingImport.value ?: return@launch
        _pendingImport.value = null
        val owner = pending.scan.ownerHandle ?: graph.settings.ownHandle
        pending.scan.ownerHandle?.let { graph.settings.ownHandle = it }
        rememberCollections(pending.scan.collectionNames)
        val r = graph.queue.enqueueExport(pending.scan.entries, includeLiked, includeOwn, owner)
        _message.value = "Import: ${r.summary}"
    }

    private fun rememberCollections(names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        graph.settings.knownCollections = (knownCollections.value + clean).distinct()
        knownCollections.value = graph.settings.knownCollections
    }

    /** Files posts into the collections read from TikTok; saved posts get the name, others are downloaded. */
    fun applyTikTokCollections(json: String) = viewModelScope.launch {
        val synced = CollectionSync.parse(json)
        if (synced.isEmpty()) {
            _message.value = "TikTok listed no collections for this account"
            return@launch
        }
        rememberCollections(synced.map { it.name })
        var filed = 0
        var downloads = 0
        var already = 0
        for (c in synced) {
            if (c.ids.isEmpty()) continue
            val r = graph.queue.enqueueCollection(c.ids.map { TikTokUrls.canonicalUrl(it) }, c.name)
            filed += r.collectionUpdates
            downloads += r.added
            already += r.alreadySaved
        }
        val posts = synced.sumOf { it.ids.size }
        _message.value = "${synced.size} collections, $posts posts: $filed being filed, $downloads new to download" +
            if (already > 0) ", $already already there" else ""
    }

    fun createCollection(name: String) = rememberCollections(listOf(name))

    /** Forgets an empty collection; collections with posts are defined by their notes. */
    fun forgetCollection(name: String) {
        graph.settings.knownCollections = knownCollections.value - name
        knownCollections.value = graph.settings.knownCollections
    }

    fun cancelImport() { _pendingImport.value = null }

    fun addLinks(text: String) = viewModelScope.launch {
        val urls = TikTokUrls.findUrls(text).filter { TikTokUrls.isPostUrl(it) || TikTokUrls.isCollectionUrl(it) }
        if (urls.isEmpty()) {
            _message.value = "No TikTok video link found"
            return@launch
        }
        _message.value = graph.queue.enqueueUrls(urls, QueueSource.MANUAL).summary
    }

    fun retry(item: VideoEntity) = viewModelScope.launch {
        _message.value = "Retry: " + graph.queue.retry(item.tiktokId, item.sourceUrl).summary
    }

    fun retryFailed() = viewModelScope.launch { _message.value = "${graph.queue.retryFailed()} items requeued" }
    fun retryWaitingNow() = viewModelScope.launch { graph.queue.retryWaitingNow() }
    fun clearDone() = viewModelScope.launch { graph.queue.clearDone() }
    fun removeQueueItem(id: Long) = viewModelScope.launch { graph.queue.remove(id) }

    // --- edits ------------------------------------------------------------------------------

    /** Your own most-used tags, for quick re-use. Hashtags from TikTok aren't suggested. */
    fun suggestTags(item: VideoEntity): List<String> = tags.value.map { it.first }.filter { it !in item.tagList }.take(12)

    /** Attaches a video file from the phone (e.g. your own private post, saved from TikTok) to a note. */
    fun attachVideo(item: VideoEntity, uri: Uri) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        withBusy("Attaching video…") {
            _message.value = try {
                graph.localImport.attach(storage, item, uri)
            } catch (e: Exception) {
                "Could not attach: ${e.message}"
            }
        }
    }

    val ownHandle: String? get() = graph.settings.ownHandle
    val showInGallery: Boolean get() = graph.settings.showInGallery

    fun setShowInGallery(show: Boolean) = viewModelScope.launch {
        graph.settings.showInGallery = show
        runCatching { graph.applyGalleryVisibility() }
            .onFailure { _message.value = "Could not update the folder: ${it.message}" }
        _message.value = if (show) "Videos will show in your gallery" else "Videos hidden from your gallery (Obsidian and Loopkeep only)"
    }

    fun fileDate(uri: Uri): java.time.LocalDate? = graph.localImport.fileDate(uri)

    /** A video from the phone as a new post, e.g. your own "Only me" videos, which have no link. */
    fun addLocalVideo(uri: Uri, posted: java.time.LocalDate, caption: String, handle: String?) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        val h = handle?.trim()?.removePrefix("@")?.ifBlank { null }
        h?.let { graph.settings.ownHandle = it }
        withBusy("Adding video…") {
            _message.value = try {
                graph.localImport.addNew(storage, uri, posted, caption, h)
            } catch (e: Exception) {
                "Could not add: ${e.message}"
            }
        }
    }

    fun refreshCreatorInfo() = viewModelScope.launch {
        val posts = videos.all().filter { it.status != io.github.audxius.loopkeep.core.NoteStatus.UNAVAILABLE.value }
        val n = graph.queue.refreshCreatorInfo(posts)
        _message.value = if (n == 0) "Nothing to fetch" else "Fetching creator comments and links for $n posts"
    }

    /** Writes tags and notes back into the markdown file, touching nothing else. */
    fun saveEdits(
        item: VideoEntity,
        newTags: List<String>,
        newNotes: String,
        newCollections: List<String> = item.collectionList,
        /** Only used when the note has no caption yet (e.g. private posts); an existing caption is never replaced. */
        newCaption: String = "",
    ) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        try {
            withContext(Dispatchers.IO) {
                val text = storage.readText(item.noteDocId)
                val doc = NoteDocument.parse(text)
                val cleaned = newTags.mapNotNull { TagSuggester.normalize(it) ?: it.trim().trimStart('#').replace(' ', '_').ifBlank { null } }.distinct()
                if (cleaned != doc.frontmatter.getList(NoteKeys.TAGS)) doc.frontmatter.setList(NoteKeys.TAGS, cleaned)
                if (newNotes.trim() != doc.notes().trim()) doc.setNotes(newNotes)
                if (newCaption.isNotBlank() && doc.caption().isBlank()) doc.fillCaption(newCaption)
                val cols = newCollections.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (cols != doc.frontmatter.getList(NoteKeys.COLLECTIONS)) {
                    if (cols.isEmpty()) doc.frontmatter.remove(NoteKeys.COLLECTIONS) else doc.frontmatter.setList(NoteKeys.COLLECTIONS, cols)
                }
                val out = doc.render()
                if (out != text) storage.writeText(item.noteDocId, out)
                storage.info(item.noteDocId)?.let { graph.indexer.indexNote(storage, it) }
            }
            _message.value = "Saved to ${item.noteName}"
        } catch (e: Exception) {
            _message.value = "Could not save: ${e.message}. The note may have been moved or renamed; try Rescan."
        }
    }

    /** Adds [name] to (or removes it from) the collections of every post in [items], one note at a time. */
    fun fileInCollection(items: List<VideoEntity>, name: String, add: Boolean) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        val col = name.trim().ifEmpty { return@launch }
        if (add) rememberCollections(listOf(col))
        var changed = 0
        var failed = 0
        withBusy(if (add) "Adding to $col…" else "Removing from $col…") {
            withContext(Dispatchers.IO) {
                for (item in items) {
                    try {
                        val text = storage.readText(item.noteDocId)
                        val doc = NoteDocument.parse(text)
                        val current = doc.frontmatter.getList(NoteKeys.COLLECTIONS)
                        val next = if (add) (current + col).distinct() else current - col
                        if (next == current) continue
                        if (next.isEmpty()) doc.frontmatter.remove(NoteKeys.COLLECTIONS) else doc.frontmatter.setList(NoteKeys.COLLECTIONS, next)
                        storage.writeText(item.noteDocId, doc.render())
                        storage.info(item.noteDocId)?.let { graph.indexer.indexNote(storage, it) }
                        changed++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
        }
        val posts = if (changed == 1) "post" else "posts"
        _message.value = (if (add) "Added $changed $posts to $col" else "Removed $changed $posts from $col") +
            if (failed > 0) ". $failed couldn't be changed; try Rescan." else ""
    }

    /** Deletes the note and all of its media files from the folder, and forgets the item. */
    fun delete(item: VideoEntity) = delete(listOf(item))

    fun delete(items: List<VideoEntity>) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        var deleted = 0
        withContext(Dispatchers.IO) {
            val mediaDir = runCatching { storage.mediaDir() }.getOrNull()
            for (item in items) {
                try {
                    val media = listOfNotNull(item.videoName, item.coverName, item.audioName) +
                        item.imageNames.split('\n').filter { it.isNotEmpty() }
                    mediaDir?.let { storage.deleteNamed(it, media.toSet()) }
                    storage.delete(item.noteDocId)
                    videos.deleteByTiktokId(item.tiktokId)
                    graph.db.queue().deleteByTiktokId(item.tiktokId)
                    deleted++
                } catch (_: Exception) {
                }
            }
        }
        _message.value = when {
            deleted == items.size && deleted == 1 -> "Deleted ${items[0].noteName}"
            deleted == items.size -> "Deleted $deleted posts"
            else -> "Deleted $deleted of ${items.size} posts; the rest couldn't be removed. Try Rescan."
        }
    }

    // --- settings ---------------------------------------------------------------------------

    fun refreshStorageInfo() = viewModelScope.launch {
        val storage = graph.storage() ?: return@launch
        _storageInfo.value = runCatching {
            withContext(Dispatchers.IO) {
                val notes = storage.listChildren(storage.notesDir())
                val media = storage.listChildren(storage.mediaDir())
                StorageInfo(storage.displayPath(), notes.count { it.name.endsWith(".md") }, media.size, (notes + media).sumOf { it.size })
            }
        }.getOrNull()
    }

    fun refreshYtDlpVersion() = viewModelScope.launch {
        _ytDlpVersion.value = withContext(Dispatchers.IO) {
            runCatching { graph.ytDlp.ensureInit(); graph.ytDlp.version() }.getOrNull()
        }
    }

    fun updateYtDlp() = viewModelScope.launch {
        withBusy("Updating yt-dlp…") {
            _message.value = try {
                graph.ytDlp.update()
            } catch (e: Exception) {
                "yt-dlp update failed: ${e.message}"
            }
            graph.settings.lastYtDlpUpdateCheck = System.currentTimeMillis()
            _ytDlpVersion.value = graph.ytDlp.version()
        }
    }

    fun setAutoUpdateYtDlp(enabled: Boolean) { graph.settings.autoUpdateYtDlp = enabled }

    private suspend fun withBusy(label: String, block: suspend () -> Unit) {
        _busy.value = label
        try {
            block()
        } catch (e: Exception) {
            _message.value = e.message ?: e.toString()
        } finally {
            _busy.value = null
        }
    }
}
