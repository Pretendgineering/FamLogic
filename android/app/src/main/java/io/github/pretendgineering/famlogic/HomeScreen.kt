package io.github.pretendgineering.famlogic

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
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
    onOpenSettings: () -> Unit,
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
    var editing by remember { mutableStateOf(false) }
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
                members = res.members
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

    // Tiles this person can use, in their order.
    fun usable(t: String) = when (t) {
        "map" -> locationOn && Access.can("map")
        "chat" -> chatOn && Access.can("chat")
        "sos" -> locationOn && Access.can("sos") && "sos" in features && !Access.isDisplay
        "checkin" -> locationOn && Access.can("checkin") && "checkin" in features && !Access.isDisplay
        else -> Modules.has(features, t) // modules still being built show as "coming soon"
    }
    val tiles = Access.home.ifEmpty { Access.defaultHome() }.filter { usable(it) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val big = Access.younger || Access.isDisplay

    val askSettings = rememberSettingsGate(prefs, onOpenSettings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(familyName.ifEmpty { "FamLogic" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (!Access.isChild && !Access.isDisplay) {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Arrange home screen") }
                    }
                    IconButton(onClick = askSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
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
            if (tiles.isEmpty()) {
                Text(
                    "Nothing to show here yet. A parent chooses what's on this screen in the web console.",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (big) 260.dp else if (wide) 220.dp else 160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tiles, key = { it }, span = { t -> if (t == "map" || t == "sos") GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { t ->
                    when (t) {
                        "map" -> MapTile(prefs, members, tileUrl, tileUrlDark, attribution, cameraSeq, big) { onOpen(Tab.Map) }
                        "chat" -> ChatTile(prefs, chats, members, big) { onOpen(Tab.Chat) }
                        "sos" -> ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                if (big) Text("Hold the red bar if you need help", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                                HoldForSosBar(onTriggered = { Sos.send(context, scope, end = false); sosActive = true }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        "checkin" -> SimpleTile("I'm here", "Let everyone know you're OK", Icons.Filled.CheckCircle, big) { confirmCheckIn = true }
                        "calendar" -> SimpleTile("Calendar", "Coming soon", Icons.Filled.DateRange, big) {}
                        "todos" -> SimpleTile("To-dos", "Coming soon", Icons.AutoMirrored.Filled.List, big) {}
                        "chores" -> SimpleTile("Chores", "Coming soon", Icons.Filled.Star, big) {}
                        "meals" -> SimpleTile("Meals", "Coming soon", Icons.Filled.ShoppingCart, big) {}
                    }
                }
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
    if (editing) HomeEditor(prefs, features) { editing = false }
}

@Composable
private fun MapTile(
    prefs: Prefs, members: List<Member>, tileUrl: String, tileUrlDark: String, attribution: String,
    cameraSeq: Int, big: Boolean, onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val tiles = MapStyles.resolve(prefs.mapStyle, dark, tileUrl, tileUrlDark, attribution, prefs.dimMap)
    val placed = members.filter { it.loc != null && !it.paused }
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open the map", onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(if (big) 320.dp else 220.dp)) {
            OsmMap(
                members = placed,
                tiles = tiles,
                camera = if (placed.isNotEmpty()) CameraCommand.FitAll(cameraSeq + placed.size) else null,
                statusOf = { "" },
            )
            // The map is a preview: a tap anywhere opens Thereabouts.
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "Open the map", onClick = onClick))
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Where everyone is", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleMedium)
            }
            members.take(if (big) 6 else 4).forEach { m ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.name, m.emoji, m.color, if (big) 36 else 28)
                    Text(m.name, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                    Text(
                        "  " + whereShort(m),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
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
private fun ChatTile(prefs: Prefs, chats: List<ChatConversation>, members: List<Member>, big: Boolean, onClick: () -> Unit) {
    val unread = chats.sumOf { it.unread }
    val latest = chats.mapNotNull { c -> c.last?.let { c to it } }.maxByOrNull { it.second.time }
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().heightIn(min = if (big) 180.dp else 140.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (big) 40.dp else 28.dp))
                Text("Chat", Modifier.padding(start = 8.dp).weight(1f), style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                if (unread > 0) {
                    Box(
                        Modifier.size(if (big) 36.dp else 28.dp).then(Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$unread") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (latest == null) {
                Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val sender = members.find { it.id == latest.second.sender }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Their picture, so a child who can't read knows who wrote.
                    Avatar(sender?.name ?: "?", sender?.emoji.orEmpty(), sender?.color ?: "#888888", if (big) 44 else 32)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(latest.first.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            latest.second.text.ifEmpty { "Photo" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleTile(title: String, sub: String, icon: ImageVector, big: Boolean, onClick: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().heightIn(min = if (big) 180.dp else 140.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (big) 56.dp else 36.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val TILE_NAMES = linkedMapOf(
    "map" to "Map", "chat" to "Chat", "sos" to "SOS", "checkin" to "Check in",
    "calendar" to "Calendar", "todos" to "To-dos", "chores" to "Chores", "meals" to "Meals",
)

/** Adults arrange their own home screen: tick tiles, order them with the arrows. */
@Composable
private fun HomeEditor(prefs: Prefs, features: Set<String>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf(Access.home.ifEmpty { Access.defaultHome() }) }
    val all = order + TILE_NAMES.keys.filter { it !in order }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Your home screen") },
        text = {
            Column {
                Text("Tick what to show; arrows change the order. Modules your family hasn't switched on stay hidden.", style = MaterialTheme.typography.bodySmall)
                all.forEach { t ->
                    val on = t in order
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { order = if (it) order + t else order - t })
                        Text(TILE_NAMES[t] ?: t, Modifier.weight(1f))
                        if (on) {
                            IconButton(onClick = {
                                val i = order.indexOf(t)
                                if (i > 0) order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                            }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${TILE_NAMES[t]} up") }
                            IconButton(onClick = {
                                val i = order.indexOf(t)
                                if (i < order.size - 1) order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                            }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${TILE_NAMES[t]} down") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tiles = order
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).setHome(tiles)
                        prefs.homeTiles = tiles
                        Access.home = tiles
                        onDone()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * Opening settings: on a child's device or a family display, a parent types the
 * family's parent PIN first. Returns the function to call.
 */
@Composable
fun rememberSettingsGate(prefs: Prefs, onOpen: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    if (asking) PinDialog(prefs, onDismiss = { asking = false }, onOk = { asking = false; onOpen() })
    return { if (Access.settingsLocked && Access.pinSet) asking = true else onOpen() }
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
