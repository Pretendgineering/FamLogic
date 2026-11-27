package io.github.pretendgineering.famlogic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Younger children have no settings at all (a parent still gets in by holding the family
// name). The one thing they can change is the colours: a paint-palette button in the top
// bar opens a list of bars, each bar painted in its theme's own main colour.

/** The top bar's colour button for younger children. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KidColourButton(prefs: Prefs) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        if (Access.pictures) PalettePicture(30) else Icon(Icons.Filled.Palette, contentDescription = "Colours", tint = Accent2)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        val haptics = LocalHapticFeedback.current
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Colours", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
            Palette.entries.forEach { p ->
                val on = ThemeState.palette == p
                val ink = Color(p.swatchInk)
                Row(
                    Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color(p.swatch))
                        // A faint edge on every bar, so a black (or near-background) bar still reads as a bar.
                        .border(if (on) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (on) 1f else .18f), RoundedCornerShape(18.dp))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            prefs.appTheme = p.id
                            ThemeState.palette = p
                        }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.title, Modifier.weight(1f), color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (on) Icon(Icons.Filled.Check, contentDescription = "In use", tint = ink, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** A painter's palette with blobs of colour, for the picture icons. */
@Composable
private fun PalettePicture(size: Int) = Canvas(Modifier.size(size.dp)) {
    val s = this.size.minDimension
    drawCircle(Color(0xFFE8C28E), s * .46f, Offset(s * .5f, s * .5f))
    drawCircle(Color(0x33000000), s * .46f, Offset(s * .5f, s * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(s * .04f))
    drawCircle(Color.White, s * .09f, Offset(s * .66f, s * .7f))
    listOf(Color(0xFFE5533D) to Offset(.3f, .34f), Color(0xFF59B7F0) to Offset(.52f, .24f), Color(0xFF3DBE6A) to Offset(.72f, .38f), Color(0xFFFFB020) to Offset(.3f, .62f))
        .forEach { (c, o) -> drawCircle(c, s * .1f, Offset(s * o.x, s * o.y)) }
}
