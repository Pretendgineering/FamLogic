package io.github.pretendgineering.famlogic

import android.content.ActivityNotFoundException
import android.view.KeyEvent
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Corkboard on the TV: one dashboard for the family's day, read from across the
 * room. Routines, Upkeep and the Docket fill the screen; buttons down the right
 * open the map, the feed, the calendar, meals and Steward (by the remote's
 * microphone); the launcher along the bottom starts the TV's apps. The look
 * follows the sun. Parents can start quiet time and send a message from their phones.
 */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeState.load(this)
        takeOpen(intent)
        setContent { FamLogicTheme { TvApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpen(intent)
    }

    /** The remote helper (or Home) asking for the board, or for Steward. */
    private fun takeOpen(i: Intent?) { i?.getStringExtra("open")?.let { TvNav.request(it) } }
}

@Composable
private fun TvApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val config = remember { TvConfig(context) }
    var joined by remember { mutableStateOf(prefs.joined) }
    CompositionLocalProvider(LocalTvEdges provides config.edges) {
        TvScale(config.edges) { if (!joined) TvSetup(prefs) { joined = true } else TvBoard(prefs, config) }
    }
}

// ---- fitting the TV: everything is drawn on a 960 × 540 board, stretched to fill the screen

/** How far in from the screen's edges to draw (percent), for TVs that cut the edges off. */
private val LocalTvEdges = compositionLocalOf { 0 }

/**
 * Draws [content] as if the screen were 960 × 540 dp, stretched (or shrunk) to fill it
 * whatever the TV's resolution or display-size setting, and [edges] percent in from each side.
 */
@Composable
private fun TvScale(edges: Int, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().padding(horizontal = maxWidth * (edges / 100f), vertical = maxHeight * (edges / 100f))) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val scale = minOf(constraints.maxWidth / 960f, constraints.maxHeight / 540f)
                CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) { content() }
            }
        }
    }
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

// ---- how this TV is set up (kept on the TV)

