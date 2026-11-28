package io.github.pretendgineering.famlogic

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.filled.Groups
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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

    /**
     * Every button press: the TV isn't idle. While the screen saver is up, the first press
     * only wakes it; holding OK asks Steward straight away.
     */
    // (Overriding Activity.dispatchKeyEvent is ordinary API; lint flags ComponentActivity's copy as restricted.)
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        TvSaver.touch()
        if (TvSaver.on) {
            val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
            if (ok && event.action == KeyEvent.ACTION_DOWN && event.repeatCount >= 1) { TvSaver.on = false; TvNav.request("steward"); return true }
            if (event.action == KeyEvent.ACTION_UP) TvSaver.on = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }
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

// ---- fitting the TV: everything is drawn on a 1024 × 576 board, stretched to fill the screen

/** How far in from the screen's edges to draw (percent), for TVs that cut the edges off. */
private val LocalTvEdges = compositionLocalOf { 0 }

/**
 * Draws [content] as if the screen were 1024 × 576 dp, stretched (or shrunk) to fill it
 * whatever the TV's resolution or display-size setting, and [edges] percent in from each side.
 */
@Composable
private fun TvScale(edges: Int, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().padding(horizontal = maxWidth * (edges / 100f), vertical = maxHeight * (edges / 100f))) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val scale = minOf(constraints.maxWidth / 1024f, constraints.maxHeight / 576f)
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
            TvButton(if (busy) "Connecting…" else "Connect", QUIET_LOOK, enabled = !busy && server.isNotEmpty() && code.isNotEmpty(), onClick = {
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
            })
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

    /** The screen saver: minutes idle before it starts (0 = never), what it shows, and its theme. */
    var saverMinutes by mutableIntStateOf(sp.getInt("saverMinutes", 10))
        private set
    var saverKind by mutableStateOf(sp.getString("saverKind", "clock") ?: "clock")
        private set
    var saverTheme by mutableStateOf(sp.getString("saverTheme", "seasonal") ?: "seasonal")
        private set
    /** Family updates (the feed) popping up over the screen saver, and over the home screen. */
    var popSaver by mutableStateOf(sp.getBoolean("popSaver", true))
        private set
    var popHome by mutableStateOf(sp.getBoolean("popHome", false))
        private set

    fun setSaver(minutes: Int = saverMinutes, kind: String = saverKind, theme: String = saverTheme) {
        saverMinutes = minutes; saverKind = kind; saverTheme = theme
        sp.edit().putInt("saverMinutes", minutes).putString("saverKind", kind).putString("saverTheme", theme).apply()
    }
    fun setPops(saver: Boolean = popSaver, home: Boolean = popHome) {
        popSaver = saver; popHome = home
        sp.edit().putBoolean("popSaver", saver).putBoolean("popHome", home).apply()
    }

    /** Apps hidden from the app drawer (shown again from Settings). */
    var hidden by mutableStateOf(sp.getString("hidden", "").orEmpty().split(",").filter { it.isNotBlank() }.toSet())
        private set

    fun hide(pkg: String) { hidden = hidden + pkg; sp.edit().putString("hidden", hidden.joinToString(",")).apply() }
    fun unhide(pkg: String) { hidden = hidden - pkg; sp.edit().putString("hidden", hidden.joinToString(",")).apply() }
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

/** Every Quick Access Module (the buttons down the right side): id, name, icon. Names follow the
 *  family's choice of module names (Thereabouts, Datebook…) or plain ones (Map, Calendar…). */
private val SIDE_BUTTONS: List<Triple<String, String, ImageVector>>
    get() = listOf(
        Triple("map", Access.name("map"), Icons.Filled.LocationOn),
        Triple("feed", "Feed", Icons.Filled.DynamicFeed),
        Triple("steward", Access.name("steward"), Icons.Filled.Mic),
        Triple("calendar", Access.name("calendar"), Icons.Filled.DateRange),
        Triple("meals", Access.name("meals"), Icons.Filled.Restaurant),
        Triple("meetings", Access.name("meetings"), Icons.Filled.Groups),
    )

/** What each Quick Access Module opens, for Settings. */
private val SIDE_BLURBS = mapOf(
    "map" to "Where everyone is, on a map; choose someone to zoom to them.",
    "feed" to "Today so far: arrivals, check-ins, chores done.",
    "steward" to "Ask the family's helper, by talking into the remote.",
    "calendar" to "What's coming up: the list, 3 days, the week or the month.",
    "meals" to "This week's meals.",
    "meetings" to "Family meetings to click through together.",
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

/*
 * The sky in the bottom-left corner. A quarter sun creeps in from the corner after
 * sunrise, is fully in at midday, and sinks back out before sunset; after a gap, a
 * quarter moon does the same through the night (fully in at midnight). Each one's
 * light spreads across the board as it rises: pale yellow by day, silver by night.
 * It moves so slowly you only notice it hours apart; the sun's rays turn gently.
 */
private fun skyRise(now: Long, from: Long, to: Long): Float {
    if (to <= from || now <= from || now >= to) return 0f
    val t = (now - from).toFloat() / (to - from)
    return kotlin.math.sin(t * Math.PI).toFloat() // 0 at the edges, 1 halfway
}

@Composable
private fun SkyCorner(now: Long, rise: Long, set: Long) {
    val m = 60_000L
    val sun = skyRise(now, rise + 20 * m, set - 20 * m)
    // The moon: from a while after sunset until a while before tomorrow's sunrise (or
    // from yesterday's sunset, early in the morning).
    val day = 86_400_000L
    val moon = maxOf(skyRise(now, set + 50 * m, rise + day - 50 * m), skyRise(now, set - day + 50 * m, rise - 50 * m))
    val spin = rememberInfiniteTransition(label = "sky")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(240_000, easing = LinearEasing)), label = "turn")
    Box(Modifier.fillMaxSize()) {
        // The light, spreading from the corner as it rises.
        Canvas(Modifier.fillMaxSize()) {
            val corner = Offset(0f, size.height)
            val reach = size.maxDimension * (0.35f + 0.85f * maxOf(sun, moon))
            if (sun > 0f) {
                drawRect(Color(0xFFFFF6D6).copy(alpha = .35f * sun)) // the pale yellow day
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .75f * sun), Color(0xFFFFF1B8).copy(alpha = .35f * sun), Color.Transparent), center = corner, radius = reach))
            }
            if (moon > 0f) drawRect(Brush.radialGradient(listOf(Color(0xFFCFD8F0).copy(alpha = .22f * moon), Color(0xFF8A94C8).copy(alpha = .08f * moon), Color.Transparent), center = corner, radius = reach))
        }
        val r = 190.dp
        // The sun: a quarter in the corner, coming in along the diagonal; its rays turn slowly.
        if (sun > 0f) Canvas(
            Modifier.align(Alignment.BottomStart).size(r * 2).offset(x = -r - r * (1f - sun), y = r + r * (1f - sun))
                .graphicsLayer { rotationZ = turn },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val rad = size.minDimension / 2
            for (i in 0 until 16) rotate(i * 22.5f, c) {
                drawLine(Color(0xFFFFC94D).copy(alpha = .55f), Offset(c.x, c.y - rad * .72f), Offset(c.x, c.y - rad * .98f), strokeWidth = rad * .05f, cap = StrokeCap.Round)
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4C2), Color(0xFFFFD45E)), center = c, radius = rad * .62f), rad * .62f, c)
        }
        // The moon: a soft crescent quarter.
        if (moon > 0f) Canvas(Modifier.align(Alignment.BottomStart).size(r * 2).offset(x = -r - r * (1f - moon), y = r + r * (1f - moon))) {
            val c = Offset(size.width / 2, size.height / 2)
            val rad = size.minDimension / 2 * .62f
            drawCircle(Color(0xFFE9EDF8).copy(alpha = .9f), rad, c)
            drawCircle(Color(0xFFCBD3EA).copy(alpha = .8f), rad * .16f, Offset(c.x + rad * .35f, c.y - rad * .3f))
            drawCircle(Color(0xFFCBD3EA).copy(alpha = .7f), rad * .1f, Offset(c.x + rad * .55f, c.y - rad * .62f))
        }
    }
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
private data class Look(val bg: Color, val card: Color, val ink: Color, val soft: Color, val accent: Color, val onAccent: Color = Color.White)

