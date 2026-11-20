package io.github.spasm0dic.thereabouts

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

private const val REFRESH_MS = 15_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(prefs: Prefs, onLeft: () -> Unit, focusMember: String? = null, onFocusHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var familyName by remember { mutableStateOf(prefs.familyName) }
    var tileUrl by remember { mutableStateOf(prefs.tileUrl) }
    var attribution by remember { mutableStateOf(prefs.tileAttribution) }
    var error by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(prefs.sharing) }
    var showSettings by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<CameraCommand?>(null) }
    var cameraSeq by remember { mutableIntStateOf(0) }
    var fittedOnce by remember { mutableStateOf(false) }
    var perms by remember { mutableStateOf(PermissionState.read(context)) }
    var shareLowBattery by remember { mutableStateOf<Boolean?>(null) } // null until the server tells us
    var hasPassword by remember { mutableStateOf<Boolean?>(null) } // null until the server tells us
    var username by remember { mutableStateOf(prefs.username) }
    var sosActive by remember { mutableStateOf(prefs.sosActive) }
    var sosPending by remember { mutableStateOf(prefs.sosPending) }
    var confirmOk by remember { mutableStateOf(false) }
    var showCheckIn by remember { mutableStateOf(false) }
    // Bumped every minute so "5 min ago" labels stay current.
    var tick by remember { mutableIntStateOf(0) }

    fun leave() {
        LocationService.stop(context)
        prefs.clearMembership()
        onLeft()
    }

    suspend fun refresh() {
        try {
            val res = Api(prefs.serverUrl, prefs.token).members(since = prefs.eventCursor)
            // Alerts also come in here, so they show while the app is open even if sharing is paused.
            Alerts.handle(context, res.events)
            shareLowBattery = res.shareLowBattery
            hasPassword = res.hasPassword
            username = res.username
            prefs.username = res.username
            members = res.members
            familyName = res.server.name
            tileUrl = res.server.tileUrl
            attribution = res.server.tileAttribution
            prefs.familyName = res.server.name
            prefs.tileUrl = res.server.tileUrl
            prefs.tileAttribution = res.server.tileAttribution
            error = null
            // Keep the server in step with the pause switch (e.g. if pausing happened offline).
            res.members.find { it.id == res.me }?.let { me ->
                // SOS un-pauses sharing on the server; follow it here.
                if (me.sosSince > 0 && !prefs.sharing) {
                    prefs.sharing = true
                    sharing = true
                }
                if (me.paused == prefs.sharing) Api(prefs.serverUrl, prefs.token).setPaused(!prefs.sharing)
                // Once nothing is waiting to be sent, the server knows best whether our SOS is active.
                if (!prefs.sosPending && !prefs.sosEndPending && prefs.sosActive != (me.sosSince > 0)) {
                    prefs.sosActive = me.sosSince > 0
                    LocationService.start(context) // switch between 15-second and normal updates
                }
            }
            sosActive = prefs.sosActive
            sosPending = prefs.sosPending
            if (!fittedOnce && members.any { it.loc != null }) {
                fittedOnce = true
                camera = CameraCommand.FitAll(++cameraSeq)
            }
        } catch (e: UnauthorizedException) {
            LocationService.stop(context)
            prefs.signedOut(e)
            onLeft()
        } catch (e: IOException) {
            error = "Can't reach the server right now"
        }
    }

    // Poll while the screen is visible; recheck permissions when coming back from Settings.
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(REFRESH_MS)
                tick++
            }
        }
    }
    // Refresh straight away when an alert arrives or an SOS gets through, instead of
    // waiting for the next poll.
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var seenAlerts = Alerts.lastArrival
            var seenSos = LocationService.sosStateChanged
            while (true) {
                delay(1_000)
                sosPending = prefs.sosPending
                if (Alerts.lastArrival != seenAlerts || LocationService.sosStateChanged != seenSos) {
                    seenAlerts = Alerts.lastArrival
                    seenSos = LocationService.sosStateChanged
                    refresh()
                }
            }
        }
    }
    // Opened from a notification about someone: show them.
    LaunchedEffect(focusMember, members) {
        val target = members.find { it.id == focusMember }?.loc ?: return@LaunchedEffect
        fittedOnce = true
        camera = CameraCommand.Center(target.lat, target.lon, ++cameraSeq)
        onFocusHandled()
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                perms = PermissionState.read(context)
                LocationService.start(context) // it decides what's needed (sharing, alerts, SOS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = PermissionState.read(context)
        LocationService.start(context)
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        perms = PermissionState.read(context)
    }
    // First time on this screen: ask for location straight away.
    LaunchedEffect(Unit) {
        if (!perms.location) askLocation.launch(PermissionState.foregroundPermissions())
    }

    fun setSharing(on: Boolean) {
        sharing = on
        prefs.sharing = on
        scope.launch {
            if (on) {
                runCatching { Api(prefs.serverUrl, prefs.token).setPaused(false) }
                LocationService.start(context)
            } else {
                LocationService.start(context) // keeps running for alerts, without location
                runCatching { Api(prefs.serverUrl, prefs.token).setPaused(true) }
            }
            refresh()
        }
    }

    /**
     * SOS: the background service sends it and keeps retrying until the server has it.
     * If the service can't run (e.g. no permissions), send it from here instead.
     */
    fun sendSos(end: Boolean) {
        if (end) {
            prefs.sosActive = false
            prefs.sosPending = false
            prefs.sosEndPending = true
        } else {
            prefs.sosActive = true
            prefs.sosPending = true
            prefs.sosEndPending = false
            if (!prefs.sharing) {
                prefs.sharing = true // the server un-pauses too: someone asking for help wants to be found
                sharing = true
            }
        }
        sosActive = prefs.sosActive
        sosPending = prefs.sosPending
        LocationService.start(context)
        scope.launch {
            delay(4_000)
            if (LocationService.isRunning || !(prefs.sosPending || prefs.sosEndPending)) return@launch
            try {
                val api = Api(prefs.serverUrl, prefs.token)
                if (prefs.sosPending) api.sos(null).also { prefs.sosPending = false }
                if (prefs.sosEndPending) api.sosEnd().also { prefs.sosEndPending = false }
                sosPending = prefs.sosPending
                refresh()
            } catch (e: IOException) {
                Toast.makeText(context, "Couldn't reach the server. Thereabouts will keep trying.", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (confirmOk) {
        AlertDialog(
            onDismissRequest = { confirmOk = false },
            title = { Text("End your SOS?") },
            text = { Text("Your family will be told you're OK, and your location goes back to normal updates.") },
            confirmButton = { TextButton(onClick = { confirmOk = false; sendSos(end = true) }) { Text("I'm OK") } },
            dismissButton = { TextButton(onClick = { confirmOk = false }) { Text("Keep SOS on") } },
        )
    }
    if (showCheckIn) {
        CheckInDialog(prefs, onDone = { showCheckIn = false })
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            prefs = prefs,
            perms = perms,
            sharing = sharing,
            onSharingChange = ::setSharing,
            shareLowBattery = shareLowBattery,
            username = username,
            hasPassword = hasPassword,
            onPasswordSaved = { u ->
                username = u
                prefs.username = u
                hasPassword = true
            },
            onShareLowBatteryChange = { on ->
                val before = shareLowBattery
                shareLowBattery = on
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).setShareLowBattery(on)
                    } catch (e: IOException) {
                        shareLowBattery = before
                        Toast.makeText(context, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onBack = { showSettings = false; scope.launch { refresh() } },
            onLeave = {
                scope.launch {
                    runCatching { Api(prefs.serverUrl, prefs.token).leave() }
                    leave()
                }
            },
        )
        return
    }

    val me = members.find { it.id == prefs.memberId }
    val statusOf: (Member) -> String = { m -> statusLine(m, me, tick) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(familyName.ifEmpty { "Thereabouts" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // SOS banners come first: yours, then anyone else's.
            if (sosActive) {
                SosBanner(
                    if (sosPending) "Sending SOS… Thereabouts keeps trying until it gets through."
                    else "SOS sent. Your family has been alerted and sees your location every 15 seconds.",
                    "I'm OK",
                ) { confirmOk = true }
            }
            members.filter { it.sosSince > 0 && it.id != prefs.memberId }.forEach { m ->
                SosBanner("🆘 SOS from ${m.name}" + (m.loc?.let { " · ${ago(it.time)}" } ?: ""), if (m.loc != null) "Show" else null) {
                    m.loc?.let { camera = CameraCommand.Center(it.lat, it.lon, ++cameraSeq) }
                }
            }
            when {
                !perms.location -> Banner(
                    "Thereabouts needs your location to share it with your family.",
                    "Allow",
                ) { askLocation.launch(PermissionState.foregroundPermissions()) }
                !sharing -> Banner("You're not sharing your location right now.", "Resume") { setSharing(true) }
                hasPassword == false -> Banner(
                    "Set a password so you can sign back in on a new phone.",
                    "Set up",
                ) { showSettings = true }
                !perms.background -> Banner(
                    "Choose \"Allow all the time\" so sharing keeps working after a restart.",
                    "Fix",
                ) {
                    if (Build.VERSION.SDK_INT >= 29) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                !perms.batteryUnrestricted -> Banner(
                    "Let Thereabouts run in the background so your phone doesn't stop it.",
                    "Fix",
                ) { requestBatteryExemption(context) }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                OsmMap(
                    members = members,
                    tileUrl = tileUrl,
                    attribution = attribution,
                    camera = camera,
                    statusOf = statusOf,
                    modifier = Modifier.fillMaxSize(),
                )
                FilledTonalButton(
                    onClick = { camera = CameraCommand.FitAll(++cameraSeq) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Text("Show everyone") }
                Column(
                    Modifier.align(Alignment.BottomStart).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (prefs.showCheckInButton) {
                        FilledTonalButton(onClick = { showCheckIn = true }) { Text("Check in") }
                    }
                    if (prefs.showSosButton && !sosActive) {
                        HoldForSosButton(onTriggered = { sendSos(end = false) })
                    }
                }
            }

            Surface(tonalElevation = 2.dp) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    if (members.isEmpty()) {
                        item { Text("Loading…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(members, key = { it.id }) { m ->
                        MemberRow(
                            m = m,
                            isMe = m.id == prefs.memberId,
                            status = statusOf(m),
                            onClick = { m.loc?.let { camera = CameraCommand.Center(it.lat, it.lon, ++cameraSeq) } },
                            onDirections = { m.loc?.let { openDirections(context, m.name, it) } },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun MemberRow(m: Member, isMe: Boolean, status: String, onClick: () -> Unit, onDirections: () -> Unit) {
    val sos = m.sosSince > 0
    val stale = m.loc == null || m.paused || System.currentTimeMillis() - m.loc.time > STALE_MS
    Row(
        Modifier.fillMaxWidth().clickable(enabled = m.loc != null, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color(parseColor(m.color)).copy(alpha = if (stale) 0.5f else 1f)),
            contentAlignment = Alignment.Center,
        ) { Text(initialOf(m.name), color = Color.White, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) {
            Text(if (isMe) "${m.name} (you)" else m.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (sos) SOS_RED else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (sos) FontWeight.Bold else null,
            )
        }
        if (!isMe && m.loc != null && !m.paused) {
            TextButton(onClick = onDirections) { Text("Directions") }
        }
    }
}

/** "4 min ago · 82% · 1.3 km away" */
@Suppress("UNUSED_PARAMETER") // tick only forces recomposition
private fun statusLine(m: Member, me: Member?, tick: Int): String {
    if (m.sosSince > 0) {
        val since = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(m.sosSince))
        return "🆘 SOS since $since" + (m.loc?.let { " · ${ago(it.time)}" } ?: "")
    }
    if (m.paused) return "Location sharing paused"
    val loc = m.loc ?: return "No location yet"
    val parts = mutableListOf<String>()
    val age = System.currentTimeMillis() - loc.time
    parts += if (age < 60_000) "Just now" else DateUtils.getRelativeTimeSpanString(loc.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    if (loc.battery >= 0) parts += "${loc.battery}%" + if (loc.charging) " charging" else ""
    val myLoc = me?.loc
    if (me != null && me.id != m.id && myLoc != null && !me.paused) {
        val out = FloatArray(1)
        Location.distanceBetween(myLoc.lat, myLoc.lon, loc.lat, loc.lon, out)
        parts += formatDistance(out[0]) + " away"
    }
    return parts.joinToString(" · ")
}

private fun formatDistance(m: Float) = when {
    m < 100 -> "nearby"
    m < 1000 -> "${(m / 10).toInt() * 10} m"
    m < 10_000 -> "%.1f km".format(m / 1000)
    else -> "${(m / 1000).toInt()} km"
}

/** Opens whatever maps app the person has (OsmAnd, Organic Maps, Google Maps, ...). */
private fun openDirections(context: Context, name: String, loc: MemberLocation) {
    val uri = Uri.parse("geo:${loc.lat},${loc.lon}?q=${loc.lat},${loc.lon}(${Uri.encode(name)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
    }
}

@SuppressLint("BatteryLife") // Exactly what a family locator needs; allowed outside Google Play.
private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

data class PermissionState(
    val location: Boolean,
    val background: Boolean,
    val batteryUnrestricted: Boolean,
    val notifications: Boolean,
) {
    companion object {
        fun read(ctx: Context): PermissionState {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            return PermissionState(
                location = LocationService.hasLocationPermission(ctx),
                background = LocationService.hasBackgroundPermission(ctx),
                batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
                notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            )
        }

        fun foregroundPermissions(): Array<String> {
            val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
            return p.toTypedArray()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: Prefs,
    perms: PermissionState,
    sharing: Boolean,
    onSharingChange: (Boolean) -> Unit,
    shareLowBattery: Boolean?,
    onShareLowBatteryChange: (Boolean) -> Unit,
    username: String,
    hasPassword: Boolean?,
    onPasswordSaved: (String) -> Unit,
    onBack: () -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var interval by remember { mutableIntStateOf(prefs.intervalMinutes) }
    var name by remember { mutableStateOf("") }
    var nameMsg by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var notifyLowBattery by remember { mutableStateOf(prefs.notifyLowBattery) }
    var lowBatteryThreshold by remember { mutableIntStateOf(prefs.lowBatteryThreshold) }
    var instantAlerts by remember { mutableStateOf(prefs.instantAlerts) }
    var showSos by remember { mutableStateOf(prefs.showSosButton) }
    var notifySos by remember { mutableStateOf(prefs.notifySos) }
    var confirmSosOff by remember { mutableStateOf(false) }
    var showCheckIn by remember { mutableStateOf(prefs.showCheckInButton) }
    var notifyCheckIn by remember { mutableStateOf(prefs.notifyCheckIn) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share my location", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (sharing) "Your family can see where you are" else "Paused. Your family sees \"sharing paused\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = sharing, onCheckedChange = onSharingChange)
            }
            HorizontalDivider()

            Text("Update every", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "More often is more up to date but uses more battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Prefs.INTERVAL_CHOICES.forEach { minutes ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = interval == minutes) {
                        interval = minutes
                        prefs.intervalMinutes = minutes
                        LocationService.start(context) // restarts with the new interval
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = interval == minutes, onClick = null)
                    Text(if (minutes == 1) "1 minute" else "$minutes minutes", Modifier.padding(start = 8.dp))
                }
            }
            HorizontalDivider()

            AccountSection(prefs, username, hasPassword, onPasswordSaved)
            HorizontalDivider()

            Text("Your name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
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
                            name = ""
                            "Saved"
                        } catch (e: IOException) {
                            e.message ?: "Couldn't save"
                        }
                    }
                }) { Text("Save") }
            }
            nameMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()

            // ---- Features: each one can be switched on or off here. ----
            Text("Features", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("Low battery alerts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            SwitchRow(
                title = "Tell my family when my battery is low",
                detail = "They get a notification when your battery drops to 15% or 10%, unless you're charging.",
                checked = shareLowBattery ?: true,
                enabled = shareLowBattery != null, // wait until we know the server's setting
                onChange = onShareLowBatteryChange,
            )
            SwitchRow(
                title = "Notify me when someone's battery is low",
                detail = "Alerts can take up to one update interval to arrive.",
                checked = notifyLowBattery,
                onChange = { notifyLowBattery = it; prefs.notifyLowBattery = it },
            )
            if (notifyLowBattery) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Notify me at", style = MaterialTheme.typography.bodyMedium)
                    Prefs.LOW_BATTERY_CHOICES.forEach { level ->
                        Row(
                            Modifier.selectable(selected = lowBatteryThreshold == level) {
                                lowBatteryThreshold = level
                                prefs.lowBatteryThreshold = level
                            }.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lowBatteryThreshold == level, onClick = null)
                            Text("$level%", Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            Text("SOS", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SwitchRow(
                title = "Show the SOS button",
                detail = "Hold it for 3 seconds to alert everyone. Your location is then shared every 15 seconds until you say you're OK.",
                checked = showSos,
                onChange = { showSos = it; prefs.showSosButton = it },
            )
            SwitchRow(
                title = "Notify me when someone presses SOS",
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
                Text(
                    "Opens Android's settings for SOS alerts. Turn on \"Override Do Not Disturb\" so an SOS always gets through.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Check-ins", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
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

            Text("Instant alerts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SwitchRow(
                title = "Get alerts within seconds",
                detail = "Keeps a connection open to your family's server so SOS and other alerts arrive right away, even while your own sharing is paused. Uses a little more battery. When off, alerts can take up to one update interval.",
                checked = instantAlerts,
                onChange = {
                    instantAlerts = it
                    prefs.instantAlerts = it
                    LocationService.start(context)
                },
            )
            HorizontalDivider()

            Text("Reliability", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            CheckRow("Location access \"Allow all the time\"", perms.background) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            CheckRow("Not restricted by battery saver", perms.batteryUnrestricted) { requestBatteryExemption(context) }
            CheckRow("Notifications allowed (for alerts)", perms.notifications) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
            HorizontalDivider()

            Text("Server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(prefs.serverUrl, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Leave family", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Thereabouts ${BuildConfig.VERSION_NAME} · Free software (GPLv3) · Map data © OpenStreetMap contributors",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }

    if (confirmSosOff) {
        AlertDialog(
            onDismissRequest = { confirmSosOff = false },
            title = { Text("Turn off SOS alerts?") },
            text = { Text("If someone in your family presses SOS, this phone won't notify you. You'll only see it when you open the app.") },
            confirmButton = {
                TextButton(onClick = { confirmSosOff = false; notifySos = false; prefs.notifySos = false }) { Text("Turn off") }
            },
            dismissButton = { TextButton(onClick = { confirmSosOff = false }) { Text("Keep them on") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave family?") },
            text = { Text("Your location will be removed from the server and you'll need a new invite to come back.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
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
    var open by remember { mutableStateOf(false) }
    var newUsername by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("Account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
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
        OutlinedButton(onClick = { open = true; error = null }) {
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
    val problem = when {
        password.isEmpty() -> null
        password.length < MIN_PASSWORD_LENGTH -> "At least $MIN_PASSWORD_LENGTH characters (${password.length} so far)"
        confirm.isNotEmpty() && confirm != password -> "The passwords don't match"
        else -> null
    }
    NewPasswordFields(password, { password = it; error = null }, confirm, { confirm = it; error = null }, problem, label = "New password")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !busy && password.length >= MIN_PASSWORD_LENGTH && confirm == password &&
                (hasPassword && current.isNotEmpty() || !hasPassword && newUsername.length >= 3),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val u = Api(prefs.serverUrl, prefs.token).setPassword(current, password, newUsername)
                        autofill?.commit() // lets a password manager offer to save it
                        onSaved(u)
                        open = false
                        current = ""; password = ""; confirm = ""
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
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✓  " else "✗  ") + label, Modifier.weight(1f), color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        if (!ok) TextButton(onClick = onFix) { Text("Fix") }
    }
}


val SOS_RED = Color(0xFFD32F2F)

/** "just now", "5 minutes ago"... */
fun ago(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** A red strip across the top of the map, for SOS. */
@Composable
private fun SosBanner(text: String, action: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SOS_RED).clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            OutlinedButton(onClick = onClick, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)) {
                Text(action, color = Color.White)
            }
        }
    }
}

/**
 * The SOS button. It only fires after being held for 3 seconds (a ring fills up),
 * so it can't go off by accident in a pocket. Letting go early cancels.
 */
@Composable
private fun HoldForSosButton(onTriggered: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    var hint by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .semantics { contentDescription = "SOS. Press and hold for 3 seconds to alert your family." }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        hint = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val fill = scope.launch {
                            progress.animateTo(1f, tween(SOS_HOLD_MS, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTriggered()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) fill.cancel()
                        scope.launch { progress.snapTo(0f) }
                        scope.launch { delay(3_000); hint = false }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(SOS_RED),
                contentAlignment = Alignment.Center,
            ) { Text("SOS", color = Color.White, fontWeight = FontWeight.Bold) }
            if (progress.value > 0f) {
                CircularProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.size(64.dp),
                    color = SOS_RED,
                    strokeWidth = 5.dp,
                )
            }
        }
        if (hint) {
            Surface(shape = RoundedCornerShape(50), tonalElevation = 4.dp, shadowElevation = 2.dp) {
                Text("Hold for 3 seconds", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val SOS_HOLD_MS = 3_000

/** "I'm here", with an optional note, sent to everyone. */
@Composable
private fun CheckInDialog(prefs: Prefs, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Check in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Let your family know where you are. They get a notification with your location.")
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(140) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Made it home") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).checkIn(note.trim(), currentLocation(context))
                        Toast.makeText(context, "Checked in", Toast.LENGTH_SHORT).show()
                        onDone()
                    } catch (e: IOException) {
                        Toast.makeText(context, "Couldn't check in: ${e.message}", Toast.LENGTH_LONG).show()
                        busy = false
                    }
                }
            }) { Text(if (busy) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * Where the phone is now: the last known location if it's recent, otherwise a fresh
 * fix (waiting up to 10 seconds), otherwise whatever is known. Null without permission.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Location? {
    if (!LocationService.hasLocationPermission(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val last = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000) return last
    val provider = when {
        Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
        else -> LocationManager.NETWORK_PROVIDER
    }
    val fresh = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val cancel = android.os.CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            runCatching {
                LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(context)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
    return fresh ?: last
}
