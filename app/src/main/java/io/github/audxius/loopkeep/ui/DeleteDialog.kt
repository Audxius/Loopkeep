package io.github.audxius.loopkeep.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.audxius.loopkeep.data.VideoEntity

@Composable
fun DeleteDialog(item: VideoEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this video?") },
        text = {
            Text(
                "Removes the note \"${item.noteName}\" and its video and images from the folder. " +
                    "Your notes on it are deleted too. This can't be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
