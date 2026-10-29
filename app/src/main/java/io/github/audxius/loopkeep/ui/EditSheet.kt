package io.github.audxius.loopkeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.data.VideoEntity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(vm: AppViewModel, item: VideoEntity, onDismiss: () -> Unit) {
    val tags = remember(item.tiktokId) { mutableStateListOf(*item.tagList.toTypedArray()) }
    var notes by remember(item.tiktokId) { mutableStateOf(item.notes) }
    var newTag by remember { mutableStateOf("") }
    val suggestions = remember(item.tiktokId) { vm.suggestTags(item) }

    fun addTag(raw: String) {
        raw.split(Regex("""[\s,]+""")).map { it.trim().trimStart('#') }.filter { it.isNotEmpty() && it !in tags }.forEach { tags += it }
        newTag = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(item.noteName, style = MaterialTheme.typography.labelMedium)
            Text("Tags", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false, onClick = { tags.remove(tag) }, label = { Text("#$tag") },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) },
                    )
                }
            }
            OutlinedTextField(
                newTag, { newTag = it }, label = { Text("Add tag") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addTag(newTag) }),
            )
            val remaining = suggestions.filter { it !in tags }
            if (remaining.isNotEmpty()) {
                Text("Suggested from hashtags", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    remaining.forEach { s -> SuggestionChip(onClick = { tags += s }, label = { Text("#$s") }) }
                }
            }
            Text("Notes", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(notes, { notes = it }, minLines = 4, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Your notes (saved under ## Notes)") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (newTag.isNotBlank()) addTag(newTag)
                    vm.saveEdits(item, tags.toList(), notes)
                    onDismiss()
                }) { Text("Save") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (item.status != NoteStatus.FULL.value) {
                    TextButton(onClick = { vm.retry(item); onDismiss() }) { Text("Retry download") }
                }
            }
        }
    }
}
