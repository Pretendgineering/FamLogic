package io.github.pretendgineering.famlogic

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Datebook: the family calendar. Events are Markdown notes in the vault's Calendar/
 * folder, and phones can sync them both ways over CalDAV (DAVx5, iPhone Calendar).
 */
data class CalOccurrence(val id: String, val title: String, val start: Long, val end: Long, val allDay: Boolean, val who: List<String>, val where: String, val notes: String, val recurring: Boolean,
    /** An outside calendar's name (school, holidays...) and colour; "" for the family's own events, which can be changed. */
    val calendar: String = "", val color: String = "")
data class CalData(val events: List<CalOccurrence>, val canEdit: Boolean)
data class CalEventFull(val id: String, val title: String, val start: String, val end: String, val allDay: Boolean, val who: List<String>, val where: String, val notes: String, val rrule: String)

private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

suspend fun Api.events(from: Long, to: Long): CalData {
    val o = call("GET", "/api/events?from=$from&to=$to")
    val arr = o.optJSONArray("events") ?: JSONArray()
    return CalData((0 until arr.length()).map { i ->
        arr.getJSONObject(i).let {
            CalOccurrence(it.optString("id"), it.optString("title"), it.optLong("start"), it.optLong("end"), it.optBoolean("allDay"),
                strings(it.optJSONArray("who")), it.optString("where"), it.optString("notes"), it.optBoolean("recurring"),
                it.optString("calendar"), it.optString("color"))
        }
    }, o.optBoolean("canEdit"))
}
suspend fun Api.event(id: String): CalEventFull = call("GET", "/api/events/$id").let {
    CalEventFull(it.optString("id"), it.optString("title"), it.optString("start"), it.optString("end"), it.optBoolean("allDay"),
        strings(it.optJSONArray("who")), it.optString("where"), it.optString("notes"), it.optString("rrule"))
}
suspend fun Api.saveEvent(id: String?, body: JSONObject) { call("POST", if (id == null) "/api/events" else "/api/events/$id", body) }
suspend fun Api.deleteEvent(id: String) { call("POST", "/api/events/$id/delete") }

object Datebook {
    var data by mutableStateOf<CalData?>(null)
    fun midnight(daysFromToday: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, daysFromToday)
    }.timeInMillis
    suspend fun refresh(prefs: Prefs) {
        data = runCatching { Api(prefs.serverUrl, prefs.token).events(midnight(), midnight(45)) }.getOrDefault(data)
    }
    fun timeText(o: CalOccurrence): String = if (o.allDay) "All day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(o.start))
}

