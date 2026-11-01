package io.github.audxius.loopkeep.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.audxius.loopkeep.core.ExportSource
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.PostType
import io.github.audxius.loopkeep.data.FeedOrder
import io.github.audxius.loopkeep.data.QueueState
import io.github.audxius.loopkeep.data.VideoEntity

// --- Setup ------------------------------------------------------------------------------------

@Composable
fun SetupScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setFolder(uri)
    }
    Box(
        Modifier.fillMaxSize().background(Brand.Background)
            .background(Brush.radialGradient(listOf(Brand.Coral.copy(alpha = 0.22f), Color.Transparent), radius = 900f)),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            LogoMark(84)
            Spacer(Modifier.height(28.dp))
            Text("Keep every TikTok\nyou ever saved.", style = MaterialTheme.typography.displaySmall, color = Brand.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text(
                "Loopkeep downloads your saved videos with their captions into a folder you own, then plays them back like TikTok. No account, no algorithm.",
                style = MaterialTheme.typography.bodyLarge, color = Brand.TextSecondary,
            )
            Spacer(Modifier.height(28.dp))
            Feature(Icons.Default.FolderOpen, "Plain files", "Markdown notes + video files. Works inside Obsidian.")
            Feature(Icons.Default.SwipeVertical, "Your own feed", "Swipe through only what you saved.")
            Feature(Icons.Default.Search, "Find anything", "Search captions, tags, notes and creators.")
            Spacer(Modifier.height(32.dp))
            GradientButton("Choose a folder", onClick = { pick.launch(null) }, icon = Icons.Default.FolderOpen, enabled = busy == null, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                "Tip: pick or create a folder inside your Obsidian vault, e.g. Vault/TikTok.",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            busy?.let {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Brand.Coral, trackColor = Brand.Surface3)
                Text(it, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun LogoMark(size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(Brand.Gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Bookmark, null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
        Icon(Icons.Default.PlayArrow, null, tint = Brand.Coral, modifier = Modifier.size((size * 0.26f).dp).padding(bottom = (size * 0.04f).dp))
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Brand.Peach, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Brand.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(text, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- Library ----------------------------------------------------------------------------------

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Long-press starts selecting; then taps add or remove posts. Kept by ID so the list can change underneath.
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selectedIds.isNotEmpty()
    val selected = items.orEmpty().filter { it.tiktokId in selectedIds }
    var deleting by remember { mutableStateOf<List<VideoEntity>?>(null) }
    var filing by remember { mutableStateOf(false) }
    MessageEffect(vm, snackbar)
    ImportDialog(vm)
    BackHandler(enabled = selecting) { selectedIds = emptySet() }
    deleting?.let { d -> DeleteDialog(d, onConfirm = { vm.delete(d); selectedIds = emptySet() }, onDismiss = { deleting = null }) }
    if (filing) CollectionPicker(vm, selected, onDone = { filing = false; selectedIds = emptySet() }, onDismiss = { filing = false })
    fun toggle(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }

    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.readExport(uri)
    }

    Box(Modifier.fillMaxSize().background(Brand.Background)) {
        val list = items
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header(vm) }
            item(span = { GridItemSpan(maxLineSpan) }) { Stats(vm) }
            item(span = { GridItemSpan(maxLineSpan) }) { DownloadBanner(vm) }
            busy?.let { b ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Brand.Coral, trackColor = Brand.Surface3)
                        Text(b, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (list.isNullOrEmpty() && !filter.isFiltered) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(onImport = { pickExport.launch(arrayOf("*/*")) }, onLinks = vm::addLinks) }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { SearchBar(filter.search, vm::setSearch) }
                item(span = { GridItemSpan(maxLineSpan) }) { FilterRow(vm) }
                if (filter.collection == null && filter.search.isBlank()) item(span = { GridItemSpan(maxLineSpan) }) { CollectionsShelf(vm, list.orEmpty()) }
                item(span = { GridItemSpan(maxLineSpan) }) { ResultsLabel(vm, list?.size ?: 0) }
                if (list != null && list.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val empty = filter.collection?.takeIf { filter.search.isBlank() && filter.status == null && filter.tag == null && filter.authorHandle == null }
                            if (empty != null) {
                                Text("Nothing in $empty yet", color = Brand.TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Sync from TikTok to fill your collections automatically, or long-press posts in All posts to file them yourself.",
                                    color = Brand.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                                )
                                GradientButton("Sync from TikTok", onClick = { vm.open(Screen.TikTokSync) }, icon = Icons.Outlined.CollectionsBookmark, modifier = Modifier.padding(top = 14.dp))
                                Row {
                                    TextButton(onClick = vm::clearFilters) { Text("All posts", color = Brand.Coral) }
                                    TextButton(onClick = { vm.forgetCollection(empty); vm.clearFilters() }) { Text("Remove collection", color = Brand.TextSecondary) }
                                }
                            } else {
                                Text("Nothing matches", color = Brand.TextSecondary)
                                TextButton(onClick = vm::clearFilters) { Text("Clear filters", color = Brand.Coral) }
                            }
                        }
                    }
                }
                items(list.orEmpty(), key = { it.tiktokId }) { item ->
                    GridCell(item, selected = if (selecting) item.tiktokId in selectedIds else null, onLongClick = { toggle(item.tiktokId) }) {
                        if (selecting) toggle(item.tiktokId) else vm.open(Screen.Feed(item.tiktokId))
                    }
                }
            }
        }

        if (selecting) {
            SelectionBar(
                count = selected.size,
                onClose = { selectedIds = emptySet() },
                onSelectAll = { selectedIds = items.orEmpty().map { it.tiktokId }.toSet() },
                onCollection = { filing = true },
                onDelete = { deleting = selected },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (!items.isNullOrEmpty()) {
            GradientButton(
                "Play", onClick = { vm.open(Screen.Feed(null)) }, icon = Icons.Default.PlayArrow,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }
}

@Composable
private fun Header(vm: AppViewModel) {
    val queueCounts by vm.queueCounts.collectAsStateWithLifecycle()
    val active = (queueCounts[QueueState.PENDING] ?: 0) + (queueCounts[QueueState.RUNNING] ?: 0)
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(34)
        Spacer(Modifier.width(12.dp))
        Text("Loopkeep", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { vm.open(Screen.Queue) }) {
            BadgedBox(badge = { if (active > 0) Badge(containerColor = Brand.Coral) { Text("$active") } }) {
                Icon(Icons.Default.CloudDownload, "Download queue", tint = Brand.TextPrimary)
            }
        }
        IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings", tint = Brand.TextPrimary) }
    }
}

/** The counters: saved / caption only / unavailable / failed. Tap to filter (failed opens the queue). */
@Composable
private fun Stats(vm: AppViewModel) {
    val counts by vm.statusCounts.collectAsStateWithLifecycle()
    val queueCounts by vm.queueCounts.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val total = counts.values.sum()
    val failed = queueCounts[QueueState.FAILED] ?: 0
    Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
        Text(
            if (total == 0) "Nothing saved yet" else "$total ${if (total == 1) "post" else "posts"} in your archive",
            color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun tile(value: Int, label: String, color: Color, status: String?, onClick: () -> Unit) {
                val selected = status != null && filter.status == status
                StatTile(
                    "$value", label, color,
                    Modifier.weight(1f)
                        .then(if (selected) Modifier.border(1.5.dp, color, RoundedCornerShape(16.dp)) else Modifier)
                        .clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
                )
            }
            tile(counts[NoteStatus.FULL.value] ?: 0, "Saved", Brand.Teal, NoteStatus.FULL.value) { vm.toggleStatus(NoteStatus.FULL.value) }
            tile(counts[NoteStatus.TEXT_ONLY.value] ?: 0, "Partial", Brand.Gold, NoteStatus.TEXT_ONLY.value) { vm.toggleStatus(NoteStatus.TEXT_ONLY.value) }
            tile(counts[NoteStatus.UNAVAILABLE.value] ?: 0, "Gone", Brand.Rose, NoteStatus.UNAVAILABLE.value) { vm.toggleStatus(NoteStatus.UNAVAILABLE.value) }
            tile(failed, "Failed", Brand.Coral, null) { vm.open(Screen.Queue) }
        }
    }
}

@Composable
private fun DownloadBanner(vm: AppViewModel) {
    val queueCounts by vm.queueCounts.collectAsStateWithLifecycle()
    val pending = (queueCounts[QueueState.PENDING] ?: 0) + (queueCounts[QueueState.RUNNING] ?: 0)
    if (pending == 0) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Brand.Coral.copy(alpha = 0.18f), Brand.Peach.copy(alpha = 0.10f))))
            .clickable { vm.open(Screen.Queue) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CloudDownload, null, tint = Brand.Peach)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Saving in the background", color = Brand.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("$pending left · a few seconds apart", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Brand.Peach, trackColor = Brand.Surface3)
        }
    }
}

