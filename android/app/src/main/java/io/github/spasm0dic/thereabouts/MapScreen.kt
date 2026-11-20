package io.github.spasm0dic.thereabouts

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
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
fun MapScreen(prefs: Prefs, onLeft: () -> Unit) {
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
                if (me.paused == prefs.sharing) Api(prefs.serverUrl, prefs.token).setPaused(!prefs.sharing)
            }
            if (!fittedOnce && members.any { it.loc != null }) {
                fittedOnce = true
                camera = CameraCommand.FitAll(++cameraSeq)
            }
        } catch (e: UnauthorizedException) {
            prefs.removedNotice = true
            leave()
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
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                perms = PermissionState.read(context)
                if (prefs.sharing && perms.location) LocationService.start(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = PermissionState.read(context)
        if (perms.location && prefs.sharing) LocationService.start(context)
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
                LocationService.stop(context)
                runCatching { Api(prefs.serverUrl, prefs.token).setPaused(true) }
            }
            refresh()
        }
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            prefs = prefs,
            perms = perms,
            sharing = sharing,
            onSharingChange = ::setSharing,
            shareLowBattery = shareLowBattery,
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
            when {
                !perms.location -> Banner(
                    "Thereabouts needs your location to share it with your family.",
                    "Allow",
                ) { askLocation.launch(PermissionState.foregroundPermissions()) }
                !sharing -> Banner("You're not sharing your location right now.", "Resume") { setSharing(true) }
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
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!isMe && m.loc != null && !m.paused) {
            TextButton(onClick = onDirections) { Text("Directions") }
        }
    }
}

/** "4 min ago · 82% · 1.3 km away" */
@Suppress("UNUSED_PARAMETER") // tick only forces recomposition
private fun statusLine(m: Member, me: Member?, tick: Int): String {
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

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
