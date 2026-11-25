package io.github.pretendgineering.famlogic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UnifrakturMaguntia: an ornate blackletter (SIL Open Font License, see assets/licenses). */
val Fraktur = FontFamily(Font(R.font.unifraktur_maguntia))

private val GOLD = Color(0xFFD4AF37)

/**
 * The family's initial, from its name: "The Smiths" and "Smith Family" give S.
 * Null when the name has no surname in it ("Our Family").
 */
fun familyInitial(name: String): Char? {
    val skip = setOf("the", "our", "family", "famlogic", "household", "home", "clan", "house", "crew", "of", "and", "&")
    val word = name.split(Regex("""[\s\-_]+""")).firstOrNull { it.isNotBlank() && it.lowercase() !in skip } ?: return null
    return word.firstOrNull { it.isLetter() }?.uppercaseChar()
}

/**
 * The family chat's picture: the family's initial in an ornate blackletter, like a
 * monogram on a coat of arms, in a gold ring. A star if the family's name has no
 * surname to take it from.
 */
@Composable
fun FamilyCrest(size: Int, modifier: Modifier = Modifier, color: String = "") {
    val name = Prefs(LocalContext.current).familyName
    val initial = familyInitial(name)
    Box(
        modifier.size(size.dp).clip(CircleShape).background(color.takeIf { it.length > 1 }?.let { c -> runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary)
            .border((size / 18f).coerceAtLeast(2f).dp, Accent2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initial == null) {
            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size((size / 2).dp))
        } else {
            // Centre the letter by its ink, not its line box: blackletter capitals sit
            // high and lean, so text centring leaves them visibly off-centre.
            val ink = MaterialTheme.colorScheme.onPrimary
            val context = LocalContext.current
            val face = androidx.compose.runtime.remember { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.unifraktur_maguntia) }
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = face; this.color = ink.toArgb(); textSize = this@Canvas.size.minDimension * .7f
                }
                val str = initial.toString()
                val b = android.graphics.Rect()
                paint.getTextBounds(str, 0, 1, b)
                // Fit the ink inside ~60% of the circle, whatever the letter's shape.
                val fit = this@Canvas.size.minDimension * .6f / maxOf(b.width(), b.height()).coerceAtLeast(1)
                paint.textSize *= fit
                paint.getTextBounds(str, 0, 1, b)
                drawContext.canvas.nativeCanvas.drawText(str, center.x - b.exactCenterX(), center.y - b.exactCenterY(), paint)
            }
        }
    }
}
