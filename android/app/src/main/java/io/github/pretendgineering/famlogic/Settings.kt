package io.github.pretendgineering.famlogic

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.IOException

/** The pages of Settings. */
private enum class SettingsPage(val title: String, val icon: ImageVector) {
    Sharing("Location Sharing", Icons.Filled.Share),
    You("You", Icons.Filled.AccountCircle),
    Look("Look and Feel", Icons.Filled.Edit),
    Alerts("Alerts & Safety", Icons.Filled.Notifications),
    Places("Groups & Fences", Icons.Filled.Place),
    Pebble("Watch & Ring", Icons.Filled.Star),
    About("About", Icons.Filled.Info),
}

/** What children see in Settings: making the app their own, and their sign-in. */
private val CHILD_PAGES = setOf(SettingsPage.You, SettingsPage.Look, SettingsPage.About)

/**
 * Settings: a short menu of pages (Sharing, Groups, Fences, Alerts, Trail,
 * Account, Phone setup, About), each grouping its options into cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    perms: PermissionState,
    features: Set<String>?,
    sharing: Boolean,
    onSharingChange: (Boolean) -> Unit,
    shareLowBattery: Boolean?,
    onShareLowBatteryChange: (Boolean) -> Unit,
    username: String,
    hasPassword: Boolean?,
    circles: List<CircleInfo>,
    onCircleSharingChange: (CircleInfo, Boolean) -> Unit,
    onCirclesChanged: () -> Unit,
    places: List<Place>,
    onEditPlace: (PlaceEdit) -> Unit,
    shareArrivals: Boolean?,
    onShareArrivalsChange: (Boolean) -> Unit,
    trailHours: Int?,
    onTrailHoursChange: (Int) -> Unit,
    serverVersion: String,
    onPasswordSaved: (String) -> Unit,
    onBack: () -> Unit,
    onLeave: () -> Unit,
    members: List<Member> = emptyList(),
    myId: String = "",
    onProfileChanged: () -> Unit = {},
    onMapStyleChange: (String) -> Unit = {},
    onDimMapChange: (Boolean) -> Unit = {},
    tripDays: Int? = null,
    onTripDaysChange: (Int) -> Unit = {},
    /** A child's own settings: only You, Look and Feel, and About. */
    limited: Boolean = false,
    /** Opens the home screen in arranging mode, with the drawer of tiles. */
    onOpenHomeEditor: () -> Unit = {},
) {
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { SettingsPage.valueOf(it) }
    fun supports(f: String) = features == null || f in features
    if (page != null) BackHandler { pageName = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = { if (page != null) pageName = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        FitScrollColumn(page, Modifier.padding(padding).fillMaxSize()) {
            when (page) {
                null -> {
                    val pages = SettingsPage.entries.filter {
                        when (it) {
                            SettingsPage.Places -> supports("circles") || supports("places")
                            else -> true
                        } && (!limited || it in CHILD_PAGES)
                    }
                    pages.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                        MenuRow(p.icon, p.title, menuSummary(p, prefs, sharing, circles, places, trailHours, username, hasPassword, perms, tripDays)) {
                            pageName = p.name
                        }
                    }
                }
                SettingsPage.Sharing -> {
                    SharingPage(prefs, sharing, onSharingChange)
                    if (supports("trails")) TrailPage(prefs, trailHours, onTrailHoursChange, tripDays, onTripDaysChange, supports("trips"))
                    MapStyleCards(prefs, onMapStyleChange, onDimMapChange)
                }
                SettingsPage.You -> {
                    IconCard(prefs, members.find { it.id == myId }, supports("emoji"), onProfileChanged)
                    if (supports("voice-tts")) ReadingVoiceCard(prefs, members.find { it.id == myId }?.voice.orEmpty(), onProfileChanged)
                    AccountPage(prefs, username, hasPassword, onPasswordSaved)
                }
                SettingsPage.Look -> {
                    LookCards(prefs)
                    if (!limited && !Access.isChild) ModulesCard(prefs, features, onOpenHomeEditor)
                    VoiceCard(prefs)
                }
                SettingsPage.Alerts -> AlertsPage(prefs, features, shareLowBattery, onShareLowBatteryChange, members.filter { it.id != myId })
                SettingsPage.Places -> {
                    if (supports("circles")) GroupsPage(prefs, sharing, circles, onCircleSharingChange, onCirclesChanged)
                    if (supports("places")) FencesPage(prefs, circles, places, onEditPlace, shareArrivals, onShareArrivalsChange)
                    if (supports("reminders") && features != null) RemindersCard(prefs, places)
                }
                SettingsPage.Pebble -> PebblePage(prefs, supports("index01"))
                SettingsPage.About -> {
                    AboutPage(prefs, perms, serverVersion, onLeave)
                    if (!limited) AdvancedCard()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun menuSummary(
    p: SettingsPage,
    prefs: Prefs,
    sharing: Boolean,
    circles: List<CircleInfo>,
    places: List<Place>,
    trailHours: Int?,
    username: String,
    hasPassword: Boolean?,
    perms: PermissionState,
    tripDays: Int?,
): String = when (p) {
    SettingsPage.Sharing -> listOfNotNull(
        if (sharing) "On, every ${plural(prefs.intervalMinutes, "minute")}" else "Paused",
        trailHours?.takeIf { it > 0 }?.let { "trail $it h" },
        tripDays?.takeIf { it > 0 }?.let { "trips ${if (it == 1) "1 day" else "$it days"}" },
    ).joinToString(", ")
    SettingsPage.Places -> (circles.map { it.name } + places.map { it.name }).joinToString(", ").ifEmpty { "Who sees whom, and alerts when people arrive or leave" }
    SettingsPage.Alerts -> prefs.mutedPeople.size.let { if (it == 0) "SOS, check-ins, driving, low battery" else "$it ${if (it == 1) "person" else "people"} muted" }
    SettingsPage.Look -> "Colours, names, home screen, voice"
    SettingsPage.Pebble -> "Pebble watch app, timeline, Index 01 ring"
    SettingsPage.You -> when (hasPassword) {
        false -> "Your icon · no password yet: set one"
        else -> if (username.isNotEmpty()) "Your name and icon · signed in as $username" else "Your name, icon and sign-in"
    }
    SettingsPage.About -> if (perms.background && perms.batteryUnrestricted && perms.notifications) {
        "Version ${BuildConfig.VERSION_NAME} · all set"
    } else {
        "Phone setup needs attention"
    }
}

/** Which speech engine this device uses for talking and reading aloud. */
@Composable
private fun VoiceCard(prefs: Prefs) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var choice by remember { mutableStateOf(prefs.voiceEngine) }
    LaunchedEffect(Unit) { Voice.warmUp(context) }
    val features = prefs.serverFeatures
    fun name(e: Voice.Engine?) = when (e) {
        Voice.Engine.Phone -> "this phone's own speech"
        Voice.Engine.Server -> "your family's server"
        null -> "nothing (no speech service here, and the server has no voice)"
    }
    SettingsCard("Voice") {
        Hint("Talking to send a message, and hearing messages read aloud. Only changes this device.")
        listOf(
            "auto" to ("Automatic" to if (BuildConfig.FLAVOR == "google") "The phone's own speech when it has it (usually quickest), otherwise your family's server." else "Your family's server: private, and the same on every device."),
            "phone" to ("This phone's speech" to "Android's speech recognition and text to speech (often Google's, which may send audio to Google)."),
            "server" to ("Your family's server" to "Everything stays on your server."),
        ).forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = choice == id) {
                    if (choice != id) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    choice = id
                    prefs.voiceEngine = id
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == id, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(label.first, style = MaterialTheme.typography.titleMedium)
                    Hint(label.second)
                }
            }
        }
        key(choice) {
            Hint("Listening with ${name(Voice.listener(context, features))}; reading aloud with ${name(Voice.speaker(context, features))}.")
        }
    }
}

/**
 * Look and Feel → Home Screen and Modules: arrange the home screen (with the
 * drawer of tiles), and choose modules. An admin's switches change the whole
 * family; everyone else's only hide a module on their own phone.
 */
@Composable
private fun ModulesCard(prefs: Prefs, features: Set<String>?, onOpenHomeEditor: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val admin = prefs.access == "admin"
    var mods by remember { mutableStateOf<List<ModuleInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        mods = runCatching { Api(prefs.serverUrl, prefs.token).modules() }.onFailure { error = it.message }.getOrNull()
    }
    SettingsCard("Home Screen and Modules") {
        OutlinedButton(onClick = onOpenHomeEditor, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("Arrange home screen") }
        // How many tiles across.
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tiles across", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            listOf(3 to "3", 2 to "2").forEach { (n, label) ->
                androidx.compose.material3.FilterChip(
                    selected = (if (HomeLayout.columns == 2) 2 else 3) == n,
                    onClick = { prefs.homeColumns = n; HomeLayout.columns = n },
                    label = { Text(label) },
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Hint(
            if (admin) "As an admin, switching a module off here switches it off for the whole family."
            else "Hide the modules you don't use on this phone. Your family's admin chooses which modules the family has.",
        )
        val list = mods
        when {
            list == null && error != null -> Hint("Couldn't load the modules: $error")
            list == null -> Hint("Loading…")
            else -> list.filter { admin || it.enabled }.forEach { m ->
                val name = if (Access.plainNames) m.plain.ifEmpty { m.name } else m.name
                SwitchRow(
                    title = name + if (!m.available) " (coming soon)" else "",
                    detail = m.description,
                    checked = if (admin) m.enabled && m.id !in Modules.hidden else m.id !in Modules.hidden,
                    onChange = { on ->
                        if (admin && (on || m.enabled)) {
                            // An admin switching on also un-hides it here; switching off is family-wide.
                            Modules.hidden = Modules.hidden - m.id
                            prefs.hiddenModules = Modules.hidden
                            scope.launch {
                                runCatching { Api(prefs.serverUrl, prefs.token).setModules(list.associate { it.id to (if (it.id == m.id) on else it.enabled) }) }
                                    .onSuccess { mods = it }
                                    .onFailure { android.widget.Toast.makeText(context, "Couldn't save: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        } else {
                            Modules.hidden = if (on) Modules.hidden - m.id else Modules.hidden + m.id
                            prefs.hiddenModules = Modules.hidden
                        }
                    },
                )
            }
        }
    }
}

/** You → Reading Voice: the voice your messages are read aloud in, for everyone. */
@Composable
private fun ReadingVoiceCard(prefs: Prefs, current: String, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var choice by remember(current) { mutableStateOf(current) }
    SettingsCard("Reading Voice") {
        Hint("When someone has your messages read aloud, they hear this voice.")
        listOf("female" to "Female", "male" to "Male", "" to "No preference").forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = choice == id) {
                    if (choice == id) return@selectable
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    val before = choice
                    choice = id
                    scope.launch {
                        runCatching { Api(prefs.serverUrl, prefs.token).setVoice(id) }
                            .onSuccess {
                                onChanged()
                                // A sample, in the new voice.
                                Voice.speak(context, prefs, "Hi! This is how your messages will sound.", id)
                            }
                            .onFailure { choice = before; android.widget.Toast.makeText(context, "Couldn't save: ${it.message}", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }.padding(vertical = squeezed(6.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == id, onClick = null)
                Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** A theme's look in a dot: its background, with its main and second colours as a split inside. */
@Composable
private fun ThemeSwatch(p: Palette, size: Int = 28) {
    val (bg, accent, second) = p.swatches
    androidx.compose.foundation.Canvas(Modifier.size(size.dp)) {
        val r = this.size.minDimension / 2
        drawCircle(androidx.compose.ui.graphics.Color(bg), r)
        drawArc(androidx.compose.ui.graphics.Color(accent), 90f, 180f, true, topLeft = androidx.compose.ui.geometry.Offset(r * .25f, r * .25f), size = androidx.compose.ui.geometry.Size(r * 1.5f, r * 1.5f))
        drawArc(androidx.compose.ui.graphics.Color(second), 270f, 180f, true, topLeft = androidx.compose.ui.geometry.Offset(r * .25f, r * .25f), size = androidx.compose.ui.geometry.Size(r * 1.5f, r * 1.5f))
        drawCircle(androidx.compose.ui.graphics.Color(0x33000000), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

/** About → Advanced: things hardly anyone changes, folded away. */
@Composable
private fun AdvancedCard() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var open by rememberSaveable { mutableStateOf(Access.isDisplay) }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Hide" else "Show")
    }
    if (!open) return
    SettingsCard("Device Mode") {
        SwitchRow(
            title = "Use as a Control Deck",
            detail = "A family screen for the wall or the kitchen counter: full-screen home tiles, the screen stays on while charging, " +
                "it doesn't share its own location, and settings open with the parent PIN. Tablets get the bigger layout either way.",
            checked = Access.isDisplay,
            onChange = { on ->
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                Access.setDeviceMode(context, if (on) "display" else "phone")
                LocationService.start(context) // a Control Deck stops sharing its location
            },
        )
        if (Access.isDisplay && !Access.pinSet) {
            Hint("No parent PIN is set yet, so anyone can open settings here. An admin sets one in the web console (Access & Control → Parent PIN).")
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"

/** Where FamLogic's source code is (the AGPL asks that users can get it). */
const val SOURCE_URL = "https://github.com/Pretendgineering/FamLogic"

// ---------------------------------------------------------------- pages

@Composable
private fun SharingPage(prefs: Prefs, sharing: Boolean, onSharingChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var interval by remember { mutableIntStateOf(prefs.intervalMinutes) }
    var wifi by remember { mutableStateOf(prefs.wifiPositioning) }
    var confirmWifi by remember { mutableStateOf(false) }

    SettingsCard {
        SwitchRow(
            title = "Share my location",
            detail = if (sharing) "Your groups can see where you are" else "Paused. Everyone sees \"sharing paused\"",
            checked = sharing,
            onChange = onSharingChange,
        )
    }
    SettingsCard("Update Every") {
        Hint("More often is more up to date but uses more battery.")
        Prefs.INTERVAL_CHOICES.forEach { minutes ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = interval == minutes) {
                    if (interval != minutes) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    interval = minutes
                    prefs.intervalMinutes = minutes
                    LocationService.start(context) // restarts with the new interval
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = interval == minutes, onClick = null)
                Text(plural(minutes, "minute"), Modifier.padding(start = 12.dp))
            }
        }
    }
    SettingsCard("Battery") {
        var saver by remember { mutableStateOf(prefs.batterySaver) }
        SwitchRow(
            title = "Update less often when my battery is low",
            detail = "Below ${LocationService.SAVER_LEVEL}% and not charging, updates slow to every ${LocationService.SAVER_INTERVAL_MIN} minutes so your phone lasts longer. Others see \"saving battery\". An SOS always updates every 15 seconds.",
            checked = saver,
            onChange = {
                saver = it
                prefs.batterySaver = it
                LocationService.start(context)
            },
        )
    }
    if (Flavor.GOOGLE) GoogleCard(prefs)
    SettingsCard("Wi-Fi Positioning") {
        SwitchRow(
            title = "Find my location from Wi-Fi",
            detail = "For tablets and phones without GPS or a SIM. When the phone can't find its own location, " +
                "the nearby Wi-Fi networks are looked up with beaconDB, a free community service.",
            checked = wifi,
            onChange = { on ->
                if (on) confirmWifi = true else {
                    wifi = false
                    prefs.wifiPositioning = false
                    LocationService.start(context)
                }
            },
        )
    }
    if (confirmWifi) {
        AlertDialog(
            onDismissRequest = { confirmWifi = false },
            title = { Text("Use beaconDB?") },
            text = {
                Text(
                    "When this phone can't find its location itself, FamLogic will send the hardware addresses " +
                        "(BSSIDs) and signal strengths of nearby Wi-Fi networks to api.beacondb.net, which answers with " +
                        "a rough location. Your name and account are never sent. Networks whose name ends in _nomap are skipped.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWifi = false
                    wifi = true
                    prefs.wifiPositioning = true
                    LocationService.start(context)
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmWifi = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupsPage(
    prefs: Prefs,
    sharing: Boolean,
    circles: List<CircleInfo>,
    onCircleSharingChange: (CircleInfo, Boolean) -> Unit,
    onCirclesChanged: () -> Unit,
) {
    var joining by remember { mutableStateOf(false) }
    SettingsCard("Your Groups") {
        Hint(
            if (circles.size > 1) {
                "You see people who share a group with you. Choose which groups see where you are."
            } else {
                "You see people who share a group with you. Ask whoever runs your server for an invite to join another group."
            },
        )
        circles.forEach { c ->
            SwitchRow(
                title = c.name,
                detail = if (c.sharing && sharing) "Sees where you are" else "Sees you as \"sharing paused\"",
                checked = c.sharing,
                enabled = sharing && circles.size > 1,
                onChange = { on -> onCircleSharingChange(c, on) },
            )
        }
        if (!sharing) Hint("Sharing is paused for everyone (Settings › Sharing).")
        FilledTonalButton(onClick = { joining = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Join another group…") }
    }
    if (joining) {
        JoinCircleDialog(prefs, initialCode = "", onDone = { joined ->
            joining = false
            if (joined) onCirclesChanged()
        })
    }
}

@Composable
private fun FencesPage(
    prefs: Prefs,
    circles: List<CircleInfo>,
    places: List<Place>,
    onEditPlace: (PlaceEdit) -> Unit,
    shareArrivals: Boolean?,
    onShareArrivalsChange: (Boolean) -> Unit,
) {
    var notifyArrivals by remember { mutableStateOf(prefs.notifyArrivals) }
    var notifyDepartures by remember { mutableStateOf(prefs.notifyDepartures) }
    var mutedPlaces by remember { mutableStateOf(prefs.mutedPlaces) }
    val groupName = circles.associate { it.id to it.name }

    SettingsCard("Your Fences") {
        Hint("A fence is an area like Home or School. Everyone in its group is told when someone arrives or leaves. Tap one to change it; switch one off to stop its alerts on this phone.")
        if (places.isEmpty()) {
            Text("No fences yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        places.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable { onEditPlace(PlaceEdit.Edit(p)) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val size = if (p.radius < 1000) "${p.radius.toInt()} m" else "${p.radius / 1000} km"
                    val group = if (circles.size > 1) groupName[p.circleId]?.let { " · $it" } ?: "" else ""
                    Hint(size + group + (p.schedule?.let { " · " + scheduleSummary(it) } ?: ""))
                }
                Switch(
                    checked = p.id !in mutedPlaces,
                    onCheckedChange = { on ->
                        mutedPlaces = if (on) mutedPlaces - p.id else mutedPlaces + p.id
                        prefs.mutedPlaces = mutedPlaces
                    },
                )
            }
        }
        FilledTonalButton(onClick = { onEditPlace(PlaceEdit.Add()) }, modifier = Modifier.padding(top = 8.dp)) { Text("Add a fence…") }
        Hint("You can also long-press the map to add one at that spot.")
    }
    if (circles.size > 1) {
        var mutedGroups by remember { mutableStateOf(prefs.mutedGroups) }
        SettingsCard("Fence Alerts by Group") {
            Hint("Switch a group off to stop alerts about all of its fences on this phone.")
            circles.forEach { c ->
                SwitchRow(
                    title = c.name,
                    detail = "",
                    checked = c.id !in mutedGroups,
                    onChange = { on ->
                        mutedGroups = if (on) mutedGroups - c.id else mutedGroups + c.id
                        prefs.mutedGroups = mutedGroups
                    },
                )
            }
        }
    }
    SettingsCard("Fence Alerts") {
        SwitchRow(
            title = "Tell others when I arrive or leave",
            detail = "Only for fences in groups you share your location with.",
            checked = shareArrivals ?: true,
            enabled = shareArrivals != null,
            onChange = onShareArrivalsChange,
        )
        SwitchRow(
            title = "Notify me when someone arrives",
            detail = "",
            checked = notifyArrivals,
            onChange = { notifyArrivals = it; prefs.notifyArrivals = it },
        )
        SwitchRow(
            title = "Notify me when someone leaves",
            detail = "",
            checked = notifyDepartures,
            onChange = { notifyDepartures = it; prefs.notifyDepartures = it },
        )
    }
}

@Composable
private fun AlertsPage(
    prefs: Prefs,
    features: Set<String>?,
    shareLowBattery: Boolean?,
    onShareLowBatteryChange: (Boolean) -> Unit,
    others: List<Member>,
) {
    val context = LocalContext.current
    var notifyLowBattery by remember { mutableStateOf(prefs.notifyLowBattery) }
    var lowBatteryThreshold by remember { mutableIntStateOf(prefs.lowBatteryThreshold) }
    var instantAlerts by remember { mutableStateOf(prefs.instantAlerts) }
    var showSos by remember { mutableStateOf(prefs.showSosButton) }
    var notifySos by remember { mutableStateOf(prefs.notifySos) }
    var confirmSosOff by remember { mutableStateOf(false) }
    var showCheckIn by remember { mutableStateOf(prefs.showCheckInButton) }
    var notifyCheckIn by remember { mutableStateOf(prefs.notifyCheckIn) }
    fun supports(f: String) = features == null || f in features
    var mutedPeople by remember { mutableStateOf(prefs.mutedPeople) }
    var notifySilent by remember { mutableStateOf(prefs.notifySilent) }

    if (others.isNotEmpty()) {
        SettingsCard("Who You Get Alerts About") {
            Hint("Switch someone off to stop their arrivals, departures, check-ins and battery alerts on this phone. Their SOS always gets through.")
            others.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MiniAvatar(m)
                    Box(Modifier.weight(1f).padding(start = 12.dp)) {
                        SwitchRow(
                            title = m.name,
                            detail = if (m.id in mutedPeople) "Muted (except SOS)" else "",
                            checked = m.id !in mutedPeople,
                            onChange = { on ->
                                mutedPeople = if (on) mutedPeople - m.id else mutedPeople + m.id
                                prefs.mutedPeople = mutedPeople
                            },
                        )
                    }
                }
            }
        }
    }
    if (supports("driving")) {
        var notifyDriving by remember { mutableStateOf(prefs.notifyDriving) }
        SettingsCard("Driving") {
            SwitchRow(
                title = "Tell me when someone starts driving",
                detail = "Driving is worked out from speed (over about 25 km/h / 15 mph). A short stop doesn't count as a new drive.",
                checked = notifyDriving,
                onChange = { notifyDriving = it; prefs.notifyDriving = it },
            )
        }
    }
    if (supports("silence")) {
        SettingsCard("Quiet Phones") {
            SwitchRow(
                title = "Tell me when someone's phone goes quiet",
                detail = "If a phone that shares its location hasn't been in touch for several hours (switched off, flat battery, or the app was stopped).",
                checked = notifySilent,
                onChange = { notifySilent = it; prefs.notifySilent = it },
            )
        }
    }

    if (supports("sos")) {
        SettingsCard("SOS") {
            SwitchRow(
                title = "Show the SOS bar",
                detail = "Hold it for 3 seconds to alert everyone. Your location is then shared every 15 seconds until you say you're OK.",
                checked = showSos,
                onChange = { showSos = it; prefs.showSosButton = it },
            )
            SwitchRow(
                title = "Notify me when someone sends an SOS",
                detail = "Rings like an alarm until you look.",
                checked = notifySos,
                onChange = { on -> if (on) { notifySos = true; prefs.notifySos = true } else confirmSosOff = true },
            )
            if (notifySos) {
                OutlinedButton(onClick = {
                    Alerts.ensureChannels(context)
                    context.startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Alerts.CHANNEL_SOS),
                    )
                }) { Text("Let SOS ring in Do Not Disturb…") }
                Hint("Opens Android's settings for SOS alerts. Turn on \"Override Do Not Disturb\" so an SOS always gets through.")
            }
        }
    }
    if (supports("checkin")) {
        SettingsCard("Check-Ins") {
            SwitchRow(
                title = "Show the Check in button",
                detail = "Send \"I'm here\" with your location and an optional note.",
                checked = showCheckIn,
                onChange = { showCheckIn = it; prefs.showCheckInButton = it },
            )
            SwitchRow(
                title = "Notify me when someone checks in",
                detail = "",
                checked = notifyCheckIn,
                onChange = { notifyCheckIn = it; prefs.notifyCheckIn = it },
            )
        }
    }
    SettingsCard("Low Battery") {
        SwitchRow(
            title = "Tell others when my battery is low",
            detail = "They get a notification when your battery drops to 15% or 10%, unless you're charging.",
            checked = shareLowBattery ?: true,
            enabled = shareLowBattery != null, // wait until we know the server's setting
            onChange = onShareLowBatteryChange,
        )
        SwitchRow(
            title = "Notify me when someone's battery is low",
            detail = "",
            checked = notifyLowBattery,
            onChange = { notifyLowBattery = it; prefs.notifyLowBattery = it },
        )
        if (notifyLowBattery) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notify me at", style = MaterialTheme.typography.bodyMedium)
                Prefs.LOW_BATTERY_CHOICES.forEach { level ->
                    Row(
                        Modifier.selectable(selected = lowBatteryThreshold == level) {
                            lowBatteryThreshold = level
                            prefs.lowBatteryThreshold = level
                        }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = lowBatteryThreshold == level, onClick = null)
                        Text("$level%", Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
    if (supports("instant-alerts")) {
        SettingsCard("Speed") {
            SwitchRow(
                title = "Get alerts within seconds",
                detail = "Keeps a connection open to your server so SOS and other alerts arrive right away, even while your own " +
                    "sharing is paused. Uses a little more battery. When off, alerts can take up to one update interval.",
                checked = instantAlerts,
                onChange = {
                    instantAlerts = it
                    prefs.instantAlerts = it
                    LocationService.start(context)
                },
            )
        }
    }

    if (confirmSosOff) {
        AlertDialog(
            onDismissRequest = { confirmSosOff = false },
            title = { Text("Turn off SOS alerts?") },
            text = { Text("If someone sends an SOS, this phone won't notify you. You'll only see it when you open the app.") },
            confirmButton = {
                TextButton(onClick = { confirmSosOff = false; notifySos = false; prefs.notifySos = false }) { Text("Turn off") }
            },
            dismissButton = { TextButton(onClick = { confirmSosOff = false }) { Text("Keep them on") } },
        )
    }
}

@Composable
private fun TrailPage(
    prefs: Prefs,
    trailHours: Int?,
    onTrailHoursChange: (Int) -> Unit,
    tripDays: Int?,
    onTripDaysChange: (Int) -> Unit,
    canTrips: Boolean,
) {
    val haptics = LocalHapticFeedback.current
    var showTrails by remember { mutableStateOf(prefs.showTrails) }
    SettingsCard("Keep My Trail") {
        Hint(
            "Where you've been recently, drawn as a line when someone taps you. Only people who can see your " +
                "location see it. Turning it off deletes it.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
            listOf(0 to "Off", 6 to "6 hours", 12 to "12 hours", 24 to "24 hours").forEach { (h, label) ->
                FilterChip(
                    selected = trailHours == h,
                    enabled = trailHours != null,
                    onClick = {
                        if (trailHours != h) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onTrailHoursChange(h)
                    },
                    label = { Text(label) },
                )
            }
        }
    }
    SettingsCard {
        SwitchRow(
            title = "Show a person's trail when I tap them",
            detail = "",
            checked = showTrails,
            onChange = { showTrails = it; prefs.showTrails = it },
        )
    }
    if (canTrips) {
        var notifyTrips by remember { mutableStateOf(prefs.notifyTrips) }
        SettingsCard("Keep My Trips") {
            Hint(
                "Your journeys between stops, like \"Home → School, 12 min, 4.2 km\", each with its route. " +
                    "The same people who can see your location can look through them. Turning it off deletes them.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                listOf(0 to "Off", 1 to "1 day", 3 to "3 days", 7 to "7 days").forEach { (d, label) ->
                    FilterChip(
                        selected = tripDays == d,
                        enabled = tripDays != null,
                        onClick = {
                            if (tripDays != d) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onTripDaysChange(d)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        SettingsCard {
            SwitchRow(
                title = "Notify me when someone finishes a trip",
                detail = "E.g. \"Sam finished Home → School\". Off by default; fence alerts often say enough.",
                checked = notifyTrips,
                onChange = { notifyTrips = it; prefs.notifyTrips = it },
            )
        }
    }
}

@Composable
private fun LookCards(prefs: Prefs) {
    val haptics = LocalHapticFeedback.current
    val namesContext = LocalContext.current
    SettingsCard("Names") {
        SwitchRow(
            title = "Plain names",
            detail = "Call things Map, Chat, Calendar and Lists instead of Thereabouts, Chitchat, Datebook and Odds & Ends. Only changes this device.",
            checked = Access.plainNames,
            onChange = { Access.setPlainNames(namesContext, it) },
        )
    }
    if (Access.role == "grandparent") {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        SettingsCard("Easier to Use") {
            SwitchRow(
                title = "Bigger buttons and text",
                detail = "Larger targets and words across the app.",
                checked = Access.big,
                onChange = { on -> Access.big = on; scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).setAssist(big = on) } } },
            )
            SwitchRow(
                title = "Picture icons",
                detail = "A tiny map, a talking face and other pictures instead of the usual symbols.",
                checked = Access.pictures,
                onChange = { on -> Access.pictures = on; scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).setAssist(pictures = on) } } },
            )
        }
    }
    SettingsCard("Colours") {
        // The current theme; tap to pull open the whole collection (each with its little story),
        // like a shade, and pick one to watch the app recolour.
        var open by remember { mutableStateOf(false) }
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "caret")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (open) .55f else .3f))
                .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeSwatch(ThemeState.palette, 36)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(ThemeState.palette.title, style = MaterialTheme.typography.titleMedium)
                Hint(if (open) "Pick one: the whole app changes at once." else ThemeState.palette.description)
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Close" else "See all the colours", modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = .85f, stiffness = 320f)) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.fadeOut(),
        ) {
            Column(Modifier.padding(top = 6.dp)) {
                Palette.entries.forEach { p ->
                    val on = ThemeState.palette == p
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {
                                if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                prefs.appTheme = p.id
                                ThemeState.palette = p // the whole app recolours straight away
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemeSwatch(p, 30)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                            Hint(p.description)
                        }
                        if (on) Icon(Icons.Filled.Check, contentDescription = "In use", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Hint("Only changes this device.")
    }
    SettingsCard("Light or Dark") {
        listOf("system" to "Same as this phone", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = ThemeState.mode == id) {
                    if (ThemeState.mode != id) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    prefs.darkMode = id
                    ThemeState.mode = id
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ThemeState.mode == id, onClick = null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Location Sharing → the map's look (this phone only). */
@Composable
private fun MapStyleCards(prefs: Prefs, onChange: (String) -> Unit, onDimChange: (Boolean) -> Unit) {
    var dim by remember { mutableStateOf(prefs.dimMap) }
    val haptics = LocalHapticFeedback.current
    var style by remember { mutableStateOf(prefs.mapStyle) }
    SettingsCard("Map Style") {
        Hint("Only changes this phone. All are free maps from OpenStreetMap data.")
        MapStyles.all.forEach { s ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = style == s.id) {
                    if (style != s.id) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    style = s.id
                    prefs.mapStyle = s.id
                    onChange(s.id) // the map switches right away, not when Settings closes
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = style == s.id, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge)
                    Hint(s.description)
                }
            }
        }
    }
    SettingsCard {
        SwitchRow(
            title = "Soften the map in dark mode",
            detail = "Takes the glare off the bright map at night.",
            checked = dim,
            onChange = { dim = it; prefs.dimMap = it; onDimChange(it) },
        )
    }
}

@Composable
private fun AccountPage(
    prefs: Prefs,
    username: String,
    hasPassword: Boolean?,
    onPasswordSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var nameMsg by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    if (Access.canSignIn) SettingsCard("Sign-In") { AccountSection(prefs, username, hasPassword, onPasswordSaved) }
    SettingsCard("Your Name") {
        Hint("What others see on the map.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40); nameMsg = null },
                placeholder = { Text("New name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    nameMsg = try {
                        Api(prefs.serverUrl, prefs.token).setName(name.trim())
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        name = ""
                        "Saved"
                    } catch (e: IOException) {
                        e.message ?: "Couldn't save"
                    }
                }
            }) { Text("Save") }
        }
        nameMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Your icon (an emoji and a colour), at the top of Customize. */
@Composable
private fun IconCard(prefs: Prefs, me: Member?, canEmoji: Boolean, onProfileChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var emojiMsg by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    var picking by remember { mutableStateOf(false) }
    fun saveIcon(emoji: String, color: String) {
        scope.launch {
            emojiMsg = try {
                Api(prefs.serverUrl, prefs.token).setIcon(emoji, color)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onProfileChanged()
                "Saved"
            } catch (e: IOException) {
                e.message ?: "Couldn't save"
            }
        }
    }

    if (canEmoji && me != null) {
        SettingsCard("Your Icon") {
            Row(
                Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(me.name, me.emoji, me.color, 56)
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text("Change your icon", style = MaterialTheme.typography.bodyLarge)
                    Hint("Pick a colour and an emoji for your map pin.")
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            emojiMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (picking) {
            IconPickerDialog(me.name, me.emoji, me.color, onSave = { e, c -> picking = false; saveIcon(e, c) }, onDismiss = { picking = false })
        }
    }
}

/** A small coloured circle with someone's emoji or initial. */
@Composable
internal fun MiniAvatar(m: Member, size: Int = 36) = Avatar(m.name, m.emoji, m.color, size)

/** "Mon–Fri 07:00–17:00" */
fun scheduleSummary(sc: FenceSchedule): String {
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val days = when (sc.days) {
        setOf(1, 2, 3, 4, 5) -> "Mon–Fri"
        setOf(0, 6) -> "Weekends"
        (0..6).toSet() -> "Every day"
        else -> listOf(1, 2, 3, 4, 5, 6, 0).filter { it in sc.days }.joinToString(" ") { names[it] }
    }
    return if (sc.from == sc.to) "$days, all day" else "$days ${sc.from}–${sc.to}"
}

@Composable
private fun PhoneChecks(perms: PermissionState) {
    val context = LocalContext.current
    if (perms.background && perms.batteryUnrestricted && perms.notifications) {
        Text("✓  All set: FamLogic can keep sharing in the background.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Hint("Android can stop apps in the background. Fix anything marked ✗.")
    run {
        CheckRow("Location access \"Allow all the time\"", perms.background) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        CheckRow("Not restricted by battery saver", perms.batteryUnrestricted) { requestBatteryExemption(context) }
        CheckRow("Notifications allowed (for alerts)", perms.notifications) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
}

@Composable
private fun AboutPage(prefs: Prefs, perms: PermissionState, serverVersion: String, onLeave: () -> Unit) {
    var confirmLeave by remember { mutableStateOf(false) }
    SettingsCard("Phone Setup") { PhoneChecks(perms) }
    SettingsCard("Server") {
        Text(prefs.serverUrl, style = MaterialTheme.typography.bodyMedium)
        if (serverVersion.isNotEmpty()) Hint("Server version $serverVersion")
        OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Leave this server", color = MaterialTheme.colorScheme.error)
        }
    }
    SettingsCard("FamLogic") {
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Hint("Free software under the GNU AGPLv3. Map data © OpenStreetMap contributors.")
        val context = LocalContext.current
        TextButton(onClick = {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))) }
        }) { Text("Source code") }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this server?") },
            text = { Text("Your location will be removed from the server and you'll need a new invite to come back.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- building blocks

/**
 * One group of settings: a heading and its rows, edge to edge, with a thin line
 * under it. (No cards: settings read as one continuous page.)
 */
@Composable
internal fun SettingsCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = squeezed(14.dp)),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = squeezed(6.dp))) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = squeezed(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Username and password, used to sign back in on a new phone or after reinstalling.
 * People who joined before accounts existed set them up here.
 */
@Composable
private fun AccountSection(prefs: Prefs, username: String, hasPassword: Boolean?, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autofill = LocalAutofillManager.current
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    var newUsername by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (!prefs.canUsePassword) {
        Text("You're signed in with a code from your parent, so there's no password. If you get a new phone, ask them for a new code.")
        return
    }
    when (hasPassword) {
        null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
        true -> Text("Signed in as $username. Use this username and your password to sign in on a new phone.")
        false -> Text(
            "You don't have a password yet. Set one so you can sign back in on a new phone or after reinstalling.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (hasPassword == null) return
    if (!open) {
        FilledTonalButton(onClick = { open = true; error = null }, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (hasPassword) "Change password" else "Set username and password")
        }
        return
    }
    if (!hasPassword) {
        OutlinedTextField(
            value = newUsername,
            onValueChange = { v -> newUsername = v.lowercase().filter { it.isLetterOrDigit() || it in "._-" }.take(32); error = null },
            label = { Text("Username") },
            supportingText = { Text("Letters, numbers, dots, dashes or underscores") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewUsername },
        )
    } else {
        PasswordField(current, { current = it; error = null }, "Current password", ContentType.Password)
    }
    // "Don't match" only after tapping Save, not while typing.
    var mismatch by remember { mutableStateOf(false) }
    val problem = if (mismatch) "The passwords don't match" else null
    NewPasswordFields(password, { password = it; error = null; mismatch = false }, confirm, { confirm = it; error = null; mismatch = false }, problem, label = "New password")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !busy && password.length >= MIN_PASSWORD_LENGTH && confirm.isNotEmpty() &&
                (hasPassword && current.isNotEmpty() || !hasPassword && newUsername.length >= 3),
            onClick = {
                if (confirm != password) {
                    mismatch = true
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        val u = Api(prefs.serverUrl, prefs.token).setPassword(current, password, newUsername)
                        autofill?.commit() // lets a password manager offer to save it
                        onSaved(u)
                        open = false
                        current = ""; password = ""; confirm = ""
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        Toast.makeText(context, "Password saved", Toast.LENGTH_SHORT).show()
                    } catch (e: IOException) {
                        error = e.message ?: "Couldn't save"
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Save") }
        TextButton(onClick = { open = false; error = null }) { Text("Cancel") }
    }
}

@Composable
internal fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = squeezed(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = { on ->
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onChange(on)
        })
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (ok) "✓  " else "✗  ") + label,
            Modifier.weight(1f),
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        if (!ok) TextButton(onClick = onFix) { Text("Fix") }
    }
}
