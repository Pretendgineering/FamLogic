package io.github.pretendgineering.famlogic

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Which chat is on screen, so its messages don't also pop up as notifications. */
object ChatState {
    @Volatile var openConversation: String? = null
}

/**
 * Chat (the chat module): the family chat, group chats and one-to-one chats.
 * Parents also see their children's chats, read only ("Watching").
 */
@Composable
fun ChatScreen(prefs: Prefs, openRequest: String?, onOpenHandled: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            open = openRequest
            onOpenHandled()
        }
    }
    // Everyone's names and colours (refreshed now and then).
    val people by produceState(initialValue = emptyList<Member>()) {
        while (true) {
            runCatching { Api(prefs.serverUrl, prefs.token).members(since = Long.MAX_VALUE / 2).members }.getOrNull()?.let { value = it }
            delay(60_000)
        }
    }
    val conv = open
    val config = LocalConfiguration.current
    val tablet = config.screenWidthDp >= 600
    val sideBySide = tablet && config.screenWidthDp > config.screenHeightDp
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    if (conv != null) BackHandler { if (fullscreen) fullscreen = false else open = null }
    val portrait = config.screenHeightDp >= config.screenWidthDp
    when {
        // Steward: the family's AI helper, full screen.
        conv == "steward" -> StewardScreen(prefs) { open = null }
        // Full screen: by choice, a phone on its side, or (except for younger children,
        // who get the split-open list below) any open chat in portrait.
        conv != null && (fullscreen || (!tablet && !portrait) || (!sideBySide && !Access.younger)) -> ConversationView(
            prefs, conv, people, onBack = { if (fullscreen) fullscreen = false else open = null },
            fullscreen = true, onToggleFullscreen = if (fullscreen) ({ fullscreen = false }) else null,
        )
        // Landscape tablets: the list beside the open chat.
        sideBySide -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(if (Access.big) 400.dp else 360.dp).fillMaxHeight()) {
                ConversationList(prefs, people, selected = conv, onOpen = { open = if (open == it) null else it })
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (conv == null) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (Access.pictures) Pictures.Talk(72.dp)
                        else Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Pick a chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    ConversationView(prefs, conv, people, onBack = { open = null }, embedded = true, onToggleFullscreen = { fullscreen = true })
                }
            }
        }
        // Younger children, portrait: the list splits open at the chat you tap, the rows above slide up and
        // the rows below are pushed down, and the chat opens in the gap, like pulling
        // apart a stack of cards. Tap the chat's row again (or ✕) to close it up.
        else -> ConversationList(
            prefs, people, selected = conv, onOpen = { open = if (open == it) null else it },
            expanded = conv,
            panel = { id -> ConversationView(prefs, id, people, onBack = { open = null }, embedded = true, slice = true) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationList(
    prefs: Prefs, people: List<Member>, selected: String? = null, onOpen: (String) -> Unit,
    /** Portrait: the open chat, shown in a gap that opens below its row. */
    expanded: String? = null,
    panel: (@Composable (String) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var convs by remember { mutableStateOf<List<ChatConversation>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newChat by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                convs = Api(prefs.serverUrl, prefs.token).chats()
                error = null
            } catch (e: IOException) {
                error = e.message
            }
            delay(5_000)
        }
    }
    // Search: every chat you can read.
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { found = emptyList(); return@LaunchedEffect }
        delay(300)
        found = runCatching { Api(prefs.serverUrl, prefs.token).chatSearch(query.trim()) }.getOrDefault(found)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), placeholder = { Text("Search messages") }, singleLine = true, shape = RoundedCornerShape(24.dp))
                    else Text(Access.name("chat"))
                },
                actions = {
                    if (!Access.younger) IconButton(onClick = { searching = !searching; query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) "Stop searching" else "Search")
                    }
                },
            )
        },
        floatingActionButton = {
            if (Access.can("chat_start") && expanded == null && !searching) {
                LargeFloatingActionButton(onClick = { newChat = true }) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
            }
        },
    ) { padding ->
        if (searching) {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                if (query.trim().length >= 2 && found.isEmpty()) item { Text("Nothing found.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(found.size, key = { found[it].id }) { i ->
                    val m = found[i]
                    val c = convs?.find { it.id == m.conv }
                    val who = people.find { it.id == m.sender }
                    Row(Modifier.fillMaxWidth().clickable { searching = false; onOpen(m.conv) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(who?.name ?: "?", who?.emoji.orEmpty(), who?.color ?: "#888888", 36)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("${who?.name ?: "Someone"} in ${c?.title ?: "a chat"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(m.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            return@Scaffold
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
        val viewport = maxHeight
        // The gap's height grows from nothing (the slice) to almost the whole screen.
        var shownPanel by remember { mutableStateOf<String?>(null) }
        var rowHeight by remember { mutableStateOf(if (Access.big) 96.dp else 64.dp) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Scrolling inside the open chat stays inside it: reaching the oldest or newest
        // message never drags the list (or the chat's name) along with it.
        val keepScroll = remember {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) = available
                override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) = available
            }
        }
        val gap = remember { Animatable(0f) }
        LaunchedEffect(expanded) {
            if (expanded != null) {
                if (shownPanel != null && shownPanel != expanded) gap.animateTo(0f, tween(180))
                shownPanel = expanded
                val index = convs?.indexOfFirst { it.id == expanded } ?: -1
                // The rows above slide up: the opened chat's row moves to the top.
                launch { if (index >= 0) listState.animateScrollToItem(index + if (prefs.role == "child") 1 else 0) }
                gap.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f))
            } else if (shownPanel != null) {
                gap.animateTo(0f, tween(220))
                shownPanel = null
            }
        }
        // While a chat is open the list holds still, its name pinned at the top.
        LazyColumn(Modifier.fillMaxSize(), state = listState, userScrollEnabled = expanded == null && shownPanel == null) {
            if (prefs.role == "child") {
                item {
                    Text(
                        "Your parents can see your chats.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            // Steward, pinned at the top when the family has it.
            if (Steward.available(prefs)) item(key = "steward") {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen("steward") }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(if (Access.big) 56.dp else 44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent2) }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(Access.name("steward"), style = MaterialTheme.typography.titleMedium)
                        Text(if (Access.isChild) "Tell it when a job's done" else "Your family's helper", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val list = convs
            if (list == null) {
                item { Text("Loading…", modifier = Modifier.padding(16.dp)) }
            } else {
                list.forEach { c ->
                    item(key = c.id) {
                        val open = panel != null && c.id == shownPanel
                        Box(if (open) Modifier.onSizeChanged { rowHeight = with(density) { it.height.toDp() } } else Modifier) {
                            ConversationRow(c, people, prefs.memberId, selected = c.id == selected, closable = open) { onOpen(c.id) }
                        }
                    }
                    if (panel != null && c.id == shownPanel) {
                        item(key = "panel") {
                            // The opened gap: the chat sits in it like a card pulled out of the stack.
                            // It fills everything under the chat's name, edge to edge: the list
                            // opening up to show the chat, not a card on top of it.
                            val h = (viewport - rowHeight).coerceAtLeast(240.dp)
                            Box(Modifier.fillMaxWidth().height(h * gap.value).clipToBounds().nestedScroll(keepScroll)) {
                                Column(Modifier.requiredHeight(h).fillMaxWidth().graphicsLayer { alpha = 0.4f + 0.6f * gap.value }) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Box(Modifier.weight(1f)) { panel(c.id) }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.heightIn(min = 96.dp)) }
        }
        }
    }
    if (newChat) {
        NewChatDialog(
            people = people.filter { it.id != prefs.memberId && it.role != "dependent" },
            onDismiss = { newChat = false },
            onStart = { ids, name ->
                newChat = false
                scope.launch {
                    try {
                        val api = Api(prefs.serverUrl, prefs.token)
                        val c = if (ids.size == 1) api.chatDirect(ids[0]) else api.chatGroup(name.ifBlank { "Group" }, ids)
                        onOpen(c.id)
                    } catch (e: IOException) {
                        error = e.message
                    }
                }
            },
        )
    }
}

@Composable
private fun ConversationRow(c: ChatConversation, people: List<Member>, me: String, selected: Boolean = false, closable: Boolean = false, onClick: () -> Unit) {
    val other = if (c.kind == "direct") people.find { it.id in c.members && it.id != me } else null
    // Children who can't read yet find chats by faces: bigger pictures, and a group shows who's in it.
    val big = Access.big || Access.isChild
    val size = if (Access.younger) 84 else if (big) 68 else 44
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (big) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // A chosen icon (the family chat's, or a group's).
            // The family chat: its emoji if it has one, else the family's crest (in its colour).
            c.kind == "family" && c.emoji.isEmpty() -> FamilyCrest(size, color = c.color)
            c.kind != "direct" && (c.emoji.isNotEmpty() || c.color.isNotEmpty()) ->
                Avatar(c.title, c.emoji, c.color.ifEmpty { "#6F5AA8" }, size)
            other != null -> Avatar(other.name, other.emoji, other.color, size)
            c.kind == "group" -> {
                val faces = c.members.filter { it != me }.mapNotNull { id -> people.find { it.id == id } }.take(3)
                Box(Modifier.size(size.dp)) {
                    if (faces.isEmpty()) Avatar(c.title, "", "#6e48d6", size)
                    val small = if (faces.size == 1) size else size * 6 / 10
                    val gap = size - small
                    // Two faces: top-left and bottom-right. Three: two on top, one below in the middle.
                    val spots = when (faces.size) {
                        1 -> listOf(0 to 0)
                        2 -> listOf(0 to 0, gap to gap)
                        else -> listOf(0 to 0, gap to 0, gap / 2 to gap)
                    }
                    faces.forEachIndexed { i, m ->
                        Box(Modifier.padding(start = spots[i].first.dp, top = spots[i].second.dp)) {
                            Avatar(m.name, m.emoji, m.color, small)
                        }
                    }
                }
            }
            else -> Avatar(c.title, "", "#6e48d6", size)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.oversight) SuggestionChip(onClick = {}, label = { Text("Watching") }, modifier = Modifier.padding(start = 6.dp).heightIn(max = 26.dp))
                STATUS_LABELS[c.status]?.let { SuggestionChip(onClick = {}, label = { Text(it) }, modifier = Modifier.padding(start = 6.dp).heightIn(max = 26.dp)) }
            }
            val last = c.last
            val who = if (last != null && c.kind != "direct") (if (last.sender == me) "You" else people.find { it.id == last.sender }?.name ?: "") + ": " else ""
            Text(
                if (last == null) "No messages yet" else who + last.text.ifEmpty { "Photo" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (closable) Icon(Icons.Filled.Close, contentDescription = "Close the chat", modifier = Modifier.padding(start = 8.dp).size(if (big) 36.dp else 26.dp))
        else if (c.unread > 0) Badge(Modifier.padding(start = 8.dp)) { Text(c.unread.toString(), fontSize = if (big) 16.sp else 11.sp) }
    }
}

@Composable
private fun NewChatDialog(people: List<Member>, onDismiss: () -> Unit, onStart: (List<String>, String) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            Column {
                Text("Pick one person, or several for a group.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(people, key = { it.id }) { m ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(m.id in picked, onValueChange = { on -> if (on) picked += m.id else picked -= m.id })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = m.id in picked, onCheckedChange = null)
                            Avatar(m.name, m.emoji, m.color, 32)
                            Text(m.name, Modifier.padding(start = 10.dp))
                        }
                    }
                }
                if (picked.size > 1) {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { onStart(picked.toList(), name) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationView(
    prefs: Prefs, convId: String, people: List<Member>, onBack: () -> Unit,
    /** Shown beside the list or as a shade (tablets): a close button instead of Back. */
    embedded: Boolean = false,
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    /** Inside the split-open list: the chat's row above is its title, so no bar of its own. */
    slice: Boolean = false,
) {
    val big = Access.big
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember(convId) { mutableStateListOf<ChatMessage>() }
    var thread by remember(convId) { mutableStateOf<ChatThread?>(null) }
    var text by androidx.compose.runtime.saveable.rememberSaveable(convId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var iconPicking by remember { mutableStateOf(false) }
    val readAloud = Access.younger && Access.can("voice")
    // Messages can be read aloud here (a speaker button beside each).
    val canHear = Access.can("voice") && Voice.speaker(context, prefs.serverFeatures) != null
    LaunchedEffect(convId) { prefs.lastChat = convId }
    DisposableEffect(convId) { Voice.warmUp(context); onDispose { Voice.stop() } }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    // Long-press on a message: react, reply, copy or delete.
    var acting by remember { mutableStateOf<ChatMessage?>(null) }
    var replying by remember(convId) { mutableStateOf<ChatMessage?>(null) }
    var viewing by remember { mutableStateOf<Bitmap?>(null) }
    var recording by remember { mutableStateOf<VoiceRecording?>(null) }
    var recordMs by remember { mutableStateOf(0L) }
    var lastTyping by remember { mutableStateOf(0L) }
    var newBelow by remember(convId) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val api = remember { Api(prefs.serverUrl, prefs.token) }

    DisposableEffect(convId) {
        ChatState.openConversation = convId
        Alerts.clearChat(context, convId)
        onDispose { if (ChatState.openConversation == convId) ChatState.openConversation = null }
    }
    // Load, then keep up with new messages and mark them read.
    LaunchedEffect(convId) {
        var round = 0
        while (true) {
            try {
                // Now and then a full reload, so messages a parent deleted disappear here too.
                val full = round++ % 3 == 2
                val after = if (full) 0 else messages.lastOrNull()?.id ?: 0
                val t = api.chatThread(convId, after = after)
                thread = t
                if (full) {
                    messages.retainAll { m -> t.messages.any { it.id == m.id } || m.id < (t.messages.firstOrNull()?.id ?: 0) }
                    // Reactions change on messages already shown.
                    t.messages.forEach { fresh -> val i = messages.indexOfFirst { it.id == fresh.id }; if (i >= 0 && messages[i] != fresh) messages[i] = fresh }
                }
                if (t.messages.isNotEmpty()) {
                    val fresh = t.messages.filter { m -> messages.none { it.id == m.id } }
                    val firstLoad = messages.isEmpty()
                    messages += fresh
                    // Reading further back: don't jump; a button says there's something new.
                    if (firstLoad || listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
                    else if (fresh.any { it.sender != prefs.memberId }) newBelow += fresh.count { it.sender != prefs.memberId }
                    // Younger children hear new messages read aloud as they arrive.
                    if (!firstLoad && readAloud) {
                        fresh.lastOrNull { it.sender != prefs.memberId && Voice.hasWords(it.text) }?.let { m ->
                            val who = people.find { it.id == m.sender }
                            launch { Voice.speak(context, prefs, "${who?.name ?: "Someone"} says: ${m.text}", who?.voice.orEmpty()) }
                        }
                    }
                }
                val newest = messages.lastOrNull()?.id ?: 0
                if (t.canPost && newest > 0) runCatching { api.chatRead(convId, newest) }
                error = null
            } catch (e: IOException) {
                error = e.message
            }
            delay(3_000)
        }
    }
    // A picked or taken photo waits here, with an ✕ to remove it, until Send.
    var staged by androidx.compose.runtime.saveable.rememberSaveable(convId) { mutableStateOf("") }
    fun sendPhoto(uri: Uri) {
        scope.launch {
            sending = true
            try {
                val jpeg = withContext(Dispatchers.IO) { shrinkPhoto(context, uri) } ?: throw IOException("Couldn't read that photo")
                val m = api.chatSendPhoto(convId, jpeg, text.trim(), replying?.id ?: 0)
                text = ""
                staged = ""
                replying = null
                if (messages.none { it.id == m.id }) messages += m
                listState.animateScrollToItem(0)
            } catch (e: IOException) {
                error = e.message
            } finally {
                sending = false
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris: List<Uri> ->
        when {
            uris.size == 1 -> staged = uris[0].toString()
            uris.size > 1 -> scope.launch {
                // Several: each goes as its own message, in order.
                sending = true
                for (u in uris) {
                    try {
                        val jpeg = withContext(Dispatchers.IO) { shrinkPhoto(context, u) } ?: continue
                        val m = api.chatSendPhoto(convId, jpeg, "")
                        if (messages.none { it.id == m.id }) messages += m
                    } catch (e: IOException) {
                        error = e.message
                    }
                }
                sending = false
                listState.animateScrollToItem(0)
            }
        }
    }
    // A voice message: tap the mic to record, tap again to send (or ✕ to drop it).
    fun stopRecording(send: Boolean) {
        val rec = recording ?: return
        recording = null
        if (!send) { rec.cancel(); return }
        val got = rec.finish() ?: return
        scope.launch {
            sending = true
            try {
                val m = api.chatSendVoice(convId, got.first, got.second, replying?.id ?: 0)
                replying = null
                if (messages.none { it.id == m.id }) messages += m
                listState.animateScrollToItem(0)
            } catch (e: IOException) {
                error = e.message
            } finally {
                sending = false
            }
        }
    }
    LaunchedEffect(recording) { while (recording != null) { recordMs = recording?.elapsed ?: 0; delay(200) } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // A new photo: the camera app saves it into our private cache, then it's sent like a picked one.
    var cameraFile by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = java.io.File(cameraFile)
        if (ok && f.exists()) staged = Uri.fromFile(f).toString()
    }
    fun startCamera() {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000) it.delete() }
        val f = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
        cameraFile = f.path
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        runCatching { takePhoto.launch(uri) }.onFailure { error = "No camera app found" }
    }
    var photoMenu by remember { mutableStateOf(false) }
    var muting by remember { mutableStateOf(false) }
    var mutedUntil by remember(convId) { mutableStateOf(prefs.chatMutedUntil(convId)) }
    fun sendText(t: String, after: () -> Unit = {}) {
        if (t.isEmpty() || sending) return
        scope.launch {
            sending = true
            try {
                val m = api.chatSend(convId, t)
                after()
                if (messages.none { it.id == m.id }) messages += m
                listState.animateScrollToItem(0)
            } catch (e: IOException) {
                error = e.message
            } finally {
                sending = false
            }
        }
    }
    fun send() {
        val t = text.trim()
        if (staged.isNotEmpty() && !sending) {
            sendPhoto(Uri.parse(staged))
            return
        }
        if (t.isEmpty() || sending) return
        scope.launch {
            sending = true
            try {
                val m = api.chatSend(convId, t, replying?.id ?: 0)
                text = ""
                replying = null
                if (messages.none { it.id == m.id }) messages += m
                listState.animateScrollToItem(0)
            } catch (e: IOException) {
                error = e.message
            } finally {
                sending = false
            }
        }
    }

    val conv = thread?.conversation
    val canPost = thread?.canPost == true
    // Parents manage any chat with a child in it (not the family chat).
    val moderator = prefs.role == "parent" && conv != null && conv.kind != "family" &&
        conv.members.any { id -> people.find { it.id == id }?.role == "child" }
    fun moderate(action: String) {
        scope.launch {
            try {
                api.chatModerate(convId, action)
                if (action == "delete") onBack() else thread = api.chatThread(convId, after = messages.lastOrNull()?.id ?: 0)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    Scaffold(
        contentWindowInsets = if (slice) androidx.compose.foundation.layout.WindowInsets(0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!slice) TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (embedded) Icon(Icons.Filled.Close, contentDescription = "Close the chat")
                        else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(conv?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when {
                            conv == null -> ""
                            conv.oversight -> "Watching: you can read this, not post"
                            conv.kind == "group" -> conv.members.mapNotNull { id -> people.find { it.id == id }?.name }.joinToString(", ")
                            conv.kind == "family" -> "Everyone in the family"
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (mutedUntil > System.currentTimeMillis()) Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted", modifier = Modifier.padding(end = 4.dp))
                    onToggleFullscreen?.let {
                        IconButton(onClick = it) {
                            Icon(if (fullscreen) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull, contentDescription = if (fullscreen) "Smaller" else "Full screen")
                        }
                    }
                    val canMute = Access.can("chat_mute")
                    val canIcon = (conv?.kind == "family" && prefs.role == "parent") || (conv?.kind == "group" && canPost)
                    if (canMute || moderator || canIcon || (conv?.kind == "group" && canPost)) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chat options") }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!canMute) {
                            // Children only mute chats when a parent allows it.
                        } else if (mutedUntil > System.currentTimeMillis()) {
                            DropdownMenuItem(text = { Text("Unmute") }, leadingIcon = { Icon(Icons.Filled.Notifications, null) }, onClick = {
                                menu = false
                                prefs.muteChat(convId, 0)
                                mutedUntil = 0
                            })
                        } else {
                            DropdownMenuItem(text = { Text("Mute notifications…") }, leadingIcon = { Icon(Icons.Filled.NotificationsOff, null) }, onClick = { menu = false; muting = true })
                        }
                        if (canIcon) {
                            DropdownMenuItem(text = { Text("Change icon…") }, leadingIcon = { Icon(Icons.Filled.Face, null) }, onClick = { menu = false; iconPicking = true })
                        }
                        if (moderator) {
                            HorizontalDivider()
                            if (conv.status == "pending") DropdownMenuItem(text = { Text("Say yes to this group") }, leadingIcon = { Icon(Icons.Filled.Check, null) }, onClick = { menu = false; moderate("approve") })
                            when (conv.status) {
                                "" -> DropdownMenuItem(text = { Text("Pause (read only)") }, leadingIcon = { Icon(Icons.Filled.Pause, null) }, onClick = { menu = false; moderate("pause") })
                                "paused" -> DropdownMenuItem(text = { Text("Resume") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = { menu = false; moderate("resume") })
                            }
                            when (conv.status) {
                                "blocked" -> DropdownMenuItem(text = { Text("Unblock") }, leadingIcon = { Icon(Icons.Filled.Lock, null) }, onClick = { menu = false; moderate("unblock") })
                                "pending" -> {}
                                else -> DropdownMenuItem(text = { Text("Block (hide from the children)") }, leadingIcon = { Icon(Icons.Filled.Lock, null) }, onClick = { menu = false; moderate("block") })
                            }
                            DropdownMenuItem(text = { Text("Delete chat…") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                        }
                        if (conv?.kind == "group" && canPost) {
                            DropdownMenuItem(text = { Text("Rename group") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Leave group") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }, onClick = {
                                menu = false
                                scope.launch { runCatching { api.chatLeave(convId, prefs.memberId) }; onBack() }
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val banner = when (conv?.status) {
                "pending" -> if (moderator) "A child started this group. Nobody can post until a parent says yes (⋮ → Say yes), or delete it to say no." else "Waiting for a parent to say yes."
                "paused" -> "A parent paused this chat: you can read it, not post."
                "blocked" -> "Blocked: hidden from the children in it."
                else -> null
            }
            banner?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            // Newest at the bottom: the list is reversed.
            val reversed = messages.asReversed()
            val reads = thread?.reads.orEmpty()
            val myLast = messages.lastOrNull { it.sender == prefs.memberId }
            val seenBy = if (myLast == null) emptyList() else reads.filter { (id, upTo) -> id != prefs.memberId && upTo >= myLast.id }
                .keys.mapNotNull { id -> people.find { it.id == id }?.name }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(reversed.size, key = { reversed[it].id }) { i ->
                    val m = reversed[i]
                    val older = reversed.getOrNull(i + 1)
                    val newer = reversed.getOrNull(i - 1)
                    Bubble(
                        prefs, m, people, mine = m.sender == prefs.memberId, showName = conv?.kind != "direct" || conv.oversight,
                        seenBy = if (m.id == myLast?.id) seenBy else emptyList(),
                        showAvatar = newer == null || newer.sender != m.sender || !sameDay(newer.time, m.time),
                        onLongPress = if (canPost || moderator) ({ acting = m }) else null,
                        onReact = { kind -> scope.launch { runCatching { api.chatReact(convId, m.id, kind) }; thread = api.chatThread(convId); val i = messages.indexOfFirst { it.id == m.id }; thread?.messages?.find { it.id == m.id }?.let { if (i >= 0) messages[i] = it } } },
                        onOpenPhoto = { viewing = it },
                        // A small speaker beside every message with words in it: tap to hear it,
                        // in the sender's own voice (male or female, as they chose).
                        onSpeak = if (canHear && Voice.hasWords(m.text)) ({
                            val who = people.find { it.id == m.sender }
                            val name = if (m.sender == prefs.memberId) "You" else who?.name ?: "Someone"
                            scope.launch { if (!Voice.speak(context, prefs, "$name said: ${m.text}", who?.voice.orEmpty())) Voice.oops(context) }
                        }) else null,
                    )
                    if (older == null || !sameDay(older.time, m.time)) DayHeader(m.time)
                }
            }
            LaunchedEffect(listState.firstVisibleItemIndex) { if (listState.firstVisibleItemIndex == 0) newBelow = 0 }
            if (newBelow > 0) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.FilledTonalButton(onClick = { newBelow = 0; scope.launch { listState.animateScrollToItem(0) } }) {
                        Text(if (newBelow == 1) "1 new message" else "$newBelow new messages")
                    }
                }
            }
            // Who's typing.
            val typingNames = thread?.typing.orEmpty().mapNotNull { id -> people.find { it.id == id }?.name }
            if (typingNames.isNotEmpty()) Text(
                typingNames.joinToString(", ") + if (typingNames.size == 1) " is typing…" else " are typing…",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            // Replying to a message: a glimpse of it above the box, with ✕ to stop.
            replying?.let { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val who = if (r.sender == prefs.memberId) "yourself" else people.find { it.id == r.sender }?.name ?: "someone"
                    ReplyQuote("Replying to $who", r.text.ifEmpty { if (r.photo.isNotEmpty()) "Photo" else if (r.audio.isNotEmpty()) "Voice message" else "" }, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    IconButton(onClick = { replying = null }) { Icon(Icons.Filled.Close, contentDescription = "Don't reply") }
                }
            }
            // Recording a voice message.
            if (recording != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { stopRecording(send = false) }) { Icon(Icons.Filled.Close, contentDescription = "Don't send") }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(SOS_RED))
                    Text("  Recording " + formatClock(recordMs), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    FilledIconButton(onClick = { stopRecording(send = true) }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send the voice message") }
                }
            }
            if (canPost && staged.isNotEmpty()) {
                // The photo about to be sent: check it, take it away with ✕, or send it.
                Box(Modifier.padding(start = 12.dp, top = 8.dp)) {
                    StagedPhoto(staged, if (big) 160 else 120)
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(if (big) 44.dp else 36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.inverseSurface)
                            .clickable(onClickLabel = "Don't send this photo") { staged = "" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, contentDescription = "Remove the photo", tint = MaterialTheme.colorScheme.inverseOnSurface) }
                }
            }
            // Talking: younger children get a big push-to-talk button that sends what they say;
            // everyone else a small one that types it into the message box.
            val canTalk = canPost && Access.can("voice") && Voice.listener(context, prefs.serverFeatures) != null
            // Children and tablets: a wide talk bar above the message box. Younger children's
            // goes straight out (and is read back); everyone else's types into the box.
            val talkBar = canTalk && (Access.isChild || LocalConfiguration.current.screenWidthDp >= 600)
            val photos = canPost && Access.can("chat_photos")
            // Taking or choosing a photo: the menu, opened from the camera button.
            val photoButton: @Composable (Modifier, Dp) -> Unit = { mod, icon ->
                Box(mod) {
                    Box(Modifier.matchParentSize().clickable(enabled = !sending, onClickLabel = "Send a photo") { photoMenu = true }, contentAlignment = Alignment.Center) {
                        if (Access.pictures) Pictures.Camera(icon)
                        else Icon(Icons.Filled.PhotoCamera, contentDescription = "Send a photo", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(icon))
                    }
                    DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Take a photo") },
                            leadingIcon = { Icon(Icons.Filled.CameraAlt, null) },
                            onClick = { photoMenu = false; startCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text("Choose from gallery") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                            onClick = {
                                photoMenu = false
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                    }
                }
            }
            if (talkBar) Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val barH = if (Access.younger || big) 84.dp else 64.dp
                TalkButton(
                    prefs, barH,
                    bar = true,
                    // Younger children's message goes straight out; everyone else's is typed into the box first.
                    label = "Hold to talk",
                    letGo = if (Access.younger) "send" else "type it",
                    modifier = Modifier.weight(1f),
                    onText = { said ->
                        when {
                            said.isEmpty() -> Voice.oops(context)
                            Access.younger -> sendText(said) {
                                // A cheerful "Sent!", so they know it went (not the whole message again).
                                Voice.sent(context)
                            }
                            else -> text = (text.trimEnd() + " " + said).trim().take(4000)
                        }
                    },
                )
                // The camera matches the talk bar beside it: the same rounded look, a little taller.
                if (photos) {
                    val camH = barH + 8.dp
                    val shape = RoundedCornerShape(camH / 2)
                    photoButton(
                        Modifier.padding(start = 10.dp).size(camH).clip(shape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f), shape),
                        camH * .5f,
                    )
                }
            }
            if (canPost) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canTalk && !talkBar) {
                        TalkButton(prefs, 48.dp, onText = { said ->
                            if (said.isEmpty()) android.widget.Toast.makeText(context, "Didn't catch that. Hold, talk, then let go.", android.widget.Toast.LENGTH_SHORT).show()
                            else text = (text.trimEnd() + " " + said).trim().take(4000)
                        })
                    }
                    // With the talk bar, the camera sits beside it instead.
                    if (photos && !talkBar) photoButton(Modifier.size(if (big) 64.dp else 48.dp).clip(CircleShape), if (big) 40.dp else 26.dp)
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it.take(4000)
                            val now = System.currentTimeMillis()
                            if (it.isNotBlank() && now - lastTyping > 4_000) { lastTyping = now; scope.launch { runCatching { api.chatTyping(convId) } } }
                        },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                    )
                    // A voice message, when nothing's typed; not with the talk bar there already.
                    if (text.isBlank() && staged.isEmpty() && recording == null && Access.can("voice") && !talkBar) {
                        // Nothing typed: a voice message instead.
                        IconButton(onClick = {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else {
                                Voice.stop()
                                recording = runCatching { VoiceRecording(context) }.getOrElse { error = "Couldn't start recording"; null }
                            }
                        }, modifier = Modifier.padding(start = 6.dp).then(if (big) Modifier.size(64.dp) else Modifier)) {
                            Icon(Icons.Filled.KeyboardVoice, contentDescription = "Record a voice message", tint = Accent2, modifier = if (big) Modifier.size(32.dp) else Modifier)
                        }
                    } else FilledIconButton(onClick = { send() }, enabled = (text.isNotBlank() || staged.isNotEmpty()) && !sending, modifier = Modifier.padding(start = 6.dp).then(if (big) Modifier.size(64.dp) else Modifier)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = if (big) Modifier.size(32.dp) else Modifier)
                    }
                }
            } else if (thread != null) {
                Text(
                    if (conv?.members?.contains(prefs.memberId) == true) "You can't post here right now."
                    else "You can read this chat because a child of yours is in it. Only the people in it can post.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    if (iconPicking && conv != null) {
        IconPickerDialog(
            conv.title, conv.emoji, conv.color.ifEmpty { "#6F5AA8" },
            onSave = { e, c ->
                iconPicking = false
                scope.launch {
                    try {
                        api.chatSetIcon(convId, e, c)
                        thread = api.chatThread(convId, after = messages.lastOrNull()?.id ?: 0)
                    } catch (ex: Exception) {
                        error = ex.message
                    }
                }
            },
            onDismiss = { iconPicking = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this chat?") },
            text = { Text("All its messages and photos are deleted for everyone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; moderate("delete") }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    acting?.let { m ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        MessageActions(
            onReact = { kind ->
                val already = m.reactions[kind]?.contains(prefs.memberId) == true
                scope.launch {
                    runCatching { api.chatReact(convId, m.id, if (already) "" else kind) }
                    runCatching { api.chatThread(convId) }.getOrNull()?.messages?.find { it.id == m.id }?.let { f -> val i = messages.indexOfFirst { it.id == m.id }; if (i >= 0) messages[i] = f }
                }
            },
            onReply = if (canPost) ({ replying = m }) else null,
            onCopy = if (m.text.isNotEmpty()) ({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text)) }) else null,
            onDelete = if ((m.sender == prefs.memberId && Access.can("chat_delete")) || moderator) ({ deleting = m }) else null,
            onDismiss = { acting = null },
        )
    }
    viewing?.let { b -> PhotoViewer(b) { viewing = null } }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this message?") },
            text = { Text("It's deleted for everyone in the chat.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        try {
                            api.chatDeleteMessage(convId, m.id)
                            messages.removeAll { it.id == m.id }
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (muting) {
        val now = System.currentTimeMillis()
        val tomorrow = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val choices = listOf("For 1 hour" to now + 3_600_000L, "For 8 hours" to now + 8 * 3_600_000L,
            "Until 7:00 tomorrow" to tomorrow, "Until I turn it back on" to Long.MAX_VALUE)
        AlertDialog(
            onDismissRequest = { muting = false },
            title = { Text("Mute this chat") },
            text = {
                Column {
                    Text("No notifications from this chat. Messages still arrive, and you'll see them when you open it.", style = MaterialTheme.typography.bodyMedium)
                    choices.forEach { (label, until) ->
                        TextButton(onClick = {
                            prefs.muteChat(convId, until)
                            mutedUntil = until
                            muting = false
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { muting = false }) { Text("Cancel") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(conv?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename group") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    scope.launch { runCatching { api.chatRename(convId, name.trim()) } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

private val STATUS_LABELS = mapOf("pending" to "Needs OK", "paused" to "Paused", "blocked" to "Blocked")

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    prefs: Prefs, m: ChatMessage, people: List<Member>, mine: Boolean, showName: Boolean, seenBy: List<String>,
    showAvatar: Boolean = true, onLongPress: (() -> Unit)? = null, onSpeak: (() -> Unit)? = null,
    onReact: (String) -> Unit = {}, onOpenPhoto: (Bitmap) -> Unit = {},
) {
    val sender = people.find { it.id == m.sender }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    if (!mine) {
        // Their picture (emoji or initial, in their colour) so you can tell who wrote it without reading.
        val face = if (Access.big || Access.younger) 52 else 36
        Box(Modifier.padding(end = 8.dp, bottom = 18.dp).size(face.dp)) {
            if (showAvatar) Avatar(sender?.name ?: "?", sender?.emoji.orEmpty(), sender?.color ?: "#888888", face)
        }
    }
    Column(Modifier.weight(1f).padding(vertical = 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine && showName) {
            Text(sender?.name ?: "Someone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
        }
        val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        val speaker: @Composable () -> Unit = {
            if (onSpeak != null && (Access.younger || Access.big)) {
                PressButton(onClick = onSpeak, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Read aloud", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            } else if (onSpeak != null) {
                IconButton(onClick = onSpeak, modifier = Modifier.size(if (Access.big) 44.dp else 34.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Read aloud",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                        modifier = Modifier.size(if (Access.big) 26.dp else 20.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
        if (mine) speaker()
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 6.dp, bottomEnd = if (mine) 6.dp else 18.dp))
                .background(bg)
                .then(
                    if (onLongPress != null) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClickLabel = "More", onLongClick = onLongPress,
                        )
                    } else Modifier,
                )
                .padding(if (m.photo.isNotEmpty()) 4.dp else 0.dp),
        ) {
            if (m.replyTo > 0) {
                val who = people.find { it.id == m.replySender }
                ReplyQuote(if (m.replySender == prefs.memberId) "You" else who?.name ?: "Someone", m.replyText, fg)
            }
            if (m.photo.isNotEmpty()) ChatPhoto(prefs, m.photo, onOpen = onOpenPhoto)
            if (m.audio.isNotEmpty()) VoiceBubble(prefs, m.audio, m.audioMs, fg)
            if (m.text.isNotEmpty()) Text(m.text, color = fg, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        if (!mine) speaker()
        }
        ReactionChips(m.reactions, prefs.memberId, onReact)
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.time)) + if (seenBy.isNotEmpty()) " · Seen by ${seenBy.joinToString(", ")}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
        )
    }
    }
}

@Composable
private fun DayHeader(time: Long) {
    val label = when {
        sameDay(time, System.currentTimeMillis()) -> "Today"
        sameDay(time, System.currentTimeMillis() - 86_400_000) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

// Photos need this phone's token, so they're fetched here and kept in a small cache.
private val photoCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
private fun ChatPhoto(prefs: Prefs, name: String, onOpen: (Bitmap) -> Unit = {}) {
    var bitmap by remember(name) { mutableStateOf(photoCache.get(name)) }
    LaunchedEffect(name) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = runCatching {
            val bytes = Api(prefs.serverUrl, prefs.token).chatPhoto(name)
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { photoCache.put(name, it) }
            }
        }.getOrNull()
    }
    val b = bitmap
    if (b == null) {
        Box(Modifier.size(220.dp, 160.dp).background(Color.Black.copy(alpha = 0.08f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Image(
            b.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 292.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Open the photo") { onOpen(b) },
        )
    }
}

/** Loads a picked photo, shrinks it to at most 1600 px and returns JPEG bytes (the server shrinks it too). */
private fun shrinkPhoto(context: android.content.Context, uri: Uri): ByteArray? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    if (opts.outWidth <= 0) return null
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1600) sample *= 2
    var bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    // Cameras often save "rotate 90°" as a note instead of turning the picture: apply it.
    val degrees = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            when (androidx.exifinterface.media.ExifInterface(s).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    if (degrees != 0f) {
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bmp) bmp.recycle()
        bmp = rotated
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    bmp.recycle()
    return out.toByteArray()
}

/** A small preview of a photo that's about to be sent. */
@Composable
private fun StagedPhoto(uri: String, sizeDp: Int) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val jpeg = shrinkPhoto(context, Uri.parse(uri)) ?: return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(
                b.asImageBitmap(), contentDescription = "The photo you're about to send",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

/**
 * A chunky button that really presses in: a raised key with a darker edge under
 * it; pressing pushes it down onto that edge, letting go pops it back. For small
 * hands (the speaker beside messages for younger children).
 */
@Composable
internal fun PressButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 52.dp, content: @Composable () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val depth = 4.dp
    val sink by androidx.compose.animation.core.animateDpAsState(if (pressed) depth else 0.dp, androidx.compose.animation.core.tween(70), label = "press")
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.size(size, size + depth)) {
        // The edge underneath.
        Box(Modifier.align(Alignment.BottomCenter).size(size).clip(shape).background(MaterialTheme.colorScheme.primary.copy(alpha = .55f)))
        // The key itself, which moves down when pressed.
        Box(
            Modifier.offset(y = sink).size(size).clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(2.dp, MaterialTheme.colorScheme.primary, shape)
                .clickable(interactionSource = source, indication = null, onClickLabel = "Read aloud") {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
