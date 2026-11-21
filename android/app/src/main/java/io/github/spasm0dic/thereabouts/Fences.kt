package io.github.spasm0dic.thereabouts

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.IOException

/** What the fence dialog is doing: adding (at a spot, or anywhere) or editing. */
sealed class PlaceEdit {
    /** A new fence; lat/lon are null when it wasn't made from a spot on the map. */
    data class Add(val lat: Double? = null, val lon: Double? = null) : PlaceEdit()
    data class Edit(val place: Place) : PlaceEdit()
}

private val RADIUS_CHOICES = listOf(75.0, 150.0, 300.0, 600.0, 1200.0)

/**
 * Add or edit a fence: where (a spot on the map, an address search, or where you
 * are now), its name, its size and which group it belongs to.
 */
@Composable
fun PlaceDialog(
    prefs: Prefs,
    edit: PlaceEdit,
    circles: List<CircleInfo>,
    defaultCircle: String,
    canSearch: Boolean,
    onDone: (changed: Boolean) -> Unit,
    canSchedule: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val existing = (edit as? PlaceEdit.Edit)?.place
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var radius by remember { mutableDoubleStateOf(existing?.radius ?: 150.0) }
    var circleId by remember { mutableStateOf(existing?.circleId ?: defaultCircle.ifEmpty { circles.firstOrNull()?.id ?: "" }) }
    // Where the fence goes.
    val start = when (edit) {
        is PlaceEdit.Add -> if (edit.lat != null && edit.lon != null) edit.lat to edit.lon else null
        is PlaceEdit.Edit -> edit.place.lat to edit.place.lon
    }
    var spot by remember { mutableStateOf(start) }
    var where by remember {
        mutableStateOf(
            when {
                existing != null -> "Where it is now"
                start != null -> "The spot you pressed on the map"
                else -> "Not chosen yet"
            },
        )
    }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Alert hours (optional).
    var scheduleOn by remember { mutableStateOf(existing?.schedule != null) }
    var days by remember { mutableStateOf(existing?.schedule?.days ?: setOf(1, 2, 3, 4, 5)) }
    var from by remember { mutableStateOf(existing?.schedule?.from ?: "07:00") }
    var to by remember { mutableStateOf(existing?.schedule?.to ?: "17:00") }
    fun pickTime(current: String, set: (String) -> Unit) {
        val (h, m) = current.split(":").map { it.toIntOrNull() ?: 0 }
        android.app.TimePickerDialog(context, { _, hh, mm -> set("%02d:%02d".format(hh, mm)) }, h, m,
            android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    val schedule = if (scheduleOn) FenceSchedule(days, from, to, java.util.TimeZone.getDefault().id) else null

    fun run(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onDone(true)
            } catch (e: IOException) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                Toast.makeText(context, e.message ?: "Couldn't save", Toast.LENGTH_LONG).show()
                busy = false
            }
        }
    }

    fun search() {
        if (query.trim().length < 3) return
        searching = true
        scope.launch {
            results = try {
                Api(prefs.serverUrl, prefs.token).geocode(query.trim())
            } catch (e: IOException) {
                Toast.makeText(context, e.message ?: "Search didn't work", Toast.LENGTH_SHORT).show()
                null
            }
            searching = false
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Nobody will be told about arriving at or leaving it any more.") },
            confirmButton = { TextButton(onClick = { run { Api(prefs.serverUrl, prefs.token).deletePlace(existing.id) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone(false) },
        title = { Text(if (existing == null) "Add a fence" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    Text("Everyone in the group is told when someone arrives here or leaves.", style = MaterialTheme.typography.bodySmall)
                }
                // ---- where ----
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Where: $where", style = MaterialTheme.typography.bodyMedium)
                        if (canSearch) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search for an address") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { search() }),
                                trailingIcon = {
                                    TextButton(enabled = !searching && query.trim().length >= 3, onClick = { search() }) {
                                        Text(if (searching) "…" else "Search")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            results?.let { list ->
                                if (list.isEmpty()) {
                                    Text("Nothing found. Try adding the town.", style = MaterialTheme.typography.bodySmall)
                                }
                                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                    list.forEach { r ->
                                        Text(
                                            r.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                spot = r.lat to r.lon
                                                where = r.name
                                                if (name.isBlank()) name = r.name.substringBefore(",").take(40)
                                                results = null
                                            }.padding(vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val here = currentLocation(context)
                                if (here == null) {
                                    Toast.makeText(context, "Your location isn't known yet", Toast.LENGTH_SHORT).show()
                                } else {
                                    spot = here.latitude to here.longitude
                                    where = "Where you are now"
                                }
                            }
                        }) { Text("Use where I am now") }
                        if (existing == null && start == null) {
                            Text(
                                "Tip: you can also long-press the map to add a fence at a spot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    placeholder = { Text("Home, School, Work…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Size", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    RADIUS_CHOICES.forEach { r ->
                        FilterChip(
                            selected = radius == r,
                            onClick = { if (radius != r) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); radius = r },
                            label = { Text(if (r < 1000) "${r.toInt()} m" else "${r / 1000} km") },
                        )
                    }
                }
                if (canSchedule) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Only alert at certain times", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "E.g. school days, 07:00–17:00. Where people are still shows on the map.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = scheduleOn, onCheckedChange = { scheduleOn = it })
                    }
                    if (scheduleOn) {
                        val labels = listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 0 to "S")
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            labels.forEach { (d, label) ->
                                FilterChip(
                                    selected = d in days,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        days = if (d in days) days - d else days + d
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("From")
                            OutlinedButton(onClick = { pickTime(from) { from = it } }) { Text(from) }
                            Text("to")
                            OutlinedButton(onClick = { pickTime(to) { to = it } }) { Text(to) }
                        }
                        if (from == to) {
                            Text("Same start and end: all day on those days.", style = MaterialTheme.typography.bodySmall)
                        } else if (to < from) {
                            Text("Overnight: from $from until $to the next morning.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (existing == null && circles.size > 1) {
                    Text("Group", style = MaterialTheme.typography.titleSmall)
                    circles.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = circleId == c.id) { circleId = c.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = circleId == c.id, onClick = null)
                            Text(c.name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(enabled = !busy && name.isNotBlank() && circleId.isNotEmpty() && spot != null && (!scheduleOn || days.isNotEmpty()), onClick = {
                val (lat, lon) = spot ?: return@FilledTonalButton
                run {
                    val api = Api(prefs.serverUrl, prefs.token)
                    when (edit) {
                        is PlaceEdit.Add -> api.createPlace(circleId, name.trim(), lat, lon, radius, schedule, canSchedule)
                        is PlaceEdit.Edit -> api.updatePlace(edit.place.id, name.trim(), radius, lat, lon, schedule, canSchedule)
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("Delete") }
                TextButton(enabled = !busy, onClick = { onDone(false) }) { Text("Cancel") }
            }
        },
    )
}
