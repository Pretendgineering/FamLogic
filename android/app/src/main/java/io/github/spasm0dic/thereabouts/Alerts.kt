package io.github.spasm0dic.thereabouts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Turns alerts from the server into Android notifications.
 *
 * Alerts arrive over the background service's open connection (within seconds),
 * with location uploads, and with map refreshes, so all of those call [handle].
 * The cursor makes sure each alert is shown once.
 */
object Alerts {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_SOS = "sos"

    /** Intent extra: open the map centred on this member. */
    const val EXTRA_FOCUS_MEMBER = "focus_member"

    /** Bumped whenever alerts arrive, so an open map refreshes straight away. */
    @Volatile
    var lastArrival = 0L

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
        val fresh = if (seen >= 0) batch.events.filter { it.id > seen } else emptyList()
        fresh.forEach { show(context, prefs, it) }
        // Only ever move forward: replies from the service and the map can arrive out of order.
        if (batch.cursor > seen) prefs.eventCursor = batch.cursor
        if (fresh.isNotEmpty()) lastArrival = System.currentTimeMillis()
    }

    private fun show(context: Context, prefs: Prefs, e: AppEvent) {
        when (e.type) {
            "battery_low" -> {
                // The server raises one alert at 15% and one at 10%; show the one this phone asked for.
                if (!prefs.notifyLowBattery || e.level != prefs.lowBatteryThreshold) return
                val pct = if (e.battery >= 0) "${e.battery}%" else "low"
                notify(context, CHANNEL_ALERTS, eventId(e), "${e.name}'s battery is at $pct", "Their phone may turn off soon.", e.memberId)
            }
            "sos" -> {
                if (!prefs.notifySos) return
                val where = if (e.lat != 0.0 || e.lon != 0.0) "Tap to see where they are." else "Their location isn't known yet."
                notify(
                    context, CHANNEL_SOS, sosId(e.memberId), "🆘 SOS from ${e.name}",
                    "${e.name} needs help. $where", e.memberId, sos = true,
                )
            }
            "sos_end" -> {
                // Replaces (and silences) that person's SOS notification.
                if (!prefs.notifySos) return
                notify(context, CHANNEL_ALERTS, sosId(e.memberId), "${e.name} is OK", "They ended their SOS.", e.memberId)
            }
            "checkin" -> {
                if (!prefs.notifyCheckIn) return
                val text = e.note.ifBlank { "Tap to see where." }
                notify(context, CHANNEL_ALERTS, eventId(e), "${e.name} checked in", text, e.memberId)
            }
            "arrived", "left" -> {
                val arriving = e.type == "arrived"
                if (arriving && !prefs.notifyArrivals || !arriving && !prefs.notifyDepartures) return
                if (e.placeId in prefs.mutedPlaces) return
                val title = if (arriving) "${e.name} arrived at ${e.place}" else "${e.name} left ${e.place}"
                notify(context, CHANNEL_ALERTS, eventId(e), title, "Tap to see the map.", e.memberId)
            }
            // Unknown types come from a newer server: ignore them.
        }
    }

    private fun eventId(e: AppEvent) = (e.id % 1_000_000).toInt() + 1000

    /** One notification slot per person for SOS, so "is OK" replaces their SOS. */
    private fun sosId(memberId: String) = 2_000_000 + (memberId.hashCode() and 0xffff)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Family alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Check-ins, low battery and other alerts about your family"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOS, "SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone in your family presses SOS. Rings like an alarm until you look."
                val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(
                    alarm,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /** Opens the map centred on [memberId]. */
    fun openMapIntent(context: Context, memberId: String?, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_FOCUS_MEMBER, memberId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notify(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        memberId: String,
        sos: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openMapIntent(context, memberId, id))
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
        if (sos) {
            b.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFFD32F2F.toInt())
        } else {
            b.setCategory(NotificationCompat.CATEGORY_STATUS)
        }
        val n = b.build()
        // SOS keeps ringing until the notification is opened or swiped away.
        if (sos) n.flags = n.flags or Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
