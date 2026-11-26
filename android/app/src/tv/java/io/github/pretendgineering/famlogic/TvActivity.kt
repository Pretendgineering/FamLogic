package io.github.pretendgineering.famlogic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Corkboard on the TV: the family's day from across the room. What's happening
 * today, what's coming up, the routines on now, meals and chores, who's where,
 * and shortcuts to the TV's apps. The look follows the sun: light in the morning,
 * darker towards evening, dark at night. Parents can start quiet time and send a
 * message to the TV from their phones.
 */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeState.load(this)
        setContent { FamLogicTheme { TvApp() } }
    }
}

@Composable
private fun TvApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var joined by remember { mutableStateOf(prefs.joined) }
    if (!joined) TvSetup(prefs) { joined = true } else TvBoard(prefs)
}

// ---- setup: the server's address and a display code (made in the web console: Devices → Pair a family display)

@Composable
private fun TvSetup(prefs: Prefs, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(prefs.serverUrl) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF1E1B2C)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("FamLogic on the TV", color = Color.White, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp)
            Text("In the FamLogic web console, open Devices → Pair a family display, then type the server's address and the code it shows.", color = Color(0xFFCFC8E6), fontSize = 20.sp)
            OutlinedTextField(server, { server = it.trim() }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it.trim().uppercase() }, label = { Text("Display code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error, color = Color(0xFFFF8A8A), fontSize = 18.sp)
            Button(enabled = !busy && server.isNotEmpty() && code.isNotEmpty(), onClick = {
                busy = true; error = ""
                scope.launch {
                    try {
                        val url = findServer(server)
                        val res = Api(url).join(code, "", "", "")
                        prefs.serverUrl = url
                        prefs.token = res.token
                        prefs.memberId = res.member.id
                        prefs.role = res.member.role
                        prefs.access = res.member.access
                        prefs.deviceMode = "display"
                        prefs.familyName = res.server.name
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: "Couldn't connect"
                    }
                    busy = false
                }
            }) { Text(if (busy) "Connecting…" else "Connect", fontSize = 20.sp) }
        }
    }
}

// ---- the board

/** How dark the board is (0 light, 1 dark) at [now], from the day's sunrise and sunset. */
private fun darkness(now: Long, sunrise: Long, sunset: Long): Float {
    val m = 60_000L
    fun ramp(from: Long, to: Long) = ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    return when {
        now < sunrise - 30 * m -> 1f
        now < sunrise + 90 * m -> 1f - ramp(sunrise - 30 * m, sunrise + 90 * m) // dawn: dark to light
        now < sunset - 120 * m -> 0f
        else -> ramp(sunset - 120 * m, sunset + 60 * m) // evening: light to dark
    }
}

