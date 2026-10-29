package io.github.audxius.loopkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audxius.loopkeep.core.CreatorCommentsMarkdown
import io.github.audxius.loopkeep.data.VideoEntity

/** The creator's own comments and replies on a post, as saved in its note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorCommentsSheet(item: VideoEntity, onDismiss: () -> Unit) {
    val comments = remember(item.creatorComments) { CreatorCommentsMarkdown.parse(item.creatorComments) }
    val handle = item.authorHandle.ifBlank { item.author }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Brand.Surface1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(handle, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("From the creator", style = MaterialTheme.typography.titleLarge)
                    Text("${comments.size} comment${if (comments.size == 1) "" else "s"} by @$handle", color = Brand.TextTertiary, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    items(comments) { c ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface2).padding(14.dp)) {
                            if (c.replyToText != null) {
                                Row(Modifier.padding(bottom = 8.dp)) {
                                    Spacer(Modifier.width(3.dp).height(36.dp).background(Brand.Line))
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        c.replyToUser?.let { Text("@$it", color = Brand.TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                        Text(c.replyToText, color = Brand.TextSecondary, fontSize = 13.sp, maxLines = 3)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("@$handle", color = Brand.Coral, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(6.dp))
                                StatusPill("Creator", Brand.Coral)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(c.text, color = Brand.TextPrimary, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }
    }
}