@Composable
private fun SearchBar(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(CircleShape).background(Brand.Surface2).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Brand.TextTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        PlainTextField(value, onChange, "Search captions, tags, notes, creators", Modifier.weight(1f))
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Clear, "Clear search", tint = Brand.TextSecondary) }
        }
    }
}

@Composable
private fun FilterRow(vm: AppViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val counts by vm.statusCounts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val authors by vm.authors.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    LazyRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MenuChip(
                label = when (filter.order) { FeedOrder.NEWEST -> "Newest"; FeedOrder.OLDEST -> "Oldest"; FeedOrder.SHUFFLE -> "Shuffle" },
                icon = Icons.AutoMirrored.Filled.Sort, selected = false,
                options = listOf("Newest saved first" to FeedOrder.NEWEST, "Oldest saved first" to FeedOrder.OLDEST, "Shuffle" to FeedOrder.SHUFFLE),
                onSelect = { vm.setOrder(it!!) }, allowClear = false,
            )
        }
        if (collections.isNotEmpty() || filter.collection != null) {
            item {
                MenuChip(
                    label = filter.collection ?: "Collection", icon = Icons.Outlined.CollectionsBookmark, selected = filter.collection != null,
                    options = collections.map { (name, n) -> "$name  ·  $n" to name }, onSelect = vm::setCollection,
                )
            }
        }
        item {
            MenuChip(
                label = filter.status?.let { statusLabel(it) } ?: "Status", selected = filter.status != null,
                options = NoteStatus.entries.map { "${statusLabel(it.value)}  ·  ${counts[it.value] ?: 0}" to it.value }, onSelect = vm::setStatus,
            )
        }
        item {
            MenuChip(
                label = filter.tag?.let { "#$it" } ?: "Tag", selected = filter.tag != null,
                options = tags.take(200).map { (tag, n) -> "#$tag  ·  $n" to tag }, onSelect = vm::setTag,
            )
        }
        item {
            MenuChip(
                label = filter.authorHandle?.let { "@$it" } ?: "Creator", selected = filter.authorHandle != null,
                options = authors.take(200).map { "@${it.name}  ·  ${it.count}" to it.name }, onSelect = vm::setAuthor,
            )
        }
    }
}

