package io.github.spasm0dic.libre360

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Foreground service that listens for location fixes and sends them to the server.
 *
 * It uses Android's own LocationManager (not Google Play Services), so it also works
 * on de-Googled phones. Where the phone offers a "fused" provider (Android 12+) we use
 * that, because it picks GPS/Wi-Fi/cell for good battery life. Otherwise we listen to
 * the network and GPS providers and send at most one fix per interval.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uploadLock = Mutex()
    private lateinit var prefs: Prefs
    private lateinit var lm: LocationManager

    private var lastSentElapsed = 0L // SystemClock.elapsedRealtime() of the last successful upload
    private var lastSent: Location? = null

    private val listener = LocationListenerCompat { onFix(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.joined || !prefs.sharing || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // E.g. Android refused because we were started from the background without
            // "Allow all the time". The app will start us again when it's opened.
            Log.w(TAG, "Could not start in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // removing updates is harmless; guarded anyway
    override fun onDestroy() {
        runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startUpdates() {
        runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
        val intervalMs = prefs.intervalMinutes * 60_000L
        // "Balanced" lets the phone use Wi-Fi/cell positioning, which is cheap on battery.
        // But Android's built-in fused provider only turns on GPS for high-accuracy requests,
        // so on phones with no network location (e.g. GrapheneOS without it enabled) a
        // balanced request would never produce a fix. Ask for GPS in that case.
        val hasNetworkLocation = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val quality = if (hasNetworkLocation) LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
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
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { onFix(it, force = true) }
    }

    private fun onFix(loc: Location, force: Boolean = false) {
        val intervalMs = prefs.intervalMinutes * 60_000L
        val sinceLast = SystemClock.elapsedRealtime() - lastSentElapsed
        val movedFar = lastSent?.let { it.distanceTo(loc) > 250 } ?: true
        // Several providers may report; send at most once per interval unless we moved a lot.
        if (!force && lastSentElapsed != 0L && sinceLast < intervalMs - 15_000 && !movedFar) return
        upload(loc)
    }

    private fun upload(loc: Location) = scope.launch {
        if (!uploadLock.tryLock()) return@launch // an upload is already in flight
        try {
            val (battery, charging) = batteryState()
            Api(prefs.serverUrl, prefs.token).sendLocation(
                loc.latitude, loc.longitude, loc.accuracy, loc.speed, loc.time, battery, charging,
            )
            lastSentElapsed = SystemClock.elapsedRealtime()
            lastSent = loc
        } catch (e: UnauthorizedException) {
            onRemoved()
        } catch (e: IOException) {
            // Offline or server down: the next fix will try again.
            Log.i(TAG, "Upload failed: ${e.message}")
        } finally {
            uploadLock.unlock()
        }
    }

    /** The server no longer knows this phone (an admin removed it). */
    private fun onRemoved() {
        prefs.clearMembership()
        prefs.removedNotice = true
        stopSelf()
    }

    private fun batteryState(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your location is being shared"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val family = prefs.familyName.ifEmpty { "your family" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sharing location with $family")
            .setContentText("Tap to open the map or pause sharing")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "Libre360"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        fun hasLocationPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundPermission(ctx: Context) =
            Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Start (or restart, e.g. after the interval changed) if the person wants to share. */
        fun start(ctx: Context) {
            val prefs = Prefs(ctx)
            if (!prefs.joined || !prefs.sharing || !hasLocationPermission(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start location service", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }
    }
}