/** Today's sunrise and sunset from the board, or 07:00 and 19:00 if the home's place isn't known. */
private fun sunToday(board: JSONObject?): Pair<Long, Long> {
    val sun = board?.optJSONObject("sun")
    if (sun != null && sun.has("sunrise")) return sun.optLong("sunrise") to sun.optLong("sunset")
    val c = Calendar.getInstance().apply { set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    c.set(Calendar.HOUR_OF_DAY, 7); val rise = c.timeInMillis
    c.set(Calendar.HOUR_OF_DAY, 19)
    return rise to c.timeInMillis
}

private val DAY_BG = Color(0xFFF7F2E8)
private val DAY_CARD = Color(0xFFFFFFFF)
private val DAY_TEXT = Color(0xFF2A2638)
private val NIGHT_BG = Color(0xFF15141F)
private val NIGHT_CARD = Color(0xFF242233)
private val NIGHT_TEXT = Color(0xFFECEAF4)

@Composable
private fun TvBoard(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { Api(prefs.serverUrl, prefs.token) }
    var board by remember { mutableStateOf<JSONObject?>(null) }
    var problem by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    suspend fun load() {
        runCatching { api.call("GET", "/api/display/board") }
            .onSuccess { board = it; problem = "" }
            .onFailure { problem = "Can't reach FamLogic right now" }
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(20_000) } }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }

    val (rise, set) = sunToday(board)
    val dark = darkness(now, rise, set)
    val bg by animateColorAsState(lerp(DAY_BG, NIGHT_BG, dark), tween(4000), label = "bg")
    val card by animateColorAsState(lerp(DAY_CARD, NIGHT_CARD, dark), tween(4000), label = "card")
    val ink by animateColorAsState(lerp(DAY_TEXT, NIGHT_TEXT, dark), tween(4000), label = "ink")
    val accent = MaterialTheme.colorScheme.primary
    val soft = ink.copy(alpha = .65f)

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
            // The family, the date and the time.
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(board?.optString("family").orEmpty().ifEmpty { prefs.familyName.ifEmpty { "FamLogic" } }, color = ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                    Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(now)), color = soft, fontSize = 18.sp)
                }
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), color = ink, fontFamily = Serif, fontSize = 52.sp)
            }
            if (problem.isNotEmpty()) Text(problem, color = soft, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            // The day, as a row of cards moved with the remote.
            val b = board
            LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (b != null) {
                    item { TvCard("Today", card, ink, accent) { FeedLines(b.optJSONObject("feed"), ink, soft) } }
                    b.optJSONObject("events")?.let { ev -> item { TvCard("Coming up", card, ink, accent) { EventLines(ev, ink, soft) } } }
                    b.optJSONObject("routines")?.let { r -> item { TvCard("On now", card, ink, accent) { RoutineLines(r, b.optJSONObject("members"), ink, soft) } } }
                    b.optJSONObject("meals")?.let { m -> item { TvCard("Meals", card, ink, accent) { MealLines(m, ink, soft) } } }
                    b.optJSONObject("chores")?.let { c -> item { TvCard("Chores", card, ink, accent) { ChoreLines(c, ink, soft) } } }
                    b.optJSONObject("members")?.let { m -> item { TvCard("Who's where", card, ink, accent) { PeopleLines(m, ink, soft) } } }
                }
            }
            Spacer(Modifier.height(16.dp))
            AppShortcuts(prefs, card, ink, accent)
        }

        // Quiet time: the whole screen, calm, until it ends.
        val quiet = board?.optJSONObject("quiet")
        if (quiet?.optBoolean("on") == true) QuietScreen(quiet) {
            scope.launch { runCatching { api.call("DELETE", "/api/display/quiet") }; load() }
        }

        // A message from a parent's phone: big, until someone presses OK.
        val msgs = board?.optJSONArray("messages") ?: JSONArray()
        val msg = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.optString("id") !in dismissed }
        if (msg != null && quiet?.optBoolean("on") != true) MessageCard(msg) {
            val id = msg.optString("id")
            dismissed = dismissed + id
            scope.launch { runCatching { api.call("DELETE", "/api/display/messages/$id") } }
        }
    }
}

@Composable
private fun TvCard(title: String, bg: Color, ink: Color, accent: Color, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(360.dp).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(bg)
            .border(if (focused) 4.dp else 0.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(28.dp))
            .onFocusChanged { focused = it.isFocused }.focusable().padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Text(title, color = ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        FitColumn(Modifier.weight(1f), content)
    }
}

/** A column that shows only the entries that fit whole; the rest wait for more room. */
@Composable
private fun FitColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { parts, c ->
        val placed = parts.map { it.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        var used = 0
        val shown = placed.takeWhile { p -> (used + p.height <= c.maxHeight).also { if (it) used += p.height } }
        layout(c.maxWidth, c.maxHeight) {
            var y = 0
            shown.forEach { it.place(0, y); y += it.height }
        }
    }
}