private val REPEAT_CHOICES = listOf("" to "Once", "day" to "Every day", "weekdays" to "Weekdays", "week" to "Every week", "2 weeks" to "Every 2 weeks", "month" to "Every month", "year" to "Every year")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CalEventFull?>(null) }
    var adding by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<Member>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { people = Api(prefs.serverUrl, prefs.token).members(Long.MAX_VALUE / 2).members } }
    LaunchedEffect(Unit) {
        while (true) {
            Datebook.refresh(prefs)
            delay(30_000)
        }
    }
    val data = Datebook.data
    Scaffold(
        topBar = { TopAppBar(title = { Text(Access.name("calendar")) }) },
        floatingActionButton = {
            if (data?.canEdit == true) ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add an event") })
        },
    ) { padding ->
        if (data == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
            return@Scaffold
        }
        val byDay = data.events.groupBy { o -> ((maxOf(o.start, Datebook.midnight()) - Datebook.midnight()) / 86_400_000L).toInt() }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (data.events.isEmpty()) item { Text("Nothing on the calendar for the next few weeks.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            byDay.toSortedMap().forEach { (d, evs) ->
                item(key = "d$d") {
                    Text(
                        when (d) { 0 -> "Today"; 1 -> "Tomorrow"; else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(Datebook.midnight(d))) },
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(evs, key = { "${it.id}-${it.start}-${it.title}" }) { o ->
                    ElevatedCard(
                        onClick = { if (data.canEdit && o.calendar.isEmpty()) scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).event(o.id) }.onSuccess { editing = it } } },
                        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                            // An outside calendar's events: a stripe in its colour (they're read-only).
                            if (o.calendar.isNotEmpty()) androidx.compose.foundation.layout.Box(Modifier.width(4.dp).fillMaxHeight()
                                .background(runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(o.color)) }.getOrDefault(MaterialTheme.colorScheme.outline)))
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Datebook.timeText(o), Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge, color = Accent2)
                            Column(Modifier.weight(1f)) {
                                Text(o.title, style = MaterialTheme.typography.titleMedium)
                                val sub = listOfNotNull(o.calendar.ifEmpty { null }, o.where.ifEmpty { null }, o.who.mapNotNull { id -> people.find { it.id == id }?.name }.joinToString(", ").ifEmpty { null }).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) EventDialog(prefs, people, editing) {
        adding = false; editing = null
        scope.launch { Datebook.refresh(prefs) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDialog(prefs: Prefs, people: List<Member>, existing: CalEventFull?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var day by remember { mutableStateOf(existing?.start?.take(10) ?: today) }
    var from by remember { mutableStateOf(existing?.start?.takeIf { it.length > 10 }?.substring(11, 16) ?: "17:00") }
    var until by remember { mutableStateOf(existing?.end?.takeIf { it.length > 10 }?.substring(11, 16) ?: "18:00") }
    var where by remember { mutableStateOf(existing?.where ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var who by remember { mutableStateOf(existing?.who?.toSet() ?: emptySet()) }
    var every by remember { mutableStateOf(when (existing?.rrule) { null, "" -> ""; "FREQ=DAILY" -> "day"; "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" -> "weekdays"; "FREQ=WEEKLY" -> "week"; "FREQ=WEEKLY;INTERVAL=2" -> "2 weeks"; "FREQ=MONTHLY" -> "month"; "FREQ=YEARLY" -> "year"; else -> "keep" }) }
    fun pickTime(cur: String, set: (String) -> Unit) {
        val (h, m) = cur.split(":").map { it.toInt() }
        TimePickerDialog(context, { _, hh, mm -> set("%02d:%02d".format(hh, mm)) }, h, m, android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Add an event" else "Change event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val p = day.split("-").map { it.toInt() }
                        DatePickerDialog(context, { _, y, m, d -> day = "%04d-%02d-%02d".format(y, m + 1, d) }, p[0], p[1] - 1, p[2]).show()
                    }) { Text(day) }
                    Spacer(Modifier.weight(1f))
                    Text("All day ")
                    Switch(allDay, { allDay = it }, colors = accentSwitch())
                }
                if (!allDay) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From ")
                    TextButton(onClick = { pickTime(from) { from = it } }) { Text(from) }
                    Text(" to ")
                    TextButton(onClick = { pickTime(until) { until = it } }) { Text(until) }
                }
                OutlinedTextField(where, { where = it.take(100) }, label = { Text("Where (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Repeats", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REPEAT_CHOICES.forEach { (id, label) -> FilterChip(selected = every == id, onClick = { every = id }, label = { Text(label) }) }
                    if (every == "keep") FilterChip(selected = true, onClick = {}, label = { Text("As set elsewhere") })
                }
                Text("Who", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.forEach { p -> FilterChip(selected = p.id in who, onClick = { who = if (p.id in who) who - p.id else who + p.id }, label = { Text(p.name) }) }
                }
                OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
                if (existing != null) TextButton(onClick = {
                    scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).deleteEvent(existing.id) }; onDone() }
                }) { Text("Delete this event", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && (allDay || until > from), onClick = {
                val body = JSONObject().put("title", title.trim()).put("where", where.trim()).put("notes", notes.trim()).put("who", JSONArray(who.toList()))
                    .put("start", if (allDay) day else "${day}T$from").put("end", if (allDay) "" else "${day}T$until")
                if (every != "keep") body.put("every", every)
                scope.launch {
                    runCatching { Api(prefs.serverUrl, prefs.token).saveEvent(existing?.id, body) }
                        .onSuccess { onDone() }.onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** The home tile: what's next (a short agenda when big enough). */
@Composable
fun CalendarTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            Datebook.refresh(prefs)
            delay(5 * 60_000)
        }
    }
    val now = System.currentTimeMillis()
    val next = Datebook.data?.events?.filter { it.end > now }.orEmpty()
    Column(Modifier.fillMaxWidth().height(height).clickable(onClickLabel = "Open ${Access.name("calendar")}", onClick = onOpen).padding(14.dp)) {
        TileHead(wide, big, Access.name("calendar"), icon = { Icon(tileIcon("calendar"), contentDescription = null, tint = Accent2, modifier = Modifier.size(if (big) 32.dp else 26.dp)) })
        if (next.isEmpty()) {
            Spacer(Modifier.weight(1f)); Text("Nothing coming up", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column
        }
        if (tall >= 2 || wide >= 2) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                val fit = (maxHeight / 40.dp).toInt().coerceAtLeast(1)
                Column {
                    next.take(fit).forEach { o ->
                        Column(Modifier.height(40.dp)) {
                            val day = when (((maxOf(o.start, Datebook.midnight()) - Datebook.midnight()) / 86_400_000L).toInt()) {
                                0 -> "Today"; 1 -> "Tomorrow"; else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(o.start))
                            }
                            Text(o.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text("$day · ${Datebook.timeText(o)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text(next.first().title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(Datebook.timeText(next.first()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
