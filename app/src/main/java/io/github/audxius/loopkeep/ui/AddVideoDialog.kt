package io.github.audxius.loopkeep.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Details for a video added from the phone. TikTok offers no link for "Only me" posts, so the
 * posted date is entered here (TikTok shows it on your own video).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVideoDialog(vm: AppViewModel, uri: Uri, onDismiss: () -> Unit) {
    var posted by remember { mutableStateOf(vm.fileDate(uri) ?: LocalDate.now()) }
    var caption by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf(vm.ownHandle.orEmpty()) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = posted.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { posted = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK", color = Brand.Coral) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", color = Brand.TextSecondary) } },
        ) { DatePicker(state) }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Surface2,
        title = { Text("Add video from phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The file is moved into your archive. Set the date you posted it (TikTok shows it on your video).",
                    color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall,
                )
                Text("Posted", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Surface3).clickable { picking = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CalendarMonth, null, tint = Brand.Peach, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(posted.format(DateTimeFormatter.ofPattern("d MMM yyyy")), color = Brand.TextPrimary)
                }
                Text("Your @handle", style = MaterialTheme.typography.labelLarge)
                PlainTextField(
                    handle, { handle = it }, "e.g. audxius",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Surface3),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                )
                Text("Caption", style = MaterialTheme.typography.labelLarge)
                PlainTextField(
                    caption, { caption = it }, "Optional",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Surface3),
                    singleLine = false, contentPadding = PaddingValues(14.dp),
                )
            }
        },
        confirmButton = {
            GradientButton("Add", onClick = {
                vm.addLocalVideo(uri, posted, caption, handle)
                onDismiss()
            })
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Brand.TextSecondary) } },
    )
}
