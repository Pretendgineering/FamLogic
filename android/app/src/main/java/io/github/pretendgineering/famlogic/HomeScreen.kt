package io.github.pretendgineering.famlogic

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
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
    onOpenChat: (String) -> Unit = { onOpen(Tab.Chat) },
) {
    if (Profiles.open) ProfilesDialog(prefs)
    if (Driveway.open) DrivewayDialog(prefs)
    if (Homestead.open) HomesteadDialog(prefs)
    if (Huddle.open) HuddleDialog(prefs)
    if (Corkboard.open) CorkboardDialog(prefs)
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
    // A one-button tile's pop-up: "nudges" or "ride" ("" = none).
    var shortcut by remember { mutableStateOf("") }
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
                Steward.noteFeatures(prefs, res.features)
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
        "convo.new" -> false // only while a new chat shortcut waits for its chat
        // Modules still being built: "coming soon" tiles, so a layout can be planned.
        "nudges" -> Access.can("nudges") && "requests" in features && !Access.isDisplay && t !in Modules.hidden
        "ride" -> Access.can("requests") && "requests" in features && !Access.isDisplay && t !in Modules.hidden
        "steward" -> false // the corner button does this now; old layouts with the tile just skip it
        "profiles" -> Modules.has(features, "profiles") && Access.can("profiles") && prefs.access != "dependent"
        "display" -> Modules.has(features, "display") && Corkboard.mayControl(prefs) && !Access.isDisplay
        "homestead" -> Modules.has(features, "homestead") && Access.can("homestead") && prefs.access != "dependent"
        "meetings" -> Modules.has(features, "meetings") && Access.can("meetings") && prefs.access != "dependent"
        "garage" -> (Modules.has(features, "garage") && Access.can("garage") && prefs.access != "dependent") || "soon:garage" in features
        in SOON_TILES -> "soon:$t" in features
        // Glance tiles.
        "today", "countdown", "clock" -> true
        "note" -> prefs.access != "dependent"
        "feed" -> Access.can("feed") && !Access.isDisplay
        "whoshome", "weather" -> locationOn && Access.can("map")
        "quickadd" -> Modules.has(features, "todos") && Access.can("todos") && !Access.isDisplay
        "quiet" -> Modules.has(features, "display") && Corkboard.mayControl(prefs) && !Access.isDisplay
        "chores" -> Modules.has(features, Modules.CHORES) && Access.can("chores") && prefs.access != "dependent"
        "todos" -> Modules.has(features, Modules.TODOS) && Access.can("todos") && prefs.access != "dependent"
        "calendar" -> Modules.has(features, Modules.CALENDAR) && Access.can("calendar") && prefs.access != "dependent"
        "meals" -> Modules.has(features, Modules.MEALS) && Access.can("meals") && prefs.access != "dependent"
        "routines" -> Modules.has(features, Modules.ROUTINES) && Access.can("routines") && prefs.access != "dependent"
        else -> t.startsWith("convo.") && chatOn && Access.can("chat")
    }
    val saved = parseTiles(Access.home.ifEmpty { Access.defaultHome() })
    // A module switched on (or newly built) gets its tile on Home once, so new things
    // aren't hidden in the drawer. One the person removes stays removed. Children's and
    // displays' homes are set by a parent, so they're left alone.
    LaunchedEffect(features, Access.home) {
        if (Access.isChild || Access.isDisplay || features.isEmpty()) return@LaunchedEffect
        val moduleTiles = listOf("calendar", "todos", "chores", "routines", "meals", "profiles", "homestead", "meetings", "display") + SOON_TILES
        val placed = saved.map { it.id }.toSet()
        val seen = prefs.seenTiles ?: placed
        val fresh = moduleTiles.filter { usable(it) && it !in placed && it !in seen }
        if (fresh.isEmpty()) { if (prefs.seenTiles == null) prefs.seenTiles = seen; return@LaunchedEffect }
        val list = (saved + parseTiles(fresh.map { "$it:half" })).map { it.encode() }
        runCatching { Api(prefs.serverUrl, prefs.token).setHome(list) }.onSuccess {
            prefs.homeTiles = list
            Access.home = list
            prefs.seenTiles = seen + fresh
        }
    }
    // While arranging: a draft, saved with Done.
    var draft by remember { mutableStateOf<List<HomeTile>?>(null) }
    val editing = draft != null
    LaunchedEffect(editing) { HomeEditor.arranging = editing }
    if (editing) androidx.activity.compose.BackHandler { draft = null }
    LaunchedEffect(HomeEditor.requested) {
        if (HomeEditor.requested) {
            HomeEditor.requested = false
            draft = saved.filter { usable(it.id) }
        }
    }
    val tiles = (draft ?: saved).filter { usable(it.id) || (draft != null && it.id == "convo.new") }
    var pickingChat by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val big = Access.big || Access.isDisplay
    // Landscape: the tiles on the left, the map filling the right-hand side.
    val mapBeside = landscape && !editing && tiles.any { it.id == "map" }

    val gate = rememberSettingsGate(prefs, onOpenSettings)
    // Read straight away (not after the first frame), so Home doesn't start at 3 across and glide.
    if (HomeLayout.columns == 0 && prefs.homeColumns != 0) HomeLayout.columns = prefs.homeColumns
    LaunchedEffect(Unit) { Widgets.refresh(context) } // home-screen widgets catch up when the app opens
    // Tiles across: the person's choice (Look and Feel), or 2 on phones and 3 on tablets.
    val cols = if (landscape && tiles.any { it.id == "map" } && !editing) 2 else HomeLayout.columns(config.screenWidthDp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (editing) Text("Arrange Home") else SecretTitle(familyName.ifEmpty { "FamLogic" }, gate) },
                actions = {
                    if (editing) {
                        TextButton(onClick = {
                            val out = draft.orEmpty().filter { it.id != "convo.new" }
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
                        if (Access.hideSettings) KidColourButton(prefs)
                        else IconButton(onClick = gate.cog) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!editing) RideBanner(members, prefs.memberId) { onOpen(Tab.Map) }
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
                // One-button tiles: just the icon at one column, its name too at two. Others
                // turn into a plain shortcut only when they're too narrow to show anything.
                val tiny = (if (isButtonTile(t.id)) t.w(cols) == 1 else LocalTileWidth.current < ICON_ONLY_WIDTH) && t.id != "map" &&
                    (t.id !in GLANCE_TILES || isButtonTile(t.id)) // a glance tile has its own small view
                if (tiny && t.id != "sos" && !t.id.startsWith("convo.")) IconTile(t.id, h) {
                    when (t.id) {
                        "chat" -> onOpen(Tab.Chat)
                        "checkin" -> confirmCheckIn = true
                        "chores" -> onOpen(Tab.Chores)
                        "todos" -> onOpen(Tab.Lists)
                        "calendar" -> onOpen(Tab.Calendar)
                        "routines" -> onOpen(Tab.Routines)
                        "meals" -> onOpen(Tab.Meals)
                        "steward" -> Steward.sheetOpen = true
                        "display" -> Corkboard.open = true
                        "homestead" -> Homestead.open = true
                        "meetings" -> Huddle.open = true
                        "profiles" -> Profiles.open = true
                        "garage" -> if (Modules.has(features, "garage")) Driveway.open = true else if (Steward.available(prefs)) Steward.sheetOpen = true
                        "nudges" -> shortcut = "nudges"
                        "ride" -> shortcut = "ride"
                        "quickadd" -> shortcut = "quickadd"
                        "quiet" -> shortcut = "quiet"
                        "feed" -> onOpen(Tab.Home) // the feed is a swipe right of Home
                        "today" -> onOpen(Tab.Chores)
                        "whoshome" -> onOpen(Tab.Map)
                        "countdown", "clock", "weather", "note" -> {}
                        in SOON_TILES -> if (Steward.available(prefs)) Steward.sheetOpen = true
                        else -> if (t.id.startsWith("convo.")) onOpenChat(t.id.removePrefix("convo."))
                    }
                } else when (t.id) {
                    "map" -> MapTile(prefs, members, tileUrl, tileUrlDark, attribution, cameraSeq, big, h, onClick = { onOpen(Tab.Map) }, compact = t.w(cols) == 1 || t.h() == 1)
                    "chat" -> ChatTile(chats, members, prefs.memberId, big, h, t.w(cols), t.h(), prefs.lastChat, onOpen = { onOpen(Tab.Chat) }, onOpenChat = onOpenChat)
                    // One-button tiles: the tile is the button.
                    "sos" -> HoldForSosBar(
                        onTriggered = { Sos.send(context, scope, end = false); sosActive = true },
                        modifier = Modifier.fillMaxWidth().height(h), tall = big, tile = true, iconOnly = tiny,
                    )
                    "checkin" -> ButtonTile(if (Access.pictures) "I'm here!" else "Check in", h, big,
                        picture = { Pictures.HereIAm(it) }, icon = Icons.Filled.CheckCircle) { confirmCheckIn = true }
                    "chores" -> UpkeepTile(prefs, h, t.w(cols), t.h(), big) { onOpen(Tab.Chores) }
                    "todos" -> ListsTile(prefs, h, t.w(cols), t.h(), big) { onOpen(Tab.Lists) }
                    "calendar" -> CalendarTile(prefs, h, t.w(cols), t.h(), big) { onOpen(Tab.Calendar) }
                    "routines" -> RoutinesTile(prefs, h, t.w(cols), t.h(), big) { onOpen(Tab.Routines) }
                    "meals" -> MealsTile(prefs, h, t.w(cols), t.h(), big) { onOpen(Tab.Meals) }
                    "steward" -> SimpleTile(Access.name("steward"), "Ask, add or change anything", h, big, icon = FamIcons.Spark) { Steward.sheetOpen = true }
                    "display" -> if (t.h() >= 2 || t.w(cols) >= 3) CorkboardTile(prefs, h, t.w(cols), t.h(), big) { Corkboard.open = true } else SimpleTile(Access.name("display"), if (Corkboard.quietOn) "Quiet time is on" else "Quiet time, messages", h, big, icon = tileIcon("display")) { Corkboard.open = true }
                    "homestead" -> if (t.h() >= 2 || t.w(cols) >= 3) HomesteadTile(prefs, h, t.w(cols), t.h(), big) { Homestead.open = true } else {
                        LaunchedEffect(Unit) { Homestead.refresh(prefs) }
                        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        val next = Homestead.data?.jobs?.firstOrNull()
                        SimpleTile(Access.name("homestead"), next?.let { (if (it.due <= today) "Now: " else "Next: ") + it.title } ?: "Your home's upkeep", h, big, picture = { Pictures.Home(it) }, icon = tileIcon("homestead")) { Homestead.open = true }
                    }
                    "meetings" -> if (t.h() >= 2 || t.w(cols) >= 3) HuddleTile(prefs, h, t.w(cols), t.h(), big) { Huddle.open = true } else {
                        LaunchedEffect(Unit) { Huddle.refresh(prefs) }
                        val next = Huddle.data?.meetings?.firstOrNull()
                        SimpleTile(Access.name("meetings"), next?.title ?: "Family meetings", h, big, icon = tileIcon("meetings")) { Huddle.open = true }
                    }
                    "profiles" -> if (t.h() >= 2 || t.w(cols) >= 3) DossierTile(prefs, h, t.w(cols), t.h(), big) { Profiles.open = true } else SimpleTile(Access.name("profiles"), "Allergies, medicines, contacts", h, big, icon = tileIcon("profiles")) { Profiles.open = true }
                    "garage" -> if (Modules.has(features, "garage")) SimpleTile(Access.name("garage"), "Fill-ups, mileage, services", h, big, icon = tileIcon("garage")) { Driveway.open = true }
                        else SimpleTile(Access.name(t.id), "Coming soon", h, big, icon = tileIcon(t.id)) { if (Steward.available(prefs)) Steward.sheetOpen = true }
                    in SOON_TILES -> SimpleTile(Access.name(t.id), "Coming soon", h, big, icon = tileIcon(t.id)) { if (Steward.available(prefs)) Steward.sheetOpen = true }
                    "nudges" -> ButtonTile(Access.name("nudges"), h, big, picture = { Pictures.Nudge(it) }, icon = tileIcon("nudges")) { shortcut = "nudges" }
                    "ride" -> ButtonTile(if (Access.pictures) "Pick me up" else Access.name("ride"), h, big, picture = { Pictures.Pickup(it) }, icon = tileIcon("ride")) { shortcut = "ride" }
                    "quickadd" -> ButtonTile(Access.name("quickadd"), h, big, icon = tileIcon("quickadd")) { shortcut = "quickadd" }
                    "quiet" -> ButtonTile(Access.name("quiet"), h, big, icon = tileIcon("quiet")) { shortcut = "quiet" }
                    "today" -> TodayTile(prefs, h, t.w(cols), t.h(), big, onOpen)
                    "feed" -> FeedTile(prefs, h, t.w(cols), t.h(), big) { onOpen(Tab.Home) }
                    "whoshome" -> WhosHomeTile(members, h, t.w(cols), t.h(), big) { onOpen(Tab.Map) }
                    "countdown" -> CountdownTile(prefs, h, t.w(cols), t.h(), big) { onOpen(Tab.Calendar) }
                    "clock" -> ClockTile(h, t.w(cols), t.h(), big)
                    "note" -> NoteTile(prefs, h, t.w(cols), t.h(), big)
                    "weather" -> WeatherTile(prefs, h, t.w(cols), t.h(), big)
                    "convo.new" -> SimpleTile(Access.name(t.id), "Choosing a chat…", h, big, icon = tileIcon(t.id)) {}
                    else -> if (t.id.startsWith("convo.")) {
                        ConvoTile(chats.find { it.id == t.id.removePrefix("convo.") }, members, prefs.memberId, big, h, onOpenChat, label = t.w(cols) > 1)
                    }
                }
            }
            // More tiles across, shorter rows: they stay roughly square as they narrow.
            val base = (if (big) 176.dp else 148.dp) * when (cols) { 2 -> 1f; 3 -> .88f; 4 -> .72f; else -> .6f }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // Rows stretch or shrink a little so a whole number of them fills the
            // screen exactly: no "tiny scroll" from a row that's only just too tall.
            val unit = fitUnit(maxHeight, base)
            if (mapBeside) {
                Row(Modifier.fillMaxSize()) {
                    TileBoard(tiles.filter { it.id != "map" }, unit, cols, null, Modifier.weight(1f), tile)
                    // The same rounded card as every other tile.
                    Box(Modifier.weight(1.15f).fillMaxHeight().padding(top = 12.dp, end = 12.dp, bottom = 12.dp)
                        .shadow(3.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        tile(tiles.first { it.id == "map" }, Dp.Unspecified)
                    }
                }
            } else {
                TileBoard(
                    tiles, unit, cols,
                    edit = draft?.let { d ->
                        TileEdit(
                            onChange = { draft = it },
                            hidden = ALL_TILES.filter { id ->
                                if (id == "convo.new") chatOn && Access.can("chat") && d.none { it.id == id }
                                else usable(id) && d.none { it.id == id }
                            },
                            onPickChat = { pickingChat = true },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    tile = tile,
                )
            }
            }
        }
    }

    // A new chat shortcut: which chat does it open?
    if (pickingChat) {
        val taken = draft.orEmpty().map { it.id }.toSet()
        AlertDialog(
            onDismissRequest = { pickingChat = false; draft = draft?.filter { it.id != "convo.new" } },
            title = { Text("Which chat?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    chats.filter { "convo.${it.id}" !in taken }.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pickingChat = false
                                draft = draft?.map { if (it.id == "convo.new") it.copy(id = "convo.${c.id}") else it }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ConvoFace(c, members, prefs.memberId, 40)
                            Text(c.title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (chats.isEmpty()) Text("No chats yet.")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingChat = false; draft = draft?.filter { it.id != "convo.new" } }) { Text("Cancel") } },
        )
    }

    when (shortcut) {
        "nudges" -> NudgePopup(prefs, members) { shortcut = "" }
        "ride" -> PickupPopup(prefs, members) { shortcut = "" }
        "quickadd" -> QuickAddPopup(prefs) { shortcut = "" }
        "quiet" -> QuietPopup(prefs) { shortcut = "" }
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

/** Settings asks the home screen to open in arranging mode. */
object HomeEditor {
    var requested by mutableStateOf(false)
    /** Tiles are being arranged (so the swipe to the feed is off). */
    var arranging by mutableStateOf(false)
}

/**
 * A home screen tile and its size: "WxH", W columns wide (1-3, never more than the
 * home screen has) and H rows tall (1 or 2), and optionally "@C", the column it
 * sits in (0 = the left), so a tile stretched to the left stays to the left. Older
 * sizes still read: "half" is one column, "wide" the full width, "big" the full
 * width and two rows. (The map shows its title bar only at 2 by 2 or bigger.)
 */
/** Rows a tile may be tall: the map up to 3, everything else 2. */
fun maxH(id: String) = if (isButtonTile(id)) 1 else 5

/**
 * Tiles that only do one thing (send, or open a pop-up): one column (just the icon) or
 * two (the icon and its name), and a row tall, so no button takes half the screen for
 * nothing. Every other tile goes from 1 x 1 up to 5 x 5 (as wide as the grid allows),
 * taller than wide if you like.
 */
fun isButtonTile(id: String) = id in BUTTON_TILES || id.startsWith("convo.")
val BUTTON_TILES = setOf("sos", "checkin", "ride", "nudges", "steward", "quickadd", "quiet")
fun maxW(id: String, cols: Int) = if (isButtonTile(id)) minOf(2, cols) else cols

/** The width of the tile being drawn (for tiles that turn into just an icon when narrow). */
val LocalTileWidth = androidx.compose.runtime.staticCompositionLocalOf { Dp.Unspecified }

/** Narrower than this, a tile shows only its icon, as a shortcut. */
val ICON_ONLY_WIDTH = 96.dp

data class HomeTile(val id: String, val size: String) {
    fun encode() = "$id:$size"

    private val dims get() = size.substringBefore('@')

    /** Columns wide, on a home screen [cols] columns across. */
    fun w(cols: Int): Int {
        val w = when (dims) {
            "half" -> 1
            "wide", "big" -> cols
            else -> dims.substringBefore('x').toIntOrNull() ?: 1
        }.coerceIn(1, maxW(id, cols))
        return w
    }

    private fun rawH() = when (dims) {
        "big" -> 2
        "half", "wide" -> 1
        else -> dims.substringAfter('x', "1").toIntOrNull() ?: 1
    }.coerceIn(1, maxH(id))

    /** Rows tall. */
    fun h(): Int = rawH()

    /** The column it's been put in, if any. */
    fun col(): Int? = size.substringAfter('@', "").toIntOrNull()

    fun sized(w: Int, h: Int, col: Int? = col()): HomeTile {
        return copy(size = "${w}x$h" + (col?.let { "@$it" } ?: ""))
    }
}

/** How many tiles fit across the home screen (Look and Feel), shared so a change shows at once. */
object HomeLayout {
    var columns by mutableStateOf(0) // 0 = not chosen: 3

    @Suppress("UNUSED_PARAMETER")
    fun columns(screenWidthDp: Int) = if (columns == 0) 3 else columns.coerceIn(2, 5)
}

/** A tile's place on the grid. */
private class Placed(val tile: HomeTile, val row: Int, val col: Int, val w: Int, val h: Int)

/** Lays the tiles out in order, each in the first free spot it fits (so gaps fill up). */
private fun placeTiles(tiles: List<HomeTile>, cols: Int): List<Placed> {
    val taken = mutableListOf<BooleanArray>()
    fun free(r: Int, c: Int) = r >= taken.size || !taken[r][c]
    val out = mutableListOf<Placed>()
    for (t in tiles) {
        val w = t.w(cols)
        val h = t.h()
        // A tile put in a column stays in it (in the first row where it fits there).
        val only = t.col()?.coerceIn(0, cols - w)
        var r = 0
        search@ while (true) {
            for (c in (if (only != null) only..only else 0..cols - w)) {
                if ((r until r + h).all { rr -> (c until c + w).all { cc -> free(rr, cc) } }) {
                    while (taken.size < r + h) taken += BooleanArray(cols)
                    for (rr in r until r + h) for (cc in c until c + w) taken[rr][cc] = true
                    out += Placed(t, r, c, w, h)
                    break@search
                }
            }
            r++
        }
    }
    return out
}

/** "chat:half" → a tile; without a size, the map, chat and SOS are wide and the rest half. */
fun parseTiles(list: List<String>): List<HomeTile> = list.map {
    val id = it.substringBefore(':')
    val size = it.substringAfter(':', "").ifEmpty { if (id in setOf("map", "chat", "sos")) "wide" else "half" }
    HomeTile(id, size)
}

/** How the tiles are being arranged (null when they aren't). [onPickChat] asks which chat a new chat shortcut opens. */
class TileEdit(val onChange: (List<HomeTile>) -> Unit, val hidden: List<String>, val onPickChat: () -> Unit = {})

/** Tiles for modules still being built: they show "coming soon" so a layout can be planned. */
/**
 * The row height closest to [base] that fits a whole number of rows into a board
 * [height] tall (with the board's 12 dp padding and gaps).
 */
fun fitUnit(height: Dp, base: Dp, gap: Dp = 12.dp, pad: Dp = 12.dp): Dp {
    if (height == Dp.Infinity || height <= 0.dp) return base
    val room = height - pad * 2 + gap
    val rows = (room / (base + gap)).let { kotlin.math.round(it).toInt() }.coerceAtLeast(1)
    return (room / rows - gap).coerceIn(base * .8f, base * 1.25f)
}

val PLACEHOLDER_TILES = listOf("nudges", "ride")

/** Modules switched on but still being built: a tile that says so (and opens Steward, which can keep their notes already). */
val SOON_TILES = listOf("outings", "garage", "ledger", "pets", "contacts")

/** Glance tiles: at-a-glance things that aren't a module of their own. */
val GLANCE_TILES = listOf("today", "whoshome", "weather", "clock", "countdown", "note", "feed", "quickadd", "quiet")

/** Every kind of tile, in the order the drawer shows them. "convo.new" becomes a shortcut to one chat. */
val ALL_TILES = listOf("map", "chat", "convo.new", "sos", "checkin", "chores", "todos", "calendar", "routines", "meals", "profiles", "homestead", "meetings", "display") + GLANCE_TILES + SOON_TILES + PLACEHOLDER_TILES

private val DRAWER_HEIGHT = 132.dp

/** A tile's name in the drawer, and its symbol. */
fun tileIcon(id: String): ImageVector = when (id) {
    "map" -> Icons.Filled.LocationOn
    "chat" -> Icons.Filled.Email
    "convo.new" -> Icons.Filled.Face
    "sos" -> FamIcons.Sos
    "checkin" -> Icons.Filled.CheckCircle
    "calendar" -> Icons.Filled.DateRange
    "todos" -> Icons.AutoMirrored.Filled.List
    "chores" -> FamIcons.Upkeep
    "meals" -> Icons.Filled.ShoppingCart
    "routines" -> Icons.Filled.Refresh
    "nudges" -> Icons.Filled.Favorite
    "ride" -> Icons.Filled.Place
    "steward" -> FamIcons.Spark
    "display" -> Icons.Filled.Tv
    "today" -> Icons.Filled.WbSunny
    "whoshome" -> Icons.Filled.Home
    "weather" -> Icons.Filled.WbCloudy
    "clock" -> Icons.Filled.Schedule
    "countdown" -> Icons.Filled.HourglassBottom
    "note" -> Icons.Filled.StickyNote2
    "feed" -> Icons.Filled.DynamicFeed
    "quickadd" -> Icons.Filled.AddTask
    "quiet" -> Icons.Filled.Bedtime
    "outings" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "garage" -> Icons.Filled.DirectionsCar
    "ledger" -> Icons.Filled.AccountBalanceWallet
    "pets" -> Icons.Filled.Pets
    "profiles" -> Icons.Filled.Badge
    "homestead" -> Icons.Filled.HomeRepairService
    "meetings" -> Icons.Filled.Groups
    "contacts" -> Icons.Filled.Contacts
    else -> Icons.Filled.Build
}

/** The drawer of tiles under the board while arranging: drag one up to add it. */
@Composable
private fun TileDrawer(ids: List<String>, modifier: Modifier, onBounds: (Rect) -> Unit, items: MutableMap<String, Rect>, incoming: String? = null) {
    androidx.compose.material3.Surface(
        modifier.fillMaxWidth().height(DRAWER_HEIGHT).onGloballyPositioned { onBounds(it.boundsInRoot()) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 10.dp,
    ) {
        Column {
            Text(
                if (ids.isEmpty()) "Every tile is on your home screen" else "Tiles: drag one up, or tap it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            )
            // The tiles slide aside to make room for one being dragged down into its place,
            // and close up when one is taken out, like the tiles on the board.
            val slots: List<String> = if (incoming != null && incoming in ALL_TILES && incoming !in ids)
                ALL_TILES.filter { it in ids || it == incoming }.map { if (it == incoming) "gap:$it" else it } else ids
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(slots.size, key = { slots[it] }) { i ->
                    val id = slots[i]
                    if (id.startsWith("gap:")) {
                        Box(
                            Modifier.animateItem().size(width = 92.dp, height = 80.dp)
                                .border(1.5.dp, Accent2.copy(alpha = .6f), RoundedCornerShape(12.dp)),
                        )
                        return@items
                    }
                    run {
                        DisposableEffect(id) { onDispose { items.remove(id) } }
                        Column(
                            Modifier.animateItem().size(width = 92.dp, height = 80.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f))
                                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f), RoundedCornerShape(12.dp))
                                .onGloballyPositioned { items[id] = it.boundsInRoot() }
                                .padding(6.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(tileIcon(id), contentDescription = null, tint = Accent2, modifier = Modifier.size(28.dp))
                            Text(Access.name(id), style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The tiles, in rows. While arranging, the tiles follow your finger: press and hold
 * a tile (or grab its ☰ handle straight away) and drag it, and the others make room
 * as you go. Drag the corner grip and an outline stretches with your finger, the
 * tile snapping to half width, full width or big as you pass each size (a tap on
 * the grip steps through them). ✕ takes a tile off; the + chips add one back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileBoard(tiles: List<HomeTile>, unit: Dp, cols: Int, edit: TileEdit?, modifier: Modifier, tile: @Composable (HomeTile, Dp) -> Unit) {
    // Arranging: tiles become separate squares with gaps. Otherwise they sit edge to
    // edge, divided by hairlines, so the home screen reads as one surface.
    // Going in and out of arranging eases between the two looks instead of jumping.
    val arranging by androidx.compose.animation.core.animateFloatAsState(if (edit != null) 1f else 0f, androidx.compose.animation.core.tween(320), label = "arrange")
    // Tiles are rounded cards with a little depth, spaced apart (Material You).
    val gap = 12.dp
    val pad = 12.dp
    // Each tile's column and size as laid out, for resizing.
    val spots = remember { mutableStateMapOf<String, Placed>() }
    // Where everything is on screen (root coordinates), for the gestures.
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    // The selected tile's grab bars (left, right, bottom edges) and ✕.
    val edges = remember { mutableStateMapOf<Char, Rect>() }
    val closes = remember { mutableStateMapOf<String, Rect>() }
    // The tile tapped while arranging: its controls show.
    var selected by remember { mutableStateOf<String?>(null) }
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
    var lastCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val drawerItems = remember { mutableStateMapOf<String, Rect>() }
    var drawerRect by remember { mutableStateOf(Rect.Zero) }
    val drawer: (Rect) -> Unit = { drawerRect = it }
    // Adds a tile from the drawer at the end; a chat shortcut then asks which chat.
    fun add(id: String) {
        editing?.onChange?.invoke(current + parseTiles(listOf(id)))
        if (id == "convo.new") editing?.onPickChat?.invoke()
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
        if (current.firstOrNull { it.id == id }?.size == size) return
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        editing?.onChange?.invoke(current.map { if (it.id == id) it.copy(size = size) else it })
    }
    // Dragging: whichever tile is under the finger trades places with the one being moved.
    fun columnPx(): Pair<Float, Float> {
        val gapPx = with(density) { gap.toPx() }
        return (boardSize.width - with(density) { (pad * 2).toPx() } - gapPx * (cols - 1)) / cols to gapPx
    }
    // (Judged by where tiles are going, not where they are mid-glide.)
    fun slot(p: Placed): Rect {
        val (colPx, gapPx) = columnPx()
        val unitPx = with(density) { unit.toPx() }
        val left = boardAt.x + with(density) { pad.toPx() } + p.col * (colPx + gapPx)
        val top = boardAt.y + with(density) { pad.toPx() } - scroll.value + p.row * (unitPx + gapPx)
        return Rect(left, top, left + p.w * colPx + (p.w - 1) * gapPx, top + p.h * unitPx + (p.h - 1) * gapPx)
    }
    // Carrying a tile: it goes to whichever cell its top-left corner is over (any
    // cell, empty or not), and every other tile flows around it in order. Worked
    // out only when that cell changes, so it can't flicker between two layouts.
    fun follow() {
        val id = dragging ?: return
        val t = current.firstOrNull { it.id == id } ?: return
        val (colPx, gapPx) = columnPx()
        val unitPx = with(density) { unit.toPx() }
        val padPx = with(density) { pad.toPx() }
        val b = bounds[id]
        val gx = if (grab.x < 0) (b?.width ?: colPx) / 2 else grab.x
        val gy = if (grab.y < 0) (b?.height ?: unitPx) / 2 else grab.y
        val w = t.w(cols)
        val col = kotlin.math.round((finger.x - gx - boardAt.x - padPx) / (colPx + gapPx)).toInt().coerceIn(0, cols - w)
        val row = kotlin.math.round((finger.y - gy - boardAt.y - padPx + scroll.value) / (unitPx + gapPx)).toInt().coerceAtLeast(0)
        if (lastCell == row to col) return
        lastCell = row to col
        val others = current.filter { it.id != id }
        val before = others.count { o -> spots[o.id]?.let { it.row * cols + it.col < row * cols + col } ?: true }
        val list = others.toMutableList().apply { add(before, t.sized(w, t.h(), col)) }
        if (list != current) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            editing?.onChange?.invoke(list)
        }
    }
    // Resizing by an edge: an outline follows the finger exactly; the tile itself only
    // changes size when it's let go (to the nearest whole size), so nothing reflows
    // under the finger and nothing can flip back and forth.
    var resizeStart by remember { mutableStateOf(Rect.Zero) }
    var resizeEdge by remember { mutableStateOf('r') }
    /** The outline while resizing (root coordinates). */
    fun ghost(): Rect {
        val r = resizeStart
        val min = with(density) { 48.dp.toPx() }
        return when (resizeEdge) {
            'r' -> Rect(r.left, r.top, maxOf(finger.x, r.left + min), r.bottom)
            'l' -> Rect(minOf(finger.x, r.right - min), r.top, r.right, r.bottom)
            'c' -> Rect(r.left, r.top, maxOf(finger.x, r.left + min), maxOf(finger.y, r.top + min))
            else -> Rect(r.left, r.top, r.right, maxOf(finger.y, r.top + min))
        }
    }
    /** The size (and column) the outline would give. */
    fun ghostSize(id: String): HomeTile? {
        val t = current.firstOrNull { it.id == id } ?: return null
        val spot = spots[id] ?: return null
        val (colPx, gapPx) = columnPx()
        val unitPx = with(density) { unit.toPx() }
        val g = ghost()
        val w = kotlin.math.round((g.width + gapPx) / (colPx + gapPx)).toInt().coerceIn(1, maxW(id, cols))
        val h = kotlin.math.round((g.height + gapPx) / (unitPx + gapPx)).toInt().coerceIn(1, maxH(id))
        val col = when (resizeEdge) {
            'l' -> (spot.col + spot.w - w).coerceAtLeast(0) // grows to the left: the right edge stays put
            else -> spot.col.coerceAtMost(cols - w)
        }
        return when (resizeEdge) {
            'b' -> t.sized(spot.w, h, col)
            'c' -> t.sized(w, h, col) // the corner: both at once
            else -> t.sized(w, spot.h, col)
        }
    }
    var ghostSeen by remember { mutableStateOf("") }
    fun stretch() {
        val id = resizing ?: return
        // A tick each time the outline passes a whole size (the tile doesn't move yet).
        val g = ghostSize(id)?.size ?: return
        if (g != ghostSeen) { if (ghostSeen.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); ghostSeen = g }
    }
    fun finishStretch() {
        val id = resizing ?: return
        ghostSize(id)?.let { t -> editing?.onChange?.invoke(current.map { if (it.id == id) t else it }) }
        resizing = null
        ghostSeen = ""
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
            // Carrying a tile under the finger until it's let go; over the drawer, it comes off.
            suspend fun AwaitPointerEventScope.carry(id: String) {
                dragging = id
                skip = null
                lastCell = null
                // While one tile is carried, the others aren't held to their columns: they flow.
                if (current.any { it.id != id && it.col() != null }) {
                    editing?.onChange?.invoke(current.map { if (it.id != id && it.col() != null) it.copy(size = it.size.substringBefore('@')) else it })
                }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) break
                    finger = boardAt + c.position
                    edge = when {
                        c.position.y < zone -> -(zone - c.position.y) / 4f
                        c.position.y > size.height - zone - DRAWER_HEIGHT.toPx() && !drawerRect.contains(finger) -> (c.position.y - (size.height - zone - DRAWER_HEIGHT.toPx())) / 4f
                        else -> 0f
                    }
                    if (!drawerRect.contains(finger)) follow()
                }
                edge = 0f
                dragging = null
                if (drawerRect.contains(finger)) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    editing?.onChange?.invoke(current.filter { it.id != id })
                } else if (id == "convo.new") {
                    editing?.onPickChat?.invoke()
                }
            }
            val fromDrawer = drawerItems.entries.firstOrNull { it.value.contains(at) }?.key
            if (fromDrawer != null) {
                // Up and out of the drawer carries the tile onto the board; sideways scrolls
                // the drawer; a tap adds it at the end.
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!c.pressed) { add(fromDrawer); return@awaitEachGesture }
                    val d = c.position - down.position
                    if (kotlin.math.abs(d.x) > slop && kotlin.math.abs(d.x) > kotlin.math.abs(d.y)) return@awaitEachGesture
                    if (-d.y > slop) {
                        c.consume()
                        finger = boardAt + c.position
                        grab = Offset(-1f, -1f) // held by its middle
                        editing?.onChange?.invoke(current + parseTiles(listOf(fromDrawer)))
                        carry(fromDrawer)
                        return@awaitEachGesture
                    }
                }
            }
            // The selected tile's edges: drag to make it wider, narrower, taller or shorter.
            val sel = selected
            // Bigger targets near the screen's edges, where Android's back gesture lives.
            val screenRight = boardAt.x + boardSize.width
            fun reach(r: Rect) = if (r.left - boardAt.x < 48.dp.toPx() || screenRight - r.right < 48.dp.toPx()) 30.dp.toPx() else 22.dp.toPx()
            // Of the handles in reach, the nearest (the corner wins over the edges beside it).
            val grabbed = if (sel != null) edges.entries.filter { it.value.inflate(reach(it.value)).contains(at) }
                .minByOrNull { (it.value.center - at).getDistance() }?.key else null
            if (sel != null && grabbed != null) {
                var moved = false
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    if (!moved && (c.position - down.position).getDistance() > slop / 2) {
                        moved = true
                        resizeStart = bounds[sel] ?: Rect.Zero
                        resizeEdge = grabbed
                        resizing = sel
                    }
                    if (moved) { c.consume(); finger = boardAt + c.position; stretch() }
                }
                finishStretch()
                return@awaitEachGesture
            }
            val id = bounds.entries.firstOrNull { it.value.contains(at) }?.key
            // Press and hold a tile to pick it up; a quick tap selects it (or lets it go);
            // moving straight away scrolls.
            var tapped = false
            var held = true
            val result = withTimeoutOrNull(hold) {
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (c == null) { held = false; break }
                    if (!c.pressed) { tapped = (c.position - down.position).getDistance() <= slop; held = false; break }
                    if ((c.position - down.position).getDistance() > slop) { held = false; break }
                }
            }
            if (tapped) {
                selected = if (id == null || id == selected) null else id
                if (id != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                return@awaitEachGesture
            }
            if (result != null || !held || id == null) return@awaitEachGesture
            selected = id
            finger = at
            grab = at - (bounds[id]?.topLeft ?: at)
            carry(id)
        }
    }

    Box(
        modifier
            .onGloballyPositioned { boardAt = it.positionInRoot(); boardSize = it.size }
            .then(gestures),
    ) {
        val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(pad).padding(bottom = DRAWER_HEIGHT * arranging),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            // The grid: columns of equal width, rows of [unit] height; each tile spans its size.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val colW = (maxWidth - gap * (cols - 1)) / cols
                val placed = placeTiles(tiles, cols)
                val rowsN = placed.maxOfOrNull { it.row + it.h } ?: 0
                Box(Modifier.fillMaxWidth().height(if (rowsN == 0) 0.dp else unit * rowsN + gap * (rowsN - 1))) {
                    placed.forEach { p ->
                        val t = p.tile
                        spots[t.id] = p
                        key(t.id) {
                            val h = unit * p.h + gap * (p.h - 1)
                            val isDragged = dragging == t.id
                            DisposableEffect(t.id) { onDispose { bounds.remove(t.id); closes.remove(t.id) } }
                            // Tiles glide to their new places (and sizes) instead of jumping.
                            val glide = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.85f, stiffness = 380f)
                            val x by androidx.compose.animation.core.animateDpAsState((colW + gap) * p.col, glide, label = "x")
                            val y by androidx.compose.animation.core.animateDpAsState((unit + gap) * p.row, glide, label = "y")
                            val w by androidx.compose.animation.core.animateDpAsState(colW * p.w + gap * (p.w - 1), glide, label = "w")
                            val hh by androidx.compose.animation.core.animateDpAsState(h, glide, label = "h")
                            Box(
                                Modifier.offset(x = x, y = y)
                                    .size(w, hh)
                                    .onGloballyPositioned { bounds[t.id] = it.boundsInRoot() }
                                    // Being carried: drawn floating above everything (below), its slot left empty.
                                    .graphicsLayer { alpha = if (isDragged) 0f else 1f }
                                    .shadow(if (isDragged) 0.dp else 3.dp, RoundedCornerShape(22.dp))
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalTileWidth provides w) { tile(t, h) }
                                if (edit != null) Box(Modifier.matchParentSize().graphicsLayer { alpha = arranging }) {
                                    EditChrome(t.id, selected == t.id, isDragged || resizing == t.id, edit, tiles, edges, closes)
                                }
                            }
                        }
                    }
                }
            }
            if (edit != null) {
                Text(
                    "Drag a tile up from the drawer below to add it. Tap a tile to select it, then drag the bars on its edges to resize it (1 to $cols columns wide, 1 or 2 rows tall). " +
                        "Press and hold a tile to move it, or drag it back down to the drawer to take it off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // The drawer: every tile that isn't on the home screen yet; it slides up into view.
        if (edit != null) {
            TileDrawer(
                edit.hidden, Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = (1f - arranging) * DRAWER_HEIGHT.toPx() }, drawer, items = drawerItems,
                incoming = dragging?.takeIf { drawerRect.contains(finger) },
            )
        }
        // The tile being carried floats above everything, the drawer included.
        val carried = dragging?.let { id -> spots[id] }
        val carriedBounds = dragging?.let { bounds[it] }
        if (carried != null && carriedBounds != null) {
            val g = if (grab.x < 0) Offset(carriedBounds.width / 2, carriedBounds.height / 2) else grab
            val over = drawerRect.contains(finger)
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((finger.x - g.x - boardAt.x).toInt(), (finger.y - g.y - boardAt.y).toInt()) }
                    .size(with(density) { carriedBounds.width.toDp() }, with(density) { carriedBounds.height.toDp() })
                    .graphicsLayer { scaleX = if (over) .7f else 1.05f; scaleY = if (over) .7f else 1.05f; shadowElevation = 28f; alpha = if (over) .75f else 1f }
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
            ) { tile(carried.tile, unit * carried.h + gap * (carried.h - 1)) }
        }
        // Resizing: the outline that follows the finger.
        if (resizing != null) {
            val r = ghost()
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val o = Offset(r.left - boardAt.x, r.top - boardAt.y)
                val sz = androidx.compose.ui.geometry.Size(r.width, r.height)
                drawRoundRect(color.copy(alpha = .14f), o, sz, androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                drawRoundRect(color, o, sz, androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
            }
        }
    }
}

