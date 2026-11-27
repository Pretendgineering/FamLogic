package io.github.pretendgineering.famlogic

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ---- Rhythms: routines that come round, and which one is on now.

data class Routine(val list: FamilyList, val starts: String, val days: List<String>)
data class RoutinesData(val routines: List<Routine>, val now: Map<String, String>, val canEdit: Boolean)

suspend fun Api.routines(): RoutinesData {
    val o = call("GET", "/api/routines")
    val arr = o.optJSONArray("routines") ?: JSONArray()
    val now = o.optJSONObject("now")
    return RoutinesData(
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val items = r.optJSONArray("items") ?: JSONArray()
            val days = r.optJSONArray("days") ?: JSONArray()
            val who = r.optJSONArray("who") ?: JSONArray()
            Routine(
                FamilyList(r.optString("id"), r.optString("title"), "routine", (0 until who.length()).map { who.getString(it) },
                    (0 until items.length()).map { j -> items.getJSONObject(j).let { ListItem(it.optString("id"), it.optString("text"), it.optBoolean("done"), "", "", it.optString("section")) } }),
                r.optString("starts"), (0 until days.length()).map { days.getString(it) },
            )
        },
        now?.keys()?.asSequence()?.associateWith { now.optString(it) } ?: emptyMap(),
        o.optBoolean("canEdit"),
    )
}

