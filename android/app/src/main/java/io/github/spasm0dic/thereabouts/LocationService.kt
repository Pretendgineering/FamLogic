package io.github.spasm0dic.thereabouts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The app's background service. While you're signed in it:
 *
 *  - shares your location (unless you paused sharing), using Android's own
 *    LocationManager rather than Google Play Services, so it also works on
 *    de-Googled phones;
 *  - keeps a connection open to your server so alerts (SOS above all) arrive
 *    within seconds, even with the app closed and even while your own sharing is
 *    paused. No push service or extra app is involved;
 *  - sends your SOS / "I'm OK" and keeps retrying until the server has it, and
 *    while an SOS is active shares your location every 15 seconds.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uploadLock = Mutex()
    private lateinit var prefs: Prefs
    private lateinit var lm: LocationManager

    private var lastSentElapsed = 0L // SystemClock.elapsedRealtime() of the last successful upload
    private var lastSent: Location? = null
    private var lastFix: Location? = null // newest fix seen, sent or not
    private var lastBattery = -1
    private var locating = false
    private var alertsJob: Job? = null
    private var sosJob: Job? = null
    private var wifiJob: Job? = null

    private val listener = LocationListenerCompat { onFix(it) }

    // When the battery drops past an alert level, send an update right away so the
    // family's low-battery alert doesn't wait for the next interval.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val (pct, charging) = batteryState(intent)
            val previous = lastBattery
            lastBattery = pct
            if (pct < 0 || previous < 0 || charging) return
            val crossed = Prefs.LOW_BATTERY_CHOICES.any { level -> previous > level && pct <= level }
            if (crossed) lastFix?.let { upload(it, waitForTurn = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        isRunning = true
        Alerts.ensureChannels(this)
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @SuppressLint("InlinedApi") // the type constants are plain ints; ServiceCompat ignores them where unsupported
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBackground = intent?.getBooleanExtra(EXTRA_FROM_BACKGROUND, false) ?: false
        val share = prefs.joined && prefs.sharing && hasLocationPermission(this) &&
            (!fromBackground || hasBackgroundPermission(this))
        if (!needed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+ wants to know why we run: location sharing and/or staying
        // connected for incoming alerts ("remote messaging").
        var types = 0
        if (share) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION // ignored before Android 10
        if (Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (e: Exception) {
            // E.g. Android refused because we were started from the background without
            // "Allow all the time". The app will start us again when it's opened.
            Log.w(TAG, "Could not start in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (share) startUpdates() else stopUpdates()
        if (share && prefs.wifiPositioning) startWifiLoop() else wifiJob?.cancel()
        if (prefs.instantAlerts) startAlertsLoop() else alertsJob?.cancel()
        startSosWork()
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // removing updates is harmless; guarded anyway
    override fun onDestroy() {
        stopUpdates()
        runCatching { unregisterReceiver(batteryReceiver) }
        scope.cancel() // also stops the alerts, SOS and Wi-Fi loops
        isRunning = false
        super.onDestroy()
    }

    // ---- location sharing ----

    private fun intervalMs() = if (prefs.sosActive) SOS_INTERVAL_MS else prefs.intervalMinutes * 60_000L

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startUpdates() {
        runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
        locating = true
        val intervalMs = intervalMs()
        // "Balanced" lets the phone use Wi-Fi/cell positioning, which is cheap on battery.
        // But Android's built-in fused provider only turns on GPS for high-accuracy requests,
        // so on phones with no network location (e.g. GrapheneOS without it enabled) a
        // balanced request would never produce a fix. Ask for GPS in that case, and always
        // during an SOS, when being findable matters more than battery.
        val hasNetworkLocation = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val quality = if (hasNetworkLocation && !prefs.sosActive) LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
        else LocationRequestCompat.QUALITY_HIGH_ACCURACY
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(quality)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        val providers = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        }
        for (p in providers) {
            try {
                LocationManagerCompat.requestLocationUpdates(lm, p, request, ContextCompat.getMainExecutor(this), listener)
            } catch (e: Exception) {
                Log.w(TAG, "Provider $p unavailable", e)
            }
        }

        // Send something right away so others see us without waiting a whole interval.
        newestLastKnown()?.let { onFix(it, force = true) }
    }

    @SuppressLint("MissingPermission")
    private fun stopUpdates() {
        if (!locating) return
        locating = false
        runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
    }

    @SuppressLint("MissingPermission")
    private fun newestLastKnown(): Location? {
        if (!hasLocationPermission(this)) return null
        return lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun onFix(loc: Location, force: Boolean = false) {
        if (lastFix.let { it == null || loc.time >= it.time }) lastFix = loc
        val intervalMs = intervalMs()
        val sinceLast = SystemClock.elapsedRealtime() - lastSentElapsed
        val movedFar = lastSent?.let { it.distanceTo(loc) > 250 } ?: true
        // Several providers may report; send at most once per interval unless we moved a lot.
        val slack = if (prefs.sosActive) 3_000 else 15_000
        if (!force && lastSentElapsed != 0L && sinceLast < intervalMs - slack && !movedFar) return
        upload(loc)
    }

    private fun upload(loc: Location, waitForTurn: Boolean = false) = scope.launch {
        // Routine uploads skip if one is already in flight; important ones wait their turn.
        if (waitForTurn) uploadLock.lock() else if (!uploadLock.tryLock()) return@launch
        try {
            val (battery, charging) = batteryState()
            val alerts = Api(prefs.serverUrl, prefs.token).sendLocation(
                loc.latitude, loc.longitude, loc.accuracy, loc.speed, loc.time, battery, charging,
                since = prefs.eventCursor,
            )
            Alerts.handle(this@LocationService, alerts)
            lastSentElapsed = SystemClock.elapsedRealtime()
            lastSent = loc
        } catch (e: UnauthorizedException) {
            onSignedOut(e)
        } catch (e: IOException) {
            // Offline or server down: the next fix will try again.
            Log.i(TAG, "Upload failed: ${e.message}")
        } finally {
            uploadLock.unlock()
        }
    }

    // ---- Wi-Fi positioning (opt-in) ----

    /**
     * When the phone itself hasn't produced a fix for longer than an update
     * interval, asks beaconDB where the nearby Wi-Fi networks are.
     */
    private fun startWifiLoop() {
        if (wifiJob?.isActive == true) return
        wifiJob = scope.launch {
            delay(20_000) // give GPS and network location a chance first
            while (isActive) {
                val interval = intervalMs()
                val ageMs = lastFix?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
                if (ageMs == null || ageMs > interval + 30_000) {
                    WifiLocator.requestScan(this@LocationService)
                    delay(5_000)
                    WifiLocator.locate(this@LocationService)?.let {
                        Log.i(TAG, "Wi-Fi fix, ±${it.accuracy.toInt()} m")
                        onFix(it, force = true)
                    }
                }
                delay(interval.coerceAtLeast(60_000))
            }
        }
    }

    // ---- instant alerts ----

    /**
     * Keeps a "tell me when something happens" request open to the server. It answers
     * the moment there's an alert (or after ~50 s with nothing), and we ask again.
     */
    private fun startAlertsLoop() {
        if (alertsJob?.isActive == true) return
        alertsJob = scope.launch(Dispatchers.IO) {
            var backoff = MIN_BACKOFF_MS
            while (isActive) {
                try {
                    val batch = Api(prefs.serverUrl, prefs.token).waitEvents(prefs.eventCursor, WAIT_SECONDS)
                    // Make sure the phone stays awake long enough to show the alert.
                    withWakeLock { Alerts.handle(this@LocationService, batch) }
                    backoff = MIN_BACKOFF_MS
                } catch (e: UnauthorizedException) {
                    withContext(Dispatchers.Main) { onSignedOut(e) }
                    break
                } catch (e: ApiException) {
                    if (e.status == 404) {
                        // A server from before instant alerts: alerts still come with location replies.
                        Log.i(TAG, "Server doesn't support instant alerts")
                        break
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                } catch (e: IOException) {
                    // Offline, server restarting, network switching: try again soon.
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private inline fun withWakeLock(block: () -> Unit) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Thereabouts:alert")
        wl.acquire(10_000)
        try {
            block()
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    // ---- SOS ----

    /** Sends a pending SOS / "I'm OK", retrying until the server confirms. */
    private fun startSosWork() {
        if (sosJob?.isActive == true) return
        if (!prefs.sosPending && !prefs.sosEndPending) return
        sosJob = scope.launch {
            var backoff = 2_000L
            while (isActive && (prefs.sosPending || prefs.sosEndPending)) {
                try {
                    val api = Api(prefs.serverUrl, prefs.token)
                    if (prefs.sosPending) {
                        api.sos(lastFix ?: newestLastKnown())
                        prefs.sosPending = false
                    }
                    if (prefs.sosEndPending) {
                        api.sosEnd()
                        prefs.sosEndPending = false
                    }
                    refreshNotification()
                    sosStateChanged = SystemClock.elapsedRealtime()
                } catch (e: UnauthorizedException) {
                    onSignedOut(e)
                    break
                } catch (e: ApiException) {
                    if (e.serverTooOld) {
                        // Retrying can't help: say clearly that the SOS did NOT go out.
                        prefs.sosPending = false
                        prefs.sosEndPending = false
                        prefs.sosActive = false
                        prefs.sosFailed = true
                        Alerts.notifySosFailed(this@LocationService)
                        refreshNotification()
                        sosStateChanged = SystemClock.elapsedRealtime()
                        break
                    }
                    Log.i(TAG, "SOS not sent yet: ${e.message}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                } catch (e: IOException) {
                    Log.i(TAG, "SOS not sent yet: ${e.message}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    /** The server no longer accepts this phone: removed by an admin, or signed in elsewhere. */
    private fun onSignedOut(e: UnauthorizedException) {
        prefs.signedOut(e)
        stopSelf()
    }

    // ---- battery ----

    private fun batteryState(): Pair<Int, Boolean> {
        val i = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return -1 to false
        return batteryState(i)
    }

    private fun batteryState(i: Intent): Pair<Int, Boolean> {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        // "On a charger" is what matters (no low-battery alerts while plugged in). The plug
        // state is more reliable than the charging status, which some phones leave at
        // "charging"/"full" after unplugging.
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return pct to charging
    }

    // ---- the ongoing notification ----

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Thereabouts is running"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val family = prefs.familyName.ifEmpty { "your family" }
        val (title, text) = when {
            prefs.sosActive && prefs.sosPending -> "Sending SOS…" to "Keeps trying until your family's server gets it"
            prefs.sosActive -> "SOS active" to "Sharing your location every 15 seconds. Open the app to say you're OK."
            prefs.sharing -> "Sharing location with $family" to "Tap to open the map or pause sharing"
            else -> "Location sharing paused" to "You'll still get SOS and other alerts"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "Thereabouts"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_FROM_BACKGROUND = "from_background"
        private const val SOS_INTERVAL_MS = 15_000L
        private const val WAIT_SECONDS = 50 // under the 60 s idle timeout of common reverse proxies
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L

        @Volatile
        var isRunning = false
            private set

        /** Bumped when an SOS / "I'm OK" reaches the server, so the open map can update. */
        @Volatile
        var sosStateChanged = 0L

        fun hasLocationPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundPermission(ctx: Context) =
            Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Whether the service has anything to do: share location, listen for alerts or send an SOS. */
        fun needed(ctx: Context): Boolean {
            val prefs = Prefs(ctx)
            if (!prefs.joined) return false
            return (prefs.sharing && hasLocationPermission(ctx)) || prefs.instantAlerts ||
                prefs.sosPending || prefs.sosEndPending
        }

        /**
         * Starts the service, or tells a running one to pick up changed settings (interval,
         * pause, SOS). Stops it if there's nothing to do.
         */
        fun start(ctx: Context, fromBackground: Boolean = false) {
            if (!needed(ctx)) {
                stop(ctx)
                return
            }
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, LocationService::class.java).putExtra(EXTRA_FROM_BACKGROUND, fromBackground),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the background service", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }
    }
}
