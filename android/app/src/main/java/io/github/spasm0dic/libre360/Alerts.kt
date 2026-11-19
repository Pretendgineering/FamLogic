package io.github.spasm0dic.libre360

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Turns alerts from the server into Android notifications.
 *
 * Alerts arrive with location uploads (background) and map refreshes (app open),
 * so both paths call [handle]. The cursor makes sure each alert is shown once.
 */
object Alerts {
    private const val CHANNEL_ID = "alerts"

    @Synchronized
    fun handle(context: Context, batch: EventBatch?) {
        if (batch == null) return
        val prefs = Prefs(context)
        val seen = prefs.eventCursor
        if (batch.cursor < batch.sentSince) {
            // The server's data was reset and its alert numbering started over.
            prefs.eventCursor = batch.cursor
            return
        }
        if (seen >= 0) {
            batch.events.filter { it.id > seen }.forEach { show(context, prefs, it) }
        }
        // Only ever move forward: replies from the service and the map can arrive out of order.
        if (batch.cursor > seen) prefs.eventCursor = batch.cursor
    }

    private fun show(context: Context, prefs: Prefs, e: AppEvent) {
        when (e.type) {
            "battery_low" -> {
                // The server raises one alert at 15% and one at 10%; show the one this phone asked for.
                if (!prefs.notifyLowBattery || e.level != prefs.lowBatteryThreshold) return
                val pct = if (e.battery >= 0) "${e.battery}%" else "low"
                notify(context, e.id, "${e.name}'s battery is at $pct", "Their phone may turn off soon.")
            }
            // Unknown types come from a newer server: ignore them.
        }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Family alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Low battery and other alerts about your family"
            },
        )
    }

    private fun notify(context: Context, id: Long, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // never ring twice for the same alert
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        // Notification id = alert id, so a repeated alert replaces itself instead of stacking.
        NotificationManagerCompat.from(context).notify((id % Int.MAX_VALUE).toInt() + 1000, n)
    }
}