object Rhythms {
    var data by mutableStateOf<RoutinesData?>(null)
    suspend fun refresh(prefs: Prefs) { data = runCatching { Api(prefs.serverUrl, prefs.token).routines() }.getOrDefault(data) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<Member>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { people = Api(prefs.serverUrl, prefs.token).members(Long.MAX_VALUE / 2).members } }
    LaunchedEffect(Unit) { while (true) { Rhythms.refresh(prefs); delay(30_000) } }
    val data = Rhythms.data
    val big = Access.big || Access.younger
    Scaffold(
        topBar = { TopAppBar(title = { Text(Access.name("routines")) }) },
        floatingActionButton = { if (data?.canEdit == true) ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New routine") }, modifier = Modifier.pageFab()) },
    ) { padding ->
        if (data == null) { Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }; return@Scaffold }
        val mine = data.routines.find { it.list.id == data.now[prefs.memberId] }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mine != null) {
                item { Text("Now: ${mine.list.title}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
                items(mine.list.items, key = { "n" + it.id }) { it -> RoutineStep(prefs, mine, it, big) }
            }
            items(data.routines.filter { it != mine }, key = { it.list.id }) { r ->
                ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.list.title, style = MaterialTheme.typography.titleLarge)
                        val whose = r.list.who.mapNotNull { id -> people.find { it.id == id }?.name }.joinToString(", ").ifEmpty { "Everyone" }
                        Text(listOfNotNull(whose, r.starts.ifEmpty { null }?.let { "from $it" }, r.days.joinToString(" ").ifEmpty { null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        r.list.items.forEach { Text(it.emoji.ifEmpty { "•" } + " " + it.text, modifier = Modifier.padding(top = 2.dp)) }
                        // Who's on which routine now (for parents).
                        val onNow = data.now.filterValues { it == r.list.id }.keys.mapNotNull { id -> people.find { it.id == id }?.name }
                        if (onNow.isNotEmpty() && !Access.isChild) Text("On now for " + onNow.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = Accent2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (data.routines.isEmpty()) item { Text("No routines yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (adding) NewRoutineDialog(prefs, people) { adding = false; scope.launch { Rhythms.refresh(prefs) } }
}

@Composable
private fun RoutineStep(prefs: Prefs, r: Routine, it: ListItem, big: Boolean) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().clickable {
            scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).call("POST", "/api/routines/${r.list.id}/items/${it.id}", JSONObject().put("done", !it.done)) }; Rhythms.refresh(prefs) }
        }.padding(vertical = if (big) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickCircle(it.done, if (big) 40.dp else 28.dp)
        // The step's picture, so younger children can follow without reading.
        if (it.emoji.isNotEmpty()) Text(it.emoji, Modifier.padding(start = 12.dp), fontSize = if (big) 34.sp else 24.sp)
        Text(it.text, Modifier.padding(start = 14.dp), style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            textDecoration = if (it.done) TextDecoration.LineThrough else null)
    }
}

private val DAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewRoutineDialog(prefs: Prefs, people: List<Member>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var starts by remember { mutableStateOf("19:30") }
    var days by remember { mutableStateOf(DAY_KEYS.toSet()) }
    var who by remember { mutableStateOf(emptySet<String>()) }
    var steps by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("New routine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("Name") }, placeholder = { Text("e.g. Bedtime") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Starts at ")
                    TextButton(onClick = {
                        val (h, m) = starts.split(":").map { it.toInt() }
                        TimePickerDialog(context, { _, hh, mm -> starts = "%02d:%02d".format(hh, mm) }, h, m, true).show()
                    }) { Text(starts) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_KEYS.forEach { d -> FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(d.replaceFirstChar { it.uppercase() }) }) }
                }
                Text("For (none: everyone)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.filter { it.role != "dependent" }.forEach { p -> FilterChip(selected = p.id in who, onClick = { who = if (p.id in who) who - p.id else who + p.id }, label = { Text(p.name) }) }
                }
                OutlinedTextField(steps, { steps = it.take(2000) }, label = { Text("Steps, one per line") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && days.isNotEmpty(), onClick = {
                scope.launch {
                    runCatching {
                        Api(prefs.serverUrl, prefs.token).call("POST", "/api/routines", JSONObject().put("title", title.trim()).put("starts", starts)
                            .put("days", JSONArray(if (days.size == 7) emptyList() else DAY_KEYS.filter { it in days })).put("who", JSONArray(who.toList()))
                            .put("items", JSONArray(steps.lines().map { it.trim() }.filter { it.isNotEmpty() })))
                    }.onSuccess { onDone() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                }
            }) { Text("Make it") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** The home tile: your routine right now, ticked right here when big enough. */
@Composable
fun RoutinesTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { Rhythms.refresh(prefs); delay(60_000) } }
    val data = Rhythms.data
    val mine = data?.routines?.find { it.list.id == data.now[prefs.memberId] }
    Column(Modifier.fillMaxWidth().height(height).clickable(onClickLabel = "Open ${Access.name("routines")}", onClick = onOpen).padding(14.dp)) {
        TileHead(wide, big, mine?.list?.title ?: Access.name("routines"), icon = { ModuleIcon("routines", if (big) 32.dp else 26.dp) })
        // Wide and tall: every routine on now, side by side (overlapping ones too).
        val onNow = data?.now?.values?.distinct()?.mapNotNull { id -> data.routines.find { it.list.id == id } }.orEmpty()
        if (wide >= 3 && tall >= 2 && onNow.size > 1) {
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                onNow.take(wide).forEach { r ->
                    Column(Modifier.weight(1f)) {
                        Text(r.list.title, style = MaterialTheme.typography.labelLarge, color = Accent2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            val fit = (maxHeight / 34.dp).toInt().coerceAtLeast(1)
                            Column { r.list.items.take(fit).forEach { RoutineStep(prefs, r, it, false) } }
                        }
                    }
                }
            }
            return@Column
        }
        if (mine == null) { Spacer(Modifier.weight(1f)); Text("Nothing on now", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
        val left = mine.list.items.filter { !it.done }
        if (tall >= 2 || wide >= 2) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                val fit = (maxHeight / 34.dp).toInt().coerceAtLeast(1)
                Column { mine.list.items.take(fit).forEach { RoutineStep(prefs, mine, it, false) } }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text(if (left.isEmpty()) "All done" else "${left.size} to go", style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
        }
    }
}

// ---- Pantry: the week's meals and recipes.

data class MealDay(val date: String, val meals: Map<String, String>)
data class RecipeInfo(val id: String, val title: String, val ingredients: List<String>, val method: String)
data class MealsData(val week: String, val days: List<MealDay>, val recipes: List<RecipeInfo>, val canEdit: Boolean)

suspend fun Api.meals(week: String = ""): MealsData {
    val o = call("GET", "/api/meals" + if (week.isNotEmpty()) "?week=$week" else "")
    val days = o.optJSONArray("days") ?: JSONArray()
    val rs = o.optJSONArray("recipes") ?: JSONArray()
    return MealsData(
        o.optString("week"),
        (0 until days.length()).map { i -> days.getJSONObject(i).let { d -> val m = d.optJSONObject("meals"); MealDay(d.optString("date"), m?.keys()?.asSequence()?.associateWith { m.optString(it) } ?: emptyMap()) } },
        (0 until rs.length()).map { i -> rs.getJSONObject(i).let { r -> val ing = r.optJSONArray("ingredients") ?: JSONArray(); RecipeInfo(r.optString("id"), r.optString("title"), (0 until ing.length()).map { ing.getString(it) }, r.optString("method")) } },
        o.optBoolean("canEdit"),
    )
}

object Pantry {
    var data by mutableStateOf<MealsData?>(null)
    suspend fun refresh(prefs: Prefs, week: String = "") { data = runCatching { Api(prefs.serverUrl, prefs.token).meals(week) }.getOrDefault(data) }
}

private val MEALS = listOf("Breakfast", "Lunch", "Dinner")

private fun shiftWeek(week: String, by: Int): String {
    val (y, w) = week.split("-W").map { it.toInt() }
    val c = java.util.Calendar.getInstance().apply { minimalDaysInFirstWeek = 4; firstDayOfWeek = java.util.Calendar.MONDAY; set(java.util.Calendar.YEAR, y); set(java.util.Calendar.WEEK_OF_YEAR, w); set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY); add(java.util.Calendar.WEEK_OF_YEAR, by) }
    return "%d-W%02d".format(c.getWeekYear(), c.get(java.util.Calendar.WEEK_OF_YEAR))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealsScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var week by remember { mutableStateOf("") }
    var setting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addingRecipe by remember { mutableStateOf(false) }
    LaunchedEffect(week) { Pantry.refresh(prefs, week) }
    val data = Pantry.data
    Scaffold(topBar = { TopAppBar(title = { Text(Access.name("meals")) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("This week") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Recipes") })
            }
            if (data == null) { Text("Loading…", Modifier.padding(16.dp)); return@Column }
            if (tab == 0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { week = shiftWeek(data.week, -1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Last week") }
                    Text("Week " + data.week.substringAfter("-W").trimStart('0'), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { week = shiftWeek(data.week, 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week") }
                }
                // One parser and one formatter for the whole week, not one per day on every redraw.
                val parse = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US) }
                val show = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL) }
                LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data.days, key = { it.date }) { d ->
                        ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(runCatching { show.format(parse.parse(d.date)!!) }.getOrDefault(d.date), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                MEALS.forEach { meal ->
                                    Row(Modifier.fillMaxWidth().clickable(enabled = data.canEdit) { setting = d.date to meal }.padding(vertical = 4.dp)) {
                                        Text(meal, Modifier.width(90.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(d.meals[meal].orEmpty().ifEmpty { if (data.canEdit) "Tap to plan" else "" }, color = if (d.meals[meal].isNullOrEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data.recipes, key = { it.id }) { r ->
                        ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(r.title, style = MaterialTheme.typography.titleMedium)
                                Text(r.ingredients.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { Api(prefs.serverUrl, prefs.token).call("POST", "/api/recipes/${r.id}/shop") }
                                            .onSuccess { Toast.makeText(context, "Added ${it.optInt("added")} to Shopping", Toast.LENGTH_SHORT).show(); OddsEnds.refresh(prefs) }
                                    }
                                }) { Text("Add ingredients to Shopping") }
                            }
                        }
                    }
                    if (data.recipes.isEmpty()) item { Text("No recipes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (data.canEdit) item { TextButton(onClick = { addingRecipe = true }) { Text("Add a recipe") } }
                }
            }
        }
    }
    setting?.let { (date, meal) ->
        var dish by remember { mutableStateOf(data?.days?.find { it.date == date }?.meals?.get(meal).orEmpty()) }
        AlertDialog(
            onDismissRequest = { setting = null },
            title = { Text(meal) },
            text = {
                Column {
                    OutlinedTextField(dish, { dish = it.take(100) }, placeholder = { Text("What's for $meal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { data?.recipes?.take(12)?.forEach { r -> FilterChip(selected = dish == r.title, onClick = { dish = r.title }, label = { Text(r.title) }) } }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val wk = data!!.week
                    setting = null
                    scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).call("POST", "/api/meals/$wk", JSONObject().put("date", date).put("meal", meal).put("dish", dish.trim())) }; Pantry.refresh(prefs, wk) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { setting = null }) { Text("Cancel") } },
        )
    }
    if (addingRecipe) {
        var title by remember { mutableStateOf("") }
        var ingredients by remember { mutableStateOf("") }
        var method by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingRecipe = false },
            title = { Text("Add a recipe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ingredients, { ingredients = it }, label = { Text("Ingredients, one per line") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(method, { method = it }, label = { Text("Method") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    addingRecipe = false
                    scope.launch {
                        runCatching { Api(prefs.serverUrl, prefs.token).call("POST", "/api/recipes", JSONObject().put("title", title.trim()).put("method", method.trim()).put("ingredients", JSONArray(ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() }))) }
                        Pantry.refresh(prefs, data?.week.orEmpty())
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addingRecipe = false }) { Text("Cancel") } },
        )
    }
}

/** The home tile: what's for dinner (the week's meals when big). */
@Composable
fun MealsTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { Pantry.refresh(prefs); delay(10 * 60_000) } }
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    val days = Pantry.data?.days.orEmpty().filter { it.date >= today }
    Column(Modifier.fillMaxWidth().height(height).clickable(onClickLabel = "Open ${Access.name("meals")}", onClick = onOpen).padding(14.dp)) {
        TileHead(wide, big, Access.name("meals"), icon = { ModuleIcon("meals", if (big) 32.dp else 26.dp) })
        val dayName = { d: MealDay -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d.date)!!) }
        if (wide >= 3 && days.isNotEmpty()) {
            // Wide: the week ahead, a column a day (dinner; every meal when there's room).
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.take(minOf(7, wide + 2)).forEachIndexed { i, d ->
                    Column(Modifier.weight(1f)) {
                        Text(if (i == 0) "Today" else dayName(d), style = MaterialTheme.typography.labelLarge, color = if (i == 0) Accent2 else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        val shown = if (tall >= 2) MEALS else listOf("Dinner")
                        shown.forEach { m ->
                            val dish = d.meals[m].orEmpty()
                            if (dish.isNotEmpty() || m == "Dinner") Text(dish.ifEmpty { "–" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else if (tall >= 2 && days.isNotEmpty()) {
            // Tall: today's meals, then the dinners after it.
            val first = days.first()
            MEALS.forEach { m ->
                Row(Modifier.padding(top = 4.dp)) {
                    Text(m, Modifier.width(76.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(first.meals[m].orEmpty().ifEmpty { "–" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tall >= 3) days.drop(1).take(tall * 2 - 3).forEach { d ->
                Row(Modifier.padding(top = 4.dp)) {
                    Text(dayName(d), Modifier.width(44.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(d.meals["Dinner"].orEmpty().ifEmpty { "–" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            val tonight = days.firstOrNull()?.meals?.get("Dinner").orEmpty()
            Text(if (tonight.isEmpty()) "Dinner not planned" else tonight, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
