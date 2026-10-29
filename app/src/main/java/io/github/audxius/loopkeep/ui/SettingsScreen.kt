package io.github.audxius.loopkeep.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.audxius.loopkeep.BuildConfig
import io.github.audxius.loopkeep.core.NoteStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val info by vm.storageInfo.collectAsStateWithLifecycle()
    val counts by vm.statusCounts.collectAsStateWithLifecycle()
    val ytDlp by vm.ytDlpVersion.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var autoUpdate by remember { mutableStateOf(vm.autoUpdateYtDlp) }
    var link by rememberSaveable { mutableStateOf("") }
    MessageEffect(vm, snackbar)
    ImportDialog(vm)

    LaunchedEffect(Unit) {
        vm.refreshStorageInfo()
        vm.refreshYtDlpVersion()
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) vm.setFolder(uri) }
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) vm.readExport(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Section("Add videos")
            Button(onClick = { pickExport.launch(arrayOf("*/*")) }) { Text("Import TikTok data export") }
            Text(
                "Use the JSON export if you can (TikTok → Settings → Account → Download your data). TXT, HTML, and the ZIP as downloaded also work.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(link, { link = it }, label = { Text("Paste TikTok link(s)") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.addLinks(link); link = "" }, enabled = link.isNotBlank()) { Text("Add to queue") }

            HorizontalDivider()
            Section("Folder")
            Text(info?.folder ?: "…")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Change folder") }
                OutlinedButton(onClick = { vm.rescan(full = true) }) { Text("Rescan folder") }
            }
            Text(
                "Rescan rebuilds the app's index from the notes. It runs automatically (only changed notes) every time the app starts, so edits made in Obsidian show up.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Storage")
            info?.let { i ->
                Text("${i.notes} notes, ${i.mediaFiles} media files, ${Formatter.formatFileSize(context, i.bytes)}")
            }
            Text(NoteStatus.entries.joinToString(" · ") { "${counts[it.value] ?: 0} ${statusLabel(it.value).lowercase()}" })
            Text(
                "Backup: copy the whole folder (notes/ and media/) anywhere. That's the complete archive; the app keeps nothing else that matters.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Downloader (yt-dlp)")
            Text("Version: ${ytDlp ?: "…"}")
            OutlinedButton(onClick = vm::updateYtDlp, enabled = busy == null) { Text("Update yt-dlp now") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-update every few days", Modifier.weight(1f))
                Switch(autoUpdate, { autoUpdate = it; vm.setAutoUpdateYtDlp(it) })
            }
            Text(
                "TikTok changes often and breaks downloaders. If downloads start failing, update yt-dlp and use \"Retry failed\" in the queue.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Loopkeep ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)
