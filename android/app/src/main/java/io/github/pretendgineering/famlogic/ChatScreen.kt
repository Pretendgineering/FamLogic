package io.github.pretendgineering.famlogic

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
        // Full screen, by choice (or a phone on its side).
        conv != null && (fullscreen || (!tablet && !portrait)) -> ConversationView(
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
        // Portrait: the list splits open at the chat you tap, the rows above slide up and
        // the rows below are pushed down, and the chat opens in the gap, like pulling
        // apart a stack of cards. Tap the chat's row again (or ✕) to close it up.
        else -> ConversationList(
            prefs, people, selected = conv, onOpen = { open = if (open == it) null else it },
            expanded = conv,
            panel = { id ->
                ConversationView(prefs, id, people, onBack = { open = null }, embedded = true, onToggleFullscreen = { fullscreen = true })
            },
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
    Scaffold(
        topBar = { TopAppBar(title = { Text(Access.name("chat")) }) },
        floatingActionButton = {
            if (Access.can("chat_start")) {
                LargeFloatingActionButton(onClick = { newChat = true }) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
        val viewport = maxHeight
        // The gap's height grows from nothing (the slice) to almost the whole screen.
        var shownPanel by remember { mutableStateOf<String?>(null) }
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
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
            val list = convs
            if (list == null) {
                item { Text("Loading…", modifier = Modifier.padding(16.dp)) }
            } else {
                list.forEach { c ->
                    item(key = c.id) { ConversationRow(c, people, prefs.memberId, selected = c.id == selected) { onOpen(c.id) } }
                    if (panel != null && c.id == shownPanel) {
                        item(key = "panel") {
                            // The opened gap: the chat sits in it like a card pulled out of the stack.
                            val h = (viewport - (if (Access.big) 96.dp else 72.dp) - 56.dp).coerceAtLeast(240.dp)
                            Box(Modifier.fillMaxWidth().height(h * gap.value).clipToBounds()) {
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    shadowElevation = 10.dp,
                                    tonalElevation = 3.dp,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.requiredHeight(h).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                                        .graphicsLayer { scaleX = 0.96f + 0.04f * gap.value; alpha = 0.6f + 0.4f * gap.value },
                                ) { panel(c.id) }
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
private fun ConversationRow(c: ChatConversation, people: List<Member>, me: String, selected: Boolean = false, onClick: () -> Unit) {
    val other = if (c.kind == "direct") people.find { it.id in c.members && it.id != me } else null
    // Children who can't read yet find chats by faces: bigger pictures, and a group shows who's in it.
    val big = Access.big
    val size = if (big) 68 else 44
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (big) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            c.kind == "family" -> Box(
                Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size((size / 2).dp)) }
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
        if (c.unread > 0) Badge(Modifier.padding(start = 8.dp)) { Text(c.unread.toString(), fontSize = if (big) 16.sp else 11.sp) }
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
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
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
                val full = round++ % 10 == 9
                val after = if (full) 0 else messages.lastOrNull()?.id ?: 0
                val t = api.chatThread(convId, after = after)
                thread = t
                if (full) messages.retainAll { m -> t.messages.any { it.id == m.id } || m.id < (t.messages.firstOrNull()?.id ?: 0) }
                if (t.messages.isNotEmpty()) {
                    messages += t.messages.filter { m -> messages.none { it.id == m.id } }
                    listState.animateScrollToItem(0)
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
                val m = api.chatSendPhoto(convId, jpeg, text.trim())
                text = ""
                staged = ""
                if (messages.none { it.id == m.id }) messages += m
                listState.animateScrollToItem(0)
            } catch (e: IOException) {
                error = e.message
            } finally {
                sending = false
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) staged = uri.toString()
    }
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
                val m = api.chatSend(convId, t)
                text = ""
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
        topBar = {
            TopAppBar(
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
                    if (canMute || moderator || (conv?.kind == "group" && canPost)) {
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
                        if (moderator && conv != null) {
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
                        onLongPress = if ((m.sender == prefs.memberId && Access.can("chat_delete")) || moderator) ({ deleting = m }) else null,
                    )
                    if (older == null || !sameDay(older.time, m.time)) DayHeader(m.time)
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
            if (canPost) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (Access.can("chat_photos")) Box {
                        IconButton(onClick = { photoMenu = true }, enabled = !sending, modifier = if (big) Modifier.size(64.dp) else Modifier) {
                            if (Access.pictures) Pictures.Camera(if (big) 44.dp else 32.dp)
                            else Icon(Icons.Filled.PhotoCamera, contentDescription = "Send a photo", modifier = if (big) Modifier.size(36.dp) else Modifier)
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
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(4000) },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                    )
                    FilledIconButton(onClick = { send() }, enabled = (text.isNotBlank() || staged.isNotEmpty()) && !sending, modifier = Modifier.padding(start = 6.dp).then(if (big) Modifier.size(64.dp) else Modifier)) {
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
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this chat?") },
            text = { Text("All its messages and photos are deleted for everyone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; moderate("delete") }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
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
    showAvatar: Boolean = true, onLongPress: (() -> Unit)? = null,
) {
    val sender = people.find { it.id == m.sender }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    if (!mine) {
        // Their picture (emoji or initial, in their colour) so you can tell who wrote it without reading.
        val face = if (Access.big) 52 else 36
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
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 6.dp, bottomEnd = if (mine) 6.dp else 18.dp))
                .background(bg)
                .then(if (onLongPress != null) Modifier.combinedClickable(onLongClickLabel = "Delete", onLongClick = onLongPress, onClick = {}) else Modifier)
                .padding(if (m.photo.isNotEmpty()) 4.dp else 0.dp),
        ) {
            if (m.photo.isNotEmpty()) ChatPhoto(prefs, m.photo)
            if (m.text.isNotEmpty()) Text(m.text, color = fg, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
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
private fun ChatPhoto(prefs: Prefs, name: String) {
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
            modifier = Modifier.widthIn(max = 292.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(14.dp)),
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
