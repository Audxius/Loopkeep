package io.github.audxius.loopkeep.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.audxius.loopkeep.data.QueueItem
import io.github.audxius.loopkeep.data.QueueState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: AppViewModel) {
    val items by vm.queueItems.collectAsStateWithLifecycle()
    val counts by vm.queueCounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download queue") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                listOf(QueueState.RUNNING, QueueState.PENDING, QueueState.FAILED, QueueState.DONE)
                    .joinToString("  ·  ") { "${counts[it] ?: 0} $it" },
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::retryFailed, enabled = (counts[QueueState.FAILED] ?: 0) > 0) { Text("Retry failed") }
                OutlinedButton(onClick = vm::retryWaitingNow, enabled = (counts[QueueState.PENDING] ?: 0) > 0) { Text("Run now") }
                OutlinedButton(onClick = vm::clearDone, enabled = (counts[QueueState.DONE] ?: 0) > 0) { Text("Clear done") }
            }
            HorizontalDivider()
            if (items.isEmpty()) {
                Text("Nothing queued. Import an export or share a link.", Modifier.padding(16.dp))
            }
            LazyColumn {
                items(items, key = { it.id }) { QueueRow(it, onRemove = { vm.removeQueueItem(it.id) }) }
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.tiktokId ?: item.url, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val state = when {
                item.state == QueueState.DONE -> item.resultStatus?.let { statusLabel(it) } ?: "Done"
                item.state == QueueState.PENDING && item.nextAttemptAt > System.currentTimeMillis() ->
                    "Retry " + DateUtils.getRelativeTimeSpanString(item.nextAttemptAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                else -> item.state.replaceFirstChar { it.uppercase() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(state) })
                Text("${item.source}${if (item.attempts > 0) " · ${item.attempts} tries" else ""}", style = MaterialTheme.typography.labelSmall)
            }
            item.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.state != QueueState.RUNNING) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove from queue") }
    }
}