private class TvConfig(context: Context) {
    private val sp = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    /** How many tiles the launcher shows (1 to 6). */
    var slots by mutableIntStateOf(sp.getInt("slots", 4).coerceIn(1, 6))
        private set
    /** The app on each tile, in order ("" for an empty tile); null until first set. */
    var apps by mutableStateOf(sp.getString("apps", null)?.split(","))
        private set
    /** The buttons down the right side. */
    var side by mutableStateOf(sp.getString("side", null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_SIDE)
        private set
    /** How far in from the screen's edges to draw (0 to 10 percent). */
    var edges by mutableIntStateOf(sp.getInt("edges", 0).coerceIn(0, 10))
        private set

    fun changeEdges(n: Int) { edges = n.coerceIn(0, 10); sp.edit().putInt("edges", edges).apply() }
    private fun save(list: List<String>) { apps = list; sp.edit().putString("apps", list.joinToString(",")).apply() }
    private fun shown() = (apps ?: emptyList()).take(slots).toMutableList().also { while (it.size < slots) it += "" }

    /** Puts an app on the first empty tile, or a new tile; false if all 6 are taken. */
    fun addApp(pkg: String): Boolean {
        val list = shown()
        val free = list.indexOfFirst { it.isEmpty() }
        when {
            free >= 0 -> list[free] = pkg
            slots < 6 -> { changeSlots(slots + 1); list += pkg }
            else -> return false
        }
        save(list)
        return true
    }
    fun removeApp(pkg: String) = save((apps ?: emptyList()).filter { it != pkg })
    /** Moves the tile at [i] one place left (-1) or right (+1). */
    fun move(i: Int, by: Int) {
        val list = shown()
        val j = i + by
        if (i !in list.indices || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        save(list)
    }

    fun changeSlots(n: Int) { slots = n.coerceIn(1, 6); sp.edit().putInt("slots", slots).apply() }
    fun setApp(i: Int, pkg: String) {
        val list = (apps ?: emptyList()).toMutableList()
        while (list.size <= i) list += ""
        list[i] = pkg
        save(list)
    }
    fun toggleSide(id: String) {
        side = if (id in side) side - id else SIDE_BUTTONS.map { it.first }.filter { it in side || it == id }
        sp.edit().putString("side", side.joinToString(",")).apply()
    }
}

private val DEFAULT_SIDE = listOf("map", "feed", "steward", "calendar")

/** Every button that can go down the right side: id, name, icon. */
private val SIDE_BUTTONS: List<Triple<String, String, ImageVector>> = listOf(
    Triple("map", "Map", Icons.Filled.LocationOn),
    Triple("feed", "Feed", Icons.Filled.DynamicFeed),
    Triple("steward", "Steward", Icons.Filled.Mic),
    Triple("calendar", "Datebook", Icons.Filled.DateRange),
    Triple("meals", "Pantry", Icons.Filled.Restaurant),
)

// ---- the dashboard

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

private fun greeting(now: Long): String = when (Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

private val DAY_BG = Color(0xFFF7F2E8)
private val DAY_CARD = Color(0xFFFFFFFF)
private val DAY_TEXT = Color(0xFF2A2638)
private val NIGHT_BG = Color(0xFF15141F)
private val NIGHT_CARD = Color(0xFF242233)
private val NIGHT_TEXT = Color(0xFFECEAF4)

/** The board's colours right now. */
private data class Look(val bg: Color, val card: Color, val ink: Color, val soft: Color, val accent: Color)

@Composable
private fun TvBoard(prefs: Prefs, config: TvConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { Api(prefs.serverUrl, prefs.token) }
    var board by remember { mutableStateOf<JSONObject?>(null) }
    var problem by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    // What's open over the board. Kept in a holder the board itself never reads, so opening
    // a view doesn't redraw the board underneath it.
    val nav = remember { BoardNav() }
    suspend fun load() {
        runCatching { api.call("GET", "/api/display/board") }
            .onSuccess { board = it; problem = "" }
            .onFailure { problem = "Can't reach FamLogic right now" }
    }
    LaunchedEffect(Unit) {
        // What the server can do (its voices, for Steward's answers).
        runCatching { api.members(0) }.onSuccess { prefs.serverFeatures = it.features }
        // So the console knows this is a TV (and names it "Living room", not someone's phone).
        runCatching { api.sendStatus(BuildConfig.VERSION_NAME, emptyList(), "tv") }
        while (true) { load(); delay(20_000) }
    }
    // Home (with the remote helper) closes whatever is open; a remapped button can ask Steward.
    LaunchedEffect(TvNav.seq) {
        when (TvNav.open) {
            "home" -> { nav.view = null; nav.menuFor = null; nav.pickFor = null }
            "steward" -> nav.view = "steward"
        }
        TvNav.open = null
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }

    // The TV's apps, kept up to date as apps are installed and removed.
    // (Read off the main thread: loading every app's banner takes a moment.)
    var installed by remember { mutableStateOf(emptyList<TvApp>()) }
    var appsChanged by remember { mutableIntStateOf(0) }
    LaunchedEffect(appsChanged) { installed = withContext(Dispatchers.IO) { installedApps(context) } }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { appsChanged++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // The first time, the launcher starts with Jellyfin, Spotify and SmartTube if the TV has them.
    LaunchedEffect(installed) {
        if (config.apps == null && installed.isNotEmpty()) SUGGESTED_APPS.filter { p -> installed.any { it.pkg == p } }.take(config.slots).forEachIndexed { i, p -> config.setApp(i, p) }
    }
    // Tell the server which apps are on the launcher, so the console can give each a picture.
    LaunchedEffect(config.apps, config.slots, installed) {
        val chosen = (config.apps ?: emptyList()).take(config.slots).mapNotNull { p -> installed.find { it.pkg == p } }
        if (chosen.isNotEmpty()) runCatching {
            api.call("PUT", "/api/display/apps", JSONObject().put("apps", JSONArray(chosen.map { JSONObject().put("pkg", it.pkg).put("label", it.label) })))
        }
    }

    val (rise, set) = sunToday(board)
    val dark = darkness(now, rise, set)
    // The sun moves slowly, so the colours change in small steps every 15 seconds (no animation:
    // redrawing the whole board every frame would be wasted work for the TV).
    val bg = lerp(DAY_BG, NIGHT_BG, dark)
    val card = lerp(DAY_CARD, NIGHT_CARD, dark)
    // The text switches between dark and light rather than fading through grey, so it always reads.
    val ink by animateColorAsState(if (dark < .5f) DAY_TEXT else NIGHT_TEXT, tween(4000), label = "ink")
    // The accent: a deep purple on the light daytime board, a soft lavender at night.
    val accent by animateColorAsState(if (dark < .5f) Color(0xFF5B4596) else Color(0xFFB8A9E8), tween(4000), label = "accent")
    val look = Look(bg, card, ink, ink.copy(alpha = .65f), accent)
    val b = board
    val modules = b?.optJSONArray("modules")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    // The family's people (not displays), by id.
    val names = objs(b?.optJSONObject("members")?.optJSONArray("members"))
        .filter { it.optString("access") != "display" && it.optString("role") != "dependent" }.associate { it.optString("id") to it.optString("name") }

    Box(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // A greeting for the time of day, the family and the date, and the time.
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(now), color = ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                        val family = b?.optString("family").orEmpty().ifEmpty { prefs.familyName }
                        Text(listOf(family, SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now))).filter { it.isNotEmpty() }.joinToString(" · "),
                            color = look.soft, fontSize = 16.sp)
                    }
                    Text(time(now), color = ink, fontFamily = Serif, fontSize = 42.sp)
                }
                if (problem.isNotEmpty()) Text(problem, color = look.soft, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                // Routines, Upkeep and the Docket: the family's day at a glance.
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (b != null) {
                        val routines = b.optJSONObject("routines")
                        val chores = b.optJSONObject("chores")
                        val lists = b.optJSONObject("lists")
                        if (routines != null) Panel("Routines", look, 1.35f) { RoutineLines(routines, b.optJSONArray("routinesLater"), names, look) }
                        if (chores != null) Panel("Upkeep", look) { ChoreLines(chores, names, look) }
                        if (lists != null) Panel("Docket", look) { DocketLines(lists, names, look) }
                        if (routines == null && chores == null && lists == null) Panel("Today", look) { FeedLines(b.optJSONObject("feed"), look) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Launcher(api, config, installed, b?.optJSONArray("apps"), look, onEmpty = { nav.pickFor = it; nav.view = "apps" }, onMenu = { nav.menuFor = it })
            }
            Spacer(Modifier.width(20.dp))
            // Buttons down the right side, then the app drawer and Settings.
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                SIDE_BUTTONS.filter { (id) -> id in config.side && available(id, modules, b) }.forEach { (id, label, icon) ->
                    SideButton(label, icon, look) { nav.view = id }
                }
                Spacer(Modifier.height(4.dp))
                SideButton("Apps", Icons.Filled.Apps, look) { nav.pickFor = null; nav.view = "apps" }
                SideButton("Settings", Icons.Filled.Settings, look) { nav.view = "settings" }
            }
        }

        BoardViews(nav, prefs, api, config, installed, modules, board, look) { scope.launch { load() } }

        // Quiet time: the whole screen, calm, until it ends.
        val quiet = board?.optJSONObject("quiet")
        if (quiet?.optBoolean("on") == true) QuietScreen(quiet) {
            scope.launch { runCatching { api.call("DELETE", "/api/display/quiet") }; load() }
        }

        // A message from a parent's phone: big, until someone presses OK.
        val msgs = board?.optJSONArray("messages") ?: JSONArray()
        val msg = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.optString("id") !in dismissed }
        if (msg != null && quiet?.optBoolean("on") != true) MessageCard(prefs, msg) {
            val id = msg.optString("id")
            dismissed = dismissed + id
            scope.launch { runCatching { api.call("DELETE", "/api/display/messages/$id") } }
        }
    }
}

