package io.github.spasm0dic.famlogic

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// FamLogic' own purple palette (the same as the web dashboard), light or dark
// following the system. Fixed rather than taken from the wallpaper so the app
// always looks like itself.

private val Light = lightColorScheme(
    primary = Color(0xFF6E48D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3D9FB),
    onPrimaryContainer = Color(0xFF2A1462),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF7D5260),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFF4F1FB),
    onBackground = Color(0xFF231F33),
    surface = Color(0xFFF4F1FB),
    onSurface = Color(0xFF231F33),
    surfaceVariant = Color(0xFFE7E0F3),
    onSurfaceVariant = Color(0xFF5B5570),
    surfaceTint = Color(0xFF6E48D6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF9FF),
    surfaceContainer = Color(0xFFF1ECFA),
    surfaceContainerHigh = Color(0xFFECE6F8),
    surfaceContainerHighest = Color(0xFFE5DEF4),
    outline = Color(0xFF8C86A0),
    outlineVariant = Color(0xFFDDD6EE),
    error = Color(0xFFC7354B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADC),
    onErrorContainer = Color(0xFF410010),
)

// Dracula-inspired: deep blue-grey surfaces, soft lilac accent.
private val Dark = darkColorScheme(
    primary = Color(0xFFBD93F9),
    onPrimary = Color(0xFF2B1958),
    primaryContainer = Color(0xFF433A64),
    onPrimaryContainer = Color(0xFFE4D6FF),
    secondary = Color(0xFFCBC2E2),
    onSecondary = Color(0xFF332D45),
    secondaryContainer = Color(0xFF3A3653),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFFF92DF),
    tertiaryContainer = Color(0xFF5C2D52),
    onTertiaryContainer = Color(0xFFFFD8EE),
    background = Color(0xFF1D1E28),
    onBackground = Color(0xFFF2F0FA),
    surface = Color(0xFF1D1E28),
    onSurface = Color(0xFFF2F0FA),
    surfaceVariant = Color(0xFF31324A),
    onSurfaceVariant = Color(0xFFA4A2BF),
    surfaceTint = Color(0xFFBD93F9),
    surfaceContainerLowest = Color(0xFF17181F),
    surfaceContainerLow = Color(0xFF22232F),
    surfaceContainer = Color(0xFF262735),
    surfaceContainerHigh = Color(0xFF2C2D3E),
    surfaceContainerHighest = Color(0xFF34354A),
    outline = Color(0xFF6E6C8A),
    outlineVariant = Color(0xFF3A3B52),
    error = Color(0xFFFF6E7F),
    onError = Color(0xFF4A0013),
    errorContainer = Color(0xFF7A1F2E),
    onErrorContainer = Color(0xFFFFDADC),
)

/** SOS red: clear but not neon; a little softer in the dark. */
val SOS_RED: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE25569) else Color(0xFFC7354B)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun FamLogicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = AppShapes, content = content)
}
