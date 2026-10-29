package io.github.audxius.loopkeep.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.audxius.loopkeep.BuildConfig
import io.github.audxius.loopkeep.core.NoteStatus

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

    Box(Modifier.fillMaxSize().background(Brand.Background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader("Settings", onBack = { vm.back() })
            Column(
                Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                busy?.let {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Brand.Coral, trackColor = Brand.Surface3)
                        Text(it, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                SectionCard("Archive") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("${counts[NoteStatus.FULL.value] ?: 0}", "Saved", Brand.Teal, Modifier.weight(1f))
                        StatTile("${counts[NoteStatus.TEXT_ONLY.value] ?: 0}", "Partial", Brand.Gold, Modifier.weight(1f))
                        StatTile("${counts[NoteStatus.UNAVAILABLE.value] ?: 0}", "Gone", Brand.Rose, Modifier.weight(1f))
                    }
                    SettingRow(
                        Icons.Outlined.Inventory2, info?.let { Formatter.formatFileSize(context, it.bytes) } ?: "…",
                        info?.let { "${it.notes} notes · ${it.mediaFiles} media files" }, iconTint = Brand.Teal,
                    )
                    RowDivider()
                    SettingRow(
                        Icons.Outlined.Info, "Backing up",
                        "Copy the whole folder (notes/ and media/). That's the complete archive; the app keeps nothing else that matters.",
                        iconTint = Brand.TextSecondary,
                    )
                }

                SectionCard("Add videos") {
                    SettingRow(
                        Icons.Default.UploadFile, "Import TikTok data export",
                        "JSON, TXT, HTML or the ZIP as downloaded. TikTok → Settings → Account → Download your data.",
                        onClick = { pickExport.launch(arrayOf("*/*")) },
                    ) { Icon(Icons.Default.ChevronRight, null, tint = Brand.TextTertiary) }
                    RowDivider()
                    SettingRow(
                        Icons.Outlined.CollectionsBookmark, "Import a collection",
                        "In TikTok, make the collection public and share its link to Loopkeep. Posts are filed into that collection.",
                        iconTint = Brand.Peach,
                    )
                    RowDivider()
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).clip(CircleShape).background(Brand.Surface2).padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Link, null, tint = Brand.TextTertiary)
                            Spacer(Modifier.width(8.dp))
                            PlainTextField(link, { link = it }, "Paste TikTok links", Modifier.weight(1f), contentPadding = PaddingValues(0.dp))
                        }
                        TextButton(onClick = { vm.addLinks(link); link = "" }, enabled = link.isNotBlank()) {
                            Text("Add", color = if (link.isNotBlank()) Brand.Coral else Brand.TextTertiary)
                        }
                    }
                }

                SectionCard("Creators") {
                    SettingRow(
                        Icons.AutoMirrored.Outlined.Comment, "Fetch creator comments & links",
                        "For posts you already saved: the creator's own comments and replies, and the link in their bio. Nothing is re-downloaded.",
                        onClick = vm::refreshCreatorInfo, iconTint = Brand.Peach,
                    ) { Icon(Icons.Default.ChevronRight, null, tint = Brand.TextTertiary) }
                }

                SectionCard("Folder") {
                    SettingRow(Icons.Default.Folder, info?.folder ?: "…", "Notes in notes/, videos and images in media/", iconTint = Brand.Gold)
                    RowDivider()
                    SettingRow(Icons.AutoMirrored.Filled.DriveFileMove, "Change folder", onClick = { pickFolder.launch(null) }) {
                        Icon(Icons.Default.ChevronRight, null, tint = Brand.TextTertiary)
                    }
                    RowDivider()
                    SettingRow(
                        Icons.Default.Refresh, "Rescan folder",
                        "Rebuild the library from the notes. Changed notes are picked up automatically whenever the app opens.",
                        onClick = { vm.rescan(full = true) },
                    )
                }

                SectionCard("Downloader") {
                    SettingRow(
                        Icons.Default.SystemUpdateAlt, "Update yt-dlp now", "Installed: ${ytDlp ?: "…"}. Do this if downloads start failing.",
                        onClick = vm::updateYtDlp,
                    )
                    RowDivider()
                    SettingRow(Icons.Default.Autorenew, "Update automatically", "Checks every few days before downloading.") {
                        Switch(
                            autoUpdate, { autoUpdate = it; vm.setAutoUpdateYtDlp(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Coral, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
                        )
                    }
                }

                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    LogoMark(36)
                    Spacer(Modifier.height(8.dp))
                    Text("Loopkeep ${BuildConfig.VERSION_NAME}", color = Brand.TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
