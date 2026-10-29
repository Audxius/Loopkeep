package io.github.audxius.loopkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audxius.loopkeep.core.NoteStatus

fun statusLabel(status: String) = when (status) {
    NoteStatus.FULL.value -> "Saved"
    NoteStatus.TEXT_ONLY.value -> "Caption only"
    NoteStatus.UNAVAILABLE.value -> "Unavailable"
    else -> status
}

fun statusColor(status: String) = when (status) {
    NoteStatus.FULL.value -> Brand.Teal
    NoteStatus.TEXT_ONLY.value -> Brand.Gold
    NoteStatus.UNAVAILABLE.value -> Brand.Rose
    else -> Brand.TextSecondary
}

/** Small rounded label with a coloured dot. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, solid: Boolean = false) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (solid) Color(0xCC000000) else color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, color = if (solid) Color.White else color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Gradient circle with the first letter of a name. */
@Composable
fun Avatar(name: String, size: Dp = 36.dp) {
    val letter = name.trimStart('@').firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(Modifier.size(size).clip(CircleShape).background(Brand.Gradient), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/** The app's primary call to action: a pill with the brand gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (enabled) Brand.GradientHorizontal else androidx.compose.ui.graphics.SolidColor(Brand.Surface3))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 22.dp, vertical = 14.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) Color.White else Brand.TextTertiary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** A rounded surface card with an optional heading. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Brand.TextTertiary,
                letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brand.Surface1),
            content = content,
        )
    }
}

/** One tappable row inside a [SectionCard]. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    iconTint: Color = Brand.Coral,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Brand.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
fun RowDivider() = Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(1.dp).background(Brand.Line.copy(alpha = 0.6f)))

/** Big number with a caption, for stats. */
@Composable
fun StatTile(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Brand.Surface2).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Brand.TextSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

/** Title bar for secondary screens. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

/**
 * Borderless text field whose whole visible area is the input (tapping anywhere focuses it),
 * with a placeholder.
 */
@Composable
fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    fontSize: Int = 16,
    contentPadding: PaddingValues = PaddingValues(vertical = 12.dp),
    cursorColor: Color = Brand.Coral,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = androidx.compose.ui.text.TextStyle(color = Brand.TextPrimary, fontSize = fontSize.sp, lineHeight = (fontSize * 1.4f).sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(cursorColor),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBox = { inner ->
            Box(Modifier.padding(contentPadding), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(placeholder, color = Brand.TextTertiary, fontSize = fontSize.sp, maxLines = if (singleLine) 1 else 3)
                inner()
            }
        },
    )
}