@Composable
private fun <T> MenuChip(
    label: String,
    selected: Boolean,
    options: List<Pair<String, T>>,
    onSelect: (T?) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    allowClear: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape)
                .background(if (selected) Brand.Coral.copy(alpha = 0.18f) else Brand.Surface2)
                .then(if (selected) Modifier.border(1.dp, Brand.Coral.copy(alpha = 0.6f), CircleShape) else Modifier)
                .clickable { open = true }.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (selected) Brand.Coral else Brand.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = if (selected) Brand.TextPrimary else Brand.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (selected && allowClear) {
                Icon(Icons.Default.Close, "Clear", tint = Brand.Coral, modifier = Modifier.padding(start = 4.dp).size(16.dp).clickable { onSelect(null) })
            } else {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = Brand.TextTertiary, modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Brand.Surface2) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("Nothing yet", color = Brand.TextTertiary) }, onClick = { open = false }, enabled = false)
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false }) }
        }
    }
}

@Composable
private fun CollectionsShelf(vm: AppViewModel, items: List<VideoEntity>) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    if (collections.isEmpty()) return
    Column(Modifier.padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Collections", style = MaterialTheme.typography.titleMedium, color = Brand.TextPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.open(Screen.TikTokSync) }) { Text("Sync from TikTok", color = Brand.Coral) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(collections, key = { it.first }) { (name, n) ->
                val cover = items.firstOrNull { name in it.collectionList && it.coverUri != null }?.coverUri
                Box(
                    Modifier.width(118.dp).height(150.dp).clip(RoundedCornerShape(18.dp)).background(Brand.Surface2)
                        .clickable { vm.setCollection(name) },
                ) {
                    if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)), startY = 120f)))
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (n == 0) "Empty" else "$n ${if (n == 1) "post" else "posts"}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsLabel(vm: AppViewModel, count: Int) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                filter.collection != null -> filter.collection!!
                filter.isFiltered -> "$count ${if (count == 1) "match" else "matches"}"
                else -> "All posts"
            },
            style = MaterialTheme.typography.titleMedium, color = Brand.TextPrimary, modifier = Modifier.weight(1f),
        )
        if (filter.isFiltered) TextButton(onClick = vm::clearFilters) { Text("Clear", color = Brand.Coral) }
    }
}

