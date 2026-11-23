package io.github.pretendgineering.famlogic

import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.Canvas
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The home screen: tiles for what this person uses. A live map tile that opens
 * Thereabouts, chat, SOS, check-in, and (as they arrive) the other modules.
 * Adults arrange their own; parents arrange children's in the web console.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    prefs: Prefs,
    features: Set<String>,
    onOpen: (Tab) -> Unit,
    onOpenSettings: (full: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var chats by remember { mutableStateOf<List<ChatConversation>>(emptyList()) }
    var familyName by remember { mutableStateOf(prefs.familyName) }
    var tileUrl by remember { mutableStateOf(prefs.tileUrl) }
    var tileUrlDark by remember { mutableStateOf(prefs.tileUrlDark) }
    var attribution by remember { mutableStateOf(prefs.tileAttribution) }
    var sosActive by remember { mutableStateOf(prefs.sosActive) }
    var confirmCheckIn by remember { mutableStateOf(false) }
    var cameraSeq by remember { mutableStateOf(0) }
    val locationOn = Modules.on(features, Modules.THEREABOUTS)
    val chatOn = Modules.has(features, Modules.CHAT)

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val api = Api(prefs.serverUrl, prefs.token)
                val res = api.members(since = prefs.eventCursor)
                Alerts.handle(context, res.events)
                Access.update(context, res)
                members = res.members.sortedBy { it.id == prefs.memberId } // you last
                familyName = res.server.name
                tileUrl = res.server.tileUrl
                tileUrlDark = res.server.tileUrlDark
                attribution = res.server.tileAttribution
                if (chatOn && Access.can("chat")) chats = runCatching { api.chats() }.getOrDefault(chats)
            } catch (_: Exception) {
                // Offline: keep what we have; the tiles say how old it is.
            }
            sosActive = prefs.sosActive
            delay(if (Access.isDisplay) 30_000 else 15_000)
        }
    }

    // Tiles this person can use, in their order, with their sizes.
    fun usable(t: String) = when (t) {
        "map" -> locationOn && Access.can("map")
        "chat" -> chatOn && Access.can("chat")
        "sos" -> locationOn && Access.can("sos") && "sos" in features && !Access.isDisplay
        "checkin" -> locationOn && Access.can("checkin") && "checkin" in features && !Access.isDisplay
        else -> Modules.has(features, t) // modules still being built show as "coming soon"
    }
    val saved = parseTiles(Access.home.ifEmpty { Access.defaultHome() })
    // While arranging: a draft, saved with Done.
    var draft by remember { mutableStateOf<List<HomeTile>?>(null) }
    val editing = draft != null
    if (editing) androidx.activity.compose.BackHandler { draft = null }
    val tiles = (draft ?: saved).filter { usable(it.id) }
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val big = Access.big || Access.isDisplay
    // Landscape: the tiles on the left, the map filling the right-hand side.
    val mapBeside = landscape && !editing && tiles.any { it.id == "map" }

    val gate = rememberSettingsGate(prefs, onOpenSettings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (editing) Text("Arrange Home") else SecretTitle(familyName.ifEmpty { "FamLogic" }, gate) },
                actions = {
                    if (editing) {
                        TextButton(onClick = {
                            val out = draft.orEmpty()
                            scope.launch {
                                try {
                                    val list = out.map { it.encode() }
                                    Api(prefs.serverUrl, prefs.token).setHome(list)
                                    prefs.homeTiles = list
                                    Access.home = list
                                    draft = null
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text("Done") }
                    } else {
                        if (!Access.isChild && !Access.isDisplay) {
                            IconButton(onClick = { draft = saved.filter { usable(it.id) } }) { Icon(Icons.Filled.Edit, contentDescription = "Arrange home screen") }
                        }
                        if (!Access.hideSettings) IconButton(onClick = gate.cog) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (sosActive) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SOS sent. Your family has been alerted.", Modifier.weight(1f), color = SOS_RED, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { Sos.send(context, scope, end = true); sosActive = false }) { Text("I'm OK") }
                }
            }
            if (tiles.isEmpty() && !editing) {
                Text(
                    "Nothing to show here yet. A parent chooses what's on this screen in the web console.",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val tile: @Composable (HomeTile, Dp) -> Unit = { t, h ->
                when (t.id) {
                    "map" -> MapTile(prefs, members, tileUrl, tileUrlDark, attribution, cameraSeq, big, h) { onOpen(Tab.Map) }
                    "chat" -> ChatTile(chats, members, big, h) { onOpen(Tab.Chat) }
                    // One-button tiles: the tile is the button.
                    "sos" -> HoldForSosBar(
                        onTriggered = { Sos.send(context, scope, end = false); sosActive = true },
                        modifier = Modifier.fillMaxWidth().height(h), tall = big, tile = true,
                    )
                    "checkin" -> ButtonTile(if (Access.pictures) "I'm here!" else "Check in", h, big,
                        picture = { Pictures.HereIAm(it) }, icon = Icons.Filled.CheckCircle) { confirmCheckIn = true }
                    "calendar" -> SimpleTile(Access.name("calendar"), "Coming soon", h, big, icon = Icons.Filled.DateRange) {}
                    "todos" -> SimpleTile(Access.name("todos"), "Coming soon", h, big, icon = Icons.AutoMirrored.Filled.List) {}
                    "chores" -> SimpleTile(Access.name("chores"), "Coming soon", h, big, icon = Icons.Filled.Star) {}
                    "meals" -> SimpleTile(Access.name("meals"), "Coming soon", h, big, icon = Icons.Filled.ShoppingCart) {}
                }
            }
            val unit = if (big) 176.dp else 148.dp
            if (mapBeside) {
                Row(Modifier.fillMaxSize()) {
                    TileBoard(tiles.filter { it.id != "map" }, unit, null, Modifier.weight(1f), tile)
                    Box(Modifier.weight(1.15f).fillMaxHeight().padding(top = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        tile(tiles.first { it.id == "map" }, Dp.Unspecified)
                    }
                }
            } else {
                TileBoard(
                    tiles, unit,
                    edit = draft?.let { d ->
                        TileEdit(
                            onChange = { draft = it },
                            hidden = (listOf("map", "chat", "sos", "checkin") + listOf("calendar", "todos", "chores", "meals"))
                                .filter { id -> usable(id) && d.none { it.id == id } },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    tile = tile,
                )
            }
        }
    }

    if (confirmCheckIn) {
        AlertDialog(
            onDismissRequest = { confirmCheckIn = false },
            title = { Text("Check in?") },
            text = { Text("Your family gets a note that you're here and OK.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCheckIn = false
                    scope.launch {
                        try {
                            Api(prefs.serverUrl, prefs.token).checkIn("", currentLocation(context))
                            Toast.makeText(context, "Checked in", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Couldn't check in: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Check in") }
            },
            dismissButton = { TextButton(onClick = { confirmCheckIn = false }) { Text("Cancel") } },
        )
    }
}

/** A home screen tile and its size: "half" (half the width), "wide" (the full width) or "big" (full width, twice as tall). */
data class HomeTile(val id: String, val size: String) {
    fun encode() = "$id:$size"
}

/** "chat:half" → a tile; without a size, the map, chat and SOS are wide and the rest half. */
fun parseTiles(list: List<String>): List<HomeTile> = list.map {
    val id = it.substringBefore(':')
    val size = it.substringAfter(':', "").ifEmpty { if (id in setOf("map", "chat", "sos")) "wide" else "half" }
    HomeTile(id, if (id == "map" && size == "half") "wide" else size)
}

/** Rows: two half tiles side by side; a half tile alone on its row stretches to the full width. */
private fun rowsOf(tiles: List<HomeTile>): List<List<HomeTile>> {
    val rows = mutableListOf<List<HomeTile>>()
    var pending: HomeTile? = null
    for (t in tiles) {
        if (t.size == "half") {
            val p = pending
            if (p != null) { rows += listOf(p, t); pending = null } else pending = t
        } else {
            pending?.let { rows += listOf(it); pending = null }
            rows += listOf(t)
        }
    }
    pending?.let { rows += listOf(it) }
    return rows
}

/** How the tiles are being arranged (null when they aren't). */
class TileEdit(val onChange: (List<HomeTile>) -> Unit, val hidden: List<String>)

/**
 * The tiles, in rows. While arranging, the tiles follow your finger: press and hold
 * a tile (or grab its ☰ handle straight away) and drag it, and the others make room
 * as you go. Drag the corner grip and an outline stretches with your finger, the
 * tile snapping to half width, full width or big as you pass each size (a tap on
 * the grip steps through them). ✕ takes a tile off; the + chips add one back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileBoard(tiles: List<HomeTile>, unit: Dp, edit: TileEdit?, modifier: Modifier, tile: @Composable (HomeTile, Dp) -> Unit) {
    val gap = 12.dp
    val pad = 12.dp
    // Where everything is on screen (root coordinates), for the gestures.
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val handles = remember { mutableStateMapOf<String, Rect>() }
    val grips = remember { mutableStateMapOf<String, Rect>() }
    val closes = remember { mutableStateMapOf<String, Rect>() }
    var boardAt by remember { mutableStateOf(Offset.Zero) }
    var boardSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var resizing by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) } // root coordinates
    var grab by remember { mutableStateOf(Offset.Zero) }   // where in the tile it was picked up
    var edge by remember { mutableStateOf(0f) }            // auto-scroll speed near the top or bottom
    val scroll = rememberScrollState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val current by rememberUpdatedState(tiles)
    val editing by rememberUpdatedState(edit)
    var skip by remember { mutableStateOf<String?>(null) }
    // A tile on the right-hand side stretches leftwards (from its right edge), others rightwards.
    var anchorRight by remember { mutableStateOf(false) }
    fun span(r: Rect): Pair<Float, Float> {
        val a = if (anchorRight) r.right else r.left
        return minOf(a, finger.x) to maxOf(a, finger.x)
    }

    fun move(id: String, to: String) {
        val list = current.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val target = list.indexOfFirst { it.id == to }
        if (from < 0 || target < 0 || from == target) return
        list.add(target, list.removeAt(from))
        editing?.onChange?.invoke(list)
    }
    fun resize(id: String, size: String) {
        if (id == "map" && size == "half") return
        if (current.firstOrNull { it.id == id }?.size == size) return
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        editing?.onChange?.invoke(current.map { if (it.id == id) it.copy(size = size) else it })
    }
    // Dragging: whichever tile is under the finger trades places with the one being moved.
    fun follow() {
        val id = dragging ?: return
        skip?.let { s -> if (bounds[s]?.contains(finger) != true) skip = null }
        if (bounds[id]?.contains(finger) == true) return
        val over = bounds.entries.firstOrNull { (k, r) -> k != id && k != skip && r.contains(finger) }?.key ?: return
        skip = over
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        move(id, over)
    }
    // Resizing: the outline from the tile's corner to the finger picks the size.
    fun stretch() {
        val id = resizing ?: return
        val r = bounds[id] ?: return
        val unitPx = with(density) { unit.toPx() }
        val full = boardSize.width - with(density) { (pad * 2).toPx() }
        val (l, rr) = span(r)
        val w = rr - l
        val h = finger.y - r.top
        resize(id, when {
            h > unitPx * 1.5f -> "big"
            w < full * 0.7f && id != "map" -> "half"
            else -> "wide"
        })
    }
    // Near the top or bottom while dragging, the board scrolls by itself.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            if (edge != 0f) { scroll.scrollBy(edge); follow() }
        }
    }

    val gestures = if (edit == null) Modifier else Modifier.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val hold = viewConfiguration.longPressTimeoutMillis
        val zone = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val at = boardAt + down.position
            if (closes.values.any { it.contains(at) }) return@awaitEachGesture // ✕ handles itself
            val grip = grips.entries.firstOrNull { it.value.contains(at) }?.key
            if (grip != null) {
                // Resize: on the first movement the outline appears and follows the finger.
                var moved = false
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    if (!moved && (c.position - down.position).getDistance() > slop) {
                        moved = true
                        anchorRight = (bounds[grip]?.center?.x ?: 0f) > boardAt.x + boardSize.width / 2f + 8f
                        resizing = grip
                    }
                    if (moved) { c.consume(); finger = boardAt + c.position; stretch() }
                }
                if (!moved) {
                    // A tap: half → full width → big → half.
                    val t = current.firstOrNull { it.id == grip }
                    if (t != null) resize(grip, when (t.size) { "half" -> "wide"; "wide" -> "big"; else -> if (grip == "map") "wide" else "half" })
                }
                resizing = null
                return@awaitEachGesture
            }
            val handle = handles.entries.firstOrNull { it.value.contains(at) }?.key
            val id = handle ?: bounds.entries.firstOrNull { it.value.contains(at) }?.key ?: return@awaitEachGesture
            if (handle == null) {
                // Anywhere else on a tile: press and hold (moving first is a scroll).
                var held = true
                val result = withTimeoutOrNull(hold) {
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed || (c.position - down.position).getDistance() > slop) { held = false; break }
                    }
                }
                if (result != null || !held) return@awaitEachGesture
            }
            dragging = id
            finger = at
            grab = at - (bounds[id]?.topLeft ?: at)
            skip = null
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                c.consume()
                if (!c.pressed) break
                finger = boardAt + c.position
                edge = when {
                    c.position.y < zone -> -(zone - c.position.y) / 4f
                    c.position.y > size.height - zone -> (c.position.y - (size.height - zone)) / 4f
                    else -> 0f
                }
                follow()
            }
            edge = 0f
            dragging = null
        }
    }

    Box(
        modifier
            .onGloballyPositioned { boardAt = it.positionInRoot(); boardSize = it.size }
            .then(gestures),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(pad),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            rowsOf(tiles).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { t ->
                        key(t.id) {
                            val h = if (t.size == "big") unit * 2 + gap else unit
                            val isDragged = dragging == t.id
                            DisposableEffect(t.id) { onDispose { bounds.remove(t.id); handles.remove(t.id); grips.remove(t.id); closes.remove(t.id) } }
                            Box(
                                Modifier.weight(1f)
                                    .onGloballyPositioned { bounds[t.id] = it.boundsInRoot() }
                                    .zIndex(if (isDragged) 1f else 0f)
                                    .graphicsLayer {
                                        if (isDragged) {
                                            // Glued to the finger, wherever the tile's slot is now.
                                            val b = bounds[t.id]
                                            if (b != null) { translationX = finger.x - grab.x - b.left; translationY = finger.y - grab.y - b.top }
                                            scaleX = 1.05f; scaleY = 1.05f; shadowElevation = 28f
                                        }
                                    },
                            ) {
                                tile(t, h)
                                if (edit != null) EditChrome(t.id, isDragged || resizing == t.id, edit, tiles, handles, grips, closes)
                            }
                        }
                    }
                }
            }
            if (edit != null) {
                if (edit.hidden.isNotEmpty()) Text("Add to home", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    edit.hidden.forEach { id ->
                        androidx.compose.material3.AssistChip(
                            onClick = { edit.onChange(tiles + parseTiles(listOf(id))) },
                            label = { Text(Access.name(id)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        )
                    }
                }
                Text(
                    "Press and hold a tile, then drag it where you want it. Drag the corner grip to stretch a tile: " +
                        "narrow for half width, wide for the full width, or down for a big tile. Two half-width tiles sit side by side.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The stretch outline, from the tile's top-left corner to the finger.
        val r = resizing?.let { bounds[it] }
        if (r != null) {
            val min = with(density) { 56.dp.toPx() }
            val (l, rr) = span(r)
            val w = (rr - l).coerceAtLeast(min)
            val hh = (finger.y - r.top).coerceAtLeast(min)
            val left = if (anchorRight) rr - w else l
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val o = Offset(left, r.top) - boardAt
                drawRoundRect(color.copy(alpha = .12f), o, androidx.compose.ui.geometry.Size(w, hh), androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()))
                drawRoundRect(
                    color, o, androidx.compose.ui.geometry.Size(w, hh), androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                )
            }
        }
    }
}

/** While arranging: a tint over the tile (so it doesn't open), the ☰ handle, ✕ and the size grip. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EditChrome(
    id: String, active: Boolean, edit: TileEdit, tiles: List<HomeTile>,
    handles: MutableMap<String, Rect>, grips: MutableMap<String, Rect>, closes: MutableMap<String, Rect>,
) {
    Box(
        Modifier.matchParentSize().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (active) .18f else .08f))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
            // Takes the touches so the tile underneath (an SOS button, say) never fires.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    )
    Box(
        Modifier.align(Alignment.TopStart).padding(6.dp).size(44.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .onGloballyPositioned { handles[id] = it.boundsInRoot() },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.DragHandle, contentDescription = "Hold and drag to move", tint = MaterialTheme.colorScheme.onPrimary) }
    Box(
        Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { closes[id] = it.boundsInRoot() }
            .clickable(onClickLabel = "Remove") { edit.onChange(tiles.filter { it.id != id }) },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Close, contentDescription = "Remove from home") }
    Box(
        Modifier.align(Alignment.BottomEnd).padding(6.dp).size(48.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .onGloballyPositioned { grips[id] = it.boundsInRoot() },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.OpenInFull, contentDescription = "Drag to stretch, or tap to change size", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun MapTile(
    prefs: Prefs, members: List<Member>, tileUrl: String, tileUrlDark: String, attribution: String,
    cameraSeq: Int, big: Boolean, height: Dp, onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val tiles = MapStyles.resolve(prefs.mapStyle, dark, tileUrl, tileUrlDark, attribution, prefs.dimMap)
    val placed = members.filter { it.loc != null && !it.paused }
    val fill = height == Dp.Unspecified
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier.height(height)).clickable(onClickLabel = "Open the map", onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            OsmMap(
                members = placed,
                tiles = tiles,
                camera = if (placed.isNotEmpty()) CameraCommand.FitAll(cameraSeq + placed.size) else null,
                statusOf = { "" },
            )
            // The map is a preview: a tap anywhere opens Thereabouts.
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "Open the map", onClick = onClick))
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (Access.pictures) Pictures.Map(28.dp) else Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(Access.name("map"), Modifier.padding(start = 6.dp, end = 10.dp), style = MaterialTheme.typography.titleMedium)
            // Faces of who's where, in a row: easy to read at a glance.
            members.take(if (big) 6 else 5).forEach { m ->
                Box(Modifier.padding(end = 4.dp)) { Avatar(m.name, m.emoji, m.color, if (big) 32 else 26) }
            }
        }
        if (fill || height > 300.dp) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                members.take(5).forEach { m ->
                    Text(
                        "${m.name}  ·  ${whereShort(m)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun whereShort(m: Member): String {
    if (m.sosSince > 0) return "SOS"
    if (m.paused) return "paused"
    val loc = m.loc ?: return "no location yet"
    val ago = if (System.currentTimeMillis() - loc.time < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(loc.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return if (m.place.isNotEmpty()) "at ${m.place} · $ago" else ago
}

@Composable
private fun ChatTile(chats: List<ChatConversation>, members: List<Member>, big: Boolean, height: Dp, onClick: () -> Unit) {
    val unread = chats.sumOf { it.unread }
    val recent = chats.mapNotNull { c -> c.last?.let { c to it } }.sortedByDescending { it.second.time }
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(height).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (Access.pictures) Pictures.Talk(if (big) 48.dp else 32.dp)
                else Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (big) 40.dp else 28.dp))
                Text(Access.name("chat"), Modifier.padding(start = 8.dp).weight(1f), style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                if (unread > 0) androidx.compose.material3.Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$unread", fontSize = if (big) 16.sp else 11.sp) }
            }
            Spacer(Modifier.height(10.dp))
            if (recent.isEmpty()) {
                Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // The latest messages (more on a big tile), each with the sender's face.
                recent.take(if (height > 300.dp) 4 else 1).forEach { (c, msg) ->
                    val sender = members.find { it.id == msg.sender }
                    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(sender?.name ?: "?", sender?.emoji.orEmpty(), sender?.color ?: "#888888", if (big) 44 else 32)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(msg.text.ifEmpty { "Photo" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A tile that does one thing: the whole tile is the button, in the theme's colour. */
@Composable
private fun ButtonTile(
    title: String, height: Dp, big: Boolean,
    picture: (@Composable (Dp) -> Unit)? = null, icon: ImageVector, onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().height(height),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val s = if (big) 64.dp else 48.dp
            if (Access.pictures && picture != null) picture(s + 12.dp)
            else Icon(icon, contentDescription = null, modifier = Modifier.size(s))
            Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SimpleTile(
    title: String, sub: String, height: Dp, big: Boolean,
    picture: (@Composable (Dp) -> Unit)? = null, icon: ImageVector, onClick: () -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(height).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            val s = if (big) 56.dp else 36.dp
            if (Access.pictures && picture != null) picture(s + 8.dp)
            else Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(s))
            Spacer(Modifier.height(10.dp))
            Text(title, style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

/**
 * Opening settings. Parents: straight in. Older children: their own small
 * Settings (Customize, Account, About). Younger children have no settings button
 * at all. A Control Deck asks for the parent PIN. Either way, a parent can get to
 * everything by holding the family name at the top for 5 seconds and typing the
 * parent PIN, or by opening settings on the device from afar (their phone or the
 * console), which skips the PIN for a while.
 */
class SettingsGate(val cog: () -> Unit, val secret: () -> Unit)

@Composable
fun rememberSettingsGate(prefs: Prefs, onOpen: (full: Boolean) -> Unit): SettingsGate {
    var asking by remember { mutableStateOf(false) }
    if (asking) PinDialog(prefs, onDismiss = { asking = false }, onOk = { asking = false; onOpen(true) })
    val full = { if (Access.pinSet && !Access.unlocked) asking = true else onOpen(true) }
    return remember {
        SettingsGate(
            cog = {
                when {
                    Access.unlocked -> onOpen(true)
                    Access.isChild -> onOpen(false)
                    Access.isDisplay -> full()
                    else -> onOpen(true)
                }
            },
            secret = full,
        )
    }
}

/** The title bar's family name: holding it for 5 seconds is the parents' secret way into settings. */
@Composable
fun SecretTitle(text: String, gate: SettingsGate) {
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    delay(5_000)
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    gate.secret()
                }
                tryAwaitRelease()
                job.cancel()
            })
        },
    )
}

@Composable
fun PinDialog(prefs: Prefs, onDismiss: () -> Unit, onOk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    fun check() {
        if (pin.length < 4 || checking) return
        checking = true
        scope.launch {
            try {
                Api(prefs.serverUrl, prefs.token).checkPin(pin)
                onOk()
            } catch (e: ApiException) {
                error = e.message
                pin = ""
            } catch (_: IOException) {
                error = "Couldn't reach the server. The PIN is checked there, so try again when you're online."
            } finally {
                checking = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text("Parent PIN") },
        text = {
            Column {
                Text("Settings on this device are for parents.", Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); error = null },
                    singleLine = true,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = { TextButton(onClick = { check() }, enabled = pin.length >= 4 && !checking) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Raising or ending an SOS from anywhere (the home screen's tile; the map has its own). */
object Sos {
    fun send(context: Context, scope: CoroutineScope, end: Boolean) {
        val prefs = Prefs(context)
        if (end) {
            prefs.sosActive = false
            prefs.sosPending = false
            prefs.sosEndPending = true
        } else {
            prefs.sosActive = true
            prefs.sosPending = true
            prefs.sosEndPending = false
            prefs.sharing = true // someone asking for help wants to be found
        }
        LocationService.start(context)
        scope.launch {
            delay(4_000)
            if (LocationService.isRunning || !(prefs.sosPending || prefs.sosEndPending)) return@launch
            try {
                val api = Api(prefs.serverUrl, prefs.token)
                if (prefs.sosPending) api.sos(null).also { prefs.sosPending = false }
                if (prefs.sosEndPending) api.sosEnd().also { prefs.sosEndPending = false }
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't reach the server. FamLogic will keep trying.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