@Composable
private fun Line(main: String, sub: String, ink: Color, soft: Color) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(main, color = ink, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Text(sub, color = soft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Empty(text: String, soft: Color) = Text(text, color = soft, fontSize = 20.sp)

private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun objs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }

@Composable
private fun FeedLines(feed: JSONObject?, ink: Color, soft: Color) {
    val items = objs(feed?.optJSONArray("items")).takeLast(8).reversed()
    if (items.isEmpty()) { Empty("A quiet day so far.", soft); return }
    items.forEach { e ->
        val what = when (e.optString("type")) {
            "arrived" -> "got to ${e.optString("place", "a place")}"
            "left" -> "left ${e.optString("place", "a place")}"
            "checkin" -> "checked in"
            "chore_done" -> "did ${e.optString("title")}"
            "reward_spent" -> "got ${e.optString("title")}"
            "trip" -> "finished a trip"
            "drive_start" -> "started driving"
            "sos" -> "sent an SOS"
            "sos_end" -> "is OK"
            else -> e.optString("type")
        }
        Line("${e.optString("name")} $what", time(e.optLong("time")), ink, soft)
    }
}

@Composable
private fun EventLines(ev: JSONObject, ink: Color, soft: Color) {
    val list = objs(ev.optJSONArray("events")).take(8)
    if (list.isEmpty()) { Empty("Nothing on the calendar.", soft); return }
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    list.forEach { e ->
        val start = e.optLong("start")
        val c = Calendar.getInstance().apply { timeInMillis = start }
        val day = when (c.get(Calendar.DAY_OF_YEAR) - today) {
            0 -> "Today"; 1 -> "Tomorrow"; else -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(Date(start))
        }
        Line(e.optString("title"), listOf(day, if (e.optBoolean("allDay")) "all day" else time(start), e.optString("calendar")).filter { it.isNotEmpty() }.joinToString(" · "), ink, soft)
    }
}

@Composable
private fun RoutineLines(r: JSONObject, members: JSONObject?, ink: Color, soft: Color) {
    val names = objs(members?.optJSONArray("members")).associate { it.optString("id") to it.optString("name") }
    val now = r.optJSONObject("now") ?: JSONObject()
    val routines = objs(r.optJSONArray("routines")).associateBy { it.optString("id") }
    val on = now.keys().asSequence().mapNotNull { who -> routines[now.optString(who)]?.let { names[who].orEmpty() to it } }.toList()
    if (on.isEmpty()) { Empty("No routine on right now.", soft); return }
    on.take(3).forEach { (who, rt) ->
        val items = objs(rt.optJSONArray("items"))
        Text("${rt.optString("title")}${if (who.isNotEmpty()) " · $who" else ""}", color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        items.take(5).forEach { i ->
            Text((if (i.optBoolean("done")) "✓  " else "○  ") + i.optString("text"), color = if (i.optBoolean("done")) soft else ink, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MealLines(m: JSONObject, ink: Color, soft: Color) {
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
    val days = objs(m.optJSONArray("days")).filter { it.optString("date") >= today }
    if (days.isEmpty()) { Empty("Nothing planned.", soft); return }
    val first = days.first().optJSONObject("meals") ?: JSONObject()
    listOf("Breakfast", "Lunch", "Dinner").forEach { k -> first.optString(k).takeIf { it.isNotEmpty() }?.let { Line(it, "$k today", ink, soft) } }
    days.drop(1).take(4).forEach { d ->
        val dinner = d.optJSONObject("meals")?.optString("Dinner").orEmpty()
        if (dinner.isNotEmpty()) Line(dinner, java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d.optString("date"))!!), ink, soft)
    }
}

@Composable
private fun ChoreLines(c: JSONObject, ink: Color, soft: Color) {
    val today = c.optString("today")
    val due = objs(c.optJSONArray("chores")).filter { !it.optBoolean("finished") && (it.optString("due").isEmpty() || it.optString("due") <= today) }
    if (due.isEmpty()) { Empty("All done for today.", soft); return }
    due.take(8).forEach { ch -> Line(ch.optString("title"), if (ch.optInt("stars") > 0) "★ ${ch.optInt("stars")}" else "", ink, soft) }
}

@Composable
private fun PeopleLines(m: JSONObject, ink: Color, soft: Color) {
    val people = objs(m.optJSONArray("members")).filter { it.optString("access") != "display" && it.optString("role") != "dependent" }
    people.take(8).forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(runCatching { Color(android.graphics.Color.parseColor(p.optString("color"))) }.getOrDefault(Color.Gray)), contentAlignment = Alignment.Center) {
                Text(p.optString("emoji").ifEmpty { p.optString("name").take(1) }, color = Color.White, fontSize = 18.sp)
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(p.optString("name"), color = ink, fontSize = 21.sp)
                val place = p.optString("place")
                Text(if (place.isNotEmpty()) "At $place" else if (p.optJSONObject("loc") != null) "Out" else "", color = soft, fontSize = 16.sp)
            }
        }
    }
}

// ---- quiet time and messages

@Composable
private fun QuietScreen(q: JSONObject, onEnd: () -> Unit) {
    val focus = remember { FocusRequester() }
    Overlay(onBack = {}) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0D16)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(Icons.Filled.NightsStay, null, tint = Color(0xFFB8A9E8), modifier = Modifier.size(120.dp))
            Text("Quiet time", color = Color(0xFFE6E1F5), fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 64.sp)
            val until = q.optLong("until")
            Text(listOfNotNull(if (until > 0) "until ${time(until)}" else null, q.optString("by").takeIf { it.isNotEmpty() }?.let { "from $it" }).joinToString(", ").replaceFirstChar { it.uppercase() },
                color = Color(0xFF9A93B5), fontSize = 26.sp)
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onEnd, modifier = Modifier.focusRequester(focus)) { Text("End quiet time", color = Color(0xFF9A93B5), fontSize = 20.sp) }
        }
    }
    }
    FocusWhenReady(focus, Unit)
}

