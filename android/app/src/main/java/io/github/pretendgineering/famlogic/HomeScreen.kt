package io.github.pretendgineering.famlogic

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
                    "sos" -> ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(h)) {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
                            if (big) Text("Hold the red button if you need help", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                            HoldForSosBar(onTriggered = { Sos.send(context, scope, end = false); sosActive = true }, modifier = Modifier.fillMaxWidth(), tall = big)
                        }
                    }
                    "checkin" -> SimpleTile(if (Access.pictures) "I'm here!" else "Check in", "Let everyone know you're OK", h, big,
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
 * The tiles, in rows. While arranging: press and hold a tile's handle (☰) and drag
 * it to a new place; drag the corner grip sideways for half or full width, and down
 * or up for big or normal height; ✕ takes a tile off, and the + chips add one back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileBoard(tiles: List<HomeTile>, unit: Dp, edit: TileEdit?, modifier: Modifier, tile: @Composable (HomeTile, Dp) -> Unit) {
    val gap = 12.dp
    val bounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val step = with(density) { 56.dp.toPx() }
    fun move(id: String, to: String) {
        val list = tiles.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val target = list.indexOfFirst { it.id == to }
        if (from < 0 || target < 0 || from == target) return
        list.add(target, list.removeAt(from))
        edit?.onChange?.invoke(list)
    }
    fun resize(id: String, size: String) {
        if (id == "map" && size == "half") return
        edit?.onChange?.invoke(tiles.map { if (it.id == id) it.copy(size = size) else it })
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        rowsOf(tiles).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { t ->
                    val h = if (t.size == "big") unit * 2 + gap else unit
                    val isDragged = dragging == t.id
                    Box(
                        Modifier.weight(1f)
                            .onGloballyPositioned { bounds[t.id] = it.boundsInRoot() }
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                if (isDragged) {
                                    translationX = dragOffset.x; translationY = dragOffset.y
                                    scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 24f
                                }
                            },
                    ) {
                        tile(t, h)
                        if (edit != null) {
                            // Covers the tile so a tap doesn't open it while arranging.
                            Box(
                                Modifier.matchParentSize().clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDragged) .18f else .08f))
                                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
                                    .clickable(enabled = false) {},
                            )
                            // The handle: press, hold and drag.
                            Box(
                                Modifier.align(Alignment.TopStart).padding(6.dp).size(44.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .pointerInput(t.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragging = t.id; dragOffset = Offset.Zero; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                            onDrag = { change, amount -> change.consume(); dragOffset += amount },
                                            onDragEnd = {
                                                val start = bounds[t.id]
                                                if (start != null) {
                                                    val point = start.center + dragOffset
                                                    bounds.entries.firstOrNull { (id, r) -> id != t.id && r.contains(point) }?.let { move(t.id, it.key) }
                                                }
                                                dragging = null
                                                dragOffset = Offset.Zero
                                            },
                                            onDragCancel = { dragging = null; dragOffset = Offset.Zero },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.DragHandle, contentDescription = "Hold and drag to move", tint = MaterialTheme.colorScheme.onPrimary) }
                            // Take it off the home screen.
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable(onClickLabel = "Remove") { edit.onChange(tiles.filter { it.id != t.id }) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, contentDescription = "Remove from home") }
                            // The size grip: drag sideways for half/full width, down/up for big/normal.
                            var resizeDrag by remember { mutableStateOf(Offset.Zero) }
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(44.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .pointerInput(t.id, t.size) {
                                        detectDragGestures(
                                            onDragStart = { resizeDrag = Offset.Zero },
                                            onDrag = { change, amount -> change.consume(); resizeDrag += amount },
                                            onDragEnd = {
                                                val d = resizeDrag
                                                val size = when {
                                                    d.y > step -> "big"
                                                    d.y < -step && t.size == "big" -> "wide"
                                                    d.x > step && t.size == "half" -> "wide"
                                                    d.x < -step && t.size != "half" -> "half"
                                                    else -> null
                                                }
                                                if (size != null && size != t.size) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                    resize(t.id, size)
                                                }
                                            },
                                        )
                                    }
                                    // A tap also works: half → full width → big → half.
                                    .clickable(onClickLabel = "Change size") {
                                        val next = when (t.size) { "half" -> "wide"; "wide" -> "big"; else -> if (t.id == "map") "wide" else "half" }
                                        resize(t.id, next)
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.OpenInFull, contentDescription = "Drag or tap to resize", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
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
                "Hold ☰ and drag to move a tile. Drag the corner grip sideways for half or full width, or down for a big tile (or tap it). " +
                    "Two half-width tiles sit side by side; one on its own fills the row.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
