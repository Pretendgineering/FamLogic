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
fun FamilyCrest(size: Int, modifier: Modifier = Modifier) {
    val name = Prefs(LocalContext.current).familyName
    val initial = familyInitial(name)
    Box(
        modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .border((size / 22f).coerceAtLeast(1.5f).dp, GOLD, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initial == null) {
            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size((size / 2).dp))
        } else {
            Text(
                initial.toString(),
                style = TextStyle(fontFamily = Fraktur, fontSize = (size * 0.62f).sp, color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center),
                modifier = Modifier.padding(bottom = (size * 0.04f).dp),
            )
        }
    }
}