/** The message card's look (cream, whatever the time of day) and quiet time's (dark). */
private val CREAM_LOOK = Look(Color(0xFFFFFBF2), Color(0xFFF1EBDD), Color(0xFF2A2638), Color(0xFF2A2638).copy(alpha = .65f), Color(0xFF5B4596))
private val QUIET_LOOK = Look(Color(0xFF0E0D16), Color(0xFF1C1A29), Color(0xFFE6E1F5), Color(0xFF9A93B5), Color(0xFFB8A9E8), Color(0xFF1E1A2E))

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
        if (TvNav.open != null) TvSaver.on = false
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
    val look = Look(bg, card, ink, ink.copy(alpha = .65f), accent, if (dark < .5f) Color.White else Color(0xFF1E1A2E))
    val b = board
    val modules = b?.optJSONArray("modules")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    // The family's people (not displays), by id.
    val names = objs(b?.optJSONObject("members")?.optJSONArray("members"))
        .filter { it.optString("access") != "display" && it.optString("role") != "dependent" }.associate { it.optString("id") to it.optString("name") }

    Box(Modifier.fillMaxSize().background(bg)) {
        SkyCorner(now, rise, set)
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
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
                Launcher(api, config, installed, b?.optJSONArray("apps"), look, onMenu = { nav.menuFor = it })
            }
            Spacer(Modifier.width(20.dp))
            // Buttons down the right side, then the app drawer and Settings; Steward always
            // has its own round button at the bottom right.
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The buttons share the height: with many switched on they get shorter, then lose their labels.
                BoxWithConstraints(Modifier.weight(1f)) {
                    val shown = SIDE_BUTTONS.filter { (id) -> id in config.side && available(id, modules, b) }
                    val n = shown.size + 2
                    val each = ((maxHeight - 8.dp * (n - 1) - 2.dp) / n).coerceIn(34.dp, 58.dp)
                    val compact = each < 52.dp
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        shown.forEach { (id, label, icon) -> SideButton(label, icon, look, each, compact) { nav.view = id } }
                        Spacer(Modifier.height(2.dp))
                        SideButton("Apps", Icons.Filled.Apps, look, each, compact) { nav.pickFor = null; nav.view = "apps" }
                        SideButton("Settings", Icons.Filled.Settings, look, each, compact) { nav.view = "settings" }
                    }
                }
                if (b?.optBoolean("steward") == true) {
                    Spacer(Modifier.height(10.dp))
                    StewardOrb(look) { nav.view = "steward" }
                }
            }
        }

        BoardViews(nav, prefs, api, config, installed, modules, board, look) { scope.launch { load() } }

        // The screen saver, after the remote has sat unused a while (and nothing is open).
        LaunchedEffect(config.saverMinutes) {
            while (true) {
                delay(5_000)
                val idle = SystemClock.elapsedRealtime() - TvSaver.lastInput
                if (config.saverMinutes > 0 && !TvSaver.on && nav.view == null && nav.menuFor == null && idle > config.saverMinutes * 60_000L) TvSaver.on = true
            }
        }
        if (TvSaver.on) ScreenSaver(config, board, api, names)

        // Family updates: new feed entries pop up (over the screen saver, or the board, as chosen).
        val feedNow = objs(board?.optJSONObject("feed")?.optJSONArray("items"))
        var seenUntil by remember { mutableLongStateOf(-1L) }
        val pops = remember { mutableStateListOf<JSONObject>() }
        LaunchedEffect(board) {
            val latest = feedNow.maxOfOrNull { it.optLong("time") } ?: 0L
            if (seenUntil < 0) seenUntil = maxOf(latest, System.currentTimeMillis() - 60_000)
            else {
                pops += feedNow.filter { it.optLong("time") > seenUntil }.sortedBy { it.optLong("time") }
                seenUntil = maxOf(seenUntil, latest)
            }
        }
        val popsOn = if (TvSaver.on) config.popSaver else config.popHome
        LaunchedEffect(popsOn) { if (!popsOn) pops.clear() }
        if (popsOn) FeedPopup(pops, look, TvSaver.on)

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
    var person by mutableStateOf<String?>(null) // the map zoomed to someone (from the feed)
}

@Composable
private fun BoardViews(nav: BoardNav, prefs: Prefs, api: Api, config: TvConfig, installed: List<TvApp>, modules: Set<String>, board: JSONObject?, look: Look, reload: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pictures = objs(board?.optJSONArray("apps")).associate { it.optString("pkg") to it.optLong("image") }
    // An app's own picture for its tile, chosen from a file on the TV (a file manager app opens).
    var pictureFor by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pkg = pictureFor
        pictureFor = null
        if (uri != null && pkg != null) scope.launch {
            runCatching { api.putBytes("/api/display/apps/$pkg/image", withContext(Dispatchers.IO) { shrinkPicture(context, uri) }, "image/jpeg") }
                .onSuccess { Toast.makeText(context, "Picture changed", Toast.LENGTH_SHORT).show(); reload() }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't use that picture", Toast.LENGTH_LONG).show() }
        }
    }
    fun choosePicture(pkg: String) {
        pictureFor = pkg
        try { picker.launch("image/*") } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No file manager here: install one (such as FX File Explorer) to choose pictures", Toast.LENGTH_LONG).show()
        }
    }
    when (nav.view) {
        "map" -> MapView(prefs, api, look, nav.person) { nav.view = null; nav.person = null }
        "feed" -> FeedView(board?.optJSONObject("feed"), look, onOpen = { e ->
            when (e.optString("type")) {
                "chore_done", "reward_spent" -> nav.view = "chores"
                else -> if (e.optString("memberId").isNotEmpty() && "thereabouts" in modules) { nav.person = e.optString("memberId"); nav.view = "map" }
            }
        }) { nav.view = null }
        "chores" -> ListView(Access.name("chores"), look, { nav.view = null }) { board?.optJSONObject("chores")?.let { ChoreLines(it, emptyMap(), look) } }
        "calendar" -> CalendarView(api, look) { nav.view = null }
        "meals" -> ListView(Access.name("meals"), look, { nav.view = null }) { board?.optJSONObject("meals")?.let { MealLines(it, look) } }
        "steward" -> StewardView(prefs, api, look) { nav.view = null; reload() }
        "meetings" -> MeetingsView(api, look, objs(board?.optJSONObject("members")?.optJSONArray("members"))
            .filter { it.optString("access") != "display" && it.optString("role") != "dependent" }) { nav.view = null }
        "apps" -> AppDrawer(installed.filter { it.pkg !in config.hidden && it !in launcherApps(config, installed) }, look, nav.pickFor, onPick = { pkg -> nav.pickFor?.let { config.setApp(it, pkg) }; nav.pickFor = null; nav.view = null }, onMenu = { nav.menuFor = it }) { nav.pickFor = null; nav.view = null }
        "settings" -> SettingsView(config, installed, modules, board, look) { nav.view = null }
    }
    nav.menuFor?.let { pkg ->
        installed.find { it.pkg == pkg }?.let { app ->
            AppMenu(app, config, look, hasPicture = (pictures[pkg] ?: 0L) > 0L, onPicture = { choosePicture(pkg) },
                onOwnPicture = { scope.launch { runCatching { api.call("DELETE", "/api/display/apps/$pkg/image") }; reload() } }) { nav.menuFor = null }
        } ?: run { nav.menuFor = null }
    }
}

