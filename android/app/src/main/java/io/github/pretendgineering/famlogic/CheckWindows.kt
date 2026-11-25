package io.github.pretendgineering.famlogic

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * Check-in windows for a child (parents only): "at School between 8:00 and 8:45 on
 * weekdays". The child is nudged to check in at the start; if by the end they
 * haven't arrived or checked in, the parents are told.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckWindows(prefs: Prefs, child: Member) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember(child.id) { mutableStateOf<List<CheckPlan>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    val api = { Api(prefs.serverUrl, prefs.token) }
    val fences = prefs.fenceNames
    LaunchedEffect(child.id) { plans = runCatching { api().checkPlans() }.getOrDefault(emptyList()).filter { it.memberId == child.id } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Check-in times", style = MaterialTheme.typography.titleSmall)
        plans.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val days = if (p.days.sorted() == listOf(1, 2, 3, 4, 5)) "Weekdays" else p.days.sorted().joinToString(" ") { DAY_NAMES[it] }
                Text("At ${fences[p.placeId] ?: "a fence"} by ${p.until} · $days", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api().deleteCheckPlan(p.id) }
                        plans = plans - p
                    }
                }) { Text("Remove") }
            }
        }
        if (fences.isEmpty()) Text("Add a fence first (like Lessons or Practice) to set a check-in time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else TextButton(onClick = { adding = true }) { Text("Add a check-in time") }
    }
    if (adding) {
        var place by remember { mutableStateOf(fences.keys.first()) }
        var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
        var from by remember { mutableStateOf("08:00") }
        var until by remember { mutableStateOf("08:45") }
        fun pick(current: String, set: (String) -> Unit) {
            val (h, m) = current.split(":").map { it.toInt() }
            TimePickerDialog(context, { _, hh, mm -> set("%02d:%02d".format(hh, mm)) }, h, m, true).show()
        }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Check-in time for ${child.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Where", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fences.forEach { (id, name) -> FilterChip(selected = place == id, onClick = { place = id }, label = { Text(name) }) }
                    }
                    Text("Days", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAY_NAMES.forEachIndexed { i, d -> FilterChip(selected = i in days, onClick = { days = if (i in days) days - i else days + i }, label = { Text(d) }) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Between ", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { pick(from) { from = it } }) { Text(from) }
                        Text(" and ")
                        TextButton(onClick = { pick(until) { until = it } }) { Text(until) }
                    }
                    Text("${child.name} gets a reminder at $from. If they haven't arrived or checked in by $until, you're told.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = days.isNotEmpty() && until > from, onClick = {
                    adding = false
                    scope.launch {
                        runCatching { api().addCheckPlan(child.id, place, days.sorted(), from, until) }
                            .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                        plans = runCatching { api().checkPlans() }.getOrDefault(plans).filter { it.memberId == child.id }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
