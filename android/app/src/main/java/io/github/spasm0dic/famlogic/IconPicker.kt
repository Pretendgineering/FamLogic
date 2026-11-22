package io.github.spasm0dic.famlogic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours to pick for your circle (the same list as the dashboard). */
val ICON_COLORS = listOf(
    "#e6194b", "#f58231", "#e0a100", "#3cb44b", "#469990", "#0fa3c2",
    "#4363d8", "#6e48d6", "#911eb4", "#f032e6", "#9a6324", "#607d8b",
)

/** A hand-picked set of emoji, by theme. "" means "just my initial". */
val ICON_EMOJI = listOf(
    "Faces" to "😀 😎 🤓 🥳 😇 🤠 🙂 😺 👻 🤖 👽 🎃",
    "People" to "👩 👨 🧑 👧 👦 👶 👵 👴 🧔 👸 🤴 🦸",
    "Animals" to "🐶 🐱 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🦉 🦄 🐝 🦋 🐢 🐙 🐬 🐳 🦈 🦖",
    "Nature" to "🌸 🌻 🌵 🌲 🍀 🍁 🌈 ⭐ 🌙 ☀️ ⚡ ❄️ 🔥 🌊",
    "Food" to "🍎 🍓 🍉 🍕 🍔 🌮 🍩 🍪 🧁 ☕ 🍦 🥑",
    "Activities" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🎮 🎸 🎨 📚 🎣 🏄 🚴 🧗 🏕️ 🎯",
    "Travel" to "🚗 🚙 🏍️ 🚲 🛴 🚌 🚂 ✈️ 🚀 ⛵ 🏠 🏫",
    "Symbols" to "❤️ 🧡 💛 💚 💙 💜 🖤 💎 👑 🎵 🍄 🌟",
).map { (title, list) -> title to list.split(" ") }

/**
 * Pick an emoji and a colour for your map pin. Nothing to type: tap one of each,
 * see the preview, then Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerDialog(name: String, emoji: String, color: String, onSave: (emoji: String, color: String) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pickedEmoji by remember { mutableStateOf(emoji) }
    var pickedColor by remember { mutableStateOf(color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Avatar(name, pickedEmoji, pickedColor, 64)
                Text("Your icon", Modifier.padding(top = 8.dp))
            }
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(44.dp),
                modifier = Modifier.heightIn(max = 380.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Section("Colour") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ICON_COLORS.forEach { c ->
                            val selected = c.equals(pickedColor, ignoreCase = true)
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Color(parseColor(c)))
                                    .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), CircleShape) else Modifier)
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        pickedColor = c
                                    }
                                    .semantics { contentDescription = "Colour $c" },
                            )
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Section("Icon") }
                item {
                    Cell(selected = pickedEmoji.isEmpty(), onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); pickedEmoji = "" }) {
                        Text(initialOf(name), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
                ICON_EMOJI.forEach { (title, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Section(title) }
                    items(list) { e ->
                        Cell(selected = pickedEmoji == e, onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); pickedEmoji = e }) {
                            Text(e, fontSize = 24.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pickedEmoji != emoji || !pickedColor.equals(color, ignoreCase = true), onClick = { onSave(pickedEmoji, pickedColor) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun Cell(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Someone's circle: their emoji (or initial) on their colour. */
@Composable
fun Avatar(name: String, emoji: String, color: String, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(parseColor(color))),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji.isNotEmpty()) Text(emoji, fontSize = (size * 0.5).sp)
        else Text(initialOf(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.42).sp)
    }
}