// ---- Huddle: family meetings, clicked through together on the TV

/** Someone who can take a job: id, name, colour. */
private data class Person(val id: String, val name: String, val color: String)

@Composable
private fun MeetingsView(api: Api, look: Look, members: List<JSONObject>, onBack: () -> Unit) {
    val people = remember(members) { members.map { Person(it.optString("id"), it.optString("name"), it.optString("color")) } }
    var data by remember { mutableStateOf<MeetingsData?>(null) }
    var open by remember { mutableStateOf<Meeting?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { data = runCatching { api.meetings() }.getOrDefault(data) }
    open?.let { m -> MeetingTv(api, look, m, people, data?.canRun == true) { open = null; refresh++ }; return }
    val first = remember { FocusRequester() }
    val d = data
    ViewFrame(Access.name("meetings"), look, onBack, start = if (d?.meetings?.isNotEmpty() == true) first else null) {
        when {
            d == null -> Empty("Loading…", look)
            d.meetings.isEmpty() -> Empty("No meetings yet. A parent can make one in the app or the console, or ask Steward.", look)
            else -> LazyColumn(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(d.meetings) { i, m ->
                    MenuRow("${m.title}   ·   ${listOfNotNull(m.date.ifEmpty { null }, "${m.slides.size} topics").joinToString(" · ")}", look,
                        if (i == 0) Modifier.focusRequester(first) else Modifier) { open = m }
                }
            }
        }
    }
}

/** One meeting, a topic at a time: what it's about, its jobs (and who has them), and its notes. */
@Composable
private fun MeetingTv(api: Api, look: Look, start: Meeting, people: List<Person>, canRun: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var m by remember { mutableStateOf(start) }
    var at by remember { mutableIntStateOf(0) }
    var jobMenu by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf<String?>(null) } // "job" or "note": typing or saying one
    fun act(f: suspend Api.() -> Meeting) = scope.launch {
        runCatching { api.f() }.onSuccess { m = it }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
    }
    val next = remember { FocusRequester() }
    ViewFrame(m.title, look, onBack, start = next) {
        if (m.slides.isEmpty()) { Empty("This meeting has no topics yet.", look); return@ViewFrame }
        val i = at.coerceIn(0, m.slides.size - 1)
        val s = m.slides[i]
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // The topic, big.
                Column(Modifier.weight(1.5f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(look.card).padding(28.dp)) {
                    Text("${i + 1} of ${m.slides.size}", color = look.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(s.title, color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp)
                    Spacer(Modifier.height(12.dp))
                    if (s.text.isNotEmpty()) Text(s.text, color = look.ink, fontSize = 22.sp, lineHeight = 30.sp)
                }
                // Its jobs and notes.
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(6.dp)) {
                    item { Heading("Jobs", look) }
                    if (s.jobs.isEmpty()) item { Text("None yet.", color = look.soft, fontSize = 15.sp) }
                    itemsIndexed(s.jobs) { k, j ->
                        val who = people.find { it.id == j.who }
                        MenuRow((if (j.sent) "📋  " else "") + j.text + "  —  " + (who?.name ?: "needs a volunteer"), look) { if (canRun) jobMenu = k }
                    }
                    if (canRun) item { TvButton("Add a job", look, icon = Icons.Filled.Add) { asking = "job" } }
                    item { Heading("Notes", look) }
                    if (s.notes.isEmpty()) item { Text("None yet.", color = look.soft, fontSize = 15.sp) }
                    items(s.notes) { n -> Text("• $n", color = look.ink, fontSize = 16.sp) }
                    if (canRun) item { TvButton("Add a note", look, icon = Icons.Filled.Add) { asking = "note" } }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("‹ Back", look, enabled = i > 0) { at = i - 1 }
                TvButton("Next ›", look, Modifier.focusRequester(next), enabled = i < m.slides.size - 1) { at = i + 1 }
                Spacer(Modifier.weight(1f))
                if (canRun) TvButton("Send jobs to the Docket", look) {
                    scope.launch {
                        runCatching { api.sendMeeting(m.id) }.onSuccess { (mm, n) ->
                            m = mm
                            Toast.makeText(context, if (n == 0) "Nothing new to send (jobs need someone first)" else "$n ${if (n == 1) "job" else "jobs"} on the Docket", Toast.LENGTH_LONG).show()
                        }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
        jobMenu?.let { k ->
            val opts = people.map { p -> "${p.name} will do it" to { act { setMeetingJob(m.id, i, k, p.id) }; Unit } } +
                listOf<Pair<String, () -> Unit>>("Needs a volunteer" to { act { setMeetingJob(m.id, i, k, "") } }, "Remove the job" to { act { setMeetingJob(m.id, i, k, "", remove = true) } })
            TvMenu(s.jobs.getOrNull(k)?.text ?: "Job", opts, look) { jobMenu = null }
        }
        asking?.let { what ->
            TvTextEntry(if (what == "job") "A job" else "A note", look, onDone = { text ->
                asking = null
                if (text.isNotBlank()) act { if (what == "job") addMeetingJob(m.id, i, text.trim()) else addMeetingNote(m.id, i, text.trim()) }
            })
        }
    }
}

/** Typing or saying a few words on the TV (the remote's microphone, or the TV's keyboard). */
@Composable
private fun TvTextEntry(title: String, look: Look, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
    }
    val speak = remember { FocusRequester() }
    Overlay({ onDone("") }) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(min = 420.dp, max = 640.dp).clip(RoundedCornerShape(28.dp)).background(look.bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                OutlinedTextField(text, { text = it.take(200) }, modifier = Modifier.fillMaxWidth(), singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Speak", look, Modifier.focusRequester(speak), icon = Icons.Filled.Mic) {
                        runCatching {
                            listen.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                        }
                    }
                    TvButton("Add", look, enabled = text.isNotBlank()) { onDone(text) }
                    TvButton("Cancel", look) { onDone("") }
                }
            }
        }
    }
    FocusWhenReady(speak, title)
}

/**
 * Scroll only as far as needed to show what's selected (the TV's own default keeps the
 * selection at a fixed point, which made the app grid hop up and down moving sideways).
 */
@OptIn(ExperimentalFoundationApi::class)
private val CalmScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset >= 0 && offset + size <= containerSize -> 0f
        offset < 0 || size > containerSize -> offset
        else -> offset + size - containerSize
    }
}

/** A picture from the TV's files, made a sensible size for a tile (at most 1280 wide, as a JPEG). */
private fun shrinkPicture(context: Context, uri: Uri): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IllegalArgumentException("That file isn't a picture")
    val scaled = if (bmp.width > 1280) android.graphics.Bitmap.createScaledBitmap(bmp, 1280, bmp.height * 1280 / bmp.width, true) else bmp
    return java.io.ByteArrayOutputStream().also { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
}

/** What happened today, across the screen: choose an entry to open where it happened (the map, Upkeep). */
@Composable
private fun FeedView(feed: JSONObject?, look: Look, onOpen: (JSONObject) -> Unit, onBack: () -> Unit) {
    val items = objs(feed?.optJSONArray("items")).reversed()
    val first = remember { FocusRequester() }
    ViewFrame("Today", look, onBack, start = if (items.isNotEmpty()) first else null) {
        if (items.isEmpty()) { Empty("A quiet day so far.", look); return@ViewFrame }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
            itemsIndexed(items) { i, e ->
                FeedRow(e, look, if (i == 0) Modifier.focusRequester(first) else Modifier) { onOpen(e) }
            }
        }
    }
}

