package io.github.pretendgineering.famlogic

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * Opens when you tap someone: directions, whether you get alerts about them,
 * and their trips (if they keep them). Tapping a trip draws its route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(
    prefs: Prefs,
    m: Member,
    isMe: Boolean,
    status: String,
    canTrips: Boolean,
    canRequests: Boolean = false,
    canNudges: Boolean = false,
    onDirections: () -> Unit,
    onShowTrip: (Trip) -> Unit,
    onDismiss: () -> Unit,
) {
    var muted by remember(m.id) { mutableStateOf(m.id in prefs.mutedPeople) }
    var trips by remember(m.id) { mutableStateOf<List<Trip>?>(null) }
    var error by remember(m.id) { mutableStateOf<String?>(null) }
    if (canTrips && m.trips) {
        LaunchedEffect(m.id) {
            try {
                val api = Api(prefs.serverUrl, prefs.token)
                trips = api.trips(m.id)
                // Street names are looked up on the server in the background: ask once more shortly.
                if (trips!!.any { it.from.isEmpty() && it.fromNear.isEmpty() || !it.ongoing && it.to.isEmpty() && it.toNear.isEmpty() }) {
                    kotlinx.coroutines.delay(4_000)
                    trips = api.trips(m.id)
                }
            } catch (e: IOException) {
                error = e.message ?: "Couldn't load trips"
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name, m.emoji, m.color, 52)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(if (isMe) "${m.name} (you)" else m.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isMe && m.loc != null && !m.paused) {
                    FilledTonalButton(onClick = onDirections) { Text("Directions") }
                }
            }
            if (!isMe && canRequests) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val context = LocalContext.current
                val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                fun ask(kind: String) {
                    scope.launch {
                        val msg = try {
                            // Only "pick me up" needs a fresh location; nudges use your last shared one.
                            Api(prefs.serverUrl, prefs.token).request(m.id, kind, if (kind == "pickup") currentLocation(context) else null)
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                            when (kind) {
                                "call" -> "Asked ${m.name} to call you"
                                "where" -> "Asked ${m.name} where they are"
                                "heart", "thumbs" -> "Sent"
                                else -> "Asked ${m.name} to pick you up"
                            }
                        } catch (e: IOException) {
                            e.message ?: "Couldn't send"
                        }
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { ask("pickup") }, modifier = Modifier.weight(1f)) { Text("Pick me up") }
                    androidx.compose.material3.OutlinedButton(onClick = { ask("call") }, modifier = Modifier.weight(1f)) { Text("Call me") }
                }
                if (canNudges) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedButton(onClick = { ask("where") }, modifier = Modifier.weight(1f)) { Text("Where are you?") }
                        androidx.compose.material3.OutlinedButton(onClick = { ask("heart") }) { Text("❤️") }
                        androidx.compose.material3.OutlinedButton(onClick = { ask("thumbs") }) { Text("👍") }
                    }
                }
            }
            if (!isMe) {
                SwitchRow(
                    title = "Alerts about ${m.name}",
                    detail = if (muted) "Muted on this phone (an SOS still gets through)" else "Arrivals, departures, check-ins and battery",
                    checked = !muted,
                    onChange = { on ->
                        muted = !on
                        prefs.mutedPeople = if (on) prefs.mutedPeople - m.id else prefs.mutedPeople + m.id
                    },
                )
            }
            if (canTrips) {
                Text("Trips", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val list = trips
                when {
                    !m.trips -> Text(
                        if (isMe) "Turn on trips in Settings → Trail & trips to keep yours." else "${m.name} doesn't keep trips.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    list == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    list.isEmpty() -> Text("No trips yet.", style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.start }) { t -> TripRow(t) { onShowTrip(t) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(t: Trip, onClick: () -> Unit) {
    val context = LocalContext.current
    val day = when {
        DateUtils.isToday(t.start) -> "Today"
        DateUtils.isToday(t.start + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateUtils.formatDateTime(context, t.start, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    }
    val times = DateUtils.formatDateTime(context, t.start, DateUtils.FORMAT_SHOW_TIME) +
        if (t.ongoing) "" else "–" + DateUtils.formatDateTime(context, t.end, DateUtils.FORMAT_SHOW_TIME)
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (t.ongoing) Text("Under way", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "$day $times · ${Units.duration(t.end - t.start)} · ${Units.distance(t.distance)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Average ${Units.speed(t.avgSpeed)} · top ${Units.speed(t.topSpeed)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