@Composable
private fun MessageCard(m: JSONObject, onOk: () -> Unit) {
    val focus = remember { FocusRequester() }
    Overlay(onBack = onOk) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 900.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFFFFFBF2)).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("From ${m.optString("from")}", color = Color(0xFF6F5AA8), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(m.optString("text"), color = Color(0xFF2A2638), fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 60.sp)
            Button(onClick = onOk, modifier = Modifier.focusRequester(focus)) { Text("OK", fontSize = 22.sp) }
        }
    }
    }
    FocusWhenReady(focus, m.optString("id"))
}

/** A full-screen window over the board, so the remote stays on its buttons. */
@Composable
private fun Overlay(onBack: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        content()
    }
}

/** Focus the button once it's on screen (the first try can come before it's attached). */
@Composable
private fun FocusWhenReady(focus: FocusRequester, key: Any) {
    LaunchedEffect(key) {
        repeat(20) {
            delay(100)
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

// ---- the TV's apps

/** Apps to start with, if the TV has them: Jellyfin, Spotify and SmartTube. */
private val SUGGESTED_APPS = listOf("org.jellyfin.androidtv", "com.spotify.tv.android", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")

private data class TvApp(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

private fun installedApps(context: Context): List<TvApp> {
    val pm = context.packageManager
    val seen = linkedMapOf<String, TvApp>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName || pkg in seen) continue
            val icon = runCatching { pm.getApplicationBanner(pkg) ?: pm.getApplicationIcon(pkg) }.getOrNull()
            seen[pkg] = TvApp(pkg, ri.loadLabel(pm).toString(), icon)
        }
    }
    return seen.values.sortedBy { it.label.lowercase() }
}

private fun launch(context: Context, pkg: String) {
    val pm = context.packageManager
    val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun AppShortcuts(prefs: Prefs, card: Color, ink: Color, accent: Color) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("tv", Context.MODE_PRIVATE) }
    val all = remember { installedApps(context) }
    var chosen by remember {
        mutableStateOf(sp.getString("apps", null)?.split(",")?.filter { it.isNotBlank() } ?: SUGGESTED_APPS.filter { p -> all.any { it.pkg == p } })
    }
    var picking by remember { mutableStateOf(false) }
    fun save(list: List<String>) { chosen = list; sp.edit().putString("apps", list.joinToString(",")).apply() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(chosen.mapNotNull { p -> all.find { it.pkg == p } }, key = { it.pkg }) { app ->
            AppTile(app.label, card, ink, accent, icon = app.icon) { launch(context, app.pkg) }
        }
        item { AppTile("Apps", card, ink, accent, plus = true) { picking = true } }
    }
    if (picking) AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("Apps on the board") },
        text = {
            LazyColumn {
                items(all, key = { it.pkg }) { app ->
                    val on = app.pkg in chosen
                    Row(Modifier.fillMaxWidth().clickable { save(if (on) chosen - app.pkg else chosen + app.pkg) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (on) "✓" else " ", Modifier.width(28.dp), fontSize = 20.sp)
                        Text(app.label, fontSize = 20.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picking = false }) { Text("Done") } },
    )
}

@Composable
private fun AppTile(label: String, bg: Color, ink: Color, accent: Color, icon: android.graphics.drawable.Drawable? = null, plus: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(200.dp).height(96.dp).clip(RoundedCornerShape(22.dp)).background(bg)
            .border(if (focused) 4.dp else 0.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(22.dp))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (plus) Icon(Icons.Filled.Add, null, tint = ink, modifier = Modifier.size(32.dp))
        else if (icon != null) {
            val bmp = remember(icon) { runCatching { icon.toBitmap(320, 180).asImageBitmap() }.getOrNull() }
            if (bmp != null) Image(bmp, label, Modifier.fillMaxWidth().weight(1f))
        }
        Text(label, color = ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Suppress("unused")
private fun hasLeanback(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