/** The views that open over the board (map, lists, Steward, the app drawer, Settings, an app's menu). */
private class BoardNav {
    var view by mutableStateOf<String?>(null) // map, feed, steward, calendar, meals, apps, settings
    var pickFor by mutableStateOf<Int?>(null) // choosing an app for this empty tile
    var menuFor by mutableStateOf<String?>(null) // an app's long-press menu
}

@Composable
private fun BoardViews(nav: BoardNav, prefs: Prefs, api: Api, config: TvConfig, installed: List<TvApp>, modules: Set<String>, board: JSONObject?, look: Look, reload: () -> Unit) {
    when (nav.view) {
        "map" -> MapView(prefs, api, look) { nav.view = null }
        "feed" -> ListView("Today", look, { nav.view = null }) { FeedLines(board?.optJSONObject("feed"), look, all = true) }
        "calendar" -> ListView("Coming up", look, { nav.view = null }) { board?.optJSONObject("events")?.let { EventLines(it, look) } }
        "meals" -> ListView("Meals", look, { nav.view = null }) { board?.optJSONObject("meals")?.let { MealLines(it, look) } }
        "steward" -> StewardView(prefs, api, look) { nav.view = null; reload() }
        "apps" -> AppDrawer(installed, look, nav.pickFor, onPick = { pkg -> nav.pickFor?.let { config.setApp(it, pkg) }; nav.pickFor = null; nav.view = null }, onMenu = { nav.menuFor = it }) { nav.pickFor = null; nav.view = null }
        "settings" -> SettingsView(config, installed, modules, board, look) { nav.view = null }
    }
    nav.menuFor?.let { pkg -> installed.find { it.pkg == pkg }?.let { app -> AppMenu(app, config) { nav.menuFor = null } } ?: run { nav.menuFor = null } }
}

/** Whether a side button can work here: its module is on (and Steward is set up for the TV). */
private fun available(id: String, modules: Set<String>, board: JSONObject?) = when (id) {
    "map" -> "thereabouts" in modules
    "calendar" -> "calendar" in modules
    "meals" -> "meals" in modules
    "steward" -> board?.optBoolean("steward") == true
    else -> true
}

