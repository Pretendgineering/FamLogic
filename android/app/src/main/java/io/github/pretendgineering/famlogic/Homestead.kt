package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Homestead: the home's profile. Tick what the home has and which days the bins go
 * out; the server keeps the matching upkeep as ordinary chores (for anyone) and bin
 * days on the calendar. Each chore's history is the log. Parents set it up.
 */
object Homestead {
    /** The Homestead screen is open (from its home tile). */
    var open by mutableStateOf(false)
    var data by mutableStateOf<HomeData?>(null)
    suspend fun refresh(prefs: Prefs) { data = runCatching { Api(prefs.serverUrl, prefs.token).home() }.getOrDefault(data) }
}

data class HomeFeature(val key: String, val name: String, val jobs: List<Pair<String, String>>)
data class HomeJob(val id: String, val title: String, val every: String, val due: String, val lastDone: String)
data class HomeData(
    val tenure: String, val features: List<String>, val bins: String, val recycling: String, val recyclingEvery: String,
    val catalog: List<HomeFeature>, val jobs: List<HomeJob>, val canEdit: Boolean,
)

suspend fun Api.home(): HomeData {
    val o = call("GET", "/api/home")
    val p = o.optJSONObject("profile") ?: JSONObject()
    fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
    val cat = o.optJSONArray("catalog") ?: JSONArray()
    val jobs = o.optJSONArray("jobs") ?: JSONArray()
    return HomeData(
        p.optString("tenure"), strs(p.optJSONArray("features")), p.optString("bins"), p.optString("recycling"), p.optString("recyclingEvery", "week"),
        (0 until cat.length()).map { i ->
            cat.getJSONObject(i).let { f ->
                val js = f.optJSONArray("jobs") ?: JSONArray()
                HomeFeature(f.optString("key"), f.optString("name"), (0 until js.length()).map { js.getJSONObject(it).let { j -> j.optString("title") to j.optString("every") } })
            }
        },
        (0 until jobs.length()).map { i ->
            jobs.getJSONObject(i).let { c ->
                val done = c.optJSONArray("done")
                HomeJob(c.optString("id"), c.optString("title"), c.optString("every"), c.optString("due"),
                    if (done != null && done.length() > 0) done.getJSONObject(0).optString("date") else "")
            }
        }.sortedBy { it.due },
        o.optBoolean("canEdit"),
    )
}

private val DAY_CHOICES = listOf("" to "None", "mon" to "Mon", "tue" to "Tue", "wed" to "Wed", "thu" to "Thu", "fri" to "Fri", "sat" to "Sat", "sun" to "Sun")

/** "every 3 months", "every Thursday", "every year". */
fun everyText(e: String): String = when (e) {
    "week" -> "every week"; "month" -> "every month"; "2 weeks" -> "every 2 weeks"; "12 months" -> "every year"; "36 months" -> "every 3 years"
    else -> DAY_CHOICES.find { it.first == e && e.isNotEmpty() }?.let { "every " + it.second } ?: "every $e"
}

@Composable
fun HomesteadDialog(prefs: Prefs) {
    Dialog(onDismissRequest = { Homestead.open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HomesteadScreen(prefs) { Homestead.open = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomesteadScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Homestead.refresh(prefs) }
    val d = Homestead.data
    // The profile being changed (parents): starts from what's saved.
    var features by remember(d) { mutableStateOf(d?.features?.toSet() ?: emptySet()) }
    var bins by remember(d) { mutableStateOf(d?.bins ?: "") }
    var recycling by remember(d) { mutableStateOf(d?.recycling ?: "") }
    var fortnightly by remember(d) { mutableStateOf(d?.recyclingEvery == "2 weeks") }
    var saving by remember { mutableStateOf(false) }
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    Scaffold(topBar = {
        TopAppBar(title = { Text(Access.name("homestead")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (d == null) { Text("Loading…", Modifier.padding(pad).padding(16.dp)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Upkeep for the home", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            if (d.jobs.isEmpty()) item { Text(if (d.canEdit) "Tick what your home has below, and its upkeep appears here, in Upkeep and on the calendar." else "Nothing set up yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(d.jobs, key = { it.id }) { j ->
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(everyText(j.every), if (j.due <= today) "due now" else "next ${j.due}", if (j.lastDone.isNotEmpty()) "last done ${j.lastDone}" else "not done yet").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = {
                            scope.launch {
                                runCatching { Api(prefs.serverUrl, prefs.token).choreDone(j.id) }
                                    .onSuccess { Toast.makeText(context, "Done: ${j.title}", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                Homestead.refresh(prefs); Upkeep.refresh(prefs)
                            }
                        }) { Text("Done") }
                    }
                }
            }
            if (d.canEdit) {
                item { Text("Your home", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                item {
                    Column {
                        Text("Bins go out", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DAY_CHOICES.forEach { (k, t) -> FilterChip(selected = bins == k, onClick = { bins = k }, label = { Text(t) }) } }
                        Text("Recycling", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DAY_CHOICES.forEach { (k, t) -> FilterChip(selected = recycling == k, onClick = { recycling = k }, label = { Text(t) }) } }
                        if (recycling.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Every other week", Modifier.weight(1f)); Switch(fortnightly, { fortnightly = it }, colors = accentSwitch())
                        }
                    }
                }
                item { Text("What the home has (each adds its upkeep as a chore for anyone):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(d.catalog, key = { "f" + it.key }) { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text(f.jobs.joinToString("; ") { "${it.first} ${everyText(it.second)}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(f.key in features, { on -> features = if (on) features + f.key else features - f.key }, colors = accentSwitch())
                    }
                }
                item {
                    TextButton(enabled = !saving, onClick = {
                        saving = true
                        val body = JSONObject().put("tenure", d.tenure).put("features", JSONArray(d.catalog.map { it.key }.filter { it in features }))
                            .put("bins", bins).put("recycling", recycling).put("recyclingEvery", if (fortnightly) "2 weeks" else "week")
                        scope.launch {
                            runCatching { Api(prefs.serverUrl, prefs.token).call("PUT", "/api/home", body) }
                                .onSuccess { Toast.makeText(context, "Saved; the chores are up to date", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                            saving = false
                            Homestead.refresh(prefs); Upkeep.refresh(prefs); Datebook.refresh(prefs)
                        }
                    }) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    }
}