private fun feedWhat(e: JSONObject): String = when (e.optString("type")) {
    "arrived" -> "got to ${e.optString("place", "a place")}"
    "left" -> "left ${e.optString("place", "a place")}"
    "checkin" -> "checked in" + e.optString("note").takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
    "chore_done" -> "did ${e.optString("title")}"
    "reward_spent" -> "got ${e.optString("title")}"
    "trip" -> "finished a trip"
    "drive_start" -> "started driving"
    "sos" -> "sent an SOS"
    "sos_end" -> "is OK"
    "battery_low" -> "is low on battery (${e.optInt("battery")}%)"
    else -> e.optString("type").replace('_', ' ')
}

private fun feedEmoji(type: String) = when (type) {
    "arrived" -> "📍"; "left" -> "👋"; "checkin" -> "✅"; "chore_done" -> "🧹"; "reward_spent" -> "⭐"; "trip" -> "🗺️"
    "drive_start" -> "🚗"; "sos" -> "🆘"; "sos_end" -> "💚"; "battery_low" -> "🔋"; else -> "•"
}

@Composable
private fun FeedRow(e: JSONObject, look: Look, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) look.onAccent else look.ink
    Row(
        modifier.fillMaxWidth().tvFocus(look, look.card, 18.dp, grow = 1.015f, fill = true) { focused = it }.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(feedEmoji(e.optString("type")), Modifier.width(40.dp), fontSize = 24.sp)
        Column(Modifier.weight(1f)) {
            Text("${e.optString("name")} ${feedWhat(e)}", color = ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time(e.optLong("time")), color = if (focused) look.onAccent.copy(alpha = .8f) else look.soft, fontSize = 13.sp)
        }
        Text("›", color = ink, fontSize = 26.sp)
    }
}

/** Datebook on the TV: the list, or the calendar (3 days, work week, week, month). */
@Composable
private fun CalendarView(api: Api, look: Look, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(CalMode.Week) }
    var anchor by remember { mutableLongStateOf(CalDays.day(System.currentTimeMillis())) }
    var picked by remember { mutableLongStateOf(anchor) }
    var events by remember { mutableStateOf(emptyList<CalOccurrence>()) }
    LaunchedEffect(mode, anchor) {
        val days = CalDays.days(mode, anchor)
        events = runCatching { api.events(days.first(), CalDays.addDays(days.last(), 1)).events }.getOrDefault(emptyList())
    }
    val colors = CalColors(look.card, look.ink, look.soft, look.accent, look.onAccent)
    val first = remember { FocusRequester() }
    ViewFrame(Access.name("calendar"), look, onBack, start = first) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalMode.entries.forEachIndexed { i, m ->
                    TvButton(m.label, look, if (m == mode) Modifier.focusRequester(first) else Modifier, selected = m == mode) { mode = m; anchor = CalDays.day(System.currentTimeMillis()); picked = anchor }
                }
                Spacer(Modifier.weight(1f))
                TvButton("‹", look) { anchor = CalDays.step(mode, anchor, -1) }
                Text(CalDays.title(mode, anchor), color = look.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                TvButton("›", look) { anchor = CalDays.step(mode, anchor, 1) }
                TvButton("Today", look) { anchor = CalDays.day(System.currentTimeMillis()); picked = anchor }
            }
            Spacer(Modifier.height(12.dp))
            val day: @Composable (Long) -> Modifier = { d ->
                Modifier.onFocusChanged { if (it.isFocused) picked = d }.tvFocus(look, Color.Transparent, 14.dp, grow = 1.02f).focusable()
            }
            when (mode) {
                CalMode.List -> {
                    val days = CalDays.days(CalMode.List, anchor).filter { CalDays.on(events, it).isNotEmpty() }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                        if (days.isEmpty()) item { Empty("Nothing on the calendar for the next month.", look) }
                        items(days) { d -> CalDayList(d, events, colors, Modifier.fillMaxWidth().then(day(d)).clip(RoundedCornerShape(14.dp)).background(look.card).padding(14.dp)) }
                    }
                }
                CalMode.Month -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CalMonthGrid(anchor, events, colors, picked, Modifier.weight(2.2f), dayModifier = day)
                    CalDayList(picked, events, colors, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(look.card).padding(14.dp))
                }
                else -> CalDayColumns(mode, anchor, events, colors, dayModifier = day)
            }
        }
    }
}

/** Whether a side button can work here: its module is on (and Steward is set up for the TV). */
private fun available(id: String, modules: Set<String>, board: JSONObject?) = when (id) {
    "map" -> "thereabouts" in modules
    "calendar" -> "calendar" in modules
    "meals" -> "meals" in modules
    "meetings" -> "meetings" in modules
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
private fun Line(main: String, sub: String, look: Look, mark: String = "", markColor: Color = look.ink, pic: String = "") {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mark.isNotEmpty()) Text(mark, Modifier.width(24.dp), color = markColor, fontSize = 17.sp)
        // A routine step's picture, so younger children can follow it without reading.
        if (pic.isNotEmpty()) Text(pic, Modifier.width(30.dp), fontSize = 20.sp)
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
                mark = if (done) "✓" else "○", markColor = if (done) look.accent else look.soft, pic = i.optString("emoji"))
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
    others.forEach { (title, left) -> Line(title, "$left left", look, mark = "≡", markColor = look.soft) }
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
private fun SideButton(label: String, icon: ImageVector, look: Look, height: Dp = 58.dp, compact: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) look.onAccent else look.ink
    Column(
        Modifier.width(68.dp).height(height).tvFocus(look, look.card, 18.dp, fill = true) { focused = it }.clickable(onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, if (compact) label else null, tint = tint, modifier = Modifier.size(24.dp))
        if (!compact) Text(label, color = if (focused) look.onAccent else look.soft, fontSize = 11.sp, maxLines = 1)
    }
}

/** Steward's own button, always at the bottom right: round, in the accent colour. */
@Composable
private fun StewardOrb(look: Look, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).tvFocus(look, look.accent, 32.dp, grow = 1.12f, ring = look.ink).clickable(onClickLabel = "Ask Steward", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, "Ask Steward", tint = look.onAccent, modifier = Modifier.size(30.dp))
    }
}

/**
 * How the remote's selection shows: the focused thing eases up a little in size and gets an
 * accent ring and tint (or, with [fill], fills with the accent). Drawn and scaled in the draw
 * phase, so moving the selection animates smoothly without redrawing the board.
 */
private fun Modifier.tvFocus(look: Look, base: Color, radius: Dp, grow: Float = 1.06f, fill: Boolean = false, ring: Color = look.accent, onFocus: (Boolean) -> Unit = {}): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "focus")
    this
        .onFocusChanged { if (focused != it.isFocused) { focused = it.isFocused; onFocus(it.isFocused) } }
        .graphicsLayer { val k = 1f + (grow - 1f) * t; scaleX = k; scaleY = k }
        .drawWithContent {
            val r = radius.toPx()
            drawRoundRect(if (fill) lerp(base, look.accent, t) else lerp(base, look.accent, .34f * t), cornerRadius = CornerRadius(r))
            drawContent()
            if (t > 0f) {
                // A soft halo outside, then a solid ring: easy to see from across the room.
                val h = 9.dp.toPx()
                drawRoundRect(ring.copy(alpha = .28f * t), topLeft = Offset(-h / 2, -h / 2), size = Size(size.width + h, size.height + h),
                    cornerRadius = CornerRadius(r + h / 2), style = Stroke(h))
                val w = 5.dp.toPx()
                drawRoundRect(ring.copy(alpha = t), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius((r - w / 2).coerceAtLeast(0f)), style = Stroke(w))
            }
        }
        // What's inside (and the system's own press and focus overlay) keeps to the same rounded shape.
        .clip(RoundedCornerShape(radius))
}

