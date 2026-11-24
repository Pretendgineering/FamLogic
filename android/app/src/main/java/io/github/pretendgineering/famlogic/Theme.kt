package io.github.pretendgineering.famlogic

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
    Dracula("dracula", "Default", "Soft dusky purples with gentle pink, cyan and green."),
    Hannah("hannah", "Hannah's Theme", "Bright sea-glass teal with tasteful gold."),
    Forest("forest", "Hobbit Door", "Deep round-door green with a brass knob."),
    Ocean("ocean", "Boring Blue", "Cool, calm, perfectly sensible blues."),
    Paper("paper", "Lofi Cabin", "Cream, wood and a deep red: beats to relax to."),
    Noir("noir", "Logic Noir", "Black, white and greys. Only what needs colour keeps it."),
    Orange("orange", "Suspicious Orange", "Dark grey and a very familiar orange. You know the one."),
    Chill("chill", "And Chill", "Near-black and a streaming-service red."),
    NightShift("nightshift", "Night Shift", "Warm amber on deep brown, easy on the eyes at 2 am."),
    Gamer("gamer", "Gamer Mode", "Black with magenta and cyan: RGB keyboard energy."),
    Couch("couch", "Grandma's Couch", "Dusty rose and olive on cream, like a floral sofa."),
    Contrast("contrast", "High contrast", "Black and white with strong accents, for bright rooms and easier reading."),
    ;

    /** Its main colour, for the picker. */
    val swatch: Long get() = tokens(this, false).accent

    /** Its look in three colours (dark background, main and second colour), for the picker. */
    val swatches: Triple<Long, Long, Long> get() = tokens(this, true).let { Triple(it.background, it.accent, it.secondary) }

    companion object {
        fun byId(id: String) = entries.find { it.id == id } ?: Dracula
    }
}