@Composable
private fun RowScope.Panel(title: String, look: Look, weight: Float = 1f, content: @Composable () -> Unit) {
    Column(Modifier.weight(weight).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(look.card).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
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
private fun Line(main: String, sub: String, look: Look, mark: String = "", markColor: Color = look.ink) {
    Row(Modifier.padding(vertical = 3.dp)) {
        if (mark.isNotEmpty()) Text(mark, Modifier.width(24.dp), color = markColor, fontSize = 17.sp)
        Column {
            Text(main, color = look.ink, fontSize = 17.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = look.soft, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Heading(text: String, look: Look) =
    Text(text, Modifier.padding(top = 6.dp, bottom = 1.dp), color = look.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Empty(text: String, look: Look) = Text(text, color = look.soft, fontSize = 17.sp)

private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** "07:30" as the TV's own clock shows times ("7:30 AM"). */
private fun clock(hhmm: String): String {
    val (h, m) = hhmm.split(":").map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 0 } to it.getOrElse(1) { 0 } }
    return time(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }.timeInMillis)
}

private fun objs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
private fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

private fun whoText(ids: List<String>, names: Map<String, String>) = ids.mapNotNull { names[it] }.joinToString(", ")

@Composable
private fun RoutineLines(r: JSONObject, later: JSONArray?, names: Map<String, String>, look: Look) {
    val now = r.optJSONObject("now") ?: JSONObject()
    val routines = objs(r.optJSONArray("routines")).associateBy { it.optString("id") }
    // Who's on each routine right now (people sharing one see the same checklist).
    val on = now.keys().asSequence().groupBy({ now.optString(it) }, { it }).filterKeys { it in routines }
    val next = objs(later)
    if (on.isEmpty() && next.isEmpty()) { Empty("No routines today.", look); return }
    if (on.isEmpty()) Empty("No routine on right now.", look)
    on.forEach { (id, who) ->
        val rt = routines.getValue(id)
        Heading(listOf(rt.optString("title"), if (who.size >= names.size) "Everyone" else whoText(who, names)).filter { it.isNotEmpty() }.joinToString(" · "), look)
        objs(rt.optJSONArray("items")).forEach { i ->
            val done = i.optBoolean("done")
            Line(i.optString("text"), whoText(listOfNotNull(i.optString("who").takeIf { it.isNotEmpty() }), names), look,
                mark = if (done) "✓" else "○", markColor = if (done) look.accent else look.soft)
        }
    }
    // "Later today" and its first entry go together, so the heading is never left on its own.
    next.forEachIndexed { i, n ->
        Column {
            if (i == 0) Heading("Later today", look)
            Line(n.optString("title"), listOf(clock(n.optString("at")), whoText(strs(n.optJSONArray("who")), names)).filter { it.isNotEmpty() }.joinToString(" · "), look)
        }
    }
}

@Composable
private fun ChoreLines(c: JSONObject, names: Map<String, String>, look: Look) {
    val today = c.optString("today")
    val due = objs(c.optJSONArray("chores"))
        .filter { !it.optBoolean("finished") && (it.optString("due").isEmpty() || it.optString("due") <= today) }
        .sortedBy { it.optString("due") }
    if (due.isEmpty()) { Empty("All done for today.", look); return }
    due.forEach { ch ->
        val who = whoText(strs(ch.optJSONArray("who")), names).ifEmpty { "Anyone" }
        val late = ch.optString("due").let { it.isNotEmpty() && it < today }
        Line(ch.optString("title"), listOfNotNull(who, if (late) "overdue" else null, ch.optInt("stars").takeIf { it > 0 }?.let { "★ $it" }).joinToString(" · "), look,
            mark = "○", markColor = if (late) Color(0xFFE0815F) else look.soft)
    }
}

@Composable
private fun DocketLines(l: JSONObject, names: Map<String, String>, look: Look) {
    val lists = objs(l.optJSONArray("lists"))
    val todos = lists.filter { it.optString("kind") == "todos" }
        .flatMap { list -> objs(list.optJSONArray("items")).filter { !it.optBoolean("done") }.map { list.optString("title") to it } }
        .sortedBy { it.second.optString("due").ifEmpty { "9999" } }
    val others = lists.filter { it.optString("kind") != "todos" }.mapNotNull { list ->
        val left = objs(list.optJSONArray("items")).count { !it.optBoolean("done") }
        if (left > 0) list.optString("title") to left else null
    }
    if (todos.isEmpty() && others.isEmpty()) { Empty("Nothing on the docket.", look); return }
    todos.forEach { (title, i) ->
        val due = i.optString("due").takeIf { it.isNotEmpty() }?.let { d ->
            runCatching { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!) }.getOrDefault(d)
        }
        Line(i.optString("text"), listOfNotNull(title, names[i.optString("who")], due).joinToString(" · "), look, mark = "○", markColor = look.soft)
    }
    others.forEach { (title, left) -> Line(title, "$left to get", look, mark = "≡", markColor = look.soft) }
}

@Composable
private fun FeedLines(feed: JSONObject?, look: Look, all: Boolean = false) {
    val items = objs(feed?.optJSONArray("items")).let { if (all) it else it.takeLast(8) }.reversed()
    if (items.isEmpty()) { Empty("A quiet day so far.", look); return }
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
        Line("${e.optString("name")} $what", time(e.optLong("time")), look)
    }
}

@Composable
private fun EventLines(ev: JSONObject, look: Look) {
    val list = objs(ev.optJSONArray("events"))
    if (list.isEmpty()) { Empty("Nothing on the calendar.", look); return }
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    list.forEach { e ->
        val start = e.optLong("start")
        val c = Calendar.getInstance().apply { timeInMillis = start }
        val day = when (c.get(Calendar.DAY_OF_YEAR) - today) {
            0 -> "Today"; 1 -> "Tomorrow"; else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(start))
        }
        Line(e.optString("title"), listOf(day, if (e.optBoolean("allDay")) "all day" else time(start), e.optString("calendar")).filter { it.isNotEmpty() }.joinToString(" · "), look)
    }
}

@Composable
private fun MealLines(m: JSONObject, look: Look) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val days = objs(m.optJSONArray("days")).filter { it.optString("date") >= today }
    if (days.isEmpty()) { Empty("Nothing planned.", look); return }
    days.forEach { d ->
        val meals = d.optJSONObject("meals") ?: JSONObject()
        val day = if (d.optString("date") == today) "Today" else runCatching {
            SimpleDateFormat("EEEE", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.optString("date"))!!)
        }.getOrDefault(d.optString("date"))
        listOf("Breakfast", "Lunch", "Dinner").forEach { k -> meals.optString(k).takeIf { it.isNotEmpty() }?.let { Line(it, "$day · $k", look) } }
    }
}

