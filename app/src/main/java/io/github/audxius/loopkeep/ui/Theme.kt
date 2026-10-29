package io.github.audxius.loopkeep.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Loopkeep's palette. The app is dark-only: it's a video viewer first. */
object Brand {
    val Coral = Color(0xFFFF5E62)
    val Peach = Color(0xFFFF9A6B)
    val Teal = Color(0xFF34E0C4)
    val Gold = Color(0xFFFFC46B)
    val Rose = Color(0xFFFF7A8A)

    val Background = Color(0xFF09090C)
    val Surface1 = Color(0xFF111116)
    val Surface2 = Color(0xFF18181F)
    val Surface3 = Color(0xFF22222B)
    val Line = Color(0xFF2C2C37)

    val TextPrimary = Color(0xFFF4F4F7)
    val TextSecondary = Color(0xFFA3A3B2)
    val TextTertiary = Color(0xFF6E6E7D)

    val Gradient = Brush.linearGradient(listOf(Coral, Peach))
    val GradientHorizontal = Brush.horizontalGradient(listOf(Coral, Peach))
}

private val colors = darkColorScheme(
    primary = Brand.Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1517),
    onPrimaryContainer = Color(0xFFFFD8D5),
    secondary = Brand.Peach,
    onSecondary = Color(0xFF2A1206),
    secondaryContainer = Color(0xFF2E1D17),
    onSecondaryContainer = Color(0xFFFFDCCB),
    tertiary = Brand.Teal,
    onTertiary = Color(0xFF00201A),
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Background,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.Surface2,
    onSurfaceVariant = Brand.TextSecondary,
    surfaceContainerLowest = Brand.Background,
    surfaceContainerLow = Brand.Surface1,
    surfaceContainer = Brand.Surface1,
    surfaceContainerHigh = Brand.Surface2,
    surfaceContainerHighest = Brand.Surface3,
    inverseSurface = Brand.TextPrimary,
    inverseOnSurface = Brand.Background,
    outline = Brand.Line,
    outlineVariant = Color(0xFF1F1F28),
    error = Color(0xFFFF6B6B),
    onError = Color.White,
    scrim = Color.Black,
)

private val base = Typography()
private val typography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LoopkeepTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        // Gives every screen the dark background and light default text colour.
        Surface(color = Brand.Background, contentColor = Brand.TextPrimary, content = content)
    }
}
