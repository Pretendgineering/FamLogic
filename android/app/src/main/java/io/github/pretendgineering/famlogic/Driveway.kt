package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * Driveway: the family's vehicles, a tab each: the numbers (odometer, economy, fuel
 * spend), the latest fill-ups and services, and a quick fill-up. The console has the
 * full page; Steward logs a fill-up from a sentence.
 */

object Driveway {
    var open by mutableStateOf(false)
}

data class Fill(val date: String, val odometer: Double, val amount: Double, val cost: Double, val full: Boolean, val economy: Double)
data class Service(val date: String, val what: String, val cost: Double)
data class Vehicle(
    val id: String, val name: String, val odometer: Double, val avgEconomy: Double, val costPerDist: Double, val spend30: Double,
    val fuel: List<Fill>, val service: List<Service>, val reminders: List<Pair<String, String>>,
)
data class Vehicles(val list: List<Vehicle>, val units: String, val canEdit: Boolean)

suspend fun Api.vehicles(units: String): Vehicles {
    val o = call("GET", "/api/vehicles?units=$units")
    val a = o.optJSONArray("vehicles") ?: JSONArray()
    fun arr(v: JSONObject, k: String) = v.optJSONArray(k) ?: JSONArray()
    return Vehicles((0 until a.length()).map { i ->
        val v = a.getJSONObject(i)
        Vehicle(
            v.optString("id"), v.optString("name"), v.optDouble("odometer", 0.0), v.optDouble("avgEconomy", 0.0), v.optDouble("costPerDist", 0.0), v.optDouble("spend30", 0.0),
            arr(v, "fuel").let { f -> (0 until f.length()).map { f.getJSONObject(it).let { e -> Fill(e.optString("date"), e.optDouble("odometer", 0.0), e.optDouble("amount", 0.0), e.optDouble("cost", 0.0), e.optBoolean("full"), e.optDouble("economy", 0.0)) } } },
            arr(v, "service").let { f -> (0 until f.length()).map { f.getJSONObject(it).let { e -> Service(e.optString("date"), e.optString("what"), e.optDouble("cost", 0.0)) } } },
            arr(v, "reminders").let { f -> (0 until f.length()).map { f.getJSONObject(it).let { e -> e.optString("title") to e.optString("due") } } },
        )
    }, o.optString("units", "mi"), o.optBoolean("canEdit"))
}

/** Miles and gallons in the US (and a couple of others), km and litres elsewhere, unless the family chose. */
private fun localUnits(): String = java.util.Locale.getDefault().country.let { if (it in setOf("US", "LR", "MM")) "mi" else "km" }

@Composable
fun DrivewayDialog(prefs: Prefs) {
    Dialog(onDismissRequest = { Driveway.open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DrivewayScreen(prefs) { Driveway.open = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivewayScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Vehicles?>(null) }
    var pick by rememberSaveable { mutableStateOf("") }
    var filling by remember { mutableStateOf(false) }
    val api = { Api(prefs.serverUrl, prefs.token) }
    suspend fun load() { runCatching { data = api().vehicles(localUnits()) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(Unit) { load() }
    val d = data
    val v = d?.list?.find { it.id == pick } ?: d?.list?.firstOrNull()
    val mi = d?.units != "km"
    val dist = if (mi) "mi" else "km"
    val vol = if (mi) "gal" else "L"
    val econ = if (mi) "mpg" else "L/100 km"
    Scaffold(topBar = {
        TopAppBar(title = { Text(Access.name("garage")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }, floatingActionButton = {
        if (v != null && d?.canEdit == true) ExtendedFloatingActionButton(onClick = { filling = true }, modifier = Modifier.pageFab()) {
            Icon(Icons.Filled.Add, contentDescription = null); Text("Fill-up", Modifier.padding(start = 8.dp))
        }
    }) { pad ->
        if (d == null) { Spacer(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.list.forEach { x -> FilterChip(selected = x.id == v?.id, onClick = { pick = x.id }, label = { Text(x.name) }) }
                }
            }
            if (v == null) {
                item { Text("No vehicles yet. Add them in the web console (Driveway), or tell Steward.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    @Composable fun stat(big: String, label: String, m: Modifier) = ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = m) {
                        Column(Modifier.padding(12.dp)) {
                            Text(big.ifEmpty { "—" }, style = MaterialTheme.typography.headlineSmall, color = Accent2, fontWeight = FontWeight.Bold)
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    stat(if (v.avgEconomy > 0) "${v.avgEconomy}" else "", "average $econ", Modifier.weight(1f))
                    stat(if (v.odometer > 0) "%,d".format(v.odometer.toLong()) else "", "$dist on the clock", Modifier.weight(1f))
                    stat(if (v.spend30 > 0) "%.0f".format(v.spend30) else "", "fuel, 30 days", Modifier.weight(1f))
                }
            }
            if (v.reminders.isNotEmpty()) {
                item { Text("Coming round", style = MaterialTheme.typography.titleMedium) }
                items(v.reminders) { (t, due) -> Text("$t · next $due", style = MaterialTheme.typography.bodyLarge) }
            }
            item { Text("Fill-ups", style = MaterialTheme.typography.titleMedium) }
            if (v.fuel.isEmpty()) item { Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(v.fuel.reversed().take(12)) { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${f.amount} $vol" + if (f.cost > 0) " · %.2f".format(f.cost) else "", style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(f.date, if (f.odometer > 0) "%,d $dist".format(f.odometer.toLong()) else null, if (!f.full) "part fill" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (f.economy > 0) Text("${f.economy} $econ", color = Accent2, fontWeight = FontWeight.SemiBold)
                }
            }
            if (v.service.isNotEmpty()) {
                item { Text("Services", style = MaterialTheme.typography.titleMedium) }
                items(v.service.take(8)) { s -> Text("${s.what} · ${s.date}" + if (s.cost > 0) " · %.2f".format(s.cost) else "", style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    if (filling && v != null) {
        var odo by remember { mutableStateOf(if (v.odometer > 0) v.odometer.toLong().toString() else "") }
        var amt by remember { mutableStateOf("") }
        var cost by remember { mutableStateOf("") }
        var full by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { filling = false },
            title = { Text("Fill-up: ${v.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(odo, { odo = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Odometer ($dist)") }, singleLine = true)
                    OutlinedTextField(amt, { amt = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Fuel ($vol)") }, singleLine = true)
                    OutlinedTextField(cost, { cost = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Cost") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(full, { full = it }, colors = accentSwitch()); Text("  Filled to the top") }
                }
            },
            confirmButton = {
                TextButton(enabled = (amt.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                    filling = false
                    val body = JSONObject().put("odometer", odo.toDoubleOrNull() ?: 0.0).put("amount", amt.toDouble()).put("cost", cost.toDoubleOrNull() ?: 0.0).put("full", full)
                    scope.launch {
                        runCatching { api().call("POST", "/api/vehicles/${v.id}/fuel", body) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        load()
                    }
                }) { Text("Log it") }
            },
            dismissButton = { TextButton(onClick = { filling = false }) { Text("Cancel") } },
        )
    }
}