@Composable
private fun SideButton(label: String, icon: ImageVector, look: Look, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(18.dp)).background(if (focused) look.accent.copy(alpha = .18f) else look.card)
            .border(if (focused) 3.dp else 0.dp, if (focused) look.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (focused) look.accent else look.ink, modifier = Modifier.size(24.dp))
        Text(label, color = look.soft, fontSize = 11.sp, maxLines = 1)
    }
}

// ---- views opened from the side

/** A full-screen window over the board, so the remote stays on its buttons. */
@Composable
private fun Overlay(onBack: () -> Unit, content: @Composable () -> Unit) {
    val edges = LocalTvEdges.current
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        // The window inherits the board's scale; only the margin for the TV's edges is added here.
        Box(Modifier.fillMaxSize().edgeInset(edges)) { content() }
    }
}

/** Draws [percent] percent in from each edge (for TVs that cut the edges off). */
private fun Modifier.edgeInset(percent: Int) = layout { m, c ->
    val dx = (c.maxWidth * percent / 100f).toInt()
    val dy = (c.maxHeight * percent / 100f).toInt()
    val p = m.measure(c.offset(-2 * dx, -2 * dy))
    layout(c.maxWidth, c.maxHeight) { p.place(dx, dy) }
}

/** Focus a button once it's on screen (the first try can come before it's attached). */
@Composable
private fun FocusWhenReady(focus: FocusRequester, key: Any) {
    LaunchedEffect(key) {
        repeat(20) {
            delay(100)
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
private fun ViewFrame(title: String, look: Look, onBack: () -> Unit, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    Overlay(onBack) {
        Column(Modifier.fillMaxSize().background(look.bg).padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                OutlinedButton(onClick = onBack, modifier = Modifier.focusRequester(focus)) { Text("Back", color = look.ink, fontSize = 18.sp) }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
    FocusWhenReady(focus, title)
}

/** A long list, moved through with the remote (each entry takes focus, so it scrolls). */
@Composable
private fun ListView(title: String, look: Look, onBack: () -> Unit, content: @Composable () -> Unit) {
    ViewFrame(title, look, onBack) {
        Box(Modifier.fillMaxHeight().widthIn(max = 900.dp).clip(RoundedCornerShape(28.dp)).background(look.card).padding(24.dp)) {
            LazyColumn { item { Column(Modifier.focusable()) { content() } } }
        }
    }
}

@Composable
private fun MapView(prefs: Prefs, api: Api, look: Look, onBack: () -> Unit) {
    val res by produceState<MembersResponse?>(null) {
        while (true) { value = runCatching { api.members(0) }.getOrDefault(value); delay(30_000) }
    }
    ViewFrame("Where everyone is", look, onBack) {
        val r = res
        if (r == null) { Empty("Loading…", look); return@ViewFrame }
        val people = r.members.filter { it.access != "display" && it.role != "dependent" }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(look.card).padding(20.dp)) {
                FitColumn(Modifier.weight(1f)) {
                    people.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(parseColor(p.color))), contentAlignment = Alignment.Center) {
                                Text(p.emoji.ifEmpty { initialOf(p.name) }, color = Color.White, fontSize = 18.sp)
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(p.name, color = look.ink, fontSize = 20.sp)
                                Text(if (p.loc == null) "No location yet" else Seen.where(p), color = look.soft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))) {
                OsmMap(
                    members = people.filter { it.loc != null && !it.paused },
                    tiles = MapStyles.resolve(prefs.mapStyle, LocalDarkTheme.current, r.server.tileUrl, r.server.tileUrlDark, r.server.tileAttribution, prefs.dimMap),
                    camera = CameraCommand.FitAll(1),
                    statusOf = { Seen.where(it) },
                    places = r.places,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Steward by voice: the remote's microphone (the TV's own speech recogniser), then
 * Steward's answer, on screen and read aloud. It acts as the TV, with a display's permissions.
 */
@Composable
private fun StewardView(prefs: Prefs, api: Api, look: Look, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var heard by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("ready") } // ready, thinking, answered, nomic
    fun ask(text: String) {
        if (text.isBlank()) { state = "ready"; return }
        heard = text; answer = ""; state = "thinking"
        scope.launch {
            runCatching { api.stewardSay(text, "display") }
                .onSuccess { st ->
                    answer = st.msgs.lastOrNull { it.role == "assistant" }?.text.orEmpty()
                    state = "answered"
                    launch { Voice.speak(context, prefs, answer) }
                }
                .onFailure { answer = it.message ?: "Steward couldn't answer"; state = "answered" }
        }
    }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        ask(res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty())
    }
    fun start() {
        Voice.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Steward")
        try { listen.launch(i) } catch (_: ActivityNotFoundException) { state = "nomic" }
    }
    LaunchedEffect(Unit) {
        runCatching { api.stewardClear() } // each question on the TV starts fresh
        start()
    }
    val focus = remember { FocusRequester() }
    ViewFrame("Steward", look, { Voice.stop(); onBack() }) {
        Column(Modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (heard.isNotEmpty()) Text("“$heard”", color = look.soft, fontSize = 24.sp, fontFamily = Serif)
            when (state) {
                "thinking" -> Text("Thinking…", color = look.soft, fontSize = 24.sp)
                "answered" -> Text(answer, color = look.ink, fontSize = 30.sp, lineHeight = 40.sp, fontFamily = Serif)
                "nomic" -> Text("This TV has no speech recogniser for Steward to listen with.", color = look.soft, fontSize = 22.sp)
                else -> Text("Press Ask, then speak into the remote.", color = look.soft, fontSize = 22.sp)
            }
            if (state != "nomic") Button(onClick = { start() }, modifier = Modifier.focusRequester(focus)) {
                Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (heard.isEmpty()) "Ask" else "Ask again", fontSize = 20.sp)
            }
        }
    }
    if (state == "answered") FocusWhenReady(focus, heard)
}

// ---- settings: the screen, the launcher, the side buttons, and the remote helper

@Composable
private fun SettingsView(config: TvConfig, installed: List<TvApp>, modules: Set<String>, board: JSONObject?, look: Look, onBack: () -> Unit) {
    val context = LocalContext.current
    var helperOn by remember { mutableStateOf(TvRemoteService.enabled(context)) }
    LaunchedEffect(Unit) { while (true) { helperOn = TvRemoteService.enabled(context); delay(1500) } }
    var override by remember { mutableStateOf(TvRemoteSettings.override(context)) }
    var boot by remember { mutableStateOf(TvRemoteSettings.boot(context)) }
    var keys by remember { mutableStateOf(TvRemoteSettings.keys(context)) }
    var learning by remember { mutableStateOf(false) }
    var learned by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) { onDispose { TvNav.capture = null } }
    fun open(i: Intent, otherwise: String) {
        try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { Toast.makeText(context, otherwise, Toast.LENGTH_LONG).show() }
    }
    ViewFrame("Settings", look, onBack) {
        LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Heading("The screen", look) }
            item {
                SettingRow("Fit to the screen", "If the TV cuts off the edges, draw further in: ${config.edges}%.", look) {
                    Stepper(config.edges > 0, config.edges < 10, { config.changeEdges(config.edges - 1) }, { config.changeEdges(config.edges + 1) })
                }
            }
            item {
                SettingRow("Tiles on the launcher", "${config.slots} along the bottom.", look) {
                    Stepper(config.slots > 1, config.slots < 6, { config.changeSlots(config.slots - 1) }, { config.changeSlots(config.slots + 1) })
                }
            }
            item { Heading("Buttons on the side", look) }
            items(SIDE_BUTTONS.filter { (id) -> available(id, modules, board) }, key = { it.first }) { (id, label) ->
                SwitchRow(label, "", id in config.side, true, look) { config.toggleSide(id) }
            }
            item { Heading("Home and remote", look) }
            item {
                SettingRow("Remote helper", if (helperOn) "On." else "Off. In the TV's Accessibility settings, switch on FamLogic TV remote.", look, Icons.Filled.Accessibility) {
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "This TV has no Accessibility settings") }) { Text("Accessibility settings", color = look.ink) }
                }
            }
            item {
                SwitchRow("Override current launcher", "Opens FamLogic instead of the TV's own home screen, and on Home. Useful if you can't change or uninstall the stock one.",
                    override, helperOn, look, Icons.Filled.Accessibility) { override = !override; TvRemoteSettings.setOverride(context, override) }
            }
            item {
                SwitchRow("Start when the TV turns on", "Opens FamLogic after the TV starts up.", boot, helperOn, look, Icons.Filled.Accessibility) {
                    boot = !boot; TvRemoteSettings.setBoot(context, boot)
                }
            }
            item {
                SettingRow("Make FamLogic the home app", "On TVs that let you choose a home app (Google TV doesn't: use the override above).", look) {
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_HOME_SETTINGS), "This TV doesn't let you choose: use Override current launcher") }) { Text("Choose", color = look.ink) }
                }
            }
            item { Heading("Remote buttons", look) }
            items(keys.entries.toList(), key = { "k" + it.key }) { (code, action) ->
                SettingRow(TvRemoteSettings.keyName(code), actionName(action, installed), look) {
                    OutlinedButton(onClick = { keys = keys - code; TvRemoteSettings.setKeys(context, keys) }) { Text("Remove", color = look.ink) }
                }
            }
            item {
                if (learning) SettingRow("Press the remote button to use", "Any button but the arrows, OK and Back.", look) {
                    OutlinedButton(onClick = { learning = false; TvNav.capture = null }) { Text("Cancel", color = look.ink) }
                } else SettingRow("Give a button a new job", if (helperOn) "Open an app, FamLogic or Steward with a button on the remote." else "Needs the remote helper.", look) {
                    OutlinedButton(enabled = helperOn, onClick = { learning = true; TvNav.capture = { code -> learning = false; learned = code } }) { Text("Add a button", color = look.ink) }
                }
            }
        }
    }
    learned?.let { code ->
        ActionPicker(TvRemoteSettings.keyName(code), installed, onPick = { a -> keys = keys + (code to a); TvRemoteSettings.setKeys(context, keys); learned = null }) { learned = null }
    }
}

