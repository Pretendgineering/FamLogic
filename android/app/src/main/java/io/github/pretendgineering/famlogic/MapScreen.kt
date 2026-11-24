package io.github.pretendgineering.famlogic

import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import android.location.LocationManager
import androidx.compose.animation.core.Animatable
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
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ElevatedCard
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
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
fun MapScreen(
    prefs: Prefs,
    onLeft: () -> Unit,
    focusMember: String? = null,
    onFocusHandled: () -> Unit = {},
    joinCircleCode: String? = null,
    onJoinCircleHandled: () -> Unit = {},
    onFeatures: (Set<String>) -> Unit = {},
    /** The home screen asked for settings: "full" or "limited" (a child's own). */
    openSettings: String? = null,
    onSettingsHandled: () -> Unit = {},
    onSettingsClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Small buzzes for a few moments that matter (picking, long-press, success), not every tap.
    val haptics = LocalHapticFeedback.current

    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var familyName by remember { mutableStateOf(prefs.familyName) }
    var tileUrl by remember { mutableStateOf(prefs.tileUrl) }
    var tileUrlDark by remember { mutableStateOf(prefs.tileUrlDark) }
    var mapStyle by remember { mutableStateOf(prefs.mapStyle) }
    var dimMap by remember { mutableStateOf(prefs.dimMap) }
    var attribution by remember { mutableStateOf(prefs.tileAttribution) }
    var error by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(prefs.sharing) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsFull by remember { mutableStateOf(true) }
    // The home screen asked for settings (it already checked the parent PIN).
    LaunchedEffect(openSettings) {
        if (openSettings != null) {
            settingsFull = openSettings == "full"
            showSettings = true
            onSettingsHandled()
        }
    }
    val gate = rememberSettingsGate(prefs) { full -> settingsFull = full; showSettings = true }
    var camera by remember { mutableStateOf<CameraCommand?>(null) }
    var cameraSeq by remember { mutableIntStateOf(0) }
    var fittedOnce by remember { mutableStateOf(false) }
    var perms by remember { mutableStateOf(PermissionState.read(context)) }
    var shareLowBattery by remember { mutableStateOf<Boolean?>(null) } // null until the server tells us
    var hasPassword by remember { mutableStateOf<Boolean?>(null) } // null until the server tells us
    var username by remember { mutableStateOf(prefs.username) }
    var sosActive by remember { mutableStateOf(prefs.sosActive) }
    var sosPending by remember { mutableStateOf(prefs.sosPending) }
    var sosFailed by remember { mutableStateOf(prefs.sosFailed) }
    // What the server can do; null until we've heard from it (show everything meanwhile).
    var features by remember { mutableStateOf<Set<String>?>(null) }
    var serverVersion by remember { mutableStateOf("") }
    var outdatedDismissed by remember { mutableStateOf(false) }
    fun supports(f: String) = features?.contains(f) ?: true
    var confirmOk by remember { mutableStateOf(false) }
    var showCheckIn by remember { mutableStateOf(false) }
    var circles by remember { mutableStateOf<List<CircleInfo>>(emptyList()) }
    var circleFilter by remember { mutableStateOf(prefs.circleFilter) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var placeEdit by remember { mutableStateOf<PlaceEdit?>(null) }
    var shareArrivals by remember { mutableStateOf<Boolean?>(null) }
    var trailHours by remember { mutableStateOf<Int?>(null) }
    // The person you tapped, whose trail (if they keep one) is drawn on the map.
    var selected by remember { mutableStateOf<String?>(null) }
    var trail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var tripDays by remember { mutableStateOf<Int?>(null) }
    // The person whose sheet (directions, alerts, trips) is open, and a trip drawn on the map.
    var sheetFor by remember { mutableStateOf<String?>(null) }
    var shownTrip by remember { mutableStateOf<Trip?>(null) }
    // How much of the map the people sheet covers (px), so fitting keeps everyone above it.
    var mapCovered by remember { mutableIntStateOf(0) }
    // Several people in one spot, after tapping their shared pin.
    var groupBubble by remember { mutableStateOf<List<String>?>(null) }
    // A trip being shared to a chat.
    var sharingTrip by remember { mutableStateOf<Trip?>(null) }
    // A fence being placed or changed right on the map.
    var fenceDraft by remember { mutableStateOf<FenceDraft?>(null) }
    if (fenceDraft != null) BackHandler { fenceDraft = null }
    // A short-lived card at the top of the map for the pin you tapped.
    var bubbleFor by remember { mutableStateOf<String?>(null) }
    var bubbleSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(bubbleFor, bubbleSeq) {
        if (bubbleFor != null) {
            delay(5_000)
            bubbleFor = null
        }
    }
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
            places = res.places
            prefs.fenceNames = res.places.associate { it.id to it.name } // for trip alerts
            tripDays = res.tripDays
            shareArrivals = res.shareArrivals
            features = res.features
            // The family may have switched location sharing off (FamLogic modules).
            val locationOn = Modules.on(res.features, Modules.THEREABOUTS)
            if (locationOn != prefs.locationModule) {
                prefs.locationModule = locationOn
                LocationService.start(context)
            }
            res.members.find { it.id == prefs.memberId }?.let { prefs.role = it.role; prefs.access = it.access }
            Access.update(context, res)
            if (res.features != prefs.serverFeatures) {
                prefs.serverFeatures = res.features
                onFeatures(res.features)
            }
            serverVersion = res.server.version
            trailHours = res.trailHours
            // Keep the selected person's trail up to date.
            val sel = res.members.find { it.id == selected }
            trail = if (sel != null && sel.trail && prefs.showTrails) {
                runCatching { Api(prefs.serverUrl, prefs.token).trail(sel.id) }.getOrDefault(trail)
            } else {
                emptyList()
            }
            hasPassword = res.hasPassword
            username = res.username
            prefs.username = res.username
            members = res.members
            circles = res.circles
            if (circleFilter.isNotEmpty() && res.circles.none { it.id == circleFilter }) {
                circleFilter = ""
                prefs.circleFilter = ""
            }
            familyName = res.server.name
            tileUrl = res.server.tileUrl
            tileUrlDark = res.server.tileUrlDark
            attribution = res.server.tileAttribution
            prefs.familyName = res.server.name
            prefs.tileUrl = res.server.tileUrl
            prefs.tileUrlDark = res.server.tileUrlDark
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
                sosFailed = prefs.sosFailed
                sosActive = prefs.sosActive
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
                scope.launch { Health.report(context, prefs) } // e.g. a permission was just fixed
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Google edition: ask once for "Physical activity" (driving from motion sensors), after location.
    val askMotion = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        LocationService.start(context)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = PermissionState.read(context)
        LocationService.start(context)
        if (Flavor.GOOGLE && perms.location && prefs.motionDriving && !prefs.motionAsked &&
            !Motion.permitted(context) && Flavor.playServicesAvailable(context)
        ) {
            prefs.motionAsked = true
            askMotion.launch(Motion.PERMISSION)
        }
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        perms = PermissionState.read(context)
    }
    // First time on this screen: ask for location straight away.
    LaunchedEffect(Unit) {
        if (!perms.location) askLocation.launch(PermissionState.foregroundPermissions())
        else if (Flavor.GOOGLE && prefs.motionDriving && !prefs.motionAsked && !Motion.permitted(context) &&
            Flavor.playServicesAvailable(context)
        ) {
            prefs.motionAsked = true
            askMotion.launch(Motion.PERMISSION)
        }
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
            } catch (e: ApiException) {
                if (e.serverTooOld) {
                    prefs.sosPending = false
                    prefs.sosEndPending = false
                    prefs.sosActive = false
                    prefs.sosFailed = true
                    sosActive = false
                    sosFailed = true
                    Alerts.notifySosFailed(context)
                }
            } catch (e: IOException) {
                Toast.makeText(context, "Couldn't reach the server. FamLogic will keep trying.", Toast.LENGTH_LONG).show()
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
    placeEdit?.let { edit ->
        PlaceDialog(prefs, edit, circles, defaultCircle = circleFilter, canSearch = features?.contains("geocode") == true, canSchedule = features?.contains("schedules") == true, onDone = { changed ->
            placeEdit = null
            if (changed) scope.launch { refresh() }
        })
    }
    if (joinCircleCode != null) {
        JoinCircleDialog(prefs, initialCode = joinCircleCode, onDone = { joined ->
            onJoinCircleHandled()
            if (joined) scope.launch { refresh() }
        })
    }

    if (showSettings) {
        BackHandler { showSettings = false; onSettingsClosed() }
        SettingsScreen(
            prefs = prefs,
            onOpenHomeEditor = { HomeEditor.requested = true; showSettings = false; onSettingsClosed() },
            perms = perms,
            features = features,
            sharing = sharing,
            onSharingChange = ::setSharing,
            shareLowBattery = shareLowBattery,
            username = username,
            hasPassword = hasPassword,
            circles = circles,
            onCircleSharingChange = { c, on ->
                circles = circles.map { if (it.id == c.id) it.copy(sharing = on) else it }
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).setCircleSharing(c.id, on)
                    } catch (e: IOException) {
                        circles = circles.map { if (it.id == c.id) it.copy(sharing = !on) else it }
                        Toast.makeText(context, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCirclesChanged = { scope.launch { refresh() } },
            places = places,
            onEditPlace = { placeEdit = it },
            shareArrivals = shareArrivals,
            serverVersion = serverVersion,
            trailHours = trailHours,
            tripDays = tripDays,
            onTripDaysChange = { d ->
                val before = tripDays
                tripDays = d
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).setTripDays(d)
                    } catch (e: IOException) {
                        tripDays = before
                        Toast.makeText(context, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onTrailHoursChange = { h ->
                val before = trailHours
                trailHours = h
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).setTrailHours(h)
                    } catch (e: IOException) {
                        trailHours = before
                        Toast.makeText(context, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onShareArrivalsChange = { on ->
                val before = shareArrivals
                shareArrivals = on
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).setShareArrivals(on)
                    } catch (e: IOException) {
                        shareArrivals = before
                        Toast.makeText(context, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
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
            onBack = { showSettings = false; onSettingsClosed(); scope.launch { refresh() } },
            limited = !settingsFull,
            onMapStyleChange = { mapStyle = it },
            onDimMapChange = { dimMap = it },
            members = members,
            myId = prefs.memberId,
            onProfileChanged = { scope.launch { refresh() } },
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
    // The circle picker narrows the map and list to one circle (you always stay in view).
    // Everyone else alphabetically, then you at the bottom.
    val shown = (if (circleFilter.isEmpty()) members else members.filter { it.id == prefs.memberId || circleFilter in it.circles })
        .sortedBy { it.id == prefs.memberId }
    val statusOf: (Member) -> String = { m -> statusLine(m, me, tick) }

    members.find { it.id == sheetFor }?.let { m ->
        PersonSheet(
            prefs = prefs,
            m = m,
            isMe = m.id == prefs.memberId,
            status = statusOf(m),
            canTrips = supports("trips") && Access.can("trips"),
            canRequests = features?.contains("requests") == true && Access.can("requests"),
            canNudges = features?.contains("nudges") == true,
            onDirections = { m.loc?.let { openDirections(context, m.name, it) } },
            onShowTrip = { t ->
                sheetFor = null
                selected = m.id
                shownTrip = t
                camera = CameraCommand.FitPoints(t.points, ++cameraSeq)
            },
            onDismiss = { sheetFor = null },
        )
    }

    sharingTrip?.let { t ->
        val who = members.find { it.id == selected }?.name ?: ""
        ShareToChatDialog(prefs, t.shareText(context, who), onDone = { sharingTrip = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { SecretTitle(familyName.ifEmpty { "FamLogic" }, gate) },
                actions = {
                    if (!Access.hideSettings) IconButton(onClick = gate.cog) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // SOS banners come first: yours, then anyone else's.
            if (sosFailed) {
                SosBanner("SOS was NOT sent: your family's server is too old for SOS. Call or message someone directly.", "OK") {
                    prefs.sosFailed = false
                    sosFailed = false
                }
            }
            if (sosActive) {
                val seenBy = members.find { it.id == prefs.memberId }?.sosSeenBy.orEmpty()
                SosBanner(
                    if (sosPending) "Sending SOS… FamLogic keeps trying until it gets through."
                    else "SOS sent. Your family has been alerted and sees your location every 15 seconds." +
                        if (seenBy.isNotEmpty()) "\nSeen by ${seenBy.joinToString(", ")}." else "",
                    "I'm OK",
                ) { confirmOk = true }
            }
            members.filter { it.sosSince > 0 && it.id != prefs.memberId }.forEach { m ->
                val myName = members.find { it.id == prefs.memberId }?.name
                val iSaw = myName != null && myName in m.sosSeenBy
                SosBanner(
                    "🆘 SOS from ${m.name}" + (m.loc?.let { " · ${ago(it.time)}" } ?: ""),
                    if (m.loc != null) "Show" else null,
                    secondAction = if (supports("sos-seen") && !iSaw) "Seen" else null,
                    onSecond = {
                        scope.launch {
                            runCatching { Api(prefs.serverUrl, prefs.token).sosSeen(m.id) }
                            androidx.core.app.NotificationManagerCompat.from(context).cancel(Alerts.sosId(m.id))
                            refresh()
                        }
                    },
                ) {
                    m.loc?.let { camera = CameraCommand.Center(it.lat, it.lon, ++cameraSeq) }
                }
            }
            when {
                !Modules.on(features, Modules.THEREABOUTS) -> Banner(
                    "Location sharing (Thereabouts) is switched off for your family, so this phone isn't sharing. Alerts still arrive.",
                    "OK",
                ) {}
                !perms.location -> Banner(
                    "FamLogic needs your location to share it with your family.",
                    "Allow",
                ) { askLocation.launch(PermissionState.foregroundPermissions()) }
                features != null && !supports("places") && !outdatedDismissed -> Banner(
                    "Your family's FamLogic server is out of date, so some features (like " +
                        listOf("sos" to "SOS", "checkin" to "check-ins", "circles" to "groups", "places" to "fences")
                            .filter { !supports(it.first) }.joinToString(", ") { it.second } +
                        ") aren't available. Ask its admin to update it.",
                    "OK",
                ) { outdatedDismissed = true }
                !sharing -> Banner("You're not sharing your location right now.", "Resume") { setSharing(true) }
                hasPassword == false && prefs.canUsePassword -> Banner(
                    "Set a password so you can sign back in on a new phone.",
                    "Set up",
                ) { gate.cog() }
                !perms.background -> Banner(
                    "Choose \"Allow all the time\" so sharing keeps working after a restart.",
                    "Fix",
                ) {
                    if (Build.VERSION.SDK_INT >= 29) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                !perms.batteryUnrestricted -> Banner(
                    "Let FamLogic run in the background so your phone doesn't stop it.",
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

            // Group filter chips, floating over the top of the map.
            val chips: @Composable () -> Unit = { if (circles.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val choices = listOf("" to "Everyone") + circles.map { it.id to it.name }
                    items(choices, key = { it.first }) { (id, name) ->
                        // Over the map: solid chips that stand out (the main colour when selected).
                        androidx.compose.material3.FilterChip(
                            selected = circleFilter == id,
                            onClick = {
                                if (circleFilter != id) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                circleFilter = id
                                prefs.circleFilter = id
                                camera = CameraCommand.FitAll(++cameraSeq)
                            },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = FilterChipDefaults.filterChipElevation(elevation = 3.dp),
                        )
                    }
                }
            } }

            // Phones and portrait tablets: the map fills the screen, the people in a sheet over it.
            // Landscape tablets: the people beside the map.
            val config = LocalConfiguration.current
            val wide = config.screenWidthDp >= 720 && config.screenWidthDp > config.screenHeightDp
            // The map, edge to edge.
            val mapCard: @Composable (Modifier) -> Unit = { mapMod ->
            Box(mapMod) {
                OsmMap(
                    members = shown,
                    places = (if (circleFilter.isEmpty()) places else places.filter { it.circleId == circleFilter }).filter { it.id != fenceDraft?.id },
                    // Long-press: a new fence right there, sized and named on the map.
                    onLongPress = { lat, lon ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!supports("places")) Toast.makeText(context, OUTDATED_SERVER, Toast.LENGTH_LONG).show()
                        else if (fenceDraft == null) fenceDraft = FenceDraft(null, "", lat, lon, 150.0, circleFilter.ifEmpty { circles.firstOrNull()?.id ?: "" })
                    },
                    onPlaceClick = { p -> if (fenceDraft == null) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); fenceDraft = FenceDraft.of(p) } },
                    fenceDraft = fenceDraft,
                    onFenceDraft = { f, done -> fenceDraft = f; if (done) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                    onMemberClick = { id ->
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        shownTrip = null
                        bubbleFor = id
                        bubbleSeq++
                        val m = members.find { it.id == id }
                        if (m != null && selected != id) {
                            selected = id
                            trail = emptyList()
                            if (m.trail && prefs.showTrails) scope.launch {
                                trail = runCatching { Api(prefs.serverUrl, prefs.token).trail(id) }.getOrDefault(emptyList())
                            }
                        }
                    },
                    onGroupClick = { ids -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); bubbleFor = null; groupBubble = ids },
                    onMapTap = { bubbleFor = null; groupBubble = null },
                    bottomInset = mapCovered,
                    trail = shownTrip?.points ?: trail,
                    trailColor = members.find { it.id == selected }?.color ?: "#4363d8",
                    tiles = MapStyles.resolve(mapStyle, LocalDarkTheme.current, tileUrl, tileUrlDark, attribution, dimMap),
                    camera = camera,
                    statusOf = statusOf,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxWidth().padding(top = 4.dp)) { chips() }
                fenceDraft?.let { f ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        FenceEditBar(
                            prefs, f, circles, canSchedule = features?.contains("schedules") == true,
                            onChange = { fenceDraft = it },
                            onMore = {
                                placeEdit = if (f.id == null) PlaceEdit.Add(f.lat, f.lon, f.name, f.radius, f.circleId) else PlaceEdit.Edit(f.asPlace())
                                fenceDraft = null
                            },
                            onDone = { changed -> fenceDraft = null; if (changed) scope.launch { refresh() } },
                        )
                    }
                }
                val bubbleTop = if (circles.size > 1) 56.dp else 10.dp
                // Several people in one spot: who's there, each face opens them.
                groupBubble?.let { ids ->
                    Box(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = bubbleTop), contentAlignment = Alignment.TopCenter) {
                        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text("Together", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                                ids.mapNotNull { id -> members.find { it.id == id } }.forEach { m ->
                                    Column(
                                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { groupBubble = null; sheetFor = m.id }.padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Avatar(m.name, m.emoji, m.color, 36)
                                        Text(m.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                members.find { it.id == bubbleFor }?.takeIf { shownTrip == null }?.let { m ->
                    Box(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = bubbleTop), contentAlignment = Alignment.TopCenter) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            tonalElevation = 6.dp,
                            shadowElevation = 4.dp,
                            onClick = { bubbleFor = null; sheetFor = m.id }, // more about them
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
                                Avatar(m.name, m.emoji, m.color, 36)
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(if (m.id == prefs.memberId) "${m.name} (you)" else m.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(statusOf(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                        }
                    }
                }
                shownTrip?.let { t ->
                    Box(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = bubbleTop), contentAlignment = Alignment.TopCenter) {
                        Surface(shape = RoundedCornerShape(50), tonalElevation = 6.dp, shadowElevation = 4.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
                                Text(
                                    t.title() + " · " + Units.duration(t.end - t.start),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (Modules.has(features, Modules.CHAT) && Access.can("chat")) {
                                    TextButton(onClick = { sharingTrip = t }) { Text("Share") }
                                }
                                TextButton(onClick = { shownTrip = null }) { Text("Close") }
                            }
                        }
                    }
                }
            }

            }
            // Actions, all in one row.
            val actions: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (prefs.showCheckInButton && supports("checkin") && Access.can("checkin") && !Access.isDisplay) {
                    FilledTonalButton(onClick = { showCheckIn = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Check in", Modifier.padding(start = 8.dp))
                    }
                }
                FilledTonalButton(
                    onClick = {
                        selected = null
                        trail = emptyList()
                        shownTrip = null
                        bubbleFor = null
                        camera = CameraCommand.FitAll(++cameraSeq)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Show everyone", Modifier.padding(start = 8.dp))
                }
            }
            }

            // Everyone. A list only just too long for its space tightens its rows to fit
            // instead of scrolling a few pixels.
            val peopleState = androidx.compose.foundation.lazy.rememberLazyListState()
            // (In the pull-up sheet the list's height changes as it's dragged, so it simply
            // scrolls there; squeezing is for the fixed side panel.)
            val peopleList: @Composable (Modifier, Boolean) -> Unit = { listMod, fit ->
            var peopleViewport by remember { mutableIntStateOf(0) }
            val squeeze = rememberSqueeze(shown.size, if (fit) peopleViewport else 0) { peopleState.overflowPx() }
            Column(listMod.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                androidx.compose.runtime.CompositionLocalProvider(LocalSqueeze provides squeeze.factor) {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f)
                        .onSizeChanged { peopleViewport = it.height }
                        .graphicsLayer { alpha = if (squeeze.ready || !fit) 1f else 0f },
                    state = peopleState,
                ) {
                    if (shown.isEmpty()) {
                        item { Text("Loading…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    itemsIndexed(shown, key = { _, m -> m.id }) { i, m ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        MemberRow(
                            m = m,
                            isMe = m.id == prefs.memberId,
                            status = statusOf(m),
                            // Tap: go to them on the map. Long-press: their card (directions, alerts, trips).
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                shownTrip = null
                                bubbleFor = null
                                m.loc?.let { camera = CameraCommand.Center(it.lat, it.lon, ++cameraSeq) }
                                selected = m.id
                                trail = emptyList()
                                if (m.trail && prefs.showTrails) scope.launch {
                                    trail = runCatching { Api(prefs.serverUrl, prefs.token).trail(m.id) }.getOrDefault(emptyList())
                                }
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sheetFor = m.id
                            },
                            onDirections = { m.loc?.let { openDirections(context, m.name, it) } },
                        )
                    }
                }
                }
            }

            }
            val sosBar: @Composable () -> Unit = {
            if (prefs.showSosButton && !sosActive && supports("sos") && Access.can("sos") && !Access.isDisplay) {
                HoldForSosBar(
                    onTriggered = { sendSos(end = false) },
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
            }
            if (wide) {
                // The people on the left, the map on the right, edge to edge.
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(400.dp).fillMaxHeight().padding(top = 4.dp)) {
                        actions()
                        peopleList(Modifier.weight(1f).padding(top = 8.dp), true)
                        sosBar()
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    mapCard(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    mapCard(Modifier.fillMaxSize())
                    if (fenceDraft == null) PullUpSheet(
                        // Peeking: the first couple of people under the buttons.
                        peekExtra = 72.dp, // one person
                        onCovered = { mapCovered = it },
                        header = { actions() },
                        footer = { sosBar() },
                    ) { peopleList(Modifier.weight(1f).padding(top = 8.dp), false) }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MemberRow(m: Member, isMe: Boolean, status: String, onClick: () -> Unit, onLongClick: () -> Unit, onDirections: () -> Unit) {
    val sos = m.sosSince > 0
    val stale = m.loc == null || m.paused || System.currentTimeMillis() - m.loc.time > STALE_MS
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClickLabel = "Show on map", onLongClickLabel = "More about ${m.name}", onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = squeezed(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color(parseColor(m.color)).copy(alpha = if (stale) 0.5f else 1f)),
            contentAlignment = Alignment.Center,
        ) {
            if (m.emoji.isNotEmpty()) Text(m.emoji, fontSize = 20.sp)
            else Text(initialOf(m.name), color = Color.White, fontWeight = FontWeight.Bold)
        }
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

/** Driving, as of a recent fix (an old "driving" fix means the phone went quiet mid-drive). */
fun isDriving(m: Member) = m.loc?.driving == true && !m.paused && System.currentTimeMillis() - m.loc.time < 10 * 60_000

/** "4 min ago · 82% · 1.3 km away" */
@Suppress("UNUSED_PARAMETER") // tick only forces recomposition
private fun statusLine(m: Member, me: Member?, tick: Int): String {
    if (m.sosSince > 0) {
        val since = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(m.sosSince))
        return "🆘 SOS since $since" + (m.loc?.let { " · ${ago(it.time)}" } ?: "")
    }
    if (m.paused) return "Location sharing paused"
    val loc = m.loc ?: return "No location yet"
    val parts = mutableListOf(Seen.where(m))
    if (loc.battery >= 0) parts += "${loc.battery}%" + when {
        loc.charging -> " charging"
        loc.saving -> " (saving battery)"
        else -> ""
    }
    val myLoc = me?.loc
    if (me != null && me.id != m.id && myLoc != null && !me.paused) {
        val out = FloatArray(1)
        Location.distanceBetween(myLoc.lat, myLoc.lon, loc.lat, loc.lon, out)
        parts += if (out[0] < 100) "nearby" else Units.distance(out[0].toDouble()) + " away"
    }
    return parts.joinToString(" · ")
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
internal fun requestBatteryExemption(context: Context) {
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


/** "just now", "5 min ago"... (see [Seen]). */
fun ago(time: Long): String = Seen.ago(time)

/** A red strip across the top of the map, for SOS. */
@Composable
private fun SosBanner(text: String, action: String?, secondAction: String? = null, onSecond: () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SOS_RED).clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        if (secondAction != null) {
            OutlinedButton(onClick = onSecond, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)) {
                Text(secondAction, color = Color.White)
            }
        }
        if (action != null) {
            OutlinedButton(onClick = onClick, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White), modifier = Modifier.padding(start = 6.dp)) {
                Text(action, color = Color.White)
            }
        }
    }
}

/**
 * The SOS bar. It only fires after being held for 3 seconds (a red fill sweeps
 * across), so it can't go off by accident in a pocket. Letting go early cancels.
 */
@Composable
internal fun HoldForSosBar(onTriggered: () -> Unit, modifier: Modifier = Modifier, tall: Boolean = false, tile: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val red = SOS_RED
    val label = if (holding) "Keep holding…" else if (Access.pictures) "Hold for help" else "Hold for SOS"
    Box(
        modifier
            .then(if (tile) Modifier else Modifier.height(if (tall) 84.dp else 60.dp))
            .clip(RoundedCornerShape(if (tile) 0.dp else 16.dp))
            .background(red.copy(alpha = if (tile) 0.2f else 0.14f))
            .drawBehind { drawRect(red, size = size.copy(width = size.width * progress.value)) }
            .semantics { contentDescription = "SOS. Press and hold for 3 seconds to alert everyone." }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    // Timed with the real clock, not an animation: animations can be
                    // sped up (or switched off) in Android's developer options, and the
                    // 3-second hold must stay 3 seconds.
                    var triggered = false
                    val fill = scope.launch {
                        val start = SystemClock.uptimeMillis()
                        var ticks = 0
                        while (true) {
                            val p = ((SystemClock.uptimeMillis() - start).toFloat() / SOS_HOLD_MS).coerceAtMost(1f)
                            progress.snapTo(p)
                            // A soft tick at each second of the hold, like a countdown.
                            if (p < 1f && (p * 3).toInt() > ticks) {
                                ticks = (p * 3).toInt()
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            if (p >= 1f) break
                            delay(16)
                        }
                        triggered = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTriggered()
                    }
                    tryAwaitRelease()
                    if (!triggered) fill.cancel()
                    holding = false
                    scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val style = if (tall || tile) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
        // The label is red on the pale bar and white where the fill has reached.
        @Composable fun Label() {
            Text(label, style = style, color = red, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                label, style = style, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().drawWithContent {
                    clipRect(right = size.width * progress.value) { this@drawWithContent.drawContent() }
                },
            )
        }
        if (tile) {
            // A home screen tile: the whole tile is the button, with the picture in the middle.
            Column(Modifier.fillMaxSize().padding(vertical = 10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                val s = if (tall) 64.dp else 48.dp
                if (Access.pictures) Pictures.Sos(s + 12.dp)
                else Icon(Icons.Filled.Warning, contentDescription = null, tint = if (progress.value > .5f) Color.White else red, modifier = Modifier.size(s))
                Box(Modifier.fillMaxWidth().padding(top = 6.dp)) { Label() }
            }
            return@Box
        }
        // A picture of an alarm (or the warning sign), so it reads without words.
        Box(Modifier.fillMaxSize().padding(start = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (Access.pictures) Pictures.Sos(if (tall) 52.dp else 38.dp)
            else Icon(Icons.Filled.Warning, contentDescription = null, tint = if (progress.value > .12f) Color.White else red, modifier = Modifier.size(if (tall) 36.dp else 28.dp))
        }
        Label()
    }
}

private const val SOS_HOLD_MS = 3_000

/** "I'm here", with an optional note, sent to everyone. */
@Composable
private fun CheckInDialog(prefs: Prefs, onDone: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
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
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        Toast.makeText(context, "Checked in", Toast.LENGTH_SHORT).show()
                        onDone()
                    } catch (e: IOException) {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
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


/**
 * Joins another circle with an invite code while staying signed in as yourself.
 * Shows which circle the code is for before joining.
 */
@Composable
fun JoinCircleDialog(prefs: Prefs, initialCode: String, onDone: (joined: Boolean) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(initialCode) }
    var circleName by remember { mutableStateOf<String?>(null) } // known once the code is checked
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check() {
        busy = true
        error = null
        scope.launch {
            try {
                val info = Api(prefs.serverUrl).inviteInfo(code)
                if (info.isReset) error = "That's a sign-in reset link, not a group invite."
                else circleName = info.circle.ifEmpty { info.server.name }
            } catch (e: IOException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { if (initialCode.isNotBlank()) check() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone(false) },
        title = { Text(circleName?.let { "Join $it?" } ?: "Join another group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (circleName == null) {
                    Text("Enter the invite code you were given. You'll stay signed in as yourself.")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; error = null },
                        label = { Text("Invite code") },
                        placeholder = { Text("ABCD-EFGH") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("You'll see everyone in $circleName, and they'll see you. You can stop sharing your location with any group in Settings.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && code.isNotBlank(), onClick = {
                if (circleName == null) {
                    check()
                    return@TextButton
                }
                busy = true
                scope.launch {
                    try {
                        val name = Api(prefs.serverUrl, prefs.token).joinCircle(code)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        Toast.makeText(context, "You joined $name", Toast.LENGTH_SHORT).show()
                        onDone(true)
                    } catch (e: IOException) {
                        error = e.message
                        busy = false
                    }
                }
            }) { Text(if (circleName == null) "Continue" else "Join") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(false) }) { Text("Cancel") } },
    )
}


/** Pick a chat and send it [text] (a trip, for now), after showing what will be sent. */
@Composable
private fun ShareToChatDialog(prefs: Prefs, text: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<ChatConversation>?>(null) }
    LaunchedEffect(Unit) {
        chats = runCatching { Api(prefs.serverUrl, prefs.token).chats() }.getOrDefault(emptyList()).filter { !it.oversight }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Share to a chat") },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                val list = chats
                if (list == null) Text("Loading…")
                else Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    list.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val ok = runCatching { Api(prefs.serverUrl, prefs.token).chatSend(c.id, text) }.isSuccess
                                    Toast.makeText(context, if (ok) "Shared in ${c.title}" else "Couldn't share", Toast.LENGTH_SHORT).show()
                                    onDone()
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
