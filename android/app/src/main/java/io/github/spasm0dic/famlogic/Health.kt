package io.github.spasm0dic.famlogic

import android.content.Context
import android.location.LocationManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import java.io.IOException

/**
 * Phone health: tells the server this phone's app version and anything that
 * could stop it sharing, so the dashboard can show problems early.
 */
object Health {
    private var lastSent = ""
    private var lastSentAt = 0L

    fun issues(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        if (!LocationService.hasLocationPermission(ctx)) out += "location"
        else if (!LocationService.hasBackgroundPermission(ctx)) out += "background"
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) out += "battery"
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) out += "notifications"
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) out += "location_off"
        return out
    }

    /** Sends the report if something changed, or the last one is over 6 hours old. Never throws. */
    suspend fun report(ctx: Context, prefs: Prefs) {
        if (!prefs.joined) return
        val issues = issues(ctx)
        val sig = BuildConfig.VERSION_NAME + issues.joinToString(",")
        val now = System.currentTimeMillis()
        if (sig == lastSent && now - lastSentAt < 6 * 3_600_000L) return
        try {
            Api(prefs.serverUrl, prefs.token).sendStatus(BuildConfig.VERSION_NAME, issues)
            lastSent = sig
            lastSentAt = now
        } catch (e: IOException) {
            // Older server (no status endpoint) or offline: try again later.
        }
    }
}
