package io.github.audxius.loopkeep.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.PostType
import io.github.audxius.loopkeep.data.FeedOrder
import io.github.audxius.loopkeep.data.QueueState
import io.github.audxius.loopkeep.data.VideoEntity

@Composable
fun SetupScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setFolder(uri)
    }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Loopkeep", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                "Pick a folder for your saved TikToks. Each video becomes a markdown note in notes/ " +
                    "with its file in media/. Choose a folder inside your Obsidian vault to see them there too.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { pick.launch(null) }, enabled = busy == null) { Text("Choose folder") }
            busy?.let {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val queueCounts by vm.queueCounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm, snackbar)
    ImportDialog(vm)

    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.readExport(uri)
    }
    val active = (queueCounts[QueueState.PENDING] ?: 0) + (queueCounts[QueueState.RUNNING] ?: 0)
    var deleting by remember { mutableStateOf<VideoEntity?>(null) }
    deleting?.let { d -> DeleteDialog(d, onConfirm = { vm.delete(d) }, onDismiss = { deleting = null }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved") },
                actions = {
                    IconButton(onClick = { vm.open(Screen.Queue) }) {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                            Icon(Icons.Default.Download, contentDescription = "Download queue")
                        }
                    }
                    IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (!items.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.open(Screen.Feed(null)) },
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    text = { Text("Watch") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            SearchField(filter.search, vm::setSearch)
            FilterRow(vm)
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() && !filter.isFiltered -> EmptyLibrary(onImport = { pickExport.launch(arrayOf("*/*")) }, onLinks = vm::addLinks)
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = vm::clearFilters) { Text("Nothing matches. Clear filters") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(list, key = { it.tiktokId }) { item ->
                        GridCell(item, onLongClick = { deleting = item }) { vm.open(Screen.Feed(item.tiktokId)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = { Text("Search captions, notes, tags, authors") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "Clear search") }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun FilterRow(vm: AppViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val counts by vm.statusCounts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val authors by vm.authors.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuChip(
            label = when (filter.order) { FeedOrder.NEWEST -> "Newest"; FeedOrder.OLDEST -> "Oldest"; FeedOrder.SHUFFLE -> "Shuffled" },
            selected = false,
            options = listOf("Newest saved" to FeedOrder.NEWEST, "Oldest saved" to FeedOrder.OLDEST, "Shuffle" to FeedOrder.SHUFFLE),
            onSelect = { vm.setOrder(it!!) },
            allowClear = false,
        )
        MenuChip(
            label = filter.status?.let { statusLabel(it) } ?: "Status",
            selected = filter.status != null,
            options = NoteStatus.entries.map { "${statusLabel(it.value)} (${counts[it.value] ?: 0})" to it.value },
            onSelect = vm::setStatus,
        )
        MenuChip(
            label = filter.tag?.let { "#$it" } ?: "Tag",
            selected = filter.tag != null,
            options = tags.take(200).map { (tag, n) -> "#$tag ($n)" to tag },
            onSelect = vm::setTag,
        )
        MenuChip(
            label = filter.authorHandle?.let { "@$it" } ?: "Author",
            selected = filter.authorHandle != null,
            options = authors.take(200).map { "@${it.name} (${it.count})" to it.name },
            onSelect = vm::setAuthor,
        )
    }
}

@Composable
private fun <T> MenuChip(label: String, selected: Boolean, options: List<Pair<String, T>>, onSelect: (T?) -> Unit, allowClear: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected, onClick = { open = true }, label = { Text(label) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowClear && selected) DropdownMenuItem(text = { Text("Any") }, onClick = { onSelect(null); open = false })
            if (options.isEmpty()) DropdownMenuItem(text = { Text("None yet") }, onClick = { open = false }, enabled = false)
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false }) }
        }
    }
}

fun statusLabel(status: String) = when (status) {
    NoteStatus.FULL.value -> "Saved"
    NoteStatus.TEXT_ONLY.value -> "Text only"
    NoteStatus.UNAVAILABLE.value -> "Unavailable"
    else -> status
}

@Composable
private fun GridCell(item: VideoEntity, onLongClick: () -> Unit, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(9f / 16f).background(Color(0xFF202020)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        if (item.coverUri != null) {
            AsyncImage(model = item.coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                item.caption.ifBlank { item.authorHandle.ifBlank { statusLabel(item.status) } },
                color = Color.White, fontSize = 11.sp, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp),
            )
        }
        val badge = when {
            item.status == NoteStatus.UNAVAILABLE.value -> Icons.Default.Block to "Unavailable"
            item.status == NoteStatus.TEXT_ONLY.value -> Icons.AutoMirrored.Filled.Subject to "Text only"
            item.type == PostType.SLIDESHOW.value -> Icons.Default.Collections to "Slideshow"
            else -> null
        }
        badge?.let { (icon, desc) ->
            Icon(
                icon, contentDescription = desc, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000)).padding(2.dp).size(16.dp),
            )
        }
        item.durationSeconds?.let { d ->
            Text(
                "%d:%02d".format(d / 60, d % 60), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x66000000)).padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onLinks: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No saved videos yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "Import your TikTok data export (TikTok → Settings → Account → Download your data, JSON format), " +
                "or share any TikTok link to Loopkeep from the TikTok app.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onImport) { Text("Import TikTok export") }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(link, { link = it }, label = { Text("…or paste a link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onLinks(link); link = "" }, enabled = link.isNotBlank()) { Text("Add") }
    }
}

@Composable
fun ImportDialog(vm: AppViewModel) {
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val p = pending ?: return
    var includeLiked by remember(p) { mutableStateOf(false) }
    val saved = p.scan.count(io.github.audxius.loopkeep.core.ExportSource.SAVED)
    val liked = p.scan.count(io.github.audxius.loopkeep.core.ExportSource.LIKED)
    val other = p.scan.count(io.github.audxius.loopkeep.core.ExportSource.OTHER)
    AlertDialog(
        onDismissRequest = vm::cancelImport,
        title = { Text("Import from export") },
        text = {
            Column {
                Text("Found $saved saved (favorite) videos.")
                if (other > 0) Text("$other other links (watch history, your own posts…) will be ignored.", style = MaterialTheme.typography.bodySmall)
                if (liked > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { includeLiked = !includeLiked }) {
                        Checkbox(includeLiked, { includeLiked = it })
                        Text("Also import $liked liked videos")
                    }
                }
                Text(
                    "Downloads run in the background, a few seconds apart. Already-saved videos are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { vm.confirmImport(includeLiked) }, enabled = saved > 0 || (includeLiked && liked > 0)) {
                Text("Queue ${saved + if (includeLiked) liked else 0}")
            }
        },
        dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel") } },
    )
}

@Composable
fun MessageEffect(vm: AppViewModel, snackbar: SnackbarHostState) {
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
}
