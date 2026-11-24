package io.github.pretendgineering.famlogic

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
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
                                "pickup" -> "Asked ${m.name} to pick you up"
                                else -> "Sent"
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
                if (canNudges && Access.can("nudges")) {
                    androidx.compose.material3.OutlinedButton(onClick = { ask("where") }, modifier = Modifier.fillMaxWidth()) { Text("Where are you?") }
                    // Nudges: one tap sends ${m.name} a little picture.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Nudges.available(prefs.role, m.role).forEach { n ->
                            androidx.compose.material3.OutlinedButton(
                                onClick = { ask(n.kind) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                            ) { Text(n.emoji, fontSize = if (Access.big) 26.sp else 20.sp) }
                        }
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
                    else -> TripTimeline(list, onShowTrip)
                }
            }
        }
    }
}

/** "🚗", "🚲" or "🚶", from how fast the trip went on average. */
fun Trip.mode(): String = when {
    avgSpeed >= 6.5 -> "🚗"
    avgSpeed >= 2.2 -> "🚲"
    else -> "🚶"
}

/** A trip as one line of text, for sharing: "🚗 Home → School · 8:05–8:27 · 6.2 km". */
fun Trip.shareText(context: android.content.Context, who: String): String {
    val times = DateUtils.formatDateTime(context, start, DateUtils.FORMAT_SHOW_TIME) +
        if (ongoing) " (still going)" else "–" + DateUtils.formatDateTime(context, end, DateUtils.FORMAT_SHOW_TIME)
    return "${mode()} $who: ${title()} · $times · ${Units.distance(distance)} in ${Units.duration(end - start)}"
}

/**
 * Someone's day as a timeline: where they stopped (and for how long) and the trips
 * between, newest first, one day at a time. Tap a trip to see its route on the map.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TripTimeline(trips: List<Trip>, onShowTrip: (Trip) -> Unit) {
    val context = LocalContext.current
    val zone = java.util.TimeZone.getDefault()
    fun dayOf(t: Long): Long { val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }; return cal.timeInMillis }
    val days = trips.map { dayOf(it.start) }.distinct().sortedDescending()
    var day by remember(trips) { mutableStateOf(days.first()) }
    fun dayName(d: Long) = when {
        DateUtils.isToday(d) -> "Today"
        DateUtils.isToday(d + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateUtils.formatDateTime(context, d, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.take(7).forEach { d ->
            FilterChip(selected = d == day, onClick = { day = d }, label = { Text(dayName(d)) })
        }
    }
    val today = trips.filter { dayOf(it.start) == day }.sortedByDescending { it.start }
    val line = MaterialTheme.colorScheme.outlineVariant
    fun time(t: Long) = DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_TIME)
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        today.forEachIndexed { i, t ->
            // The stop after this trip (until the next trip, or now).
            val next = today.getOrNull(i - 1)
            if (!t.ongoing) item(key = "stop${t.start}") {
                val until = next?.start
                TimelineRow(dot = true, line = line) {
                    Text(t.to.ifEmpty { t.toNear.let { if (it.isNotEmpty()) "Near $it" else "A stop" } }, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (until != null) "${time(t.end)}–${time(until)} · ${Units.duration(until - t.end)}" else "Since ${time(t.end)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "trip${t.start}") {
                TimelineRow(dot = false, line = line, onClick = { onShowTrip(t) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.mode(), modifier = Modifier.padding(end = 6.dp))
                        Text(t.title(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (t.ongoing) Text("Under way", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "${time(t.start)}${if (t.ongoing) "" else "–" + time(t.end)} · ${Units.distance(t.distance)} · ${Units.duration(t.end - t.start)} · top ${Units.speed(t.topSpeed)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Where the day started.
        today.lastOrNull()?.let { first ->
            item(key = "start") {
                TimelineRow(dot = true, line = line, last = true) {
                    Text(first.from.ifEmpty { first.fromNear.let { if (it.isNotEmpty()) "Near $it" else "Start" } }, fontWeight = FontWeight.SemiBold)
                    Text("Until ${time(first.start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One step of the timeline: a dot (a stop) or a stretch of line (a trip), with its text beside it. */
@Composable
private fun TimelineRow(dot: Boolean, line: androidx.compose.ui.graphics.Color, last: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show on the map", onClick = onClick) else Modifier),
    ) {
        androidx.compose.foundation.Canvas(Modifier.width(28.dp).fillMaxHeight()) {
            val x = size.width / 2
            if (dot) {
                drawLine(line, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, if (last) size.height / 2 else size.height), 3.dp.toPx())
                drawCircle(accent, 7.dp.toPx(), androidx.compose.ui.geometry.Offset(x, size.height / 2))
                drawCircle(androidx.compose.ui.graphics.Color.White, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(x, size.height / 2))
            } else {
                drawLine(accent.copy(alpha = .7f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 4.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) { content() }
    }
}