private fun tokens(p: Palette, dark: Boolean): Tokens = when (p) {
    Palette.Dracula -> if (dark) Tokens(0xFF22212C, 0xFF2A2937, 0xFF353445, 0xFFECEBF3, 0xFFA7A4BE, 0xFF403F53,
        0xFFB8A3E3, 0xFF231B3A, 0xFF46405E, 0xFFE6DCFF, 0xFFE3A3C8, 0xFFE57A8E, 0xFFE0607A)
    else Tokens(0xFFF6F4F8, 0xFFFFFFFF, 0xFFECE9F2, 0xFF282A36, 0xFF615E77, 0xFFDCD8E6,
        0xFF6F5AA8, 0xFFFFFFFF, 0xFFE4DCF5, 0xFF372A66, 0xFFA34F82, 0xFFB53A55, 0xFFC0394F)
    Palette.Paper -> if (dark) Tokens(0xFF1F1B16, 0xFF2A251F, 0xFF352E26, 0xFFEFE6D6, 0xFFB3A690, 0xFF463D31,
        0xFFD98C7A, 0xFF3A1610, 0xFF4D3128, 0xFFF6D6CC, 0xFFC9B27C, 0xFFE57A6E, 0xFFE0604F)
    else Tokens(0xFFF6F0E4, 0xFFFFFAF0, 0xFFEFE6D4, 0xFF2E2418, 0xFF6E6050, 0xFFDDD0B8,
        0xFF8E2A2A, 0xFFFFFAF0, 0xFFF0D9CF, 0xFF5C1A1A, 0xFF7A6230, 0xFFB3261E, 0xFFB3261E)
    Palette.Forest -> if (dark) Tokens(0xFF0F1A12, 0xFF15231A, 0xFF1E3024, 0xFFE3EFE4, 0xFF98B39C, 0xFF2A4030,
        0xFF5FAF6E, 0xFF08200F, 0xFF1F4A2A, 0xFFC9F0D2, 0xFFD4A64A, 0xFFE57A7A, 0xFFE06060)
    else Tokens(0xFFEEF3EC, 0xFFFFFFFF, 0xFFDCE8D8, 0xFF14261A, 0xFF52665A, 0xFFC9D8C5,
        0xFF1E5631, 0xFFFFFFFF, 0xFFCDE5D1, 0xFF0E3019, 0xFF8A6A1F, 0xFFB3261E, 0xFFC0392B)
    Palette.Ocean -> if (dark) Tokens(0xFF171E26, 0xFF1F2833, 0xFF29343F, 0xFFE3EDF5, 0xFF9DB0C2, 0xFF34414E,
        0xFF7FB8E0, 0xFF0B2A40, 0xFF23425B, 0xFFCFE6F7, 0xFF8FD3C0, 0xFFE57A84, 0xFFE0606E)
    else Tokens(0xFFEFF4F8, 0xFFFFFFFF, 0xFFE0EAF2, 0xFF1A2633, 0xFF586A7C, 0xFFD0DDE8,
        0xFF1F5F8B, 0xFFFFFFFF, 0xFFD3E5F3, 0xFF0F3654, 0xFF2D7A6A, 0xFFB3261E, 0xFFC0394F)
    Palette.Hannah -> if (dark) Tokens(0xFF0F1F1D, 0xFF152927, 0xFF1D3633, 0xFFE6F7F3, 0xFF9CC2BA, 0xFF2A4A45,
        0xFF00E3BD, 0xFF00201B, 0xFF0E4A41, 0xFFB8FFF0, 0xFFE0B84E, 0xFFEF7B7B, 0xFFE5534B)
    else Tokens(0xFFF2FAF8, 0xFFFFFFFF, 0xFFE1F2EE, 0xFF10302B, 0xFF4E6E67, 0xFFCBE3DD,
        0xFF009E84, 0xFFFFFFFF, 0xFFB8F5E9, 0xFF00382F, 0xFFB8901F, 0xFFB3261E, 0xFFC0392B)
    Palette.Noir -> if (dark) Tokens(0xFF121212, 0xFF1A1A1A, 0xFF262626, 0xFFEDEDED, 0xFFA3A3A3, 0xFF333333,
        0xFFE0E0E0, 0xFF121212, 0xFF3A3A3A, 0xFFF5F5F5, 0xFFBDBDBD, 0xFFEF6B6B, 0xFFE5484D)
    else Tokens(0xFFF5F5F5, 0xFFFFFFFF, 0xFFE8E8E8, 0xFF141414, 0xFF5E5E5E, 0xFFD4D4D4,
        0xFF1F1F1F, 0xFFFFFFFF, 0xFFE0E0E0, 0xFF141414, 0xFF4D4D4D, 0xFFB3261E, 0xFFC62828)
    Palette.Orange -> if (dark) Tokens(0xFF121212, 0xFF1B1B1B, 0xFF262626, 0xFFF2F2F2, 0xFFA6A6A6, 0xFF333333,
        0xFFFF9000, 0xFF000000, 0xFF4A2C00, 0xFFFFD9A6, 0xFFFFB347, 0xFFFF6B6B, 0xFFFF4D4D)
    else Tokens(0xFFF4F4F4, 0xFFFFFFFF, 0xFFE6E6E6, 0xFF141414, 0xFF5A5A5A, 0xFFD6D6D6,
        0xFFC76A00, 0xFFFFFFFF, 0xFFFFE2BF, 0xFF4A2C00, 0xFF1B1B1B, 0xFFB3261E, 0xFFC62828)
    Palette.Chill -> if (dark) Tokens(0xFF141414, 0xFF1C1C1C, 0xFF2A2A2A, 0xFFF5F5F1, 0xFFB3B3B3, 0xFF333333,
        0xFFE50914, 0xFFFFFFFF, 0xFF4A0F12, 0xFFFFC9CC, 0xFFB3B3B3, 0xFFFF6B6B, 0xFFFF5A36)
    else Tokens(0xFFF5F5F1, 0xFFFFFFFF, 0xFFE9E9E4, 0xFF141414, 0xFF5C5C5C, 0xFFD8D8D2,
        0xFFB20710, 0xFFFFFFFF, 0xFFFAD4D6, 0xFF5C0308, 0xFF3A3A3A, 0xFFB3261E, 0xFFD84315)
    Palette.NightShift -> if (dark) Tokens(0xFF1A1410, 0xFF221A14, 0xFF2E241B, 0xFFF2DFC4, 0xFFB39B7C, 0xFF3D3024,
        0xFFFFB347, 0xFF2A1800, 0xFF4D3418, 0xFFFFE2B8, 0xFFE08A5A, 0xFFF08A7A, 0xFFF0604F)
    else Tokens(0xFFF7EEDF, 0xFFFFF8EC, 0xFFEFE1CA, 0xFF2E2014, 0xFF6E5A44, 0xFFDCC9AA,
        0xFFA65E00, 0xFFFFFFFF, 0xFFF5DDB8, 0xFF4A2A00, 0xFF9A4A22, 0xFFB3261E, 0xFFC0392B)
    Palette.Gamer -> if (dark) Tokens(0xFF0A0A12, 0xFF12121E, 0xFF1C1C2C, 0xFFEAEAFF, 0xFF9A9AC0, 0xFF2A2A44,
        0xFFFF2BD6, 0xFF1A0016, 0xFF4A0F42, 0xFFFFC2F3, 0xFF00E5FF, 0xFFFF5C7A, 0xFFFF3355)
    else Tokens(0xFFF4F4FB, 0xFFFFFFFF, 0xFFE6E6F5, 0xFF12121E, 0xFF55557A, 0xFFD2D2E8,
        0xFFB5009A, 0xFFFFFFFF, 0xFFF8D2F0, 0xFF4A0040, 0xFF007C8C, 0xFFB3261E, 0xFFD3003F)
    Palette.Couch -> if (dark) Tokens(0xFF231C1B, 0xFF2C2422, 0xFF382E2B, 0xFFF0E2DA, 0xFFB8A39A, 0xFF4A3D38,
        0xFFE0A3B2, 0xFF3A1520, 0xFF5A3A42, 0xFFFFDCE4, 0xFFB8C27A, 0xFFEF8A8A, 0xFFE5605A)
    else Tokens(0xFFF6EFE6, 0xFFFFF9F1, 0xFFEEE2D4, 0xFF3A2E2A, 0xFF7A6A60, 0xFFDDCDBB,
        0xFFA85C6F, 0xFFFFFFFF, 0xFFF2D6DC, 0xFF5A2634, 0xFF6E7A3A, 0xFFB3261E, 0xFFC0392B)
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