/**
 * While arranging: every tile is outlined, ready to edit (and takes the touches,
 * so a tile underneath, an SOS button say, never fires). The selected one is
 * outlined boldly, with a small ✕ and grab bars on its left, right and bottom
 * edges to drag it wider, narrower, taller or shorter.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EditChrome(
    id: String, selected: Boolean, active: Boolean, edit: TileEdit, tiles: List<HomeTile>,
    edges: MutableMap<Char, Rect>, closes: MutableMap<String, Rect>,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.matchParentSize().clip(shape)
            .background(accent.copy(alpha = if (active) .16f else if (selected) .1f else .04f))
            .border(if (selected) 3.dp else 1.5.dp, accent.copy(alpha = if (selected) 1f else .45f), shape)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    )
    if (!selected) return
    DisposableEffect(id) { onDispose { edges.clear() } }
    val d = androidx.compose.ui.platform.LocalDensity.current.density
    // Grab bars: a thicker pill in the middle of each edge (shorter on small tiles), and a
    // round handle on the corner that changes both width and height.
    @Composable
    fun Bar(edge: Char, align: Alignment, vertical: Boolean) {
        Box(
            Modifier.align(align).padding(3.dp)
                .size(if (vertical) 8.dp else 32.dp, if (vertical) 32.dp else 8.dp)
                .clip(RoundedCornerShape(4.dp)).background(accent)
                .onGloballyPositioned { edges[edge] = it.boundsInRoot() }
                // Swiping from a screen edge here resizes the tile instead of going Back.
                .systemGestureExclusion { c -> val m = 24f * d; Rect(Offset(-m, -m), androidx.compose.ui.geometry.Size(c.size.width + 2 * m, c.size.height + 2 * m)) }
                .semantics { contentDescription = if (edge == 'b') "Drag to make it taller or shorter" else "Drag to make it wider or narrower" },
        )
    }
    Bar('l', Alignment.CenterStart, vertical = true)
    Bar('r', Alignment.CenterEnd, vertical = true)
    Bar('b', Alignment.BottomCenter, vertical = false)
    if (maxH(id) > 1) Box(
        Modifier.align(Alignment.BottomEnd).padding(2.dp).size(22.dp).clip(CircleShape).background(accent)
            .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .onGloballyPositioned { edges['c'] = it.boundsInRoot() }
            .systemGestureExclusion { c -> val m = 24f * d; Rect(Offset(-m, -m), androidx.compose.ui.geometry.Size(c.size.width + 2 * m, c.size.height + 2 * m)) }
            .semantics { contentDescription = "Drag to change its size" },
    )
    Box(
        Modifier.align(Alignment.TopEnd).padding(5.dp).size(30.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface).border(1.dp, accent.copy(alpha = .5f), CircleShape)
            .onGloballyPositioned { closes[id] = it.boundsInRoot() }
            .clickable(onClickLabel = "Remove") { edit.onChange(tiles.filter { it.id != id }) },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Close, contentDescription = "Remove from home", modifier = Modifier.size(18.dp)) }
}

@Composable
private fun MapTile(
    prefs: Prefs, members: List<Member>, tileUrl: String, tileUrlDark: String, attribution: String,
    cameraSeq: Int, big: Boolean, height: Dp, onClick: () -> Unit,
    /** One square: just the map and its pins, no bar under it. */
    compact: Boolean = false,
) {
    val dark = LocalDarkTheme.current
    val tiles = MapStyles.resolve(prefs.mapStyle, dark, tileUrl, tileUrlDark, attribution, prefs.dimMap)
    val placed = members.filter { it.loc != null && !it.paused }
    val fill = height == Dp.Unspecified
    Column(
        Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier.height(height)).clickable(onClickLabel = "Open the map", onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            OsmMap(
                members = placed,
                tiles = tiles,
                camera = if (placed.isNotEmpty()) CameraCommand.FitAll(cameraSeq + placed.size) else null,
                statusOf = { "" },
                corner = 22.dp,
                roundBottom = compact,
            )
            // The map is a preview: a tap anywhere opens Thereabouts.
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "Open the map", onClick = onClick))
        }
        if (!compact) Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (Access.pictures) Pictures.Map(28.dp) else Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Accent2)
            Text(Access.name("map"), Modifier.padding(start = 6.dp, end = 10.dp), style = MaterialTheme.typography.titleMedium)
            // Faces of who's where, in a row: easy to read at a glance.
            members.take(if (big) 6 else 5).forEach { m ->
                Box(Modifier.padding(end = 4.dp)) { Avatar(m.name, m.emoji, m.color, if (big) 32 else 26) }
            }
        }
        if (!compact && (fill || height > 300.dp)) {
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
    return Seen.where(m)
}