/** A button for the remote: outlined, filling with the accent when selected. */
@Composable
private fun TvButton(text: String, look: Look, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = when { !enabled -> look.soft.copy(alpha = .45f); focused -> look.onAccent; selected -> look.accent; else -> look.ink }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.tvFocus(look, look.bg, 24.dp, fill = true) { focused = it }
            .border(if (selected) 2.5.dp else 1.5.dp, when { selected -> look.accent; enabled -> look.soft.copy(alpha = .5f); else -> look.soft.copy(alpha = .2f) }, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (focused) look.onAccent else look.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = ink, fontSize = 17.sp, maxLines = 1)
    }
}

/** A small menu over everything (an app's options, a button's job): the first choice is selected. */
@Composable
private fun TvMenu(title: String, options: List<Pair<String, () -> Unit>>, look: Look, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    // The menu opens while OK is still held (that's how it was asked for): ignore OK until the
    // remote lets go and presses it again, so the held press doesn't pick the first choice.
    var armed by remember { mutableStateOf(false) }
    Overlay(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent
            val ok = k.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == KeyEvent.KEYCODE_ENTER || k.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (ok && k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) armed = true
            ok && !armed
        }, contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(min = 360.dp, max = 520.dp).clip(RoundedCornerShape(28.dp)).background(look.bg).padding(24.dp)) {
                Text(title, color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(6.dp)) {
                    itemsIndexed(options) { i, (label, act) ->
                        MenuRow(label, look, if (i == 0) Modifier.focusRequester(focus) else Modifier) { act(); onDismiss() }
                    }
                }
            }
        }
    }
    FocusWhenReady(focus, title)
}