@Composable
/** [selected] is null outside selection mode. */
private fun GridCell(item: VideoEntity, selected: Boolean?, onLongClick: () -> Unit, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).background(Brand.Surface2)
            .then(if (selected == true) Modifier.border(3.dp, Brand.Coral, RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (item.coverUri != null) {
            AsyncImage(item.coverUri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                item.caption.ifBlank { statusLabel(item.status) },
                color = Brand.TextSecondary, fontSize = 11.sp, maxLines = 7, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.4f).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.authorHandle.ifBlank { item.author }.let { if (it.isBlank()) "" else "@$it" },
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.durationSeconds?.takeIf { item.type == PostType.VIDEO.value }?.let { d ->
                Text("%d:%02d".format(d / 60, d % 60), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
            }
        }
        when {
            item.status != NoteStatus.FULL.value ->
                StatusPill(statusLabel(item.status), statusColor(item.status), Modifier.align(Alignment.TopStart).padding(6.dp), solid = true)
            item.type == PostType.SLIDESHOW.value ->
                Icon(
                    Icons.Default.Collections, "Photos", tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x88000000)).padding(3.dp).size(14.dp),
                )
        }
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().background(Brand.Coral.copy(alpha = 0.18f)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (selected) Brand.Coral else Color(0x66000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCollection: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brand.Surface3).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Stop selecting", tint = Brand.TextPrimary) }
        Column(Modifier.weight(1f)) {
            Text("$count selected", color = Brand.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Select all", color = Brand.Coral, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onSelectAll))
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Delete", tint = Brand.TextSecondary) }
        GradientButton("Collection", onClick = onCollection, icon = Icons.Outlined.CollectionsBookmark)
    }
}

/** Files the selected posts into a collection (or takes them out of one). */
@Composable
private fun CollectionPicker(vm: AppViewModel, items: List<VideoEntity>, onDone: () -> Unit, onDismiss: () -> Unit) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    var newName by rememberSaveable { mutableStateOf("") }
    val n = items.size
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Surface2,
        title = { Text("Add $n ${if (n == 1) "post" else "posts"} to…") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(CircleShape).background(Brand.Surface3).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainTextField(newName, { newName = it }, "New collection", Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp))
                    TextButton(onClick = { vm.fileInCollection(items, newName, add = true); onDone() }, enabled = newName.isNotBlank()) {
                        Text("Add", color = if (newName.isNotBlank()) Brand.Coral else Brand.TextTertiary)
                    }
                }
                collections.forEach { (name, count) ->
                    // All selected posts already in it: tapping takes them out again.
                    val allIn = items.isNotEmpty() && items.all { name in it.collectionList }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { vm.fileInCollection(items, name, add = !allIn); onDone() }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (allIn) Icons.Default.Check else Icons.Outlined.CollectionsBookmark, null,
                            tint = if (allIn) Brand.Coral else Brand.TextSecondary, modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(name, color = Brand.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (allIn) "Remove" else if (count == 0) "empty" else "$count", color = Brand.TextTertiary, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Brand.TextSecondary) } },
    )
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onLinks: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Brand.Coral.copy(alpha = 0.35f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) { LogoMark(64) }
        Spacer(Modifier.height(16.dp))
        Text("Bring your saves over", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import TikTok's data export to save everything at once, or share single videos to Loopkeep from the TikTok app.",
            color = Brand.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
        GradientButton("Import TikTok export", onClick = onImport, icon = Icons.Default.UploadFile, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(
            "TikTok → Profile → ☰ → Settings → Account → Download your data → JSON",
            color = Brand.TextTertiary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().clip(CircleShape).background(Brand.Surface2).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Link, null, tint = Brand.TextTertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            PlainTextField(link, { link = it }, "Paste a TikTok link", Modifier.weight(1f), contentPadding = PaddingValues(vertical = 14.dp))
            TextButton(onClick = { onLinks(link); link = "" }, enabled = link.isNotBlank()) { Text("Add", color = if (link.isNotBlank()) Brand.Coral else Brand.TextTertiary) }
        }
    }
}

