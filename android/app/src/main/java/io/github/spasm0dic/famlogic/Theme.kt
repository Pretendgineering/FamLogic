package io.github.spasm0dic.famlogic

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// FamLogic's look: serif headings (Liberation Serif) over clean sans text, "firm but
// friendly", in one of a few palettes, each with a light and a dark version. Muted
// Dracula is the default. The same palettes are in the web console (style.css).

/** The few colours a palette is made from; the rest of Material's scheme is derived. */
private data class Tokens(
    val background: Long, val surface: Long, val surface2: Long, val text: Long, val muted: Long,
    val line: Long, val accent: Long, val onAccent: Long, val accentSoft: Long, val onAccentSoft: Long,
    val secondary: Long, val error: Long, val sos: Long,
)

enum class Palette(val id: String, val title: String, val description: String) {
    Dracula("dracula", "Muted Dracula", "Soft dusky purples with gentle pink, cyan and green."),
    Purple("purple", "Purple", "The original bright Material purple."),
    Paper("paper", "Warm paper", "Cream and ink with a deep red: easy on the eyes."),
    Forest("forest", "Forest", "Calm greens."),
    Ocean("ocean", "Ocean", "Cool blues."),
    Contrast("contrast", "High contrast", "Black and white with strong accents, for bright rooms and easier reading."),
    ;

    companion object {
        fun byId(id: String) = entries.find { it.id == id } ?: Dracula
    }
}

private fun tokens(p: Palette, dark: Boolean): Tokens = when (p) {
    Palette.Dracula -> if (dark) Tokens(0xFF22212C, 0xFF2A2937, 0xFF353445, 0xFFECEBF3, 0xFFA7A4BE, 0xFF403F53,
        0xFFB8A3E3, 0xFF231B3A, 0xFF46405E, 0xFFE6DCFF, 0xFFE3A3C8, 0xFFE57A8E, 0xFFE0607A)
    else Tokens(0xFFF6F4F8, 0xFFFFFFFF, 0xFFECE9F2, 0xFF282A36, 0xFF615E77, 0xFFDCD8E6,
        0xFF6F5AA8, 0xFFFFFFFF, 0xFFE4DCF5, 0xFF372A66, 0xFFA34F82, 0xFFB53A55, 0xFFC0394F)
    Palette.Purple -> if (dark) Tokens(0xFF1D1E28, 0xFF262735, 0xFF31324A, 0xFFF2F0FA, 0xFFA4A2BF, 0xFF3A3B52,
        0xFFBD93F9, 0xFF2B1958, 0xFF433A64, 0xFFE4D6FF, 0xFFFF92DF, 0xFFFF6E7F, 0xFFE25569)
    else Tokens(0xFFF4F1FB, 0xFFFFFFFF, 0xFFE7E0F3, 0xFF231F33, 0xFF5B5570, 0xFFDDD6EE,
        0xFF6E48D6, 0xFFFFFFFF, 0xFFE3D9FB, 0xFF2A1462, 0xFF7D5260, 0xFFC7354B, 0xFFC7354B)
    Palette.Paper -> if (dark) Tokens(0xFF1F1B16, 0xFF2A251F, 0xFF352E26, 0xFFEFE6D6, 0xFFB3A690, 0xFF463D31,
        0xFFD98C7A, 0xFF3A1610, 0xFF4D3128, 0xFFF6D6CC, 0xFFC9B27C, 0xFFE57A6E, 0xFFE0604F)
    else Tokens(0xFFF6F0E4, 0xFFFFFAF0, 0xFFEFE6D4, 0xFF2E2418, 0xFF6E6050, 0xFFDDD0B8,
        0xFF8E2A2A, 0xFFFFFAF0, 0xFFF0D9CF, 0xFF5C1A1A, 0xFF7A6230, 0xFFB3261E, 0xFFB3261E)
    Palette.Forest -> if (dark) Tokens(0xFF1A201B, 0xFF222A23, 0xFF2C362D, 0xFFE5EEE4, 0xFF9FB09E, 0xFF364337,
        0xFF8CC79A, 0xFF0F2E17, 0xFF2F4733, 0xFFCFEED6, 0xFFC8C27A, 0xFFE57A7A, 0xFFE06060)
    else Tokens(0xFFF1F5F0, 0xFFFFFFFF, 0xFFE3ECE1, 0xFF1F2A20, 0xFF5C6B5C, 0xFFD3DED1,
        0xFF2F6B3F, 0xFFFFFFFF, 0xFFD6EAD9, 0xFF17401F, 0xFF6B6330, 0xFFB3261E, 0xFFC0392B)
    Palette.Ocean -> if (dark) Tokens(0xFF171E26, 0xFF1F2833, 0xFF29343F, 0xFFE3EDF5, 0xFF9DB0C2, 0xFF34414E,
        0xFF7FB8E0, 0xFF0B2A40, 0xFF23425B, 0xFFCFE6F7, 0xFF8FD3C0, 0xFFE57A84, 0xFFE0606E)
    else Tokens(0xFFEFF4F8, 0xFFFFFFFF, 0xFFE0EAF2, 0xFF1A2633, 0xFF586A7C, 0xFFD0DDE8,
        0xFF1F5F8B, 0xFFFFFFFF, 0xFFD3E5F3, 0xFF0F3654, 0xFF2D7A6A, 0xFFB3261E, 0xFFC0394F)
    Palette.Contrast -> if (dark) Tokens(0xFF000000, 0xFF0A0A0A, 0xFF1C1C1C, 0xFFFFFFFF, 0xFFE0E0E0, 0xFFFFFFFF,
        0xFFFFD400, 0xFF000000, 0xFF3D3500, 0xFFFFF0A0, 0xFF7FE0FF, 0xFFFF6B6B, 0xFFFF4040)
    else Tokens(0xFFFFFFFF, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF000000, 0xFF2B2B2B, 0xFF000000,
        0xFF4B0096, 0xFFFFFFFF, 0xFFEDE0FF, 0xFF24004A, 0xFF005A73, 0xFFB00000, 0xFFC00000)
}