@Composable
private fun MenuRow(label: String, look: Look, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().tvFocus(look, look.card, 16.dp, grow = 1.03f, fill = true) { focused = it }.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, color = if (focused) look.onAccent else look.ink, fontSize = 18.sp)
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
private fun ViewFrame(title: String, look: Look, onBack: () -> Unit, start: FocusRequester? = null, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    Overlay(onBack) {
        Column(Modifier.fillMaxSize().background(look.bg).padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                TvButton("Back", look, Modifier.focusRequester(focus), onClick = onBack)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
    // The selection starts on the view's own content when it has some (the first app), else on Back.
    FocusWhenReady(start ?: focus, title)
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
private fun MapView(prefs: Prefs, api: Api, look: Look, focusOn: String? = null, onBack: () -> Unit) {
    val res by produceState<MembersResponse?>(null) {
        while (true) { value = runCatching { api.members(0) }.getOrDefault(value); delay(30_000) }
    }
    // Who the map is zoomed to ("" = everyone), and a counter so choosing again re-centres.
    var chosen by remember { mutableStateOf(focusOn.orEmpty()) }
    var seq by remember { mutableIntStateOf(1) }
    val first = remember { FocusRequester() }
    ViewFrame("Where everyone is", look, onBack, start = first) {
        val r = res
        if (r == null) { Empty("Loading…", look); return@ViewFrame }
        val people = r.members.filter { it.access != "display" && it.role != "dependent" }
        val who = people.find { it.id == chosen }
        val loc = who?.loc
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LazyColumn(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(6.dp)) {
                item {
                    PersonRow("Everyone", "${people.count { it.loc != null }} on the map", null, look, chosen.isEmpty(), if (chosen.isEmpty()) Modifier.focusRequester(first) else Modifier) { chosen = ""; seq++ }
                }
                items(people, key = { it.id }) { p ->
                    PersonRow(p.name, if (p.loc == null) "No location yet" else Seen.where(p), p, look, p.id == chosen,
                        if (p.id == chosen) Modifier.focusRequester(first) else Modifier) { chosen = p.id; seq++ }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))) {
                OsmMap(
                    members = people.filter { it.loc != null && !it.paused },
                    tiles = MapStyles.resolve(prefs.mapStyle, LocalDarkTheme.current, r.server.tileUrl, r.server.tileUrlDark, r.server.tileAttribution, prefs.dimMap),
                    camera = if (loc != null) CameraCommand.Center(loc.lat, loc.lon, seq) else CameraCommand.FitAll(seq),
                    statusOf = { Seen.where(it) },
                    places = r.places,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** A person in the map's list (or "Everyone"): choose one to zoom the map to them. */
@Composable
private fun PersonRow(name: String, sub: String, p: Member?, look: Look, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().tvFocus(look, if (chosen) look.accent.copy(alpha = .22f) else look.card, 16.dp, grow = 1.03f, fill = true) { focused = it }
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (p != null) Color(parseColor(p.color)) else look.accent), contentAlignment = Alignment.Center) {
            if (p != null) Text(p.emoji.ifEmpty { initialOf(p.name) }, color = Color.White, fontSize = 16.sp)
            else Icon(Icons.Filled.LocationOn, null, tint = look.onAccent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, color = if (focused) look.onAccent else look.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = if (focused) look.onAccent.copy(alpha = .8f) else look.soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            if (state != "nomic") TvButton(if (heard.isEmpty()) "Ask" else "Ask again", look, Modifier.focusRequester(focus), icon = Icons.Filled.Mic) { start() }
        }
    }
    if (state == "answered") FocusWhenReady(focus, heard)
}

// ---- settings: the screen, the launcher, the side buttons, and the remote helper

@Composable
@OptIn(ExperimentalFoundationApi::class)
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
    var sheet by remember { mutableStateOf<String?>(null) } // modules, hidden, saver, buttons
    ViewFrame("Settings", look, onBack) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides CalmScroll) {
        LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(6.dp)) {
            item { Heading("The launcher", look) }
            item {
                SettingRow("Tiles on the launcher", "Up to ${config.slots} along the bottom.", look) {
                    Stepper(look, config.slots > 1, config.slots < 6, { config.changeSlots(config.slots - 1) }, { config.changeSlots(config.slots + 1) })
                }
            }
            item { OpenRow("Quick Access Modules", "The buttons down the right side: " + SIDE_BUTTONS.filter { (id) -> id in config.side && available(id, modules, board) }.joinToString(", ") { it.second }.ifEmpty { "none" } + ".", look) { sheet = "modules" } }
            if (config.hidden.isNotEmpty()) item { OpenRow("Hidden apps", "${config.hidden.size} hidden from Apps.", look) { sheet = "hidden" } }
            item { Heading("Screen saver", look) }
            item {
                OpenRow("Screen saver", if (config.saverMinutes == 0) "Off." else "After ${config.saverMinutes} minutes: ${saverKindName(config.saverKind)}.", look) { sheet = "saver" }
            }
            item {
                SwitchRow("Family updates over the screen saver", "What happens (arrivals, chores done) pops up in a corner for a few seconds.", config.popSaver, true, look) { config.setPops(saver = !config.popSaver) }
            }
            item {
                SwitchRow("Family updates over the home screen", "The same, while the board is showing.", config.popHome, true, look) { config.setPops(home = !config.popHome) }
            }
            item { Heading("Home and remote", look) }
            item {
                SettingRow("Remote helper", if (helperOn) "On." else "Off. In the TV's Accessibility settings, switch on FamLogic TV remote.", look, Icons.Filled.Accessibility) {
                    TvButton("Accessibility settings", look) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "This TV has no Accessibility settings") }
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
                OpenRow("Remote buttons", if (!helperOn) "Needs the remote helper." else if (keys.isEmpty()) "Give a remote button a new job: an app, FamLogic or Steward." else keys.entries.joinToString(", ") { TvRemoteSettings.keyName(it.key) + ": " + actionName(it.value, installed).lowercase() } + ".",
                    look, Icons.Filled.Accessibility, enabled = helperOn) { sheet = "buttons" }
            }
            item {
                SettingRow("Make FamLogic the home app", "On TVs that let you choose a home app (Google TV doesn't: use the override above).", look) {
                    TvButton("Choose", look) { open(Intent(Settings.ACTION_HOME_SETTINGS), "This TV doesn't let you choose: use Override current launcher") }
                }
            }
            item { Heading("The screen", look) }
            item {
                SettingRow("Fit to the screen", "If the TV cuts off the edges, draw further in: ${config.edges}%.", look) {
                    Stepper(look, config.edges > 0, config.edges < 10, { config.changeEdges(config.edges - 1) }, { config.changeEdges(config.edges + 1) })
                }
            }
        }
        }
    }
    when (sheet) {
        "modules" -> TvSheet("Quick Access Modules", look, { sheet = null }) { first ->
            SIDE_BUTTONS.filter { (id) -> available(id, modules, board) }.forEachIndexed { i, (id, label) ->
                item(key = id) { SwitchRow(label, SIDE_BLURBS[id].orEmpty(), id in config.side, true, look, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { config.toggleSide(id) } }
            }
        }
        "hidden" -> TvSheet("Hidden apps", look, { sheet = null }) { first ->
            val hidden = installed.filter { it.pkg in config.hidden }
            if (hidden.isEmpty()) item { Text("No hidden apps.", color = look.soft, fontSize = 16.sp) }
            hidden.forEachIndexed { i, app ->
                item(key = app.pkg) { MenuRow("Show ${app.label} in Apps", look, if (i == 0) Modifier.focusRequester(first) else Modifier) { config.unhide(app.pkg) } }
            }
        }
        "saver" -> SaverSheet(config, board, look, onTry = { sheet = null; onBack(); TvSaver.on = true }) { sheet = null }
        "buttons" -> TvSheet("Remote buttons", look, { learning = false; TvNav.capture = null; sheet = null }) { first ->
            if (learning) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Press the button on the remote you'd like to use", color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                        Text("Any button but the arrows, OK and Back.", color = look.soft, fontSize = 16.sp)
                        TvButton("Cancel", look, Modifier.focusRequester(first)) { learning = false; TvNav.capture = null }
                    }
                }
            } else {
                item { MenuRow("Add a button", look, Modifier.focusRequester(first)) { learning = true; TvNav.capture = { code -> learning = false; learned = code } } }
                if (keys.isEmpty()) item { Text("No buttons with a new job yet.", Modifier.padding(8.dp), color = look.soft, fontSize = 15.sp) }
                keys.entries.toList().forEach { (code, action) ->
                    item(key = "k$code") {
                        SettingRow(TvRemoteSettings.keyName(code), actionName(action, installed), look) {
                            TvButton("Remove", look) { keys = keys - code; TvRemoteSettings.setKeys(context, keys) }
                        }
                    }
                }
            }
        }
    }
    learned?.let { code ->
        ActionPicker(TvRemoteSettings.keyName(code), installed, look, onPick = { a -> keys = keys + (code to a); TvRemoteSettings.setKeys(context, keys); learned = null }) { learned = null }
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

/** A settings row that opens a popup of its own. */
@Composable
private fun OpenRow(title: String, sub: String, look: Look, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().tvFocus(look, look.card, 18.dp, grow = 1.02f, fill = true) { focused = it }.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (focused) look.onAccent else if (enabled) look.ink else look.soft
        if (icon != null) Icon(icon, null, tint = if (focused) look.onAccent else look.accent, modifier = Modifier.padding(end = 12.dp).size(22.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = ink, fontSize = 18.sp)
            if (sub.isNotEmpty()) Text(sub, color = if (focused) look.onAccent.copy(alpha = .8f) else look.soft, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = ink, fontSize = 26.sp)
    }
}

/** A popup over Settings: a title and a list, with the first entry selected. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvSheet(title: String, look: Look, onDismiss: () -> Unit, content: LazyListScope.(first: FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    Overlay(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(min = 480.dp, max = 760.dp).fillMaxHeight(.86f).clip(RoundedCornerShape(28.dp)).background(look.bg).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), color = look.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    TvButton("Done", look, onClick = onDismiss)
                }
                Spacer(Modifier.height(12.dp))
                CompositionLocalProvider(LocalBringIntoViewSpec provides CalmScroll) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(6.dp)) { content(first) }
                }
            }
        }
    }
    FocusWhenReady(first, title)
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, enabled: Boolean, look: Look, icon: ImageVector? = null, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        modifier.fillMaxWidth().tvFocus(look, look.card, 18.dp, grow = 1.02f).clickable(enabled = enabled, onClick = onToggle).padding(horizontal = 18.dp, vertical = 10.dp),
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
private fun Stepper(look: Look, canLess: Boolean, canMore: Boolean, less: () -> Unit, more: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvButton("Less", look, enabled = canLess, onClick = less)
        TvButton("More", look, enabled = canMore, onClick = more)
    }
}

/** What a remapped button does: FamLogic, Steward, or an app. */
@Composable
private fun ActionPicker(key: String, installed: List<TvApp>, look: Look, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf<Pair<String, () -> Unit>>("Open FamLogic" to { onPick("home") }, "Ask Steward" to { onPick("steward") }) +
        installed.map { app -> "Open ${app.label}" to { onPick("app:" + app.pkg) } }
    TvMenu(key, options, look, onDismiss)
}

// ---- the app drawer: every app on the TV

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AppDrawer(installed: List<TvApp>, look: Look, pickFor: Int?, onPick: (String) -> Unit, onMenu: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    ViewFrame(if (pickFor != null) "Choose an app for tile ${pickFor + 1}" else "Apps", look, onBack, start = if (installed.isNotEmpty()) first else null) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides CalmScroll) {
        LazyVerticalGrid(GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(8.dp)) {
            itemsIndexed(installed, key = { _, a -> a.pkg }) { i, app ->
                DrawerTile(app, look, if (i == 0) Modifier.focusRequester(first) else Modifier, onClick = { if (pickFor != null) onPick(app.pkg) else launch(context, app.pkg) }, onLongClick = { onMenu(app.pkg) })
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerTile(app: TvApp, look: Look, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val banner = app.banner
    val icon = app.icon
    Column(
        modifier.tvFocus(look, Color.Transparent, 16.dp, grow = 1.08f).tvClickable(onClick, onLongClick).padding(6.dp),
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
private fun AppMenu(app: TvApp, config: TvConfig, look: Look, hasPicture: Boolean, onPicture: () -> Unit, onOwnPicture: () -> Unit, onDone: () -> Unit) {
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
            add("Change picture" to onPicture)
            if (hasPicture) add("Use the app's own picture" to onOwnPicture)
        } else {
            add("Add to the launcher" to {
                if (!config.addApp(app.pkg)) Toast.makeText(context, "The launcher is full: remove a tile first", Toast.LENGTH_LONG).show()
            })
            add("Hide from Apps" to { config.hide(app.pkg) })
        }
        add("App info" to { start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) })
        add("Uninstall" to { start(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) })
    }
    TvMenu(app.label, options, look, onDone)
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
                TvButton("End quiet time", QUIET_LOOK, Modifier.focusRequester(focus), onClick = onEnd)
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
                        TvButton("OK", CREAM_LOOK, Modifier.focusRequester(focus)) { Voice.stop(); onOk() }
                        TvButton("Read aloud", CREAM_LOOK, icon = Icons.AutoMirrored.Filled.VolumeUp) { scope.launch { Voice.speak(context, prefs, m.optString("text")) } }
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
private fun Launcher(api: Api, config: TvConfig, installed: List<TvApp>, serverApps: JSONArray?, look: Look, onMenu: (String) -> Unit) {
    val context = LocalContext.current
    val pictures = objs(serverApps).associate { it.optString("pkg") to it.optLong("image") }
    val apps = launcherApps(config, installed)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 12.dp
        val n = config.slots
        // Tiles are sized for the chosen number, 16:9 like the apps' own banners, and no taller than 100dp.
        val w: Dp = minOf((maxWidth - gap * (n - 1)) / n, 100.dp * 16 / 9)
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(w * 9 / 16), contentAlignment = Alignment.Center) {
                Text("Add apps here from Apps: hold OK on one and choose Add to the launcher.", color = look.soft, fontSize = 15.sp)
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
            // Centred as a group, however many there are.
            apps.forEach { app ->
                AppTile(api, app, pictures[app.pkg] ?: 0L, w, look, onClick = { launch(context, app.pkg) }, onLongClick = { onMenu(app.pkg) })
            }
        }
    }
}

/** The apps on the launcher, in order (ones since uninstalled are left out). */
private fun launcherApps(config: TvConfig, installed: List<TvApp>): List<TvApp> =
    (config.apps ?: emptyList()).take(config.slots).filter { it.isNotEmpty() }.mapNotNull { p -> installed.find { it.pkg == p } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(api: Api, app: TvApp?, picture: Long, width: Dp, look: Look, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var custom by remember(app?.pkg, picture) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app?.pkg, picture) { if (app != null && picture != 0L) custom = loadPicture(context, api, app.pkg, picture) }
    val banner = app?.banner
    val icon = app?.icon
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.width(width).height(width * 9 / 16).tvFocus(look, look.card, 18.dp, grow = 1.08f).clip(shape).tvClickable(onClick, onLongClick),
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

// ---- the screen saver, and family updates popping up

/** Whether the screen saver is up, and when the remote was last used. */
object TvSaver {
    var on by mutableStateOf(false)
    @Volatile var lastInput = SystemClock.elapsedRealtime()
    fun touch() { lastInput = SystemClock.elapsedRealtime() }
}

private fun saverKindName(kind: String) = when (kind) {
    "photos" -> "family photos"; "stream" -> "the photo link"; "theme" -> "a theme"; "mix" -> "a mix"; else -> "the family clock"
}

@Composable
private fun SaverSheet(config: TvConfig, board: JSONObject?, look: Look, onTry: () -> Unit, onDismiss: () -> Unit) {
    val photos = board?.optJSONArray("photos")?.length() ?: 0
    val stream = board?.optBoolean("stream") == true
    TvSheet("Screen saver", look, onDismiss) { first ->
        item { Heading("Starts after", look) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min").forEachIndexed { i, (m, t) ->
                    TvButton(t, look, if (i == 0) Modifier.focusRequester(first) else Modifier, selected = config.saverMinutes == m) { config.setSaver(minutes = m) }
                }
            }
        }
        item { Heading("Shows", look) }
        val kinds = listOf(
            "clock" to "Family clock: the time, drifting slowly, with what's next and who's home",
            "photos" to "Family photos: ${if (photos == 0) "none yet (add them on the console's TV page)" else "$photos in the album"}",
            "stream" to "Photo link: ${if (stream) "pictures from the link set on the console" else "none set (add one on the console's TV page)"}",
            "theme" to "A theme: a slow gradient, a starry sky, or the season",
            "mix" to "A mix: a few minutes of each",
        )
        kinds.forEach { (k, label) ->
            item(key = "k$k") { MenuRow((if (config.saverKind == k) "✓  " else "     ") + label, look) { config.setSaver(kind = k) } }
        }
        if (config.saverKind == "theme" || config.saverKind == "mix") {
            item { Heading("Theme", look) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gradient" to "Gradient", "stars" to "Starry sky", "seasonal" to "The season").forEach { (k, t) ->
                        TvButton(t, look, selected = config.saverTheme == k) { config.setSaver(theme = k) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { TvButton("Try it now", look, onClick = onTry) }
        item { Text("Any button wakes the TV. Hold OK to ask Steward, even from the screen saver.", Modifier.padding(top = 6.dp), color = look.soft, fontSize = 14.sp) }
    }
}

/** The screen saver itself. A mix moves on every three minutes. */
@Composable
private fun ScreenSaver(config: TvConfig, board: JSONObject?, api: Api, names: Map<String, String>) {
    val photoIds = objs(board?.optJSONArray("photos")).map { it.optString("id") }
    val hasStream = board?.optBoolean("stream") == true
    var stream by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(hasStream) {
        if (hasStream) runCatching { api.call("GET", "/api/display/stream") }.onSuccess { stream = strs(it.optJSONArray("pictures")) }
    }
    val choices = buildList {
        add("clock")
        if (photoIds.isNotEmpty()) add("photos")
        if (stream.isNotEmpty()) add("stream")
        add("theme")
    }
    var turn by remember { mutableIntStateOf(0) }
    LaunchedEffect(config.saverKind) { if (config.saverKind == "mix") while (true) { delay(180_000); turn++ } }
    val kind = when (val k = config.saverKind) {
        "mix" -> choices[turn % choices.size]
        "photos" -> if (photoIds.isNotEmpty()) k else "clock"
        "stream" -> if (stream.isNotEmpty()) k else "clock"
        else -> k
    }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = fadeIn(tween(1500))) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (kind) {
                "photos" -> PhotoShow(photoIds.map { "/api/display/photos/$it" }, api)
                "stream" -> PhotoShow(stream, api)
                "theme" -> ThemeSaver(config.saverTheme)
                else -> FamilyClock(board, names)
            }
            if (kind != "clock") Text(time(System.currentTimeMillis()), Modifier.align(Alignment.TopEnd).padding(36.dp), color = Color.White.copy(alpha = .8f),
                fontFamily = Serif, fontSize = 44.sp, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 12f)))
            Text("Hold OK to ask Steward", Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), color = Color.White.copy(alpha = .35f), fontSize = 13.sp)
        }
    }
}