/**
 * Chitchat. Half width: two quick buttons, the family chat and the last chat you
 * had open. Full width (or big): the latest messages, each with the sender's face.
 */
@Composable
private fun ChatTile(
    chats: List<ChatConversation>, members: List<Member>, me: String, big: Boolean, height: Dp, wide: Int, tall: Int, lastChat: String,
    onOpen: () -> Unit, onOpenChat: (String) -> Unit,
) {
    val unread = chats.sumOf { it.unread }
    // One tile wide or tall: a strip of chats, the most recent first, as many as fit:
    // across for a wide tile, down for a tall one.
    if (wide == 1 || tall == 1) {
        val vertical = tall > wide
        val order = chats.sortedByDescending { c -> c.last?.time ?: if (c.id == lastChat) 1L else 0L }
        val face = if (big) 56 else 44
        Column(Modifier.fillMaxWidth().height(height)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Open all chats", onClick = onOpen).padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.text.BasicText(Access.name("chat"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize, stepSize = 0.5.sp))
                if (unread > 0) androidx.compose.material3.Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$unread") }
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                val slot = (face + 28).dp // a face and its name
                val fit = (((if (vertical) maxHeight else maxWidth) + 8.dp) / (slot + 8.dp)).toInt().coerceAtLeast(1)
                val shown = order.take(fit)
                val item: @Composable (ChatConversation, Modifier) -> Unit = { c, m ->
                    Column(
                        m.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open ${c.title}") { onOpenChat(c.id) },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            ConvoFace(c, members, me, face)
                            if (c.unread > 0) androidx.compose.material3.Badge(Modifier.align(Alignment.TopEnd), containerColor = MaterialTheme.colorScheme.primary) { Text("${c.unread}") }
                        }
                        Text(c.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                when {
                    shown.isEmpty() -> Text("No chats yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    vertical -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { c -> item(c, Modifier.fillMaxWidth().weight(1f)) }
                    }
                    else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { c -> item(c, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
        return
    }
    val recent = chats.mapNotNull { c -> c.last?.let { c to it } }.sortedByDescending { it.second.time }
    Column(Modifier.fillMaxWidth().height(height).clickable(onClick = onOpen).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (Access.pictures) Pictures.Talk(if (big) 48.dp else 32.dp)
            else Icon(Icons.Filled.Email, contentDescription = null, tint = Accent2, modifier = Modifier.size(if (big) 40.dp else 28.dp))
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
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenChat(c.id) }.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

/** A chat's picture: its own icon, the other person's face for a one-to-one chat, or a star for the family chat. */
@Composable
private fun ConvoFace(c: ChatConversation, members: List<Member>, me: String, size: Int) {
    val other = if (c.kind == "direct") members.find { it.id in c.members && it.id != me } else null
    when {
        c.kind == "family" && c.emoji.isEmpty() -> FamilyCrest(size, color = c.color)
        c.kind != "direct" && (c.emoji.isNotEmpty() || c.color.isNotEmpty()) -> Avatar(c.title, c.emoji, c.color.ifEmpty { "#6F5AA8" }, size)
        other != null -> Avatar(other.name, other.emoji, other.color, size)
        else -> Avatar(c.title, "", "#6F5AA8", size)
    }
}

/**
 * A shortcut to one chat, chosen when the tile was placed: the chat's picture
 * (for a one-to-one chat, the other person's face), big enough for a small child
 * to find, and its name. A tap opens that chat.
 */
@Composable
private fun ConvoTile(c: ChatConversation?, members: List<Member>, me: String, big: Boolean, height: Dp, onOpenChat: (String) -> Unit, label: Boolean = true) {
    Column(
        Modifier.fillMaxWidth().height(height).then(if (c != null) Modifier.clickable(onClickLabel = "Open ${c.title}") { onOpenChat(c.id) } else Modifier).padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (c == null) {
            Icon(Icons.Filled.Face, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Text("Chat not found", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Box {
            // One column: the face alone, as big as fits.
            val fit = ((LocalTileWidth.current.takeIf { it != Dp.Unspecified } ?: 84.dp).coerceAtMost(height) * .62f).value.toInt()
            ConvoFace(c, members, me, if (!label) fit else if (Access.younger || big) 84 else 64)
            if (c.unread > 0) androidx.compose.material3.Badge(Modifier.align(Alignment.TopEnd), containerColor = MaterialTheme.colorScheme.primary) { Text("${c.unread}") }
        }
        if (label) Text(c.title, Modifier.padding(top = 6.dp), style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A narrow tile: just its picture or icon, as a shortcut (its name is read out by TalkBack). */
@Composable
private fun IconTile(id: String, height: Dp, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(height).clickable(onClickLabel = Access.name(id), onClick = onClick)
            .semantics { contentDescription = Access.name(id) },
        contentAlignment = Alignment.Center,
    ) {
        val s = (LocalTileWidth.current.takeIf { it != Dp.Unspecified } ?: 64.dp).coerceAtMost(height) * .5f
        val pic = if (Access.pictures) Pictures.forModule(id) else null
        if (pic != null) pic(s * 1.2f)
        else Icon(tileIcon(id), contentDescription = null, tint = Accent2, modifier = Modifier.size(s))
    }
}

/** A tile that does one thing: the whole tile is the button, in the theme's colour. */
@Composable
private fun ButtonTile(
    title: String, height: Dp, big: Boolean,
    picture: (@Composable (Dp) -> Unit)? = null, icon: ImageVector, onClick: () -> Unit,
) {
    // Same card as every other tile; the action shows in a soft Accent2 badge, not an inverted block.
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().height(height),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val s = if (big) 64.dp else 48.dp
            if (Access.pictures && picture != null) picture(s + 12.dp)
            else Box(Modifier.size(s + 16.dp).clip(CircleShape).background(Accent2.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Accent2, modifier = Modifier.size(s * .62f))
            }
            // One line, shrinking to fit ("Pick me up" on a narrow tile).
            val style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            androidx.compose.foundation.text.BasicText(
                title, Modifier.fillMaxWidth().padding(top = 8.dp), style = style, maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            )
        }
    }
}

@Composable
private fun SimpleTile(
    title: String, sub: String, height: Dp, big: Boolean,
    picture: (@Composable (Dp) -> Unit)? = null, icon: ImageVector, onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(height).clickable(onClick = onClick).padding(16.dp)) {
        val s = if (big) 56.dp else 36.dp
        if (Access.pictures && picture != null) picture(s + 8.dp)
        else Icon(icon, contentDescription = null, tint = Accent2.copy(alpha = .75f), modifier = Modifier.size(s))
        Spacer(Modifier.height(10.dp))
        // Titles stay on one line, shrinking to fit a small tile.
        val style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
        androidx.compose.foundation.text.BasicText(
            title,
            style = style.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
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
        color = Accent2,
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
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
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


/**
 * A module tile's name and icon. The name is never cut off: it shrinks to fit, and on a
 * one-wide tile the icon sits above it so the name has the whole width.
 */
@Composable
fun TileHead(wide: Int, big: Boolean, title: String, icon: @Composable () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    @Composable fun Name(m: Modifier) = androidx.compose.foundation.text.BasicText(
        title, m, style = style, maxLines = 1,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
    if (wide <= 1) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) { icon(); Spacer(Modifier.weight(1f)); trailing?.invoke() }
            Name(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Name(Modifier.padding(start = 8.dp).weight(1f))
            trailing?.invoke()
        }
    }
}