private fun actionName(action: String, installed: List<TvApp>) = when {
    action == "home" -> "Opens FamLogic"
    action == "steward" -> "Asks Steward"
    else -> "Opens " + (installed.find { it.pkg == action.removePrefix("app:") }?.label ?: action.removePrefix("app:"))
}

@Composable
private fun SettingRow(title: String, sub: String, look: Look, icon: ImageVector? = null, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(look.card).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = look.accent, modifier = Modifier.padding(end = 12.dp).size(22.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = look.ink, fontSize = 18.sp)
            if (sub.isNotEmpty()) Text(sub, color = look.soft, fontSize = 14.sp)
        }
        action()
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, enabled: Boolean, look: Look, icon: ImageVector? = null, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (focused) look.accent.copy(alpha = .18f) else look.card)
            .border(if (focused) 3.dp else 0.dp, if (focused) look.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }.clickable(enabled = enabled, onClick = onToggle).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = look.accent, modifier = Modifier.padding(end = 12.dp).size(22.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) look.ink else look.soft, fontSize = 18.sp)
            if (sub.isNotEmpty()) Text(sub, color = look.soft, fontSize = 14.sp)
        }
        Switch(on, null, enabled = enabled, colors = accentSwitch())
    }
}

@Composable
private fun Stepper(canLess: Boolean, canMore: Boolean, less: () -> Unit, more: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = canLess, onClick = less) { Text("Less") }
        OutlinedButton(enabled = canMore, onClick = more) { Text("More") }
    }
}

