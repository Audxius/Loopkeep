package io.github.audxius.loopkeep.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.audxius.loopkeep.App
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

    /** Collection name → number of posts, most used first. */
    val collections: StateFlow<List<Pair<String, Int>>> = videos.observeCollectionStrings()
        .map { strings ->
            strings.flatMap { it.split('\n') }.filter { it.isNotEmpty() }
                .groupingBy { it }.eachCount().toList().sortedWith(compareBy({ -it.second }, { it.first.lowercase() }))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun setCollection(name: String?) = _filter.update { it.copy(collection = name) }
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
            if (scan.entries.isEmpty()) {
                _message.value = "No TikTok video links found in that file. Try the JSON export, or the ZIP as downloaded."
            } else {
                _pendingImport.value = PendingImport(name, scan)
            }
        }
    }

    fun confirmImport(includeLiked: Boolean) = viewModelScope.launch {
        val pending = _pendingImport.value ?: return@launch
        _pendingImport.value = null
        val r = graph.queue.enqueueExport(pending.scan.entries, includeLiked)
        _message.value = "Import: ${r.summary}"
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

    fun refreshCreatorInfo() = viewModelScope.launch {
        val posts = videos.all().filter { it.status != io.github.audxius.loopkeep.core.NoteStatus.UNAVAILABLE.value }
        val n = graph.queue.refreshCreatorInfo(posts)
        _message.value = if (n == 0) "Nothing to fetch" else "Fetching creator comments and links for $n posts"
    }

    /** Writes tags and notes back into the markdown file, touching nothing else. */
    fun saveEdits(item: VideoEntity, newTags: List<String>, newNotes: String, newCollections: List<String> = item.collectionList) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        try {
            withContext(Dispatchers.IO) {
                val text = storage.readText(item.noteDocId)
                val doc = NoteDocument.parse(text)
                val cleaned = newTags.mapNotNull { TagSuggester.normalize(it) ?: it.trim().trimStart('#').replace(' ', '_').ifBlank { null } }.distinct()
                if (cleaned != doc.frontmatter.getList(NoteKeys.TAGS)) doc.frontmatter.setList(NoteKeys.TAGS, cleaned)
                if (newNotes.trim() != doc.notes().trim()) doc.setNotes(newNotes)
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

    /** Deletes the note and all of its media files from the folder, and forgets the item. */
    fun delete(item: VideoEntity) = viewModelScope.launch {
        val storage = storageOrComplain() ?: return@launch
        try {
            withContext(Dispatchers.IO) {
                val media = listOfNotNull(item.videoName, item.coverName, item.audioName) +
                    item.imageNames.split('\n').filter { it.isNotEmpty() }
                storage.deleteNamed(storage.mediaDir(), media.toSet())
                storage.delete(item.noteDocId)
                videos.deleteByTiktokId(item.tiktokId)
                graph.db.queue().deleteByTiktokId(item.tiktokId)
            }
            _message.value = "Deleted "
        } catch (e: Exception) {
            _message.value = "Could not delete: "
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