@Composable
fun ImportDialog(vm: AppViewModel) {
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val p = pending ?: return
    val own = p.scan.count(ExportSource.OWN)
    val saved = p.scan.count(ExportSource.SAVED)
    val liked = p.scan.count(ExportSource.LIKED)
    // Never leave the dialog with nothing selectable: if no favourites were found, offer the likes.
    var includeLiked by remember(p) { mutableStateOf(saved == 0 && own == 0 && liked > 0) }
    var includeOwn by remember(p) { mutableStateOf(true) }
    val other = p.scan.count(ExportSource.OTHER)
    val withCollections = p.scan.entries.count { it.collections.isNotEmpty() }
    AlertDialog(
        onDismissRequest = vm::cancelImport,
        containerColor = Brand.Surface2,
        title = { Text("Import from TikTok") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("$saved", "Saved", Brand.Teal, Modifier.weight(1f))
                    StatTile("$liked", "Liked", Brand.Peach, Modifier.weight(1f))
                    if (own > 0) StatTile("$own", "Yours", Brand.Gold, Modifier.weight(1f))
                }
                if (own > 0) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { includeOwn = !includeOwn }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(includeOwn, { includeOwn = it }, colors = CheckboxDefaults.colors(checkedColor = Brand.Coral))
                        Text("Save my own $own posts, including \"Only me\" ones, with their original dates")
                    }
                }
                if (saved == 0 && liked > 0) {
                    Text("No favourites were found in this export, so your liked videos are selected instead. Untick them if you don't want them.", color = Brand.Gold, style = MaterialTheme.typography.bodySmall)
                }
                if (withCollections > 0) Text("$withCollections are in collections; they'll be grouped the same way.", color = Brand.TextSecondary)
                val names = p.scan.collectionNames.size
                if (names > 0 && withCollections == 0) {
                    Text(
                        "TikTok's export names your $names collections but doesn't say which videos are in them. " +
                            "After importing, use Settings → Sync collections from TikTok to sort them automatically.",
                        color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (liked > 0) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { includeLiked = !includeLiked }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(includeLiked, { includeLiked = it }, colors = CheckboxDefaults.colors(checkedColor = Brand.Coral))
                        Text("Also save my $liked liked videos")
                    }
                }
                if (other > 0) Text("$other other links (watch history, shares…) are ignored.", color = Brand.TextTertiary, style = MaterialTheme.typography.bodySmall)
                Text("Downloads run in the background, a few seconds apart. Already-saved videos are skipped.", color = Brand.TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            val n = saved + (if (includeLiked) liked else 0) + (if (includeOwn) own else 0)
            GradientButton("Save $n", onClick = { vm.confirmImport(includeLiked, includeOwn) }, enabled = n > 0)
        },
        dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel", color = Brand.TextSecondary) } },
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
