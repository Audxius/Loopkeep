package io.github.audxius.loopkeep.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.TikTokUrls
import io.github.audxius.loopkeep.data.QueueItem
import io.github.audxius.loopkeep.data.QueueSource
import io.github.audxius.loopkeep.data.QueueState
import io.github.audxius.loopkeep.data.VideoEntity

@Composable
fun QueueScreen(vm: AppViewModel) {
    val items by vm.queueItems.collectAsStateWithLifecycle()
    val counts by vm.queueCounts.collectAsStateWithLifecycle()
    val byId by vm.allById.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm, snackbar)

    Box(Modifier.fillMaxSize().background(Brand.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ScreenHeader("Downloads", onBack = { vm.back() }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    StatTile("${(counts[QueueState.PENDING] ?: 0) + (counts[QueueState.RUNNING] ?: 0)}", "Waiting", Brand.Peach, Modifier.weight(1f))
                    StatTile("${counts[QueueState.DONE] ?: 0}", "Finished", Brand.Teal, Modifier.weight(1f))
                    StatTile("${counts[QueueState.FAILED] ?: 0}", "Failed", Brand.Coral, Modifier.weight(1f))
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    item { ActionChip(Icons.Default.Refresh, "Retry failed", (counts[QueueState.FAILED] ?: 0) > 0, vm::retryFailed) }
                    item { ActionChip(Icons.Default.PlayArrow, "Run now", (counts[QueueState.PENDING] ?: 0) > 0, vm::retryWaitingNow) }
                    item { ActionChip(Icons.Default.DoneAll, "Clear finished", (counts[QueueState.DONE] ?: 0) > 0, vm::clearDone) }
                }
            }
            if (items.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = Brand.TextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Nothing in the queue", color = Brand.TextSecondary)
                        Text("Share a TikTok to Loopkeep or import your export.", color = Brand.TextTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(items, key = { it.id }) { QueueRow(it, it.tiktokId?.let(byId::get), onRemove = { vm.removeQueueItem(it.id) }) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (enabled) Brand.Surface2 else Brand.Surface1)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) Brand.Coral else Brand.TextTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) Brand.TextPrimary else Brand.TextTertiary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QueueRow(item: QueueItem, saved: VideoEntity?, onRemove: () -> Unit) {
    val now = System.currentTimeMillis()
    val waitingRetry = item.state == QueueState.PENDING && item.nextAttemptAt > now
    val (icon, color, label) = when {
        item.state == QueueState.RUNNING -> Triple(Icons.Outlined.CloudDownload, Brand.Peach, "Downloading")
        waitingRetry -> Triple(
            Icons.Outlined.Schedule, Brand.Gold,
            "Retry " + DateUtils.getRelativeTimeSpanString(item.nextAttemptAt, now, DateUtils.MINUTE_IN_MILLIS),
        )
        item.state == QueueState.PENDING -> Triple(Icons.Outlined.Schedule, Brand.TextSecondary, "Waiting")
        item.state == QueueState.FAILED -> Triple(Icons.Outlined.ErrorOutline, Brand.Coral, "Failed")
        item.resultStatus == NoteStatus.TEXT_ONLY.value -> Triple(Icons.AutoMirrored.Outlined.ShortText, Brand.Gold, "Caption only")
        item.resultStatus == NoteStatus.UNAVAILABLE.value -> Triple(Icons.Outlined.CloudOff, Brand.Rose, "Unavailable")
        item.source == QueueSource.COLLECTION && item.tiktokId == null -> Triple(Icons.Outlined.CollectionsBookmark, Brand.Teal, "Collection read")
        else -> Triple(Icons.Outlined.CheckCircle, Brand.Teal, "Saved")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface1).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val title = TikTokUrls.collectionName(item.url)?.let { "Collection: $it" }
                ?: saved?.let { s ->
                    listOfNotNull(s.authorHandle.ifBlank { null }?.let { "@$it" }, s.caption.take(60).ifBlank { null }).joinToString(" · ").ifBlank { null }
                }
                ?: TikTokUrls.extractHandle(item.url)?.let { "@$it" }
                ?: item.tiktokId?.let { "Post …${it.takeLast(6)}" } ?: item.url
            Text(title, color = Brand.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(label, color)
                Text(
                    sourceLabel(item.source) + if (item.attempts > 1) " · ${item.attempts} tries" else "",
                    color = Brand.TextTertiary, fontSize = 11.sp,
                )
            }
            item.collections?.takeIf { it.isNotBlank() }?.let {
                Text("→ " + it.replace("\n", ", "), color = Brand.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.state == QueueState.RUNNING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Brand.Peach, trackColor = Brand.Surface3)
            }
            item.lastError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    color = if (item.state == QueueState.FAILED || waitingRetry) Brand.Coral.copy(alpha = 0.9f) else Brand.TextSecondary,
                )
            }
        }
        if (item.state != QueueState.RUNNING) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove from queue", tint = Brand.TextTertiary) }
        }
    }
}

private fun sourceLabel(source: String) = when (source) {
    QueueSource.EXPORT_SAVED -> "From export"
    QueueSource.EXPORT_LIKED -> "Liked, from export"
    QueueSource.SHARE -> "Shared"
    QueueSource.MANUAL -> "Pasted"
    QueueSource.RETRY -> "Retry"
    QueueSource.COLLECTION -> "Collection"
    else -> source
}