private fun scheme(t: Tokens, dark: Boolean): ColorScheme {
    val c = { v: Long -> Color(v) }
    return if (dark) darkColorScheme(
        primary = c(t.accent), onPrimary = c(t.onAccent), primaryContainer = c(t.accentSoft), onPrimaryContainer = c(t.onAccentSoft),
        secondary = c(t.secondary), onSecondary = c(t.background), secondaryContainer = c(t.surface2), onSecondaryContainer = c(t.text),
        tertiary = c(t.secondary), tertiaryContainer = c(t.surface2), onTertiaryContainer = c(t.text),
        background = c(t.background), onBackground = c(t.text), surface = c(t.background), onSurface = c(t.text),
        surfaceVariant = c(t.surface2), onSurfaceVariant = c(t.muted), surfaceTint = c(t.accent),
        surfaceContainerLowest = c(t.background), surfaceContainerLow = c(t.surface), surfaceContainer = c(t.surface),
        surfaceContainerHigh = c(t.surface2), surfaceContainerHighest = c(t.surface2),
        outline = c(t.muted), outlineVariant = c(t.line),
        error = c(t.error), onError = c(t.background), errorContainer = c(t.sos).copy(alpha = 0.35f), onErrorContainer = c(t.text),
    ) else lightColorScheme(
        primary = c(t.accent), onPrimary = c(t.onAccent), primaryContainer = c(t.accentSoft), onPrimaryContainer = c(t.onAccentSoft),
        secondary = c(t.secondary), onSecondary = c(t.surface), secondaryContainer = c(t.surface2), onSecondaryContainer = c(t.text),
        tertiary = c(t.secondary), tertiaryContainer = c(t.surface2), onTertiaryContainer = c(t.text),
        background = c(t.background), onBackground = c(t.text), surface = c(t.background), onSurface = c(t.text),
        surfaceVariant = c(t.surface2), onSurfaceVariant = c(t.muted), surfaceTint = c(t.accent),
        surfaceContainerLowest = c(t.surface), surfaceContainerLow = c(t.surface), surfaceContainer = c(t.surface2),
        surfaceContainerHigh = c(t.surface2), surfaceContainerHighest = c(t.line),
        outline = c(t.muted), outlineVariant = c(t.line),
        error = c(t.error), onError = c(t.surface), errorContainer = c(t.sos).copy(alpha = 0.18f), onErrorContainer = c(t.text),
    )
}

/** The serif for headings, names and big numbers. Liberation Serif: SIL Open Font License (see assets/licenses). */
val Serif = FontFamily(
    Font(R.font.liberation_serif_regular, FontWeight.Normal),
    Font(R.font.liberation_serif_bold, FontWeight.Bold),
)

/** Headings and titles in the serif; body text, buttons and small labels stay sans. */
private val AppTypography: Typography = Typography().let { t ->
    fun TextStyle.serif(weight: FontWeight = FontWeight.Bold) = copy(fontFamily = Serif, fontWeight = weight, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified)
    t.copy(
        displayLarge = t.displayLarge.serif(FontWeight.Normal),
        displayMedium = t.displayMedium.serif(FontWeight.Normal),
        displaySmall = t.displaySmall.serif(FontWeight.Normal),
        headlineLarge = t.headlineLarge.serif(),
        headlineMedium = t.headlineMedium.serif(),
        headlineSmall = t.headlineSmall.serif(),
        titleLarge = t.titleLarge.serif(),
        titleMedium = t.titleMedium.serif(),
    )
}

/** SOS red for the current palette: clear but not neon. */
val SOS_RED: Color @Composable get() = Color(tokens(ThemeState.palette, LocalDarkTheme.current).sos)

/** Whether the app is showing its dark look (the user's choice, or the system's). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** The chosen palette and light/dark mode, kept here so changing them in Settings redraws everything at once. */
object ThemeState {
    var palette by mutableStateOf(Palette.Dracula)
    var mode by mutableStateOf("system") // "system", "light" or "dark"

    fun load(context: Context) {
        val prefs = Prefs(context)
        palette = Palette.byId(prefs.appTheme)
        mode = prefs.darkMode
    }
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun FamLogicTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeState.mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = scheme(tokens(ThemeState.palette, dark), dark),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
