package io.github.audxius.loopkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.data.VideoEntity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(vm: AppViewModel, item: VideoEntity, onDismiss: () -> Unit) {
    val tags = remember(item.tiktokId) { mutableStateListOf(*item.tagList.toTypedArray()) }
    val collections = remember(item.tiktokId) { mutableStateListOf(*item.collectionList.toTypedArray()) }
    var notes by remember(item.tiktokId) { mutableStateOf(item.notes) }
    val tagSuggestions = remember(item.tiktokId) { vm.suggestTags(item) }
    val allCollections by vm.collections.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Surface1,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.authorHandle.ifBlank { item.author }, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tags, collections & notes", style = MaterialTheme.typography.titleLarge)
                    Text(item.noteName, color = Brand.TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }

            ChipEditor(
                title = "Collections", values = collections, color = Brand.Coral, icon = Icons.Outlined.CollectionsBookmark,
                prefix = "", placeholder = "New collection",
                suggestions = allCollections.map { it.first }.filter { it !in collections },
                normalize = { it.trim() },
            )
            ChipEditor(
                title = "Tags", values = tags, color = Brand.Peach, icon = null, prefix = "#", placeholder = "Add a tag",
                suggestions = tagSuggestions.filter { it !in tags },
                normalize = { it.trim().trimStart('#').replace(' ', '_') },
                splitOnSpace = true,
            )

            Text("Notes", style = MaterialTheme.typography.titleMedium)
            PlainTextField(
                notes, { notes = it }, "Write anything. It's saved under ## Notes in the markdown file.",
                Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Surface2),
                singleLine = false, fontSize = 15, contentPadding = PaddingValues(14.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientButton("Save", onClick = {
                    vm.saveEdits(item, tags.toList(), notes, collections.toList())
                    onDismiss()
                })
                TextButton(onClick = onDismiss) { Text("Cancel", color = Brand.TextSecondary) }
                Spacer(Modifier.weight(1f))
                if (item.status != NoteStatus.FULL.value) {
                    TextButton(onClick = { vm.retry(item); onDismiss() }) {
                        Icon(Icons.Default.Refresh, null, tint = Brand.Coral, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry download", color = Brand.Coral)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipEditor(
    title: String,
    values: SnapshotStateList<String>,
    color: Color,
    icon: ImageVector?,
    prefix: String,
    placeholder: String,
    suggestions: List<String>,
    normalize: (String) -> String,
    splitOnSpace: Boolean = false,
) {
    var input by remember { mutableStateOf("") }
    fun commit() {
        val parts = if (splitOnSpace) input.split(Regex("""[\s,]+""")) else input.split(',')
        parts.map(normalize).filter { it.isNotEmpty() && it !in values }.forEach { values += it }
        input = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { v ->
                Row(
                    Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).clickable { values.remove(v) }
                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("$prefix$v", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.Close, "Remove", tint = color.copy(alpha = 0.8f), modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
            }
            Row(
                Modifier.clip(CircleShape).background(Brand.Surface2).padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainTextField(
                    input, { input = it }, placeholder, Modifier.width(130.dp), fontSize = 13, cursorColor = color,
                    contentPadding = PaddingValues(vertical = 7.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
                )
                Icon(Icons.Default.Add, "Add", tint = if (input.isBlank()) Brand.TextTertiary else color, modifier = Modifier.size(28.dp).clip(CircleShape).clickable(enabled = input.isNotBlank()) { commit() }.padding(4.dp))
            }
        }
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.take(12).forEach { s ->
                    Text(
                        "+ $prefix$s", color = Brand.TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.clip(CircleShape).border(1.dp, Brand.Line, CircleShape).clickable { values += s }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