/** What a remapped button does: FamLogic, Steward, or an app. */
@Composable
private fun ActionPicker(key: String, installed: List<TvApp>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key) },
        text = {
            LazyColumn {
                item { TextButton(onClick = { onPick("home") }, Modifier.fillMaxWidth()) { Text("Open FamLogic", Modifier.fillMaxWidth(), fontSize = 18.sp) } }
                item { TextButton(onClick = { onPick("steward") }, Modifier.fillMaxWidth()) { Text("Ask Steward", Modifier.fillMaxWidth(), fontSize = 18.sp) } }
                items(installed, key = { it.pkg }) { app ->
                    TextButton(onClick = { onPick("app:" + app.pkg) }, Modifier.fillMaxWidth()) { Text("Open ${app.label}", Modifier.fillMaxWidth(), fontSize = 18.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- the app drawer: every app on the TV

@Composable
private fun AppDrawer(installed: List<TvApp>, look: Look, pickFor: Int?, onPick: (String) -> Unit, onMenu: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    ViewFrame(if (pickFor != null) "Choose an app for tile ${pickFor + 1}" else "Apps", look, onBack) {
        LazyVerticalGrid(GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(installed, key = { it.pkg }) { app ->
                DrawerTile(app, look, onClick = { if (pickFor != null) onPick(app.pkg) else launch(context, app.pkg) }, onLongClick = { onMenu(app.pkg) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerTile(app: TvApp, look: Look, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val banner = app.banner
    val icon = app.icon
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.clip(shape).background(if (focused) look.accent.copy(alpha = .18f) else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) look.accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }.tvClickable(onClick, onLongClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(look.card), contentAlignment = Alignment.Center) {
            when {
                banner != null -> Image(banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                icon != null -> Image(icon, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
            }
        }
        Text(app.label, Modifier.padding(top = 4.dp), color = look.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Hold OK on an app (in the drawer or on the launcher): open, add or remove, move, app info, uninstall. */
@Composable
private fun AppMenu(app: TvApp, config: TvConfig, onDone: () -> Unit) {
    val context = LocalContext.current
    val shown = (config.apps ?: emptyList()).take(config.slots)
    val i = shown.indexOf(app.pkg)
    fun start(intent: Intent) { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    val options = buildList<Pair<String, () -> Unit>> {
        add("Open" to { launch(context, app.pkg) })
        if (i >= 0) {
            add("Remove from the launcher" to { config.removeApp(app.pkg) })
            if (i > 0) add("Move left" to { config.move(i, -1) })
            if (i < config.slots - 1) add("Move right" to { config.move(i, 1) })
        } else add("Add to the launcher" to {
            if (!config.addApp(app.pkg)) Toast.makeText(context, "The launcher is full: remove a tile first", Toast.LENGTH_LONG).show()
        })
        add("App info" to { start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) })
        add("Uninstall" to { start(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) })
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(app.label) },
        text = {
            Column {
                options.forEach { (label, act) ->
                    TextButton(onClick = { act(); onDone() }, Modifier.fillMaxWidth()) { Text(label, Modifier.fillMaxWidth(), fontSize = 18.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
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
private fun MessageCard(prefs: Prefs, m: JSONObject, onOk: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val id = m.optString("id")
    // It rises into view with a soft ping, rather than just appearing.
    val shown = remember(id) { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(id) {
        runCatching { MediaPlayer.create(context, R.raw.ping)?.apply { setOnCompletionListener { it.release() }; start() } }
    }
    Overlay(onBack = { Voice.stop(); onOk() }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(shown, Modifier.fillMaxSize(), enter = fadeIn(tween(400))) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            }
            AnimatedVisibility(shown, enter = slideInVertically(tween(600, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(450))) {
                Column(
                    Modifier.widthIn(max = 820.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFFFFFBF2)).padding(44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text("From ${m.optString("from")}", color = Color(0xFF6F5AA8), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(m.optString("text"), color = Color(0xFF2A2638), fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 54.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { Voice.stop(); onOk() }, modifier = Modifier.focusRequester(focus)) { Text("OK", fontSize = 20.sp) }
                        OutlinedButton(onClick = { scope.launch { Voice.speak(context, prefs, m.optString("text")) } }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color(0xFF6F5AA8)); Spacer(Modifier.width(8.dp))
                            Text("Read aloud", color = Color(0xFF2A2638), fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
    FocusWhenReady(focus, id)
}

// ---- the launcher: the TV's apps, as big pictures

/** Apps to start with, if the TV has them: Jellyfin, Spotify and SmartTube. */
private val SUGGESTED_APPS = listOf("org.jellyfin.androidtv", "com.spotify.tv.android", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")

/** An app on the TV, with its banner (or, without one, its icon) ready to draw. */
private data class TvApp(val pkg: String, val label: String, val banner: ImageBitmap?, val icon: ImageBitmap?)

private fun installedApps(context: Context): List<TvApp> {
    val pm = context.packageManager
    val seen = linkedMapOf<String, TvApp>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName || pkg in seen) continue
            val banner = runCatching { pm.getApplicationBanner(pkg)?.toBitmap(512, 288)?.asImageBitmap() }.getOrNull()
            val icon = if (banner != null) null else runCatching { pm.getApplicationIcon(pkg).toBitmap(192, 192).asImageBitmap() }.getOrNull()
            seen[pkg] = TvApp(pkg, ri.loadLabel(pm).toString(), banner, icon)
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
private fun Launcher(api: Api, config: TvConfig, installed: List<TvApp>, serverApps: JSONArray?, look: Look, onEmpty: (Int) -> Unit, onMenu: (String) -> Unit) {
    val context = LocalContext.current
    val pictures = objs(serverApps).associate { it.optString("pkg") to it.optLong("image") }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 16.dp
        val n = config.slots
        // Tiles share the width, 16:9 like the apps' own banners, and no taller than 100dp.
        val w: Dp = minOf((maxWidth - gap * (n - 1)) / n, 100.dp * 16 / 9)
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until n) {
                val app = config.apps?.getOrNull(i)?.let { p -> installed.find { it.pkg == p } }
                AppTile(api, app, pictures[app?.pkg] ?: 0L, w, look, onClick = { if (app != null) launch(context, app.pkg) else onEmpty(i) }, onLongClick = { if (app != null) onMenu(app.pkg) else onEmpty(i) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(api: Api, app: TvApp?, picture: Long, width: Dp, look: Look, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var custom by remember(app?.pkg, picture) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app?.pkg, picture) { if (app != null && picture != 0L) custom = loadPicture(context, api, app.pkg, picture) }
    val banner = app?.banner
    val icon = app?.icon
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.width(width).height(width * 9 / 16).clip(shape).background(look.card)
            .border(if (focused) 4.dp else 0.dp, if (focused) look.accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }.tvClickable(onClick, onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            app == null -> Icon(Icons.Filled.Add, "Add an app", tint = look.soft, modifier = Modifier.size(36.dp))
            custom != null -> Image(custom!!, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            banner != null -> Image(banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            icon != null -> Image(icon, app.label, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
            else -> Text(app.label, color = look.ink, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    }
}

/**
 * Pressing OK opens; holding OK (the remote repeats the key) opens the menu instead. Handled
 * here from the key itself, so it works the same on every remote; touch and mice still work.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier = composed {
    var held by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (k.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && k.keyCode != KeyEvent.KEYCODE_ENTER && k.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return@onPreviewKeyEvent false
        when (k.action) {
            KeyEvent.ACTION_DOWN -> {
                if (k.repeatCount == 0) held = false
                else if (!held) { held = true; onLongClick() }
                true
            }
            KeyEvent.ACTION_UP -> { if (!held) onClick(); held = false; true }
            else -> false
        }
    }.combinedClickable(onClick = onClick, onLongClick = onLongClick)
}

/** A picture uploaded on the console for an app's tile, kept on the TV until it changes. */
private suspend fun loadPicture(context: Context, api: Api, pkg: String, stamp: Long): ImageBitmap? = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, "tiles").apply { mkdirs() }
    val file = File(dir, "$pkg-$stamp")
    if (!file.exists()) {
        val bytes = runCatching { api.bytes("/api/display/apps/$pkg/image") }.getOrNull() ?: return@withContext null
        dir.listFiles { f -> f.name.startsWith("$pkg-") }?.forEach { it.delete() }
        file.writeBytes(bytes)
    }
    BitmapFactory.decodeFile(file.path)?.asImageBitmap()
}