/** The family clock: big and calm, wandering slowly round the screen so nothing burns in. */
@Composable
private fun FamilyClock(board: JSONObject?, names: Map<String, String>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var spot by remember { mutableStateOf(Offset(.1f, .15f)) }
    LaunchedEffect(Unit) {
        val rnd = java.util.Random()
        var tick = 0
        while (true) {
            delay(15_000); now = System.currentTimeMillis()
            if (++tick % 4 == 0) spot = Offset(rnd.nextFloat(), rnd.nextFloat())
        }
    }
    val x by animateFloatAsState(spot.x, tween(8000, easing = FastOutSlowInEasing), label = "x")
    val y by animateFloatAsState(spot.y, tween(8000, easing = FastOutSlowInEasing), label = "y")
    val ink = Color(0xFFE6E1F5).copy(alpha = .85f)
    val soft = Color(0xFF9A93B5)
    // What's next: today's (or tomorrow's) next event, and the routine on now or later today.
    val next = objs(board?.optJSONObject("events")?.optJSONArray("events")).firstOrNull { it.optLong("start") > now || it.optBoolean("allDay") }
    val routines = board?.optJSONObject("routines")
    val routineNow = routines?.optJSONObject("now")?.let { n -> n.keys().asSequence().firstOrNull()?.let { n.optString(it) } }
        ?.let { id -> objs(routines.optJSONArray("routines")).find { it.optString("id") == id }?.optString("title") }
    val later = objs(board?.optJSONArray("routinesLater")).firstOrNull()
    val people = objs(board?.optJSONObject("members")?.optJSONArray("members")).filter { it.optString("id") in names }
    BoxWithConstraints(Modifier.fillMaxSize().padding(40.dp)) {
        val w = 560.dp
        val h = 300.dp
        Column(Modifier.offset { IntOffset(((maxWidth - w).toPx() * x).toInt(), ((maxHeight - h).toPx() * y).toInt()) }.width(w)) {
            Text(time(now), color = ink, fontFamily = Serif, fontSize = 96.sp, lineHeight = 100.sp)
            Text(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now)), color = soft, fontSize = 24.sp)
            Spacer(Modifier.height(14.dp))
            next?.let {
                val day = if (CalDays.day(it.optLong("start")) == CalDays.day(now)) "" else SimpleDateFormat("EEEE ", Locale.getDefault()).format(Date(it.optLong("start")))
                Text("Next: ${it.optString("title")} · $day${if (it.optBoolean("allDay")) "all day" else time(it.optLong("start"))}", color = ink, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                routineNow != null -> Text("On now: $routineNow", color = ink, fontSize = 20.sp)
                later != null -> Text("${later.optString("title")} at ${clock(later.optString("at"))}", color = ink, fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                people.take(8).forEach { p ->
                    val home = p.optString("place").equals("home", ignoreCase = true)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(parseColor(p.optString("color"))).copy(alpha = if (home) .9f else .35f)), contentAlignment = Alignment.Center) {
                            Text(p.optString("emoji").ifEmpty { initialOf(p.optString("name")) }, color = Color.White, fontSize = 18.sp)
                        }
                        Text(if (home) "home" else "out", color = soft, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Photos, one at a time, slowly drifting closer (a Ken Burns pan), crossfading every 15 seconds. */
@Composable
private fun PhotoShow(urls: List<String>, api: Api) {
    val context = LocalContext.current
    val order = remember(urls) { urls.shuffled() }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(order) {
        var i = 0
        while (order.isNotEmpty()) {
            loadSaverPicture(context, api, order[i % order.size])?.let { current = it; count++ }
            i++
            delay(15_000)
        }
    }
    Crossfade(current to count, animationSpec = tween(2500), label = "photo") { (bmp, n) ->
        if (bmp != null) {
            val zoom = remember(n) { Animatable(1f) }
            LaunchedEffect(n) { zoom.animateTo(1.14f, tween(17_500, easing = LinearEasing)) }
            val dir = if (n % 2 == 0) 1f else -1f
            Image(bmp, null, Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.value; scaleY = zoom.value
                translationX = dir * (zoom.value - 1f) * size.width * .3f
            }, contentScale = ContentScale.Crop)
        }
    }
}

/** A picture for the screen saver: from the server (the album) or straight from its link. */
private suspend fun loadSaverPicture(context: Context, api: Api, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = if (url.startsWith("/")) api.bytes(url) else (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "FamLogic TV")
            try { inputStream.use { it.readBytes() } } finally { disconnect() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

/** One of the calm themes, drawn frame by frame (only the drawing changes, nothing is laid out again). */
@Composable
private fun ThemeSaver(theme: String) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { t = (it - start) / 1000f }
    }
    val month = remember { Calendar.getInstance().get(Calendar.MONTH) }
    val rnd = remember { java.util.Random(7) }
    val dots = remember { List(160) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), .5f + rnd.nextFloat(), rnd.nextFloat() * 6.28f, .3f + rnd.nextFloat()) } }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        when (theme) {
            "gradient" -> {
                val a = (t * 2f) % 360f
                drawRect(Brush.linearGradient(listOf(Color.hsv((250f + 40f * kotlin.math.sin(a * .0174f)), .5f, .32f), Color.hsv((300f + 50f * kotlin.math.cos(a * .0123f)) % 360f, .45f, .18f)),
                    start = Offset(0f, 0f), end = Offset(w, h)))
            }
            "stars" -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0A1A), Color(0xFF1B1636))))
                dots.forEach { d ->
                    val x = ((d[0] + t * .002f * d[4]) % 1f) * w
                    val alpha = .25f + .75f * kotlin.math.abs(kotlin.math.sin(t * d[4] + d[3]))
                    drawCircle(Color.White.copy(alpha = alpha), radius = d[2] * 1.6f, center = Offset(x, d[1] * h))
                }
            }
            else -> {
                // The season: snow in winter, petals in spring, fireflies in summer, leaves in autumn.
                val (bg, colors) = when (month) {
                    11, 0, 1 -> listOf(Color(0xFF10172A), Color(0xFF263A5C)) to listOf(Color.White)
                    2, 3, 4 -> listOf(Color(0xFF1E1830), Color(0xFF3A2948)) to listOf(Color(0xFFF7C6D9), Color(0xFFFFE3EC))
                    5, 6, 7 -> listOf(Color(0xFF07140F), Color(0xFF15281C)) to listOf(Color(0xFFD9F27A), Color(0xFFF4E36B))
                    else -> listOf(Color(0xFF1C130C), Color(0xFF3A2414)) to listOf(Color(0xFFE07A2F), Color(0xFFC0392B), Color(0xFFD4A017))
                }
                drawRect(Brush.verticalGradient(bg))
                val summer = month in 5..7
                dots.take(90).forEachIndexed { i, d ->
                    val c = colors[i % colors.size]
                    if (summer) {
                        val x = (d[0] + .05f * kotlin.math.sin(t * .3f * d[4] + d[3])) * w
                        val y = (d[1] + .05f * kotlin.math.cos(t * .25f * d[4] + d[3])) * h
                        val glow = .2f + .8f * kotlin.math.abs(kotlin.math.sin(t * .8f * d[4] + d[3]))
                        drawCircle(c.copy(alpha = glow * .25f), radius = 9f * d[2], center = Offset(x, y))
                        drawCircle(c.copy(alpha = glow), radius = 2.6f * d[2], center = Offset(x, y))
                    } else {
                        val y = (((d[1] + t * .025f * d[4]) % 1.1f) - .05f) * h
                        val x = (d[0] + .02f * kotlin.math.sin(t * .6f * d[4] + d[3])) * w
                        if (month in 2..4 || month in 8..10) {
                            rotate(t * 40f * d[4] + d[3] * 57f, Offset(x, y)) {
                                drawOval(c.copy(alpha = .85f), topLeft = Offset(x - 7f * d[2], y - 4f * d[2]), size = Size(14f * d[2], 8f * d[2]))
                            }
                        } else drawCircle(c.copy(alpha = .8f), radius = 2.5f * d[2], center = Offset(x, y))
                    }
                }
            }
        }
    }
}

/**
 * Family updates popping up: the newest feed entry flies up into a corner with a bounce,
 * stays a few seconds, then drops away.
 */
@Composable
private fun BoxScope.FeedPopup(queue: MutableList<JSONObject>, look: Look, saver: Boolean) {
    val e = queue.firstOrNull() ?: return
    val shown = remember(e) { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(e) {
        delay(6_000)
        shown.targetState = false
        delay(700)
        queue.remove(e)
    }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomEnd).padding(end = if (saver) 40.dp else 120.dp, bottom = if (saver) 48.dp else 30.dp),
        enter = slideInVertically(spring(dampingRatio = .5f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(450)) { it * 2 } + fadeOut(tween(400))) {
        Row(
            Modifier.widthIn(min = 300.dp, max = 440.dp).shadow(16.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(if (saver) Color(0xEE1C1A29) else look.card).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(feedEmoji(e.optString("type")), fontSize = 28.sp)
            Column(Modifier.padding(start = 14.dp)) {
                Text("${e.optString("name")} ${feedWhat(e)}", color = if (saver) Color(0xFFE6E1F5) else look.ink, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(time(e.optLong("time")), color = if (saver) Color(0xFF9A93B5) else look.soft, fontSize = 13.sp)
            }
        }
    }
}
