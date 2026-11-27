package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.IOException

// The home screen's one-button tiles that need a quick choice first: Nudges (which nudge,
// then who) and Pickup (who to ask). Each is a small pop-up, gone once it's sent.

private fun adult(role: String) = role == "parent" || role == "grandparent"

/** Who can get a request from you: everyone else in the family who uses the app. */
private fun others(members: List<Member>, me: String) = members.filter { it.id != me && it.role != "dependent" }

/** One person to choose, as a wide rounded row with their picture. */
@Composable
private fun PersonRow(m: Member, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(m.name, m.emoji, m.color, 36)
        Text(m.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
    }
}

/** Nudges: choose the nudge, then slide over to choose who gets it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NudgePopup(prefs: Prefs, members: List<Member>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var nudge by remember { mutableStateOf<Nudges.Nudge?>(null) }
    val people = others(members, prefs.memberId)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(nudge?.let { "Send ${it.emoji} to" } ?: Access.name("nudges")) },
        text = {
            AnimatedContent(nudge, transitionSpec = {
                if (targetState != null) slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                else slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
            }, label = "nudge") { chosen ->
                if (chosen == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Nudges.all.filter { !it.adultsOnly || adult(prefs.role) }.forEach { n ->
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); nudge = n },
                            contentAlignment = Alignment.Center,
                        ) { Text(n.emoji, fontSize = 28.sp) }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val can = people.filter { chosen in Nudges.available(prefs.role, it.role) }
                    if (can.isEmpty()) Text("No one to send that to.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    can.forEach { m ->
                        PersonRow(m) {
                            scope.launch {
                                val msg = try {
                                    Api(prefs.serverUrl, prefs.token).request(m.id, chosen.kind, null)
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    "Sent ${chosen.emoji} to ${m.name}"
                                } catch (e: IOException) { e.message ?: "Couldn't send" }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                onDone()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        dismissButton = if (nudge != null) ({ TextButton(onClick = { nudge = null }) { Text("Back") } }) else null,
    )
}

/** Pickup: choose who to ask to come and get you (with where you are now). */
@Composable
fun PickupPopup(prefs: Prefs, members: List<Member>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var sending by remember { mutableStateOf(false) }
    // The grown-ups (they're the ones who drive); everyone else only if there are none.
    val people = others(members, prefs.memberId).let { all -> all.filter { adult(it.role) }.ifEmpty { all } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Who should pick you up?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (people.isEmpty()) Text("No one to ask yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                people.forEach { m ->
                    PersonRow(m) {
                        if (sending) return@PersonRow
                        sending = true
                        scope.launch {
                            val msg = try {
                                Api(prefs.serverUrl, prefs.token).request(m.id, "pickup", currentLocation(context))
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                "Asked ${m.name} to pick you up"
                            } catch (e: IOException) { e.message ?: "Couldn't send" }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            onDone()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
