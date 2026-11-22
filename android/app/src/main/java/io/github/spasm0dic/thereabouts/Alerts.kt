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
import io.github.spasm0dic.thereabouts.pebble.PebbleBridge

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
        fresh.forEach {
            show(context, prefs, it)
            // The watch: SOS alarm screen, timeline pins (mutes apply, except to SOS).
            if (it.type == "sos" || it.memberId !in prefs.mutedPeople) PebbleBridge.onAlert(context, it)
        }
        // Only ever move forward: replies from the service and the map can arrive out of order.
        if (batch.cursor > seen) prefs.eventCursor = batch.cursor
        if (fresh.isNotEmpty()) lastArrival = System.currentTimeMillis()
    }

    private fun show(context: Context, prefs: Prefs, e: AppEvent) {
        when (e.type) {
            "battery_low" -> {
                // The server raises one alert at 15% and one at 10%; show the one this phone asked for.
                if (!prefs.notifyLowBattery || e.level != prefs.lowBatteryThreshold || e.memberId in prefs.mutedPeople) return
                val pct = if (e.battery >= 0) "${e.battery}%" else "low"
                notify(context, CHANNEL_ALERTS, eventId(e), "${e.name}'s battery is at $pct", "Their phone may turn off soon.", e.memberId)
            }
            "sos" -> {
                if (!prefs.notifySos) return
                val where = if (e.lat != 0.0 || e.lon != 0.0) "Tap to see where they are." else "Their location isn't known yet."
                val from = when (e.kind) {
                    "ring" -> " (from their ring)"
                    "watch" -> " (from their watch)"
                    "fall" -> " (their watch detected a fall)"
                    else -> ""
                }
                val said = if (e.note.isNotEmpty()) " They said: \u201c${e.note}\u201d" else ""
                val hr = if (e.heartRate > 0) " Heart rate ${e.heartRate}." else ""
                notify(
                    context, CHANNEL_SOS, sosId(e.memberId), "🆘 SOS from ${e.name}$from",
                    "${e.name} needs help.$said$hr $where", e.memberId, sos = true, seenFor = e.memberId,
                )
            }
            "sos_remote" -> {
                // Our own SOS, raised from the ring: switch this phone to SOS updates.
                if (!prefs.sosActive) {
                    prefs.sosActive = true
                    LocationService.start(context)
                }
                notify(
                    context, CHANNEL_ALERTS, 2_200_000, "SOS sent from your ring",
                    "Your family has been alerted. Open Thereabouts and tap I'm OK when you're safe.", "",
                )
            }
            "sos_seen" -> {
                notify(context, CHANNEL_ALERTS, eventId(e), "${e.name} has seen your SOS", "They know you need help.", e.memberId)
            }
            "request" -> {
                if (e.memberId in prefs.mutedPeople) return
                val (title, text) = requestText(e)
                notify(context, CHANNEL_ALERTS, eventId(e), title, text, e.memberId, hereFor = if (e.kind == "where") e.memberId else "")
            }
            "reminder" -> {
                val at = if (e.kind == "leave") "Leaving ${e.place}" else "At ${e.place}"
                notify(context, CHANNEL_ALERTS, eventId(e), e.note, "Reminder · $at", "")
            }
            "notice" -> notify(context, CHANNEL_ALERTS, eventId(e), e.name, e.note, "")
            "sos_end" -> {
                // Replaces (and silences) that person's SOS notification.
                if (!prefs.notifySos) return
                notify(context, CHANNEL_ALERTS, sosId(e.memberId), "${e.name} is OK", "They ended their SOS.", e.memberId)
            }
            "checkin" -> {
                if (!prefs.notifyCheckIn || e.memberId in prefs.mutedPeople) return
                val text = e.note.ifBlank { "Tap to see where." }
                notify(context, CHANNEL_ALERTS, eventId(e), "${e.name} checked in", text, e.memberId)
            }
            "arrived", "left" -> {
                val arriving = e.type == "arrived"
                if (arriving && !prefs.notifyArrivals || !arriving && !prefs.notifyDepartures) return
                if (e.placeId in prefs.mutedPlaces || e.memberId in prefs.mutedPeople || e.circleId in prefs.mutedGroups) return
                val title = if (arriving) "${e.name} arrived at ${e.place}" else "${e.name} left ${e.place}"
                notify(context, CHANNEL_ALERTS, eventId(e), title, "Tap to see the map.", e.memberId)
            }
            "trip" -> {
                if (!prefs.notifyTrips || e.memberId in prefs.mutedPeople) return
                val names = prefs.fenceNames
                val from = names[e.fromPlaceId]
                val to = names[e.placeId]
                val route = when {
                    from != null && to != null -> "$from → $to"
                    to != null -> "to $to"
                    from != null -> "from $from"
                    else -> "a trip"
                }
                notify(
                    context, CHANNEL_ALERTS, eventId(e), "${e.name} finished $route",
                    "${Units.duration(e.duration)} · ${Units.distance(e.distance)}. Tap to see where.", e.memberId,
                )
            }
            "drive_start" -> {
                if (!prefs.notifyDriving || e.memberId in prefs.mutedPeople) return
                notify(context, CHANNEL_ALERTS, eventId(e), "${e.name} started driving", "Tap to see where.", e.memberId)
            }
            "silent" -> {
                if (!prefs.notifySilent || e.memberId in prefs.mutedPeople) return
                val battery = if (e.battery >= 0) " Their battery was at ${e.battery}%." else ""
                notify(
                    context, CHANNEL_ALERTS, eventId(e), "${e.name}'s phone hasn't been in touch for a while",
                    "It may be off, out of battery or offline, or Thereabouts was stopped.$battery Tap to see where it was last.", e.memberId,
                )
            }
            // Unknown types come from a newer server: ignore them.
        }
    }

    /** Your own SOS couldn't be sent: say so loudly, so you try another way. */
    fun notifySosFailed(context: Context) {
        notify(
            context, CHANNEL_SOS, 2_100_000, "SOS was NOT sent",
            "Your family's Thereabouts server is too old for SOS. Call or message someone directly.", "", sos = true,
        )
    }

    /** Requests ("pick me up", "call me") and nudges (where are you, heart, thumbs up, here I am). */
    fun requestText(e: AppEvent): Pair<String, String> = when (e.kind) {
        "call" -> "${e.name} asks you to call them" to "Tap to see where they are."
        "where" -> "${e.name} asks where you are" to "Tap \"Send where I am\" to tell just them."
        "heart" -> "${e.name} sent you ❤️" to "Just a nudge."
        "thumbs" -> "${e.name} sent you 👍" to "Just a nudge."
        "here" -> "${e.name}: here I am" to (if (e.place.isNotEmpty()) "At ${e.place}. " else "") + "Tap to see them on the map."
        else -> "${e.name} asks you to pick them up" to "Tap to see where they are."
    }

    private fun eventId(e: AppEvent) = (e.id % 1_000_000).toInt() + 1000

    /** One notification slot per person for SOS, so "is OK" replaces their SOS. */
    fun sosId(memberId: String) = 2_000_000 + (memberId.hashCode() and 0xffff)

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
        /** Adds an "I've seen it" button that tells this person (their SOS). */
        seenFor: String = "",
        /** Adds a "Send where I am" button that answers this person's "where are you?". */
        hereFor: String = "",
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
                .setColor(0xFFC7354B.toInt())
        } else {
            b.setCategory(NotificationCompat.CATEGORY_STATUS)
        }
        if (seenFor.isNotEmpty()) {
            val seen = PendingIntent.getBroadcast(
                context, id, Intent(context, SeenReceiver::class.java).putExtra(SeenReceiver.EXTRA_MEMBER, seenFor),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, "I've seen it", seen)
        }
        if (hereFor.isNotEmpty()) {
            val here = PendingIntent.getBroadcast(
                context, id, Intent(context, SeenReceiver::class.java)
                    .setAction(SeenReceiver.ACTION_HERE).putExtra(SeenReceiver.EXTRA_MEMBER, hereFor).putExtra(SeenReceiver.EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, "Send where I am", here)
        }
        val n = b.build()
        // SOS keeps ringing until the notification is opened or swiped away.
        if (sos) n.flags = n.flags or Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
