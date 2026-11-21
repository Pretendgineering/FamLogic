package io.github.spasm0dic.thereabouts.pebble

import android.content.Context
import android.location.Location
import android.util.Log
import io.github.spasm0dic.thereabouts.Api
import io.github.spasm0dic.thereabouts.AppEvent
import io.github.spasm0dic.thereabouts.LocationService
import io.github.spasm0dic.thereabouts.Member
import io.github.spasm0dic.thereabouts.Prefs
import io.github.spasm0dic.thereabouts.Units
import io.github.spasm0dic.thereabouts.isDriving
import io.github.spasm0dic.thereabouts.parseColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * The phone side of the Thereabouts Pebble watchapp and watchface. The watch
 * never talks to the server: this sends it a compact view of everyone, passes
 * on alerts, and carries out what the watch asks for (check in, SOS, requests).
 *
 * The message format is shared with the C code in /pebble (keep in step!).
 */
object PebbleBridge {
    private const val TAG = "PebbleBridge"
    val APP_UUID: UUID = UUID.fromString("6d3c9f4e-2b8a-4c71-9a35-7e1f0b2d4c88")
    val FACE_UUID: UUID = UUID.fromString("a1f5e2c7-3d94-4b6e-8f02-5c9e7d1a3b64")

    // AppMessage keys
    const val K_CMD = 0
    const val K_IDX = 1
    const val K_COUNT = 2
    const val K_ID = 3
    const val K_NAME = 4
    const val K_STATUS = 5
    const val K_COLOR = 6
    const val K_FLAGS = 7
    const val K_TEXT = 8
    const val K_KIND = 9
    const val K_NUM = 10
    const val K_INITIAL = 11

    // Phone -> watch
    const val C_PERSON = 1
    const val C_ME = 2
    const val C_ALERT = 3
    const val C_RESULT = 4
    const val C_FACE = 5

    // Watch -> phone
    const val C_HELLO = 20
    const val C_CHECKIN = 21
    const val C_SOS = 22
    const val C_SOS_END = 23
    const val C_REQUEST = 24
    const val C_SEEN = 25
    const val C_SHARING = 26
    const val C_BATTERY = 27

    // Person flags
    const val F_ME = 1
    const val F_SOS = 2
    const val F_DRIVING = 4
    const val F_AWAY = 8 // paused or not heard from lately
    const val F_PLACE = 16 // at a fence

    // "Me" flags
    const val M_SHARING = 1
    const val M_SOS = 2
    const val M_HEART_RATE = 4
    const val M_FALLS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendLock = Mutex()
    @Volatile private var appOpen = false

    fun onAppOpened(context: Context, app: UUID) {
        if (app == APP_UUID) appOpen = true
        if (app == APP_UUID || app == FACE_UUID) push(context, app)
    }

    fun onAppClosed(app: UUID) {
        if (app == APP_UUID) appOpen = false
    }

    /** A message from the watch. Returns false only if it can't be handled at all. */
    fun onWatchMessage(context: Context, app: UUID, d: Map<Int, Any>): Boolean {
        if (app != APP_UUID && app != FACE_UUID) return false
        val prefs = Prefs(context)
        if (!prefs.joined) return false
        val cmd = (d[K_CMD] as? Long)?.toInt() ?: return false
        val text = d[K_TEXT] as? String ?: ""
        val id = d[K_ID] as? String ?: ""
        val num = (d[K_NUM] as? Long)?.toInt() ?: 0
        if (app == APP_UUID) appOpen = true
        scope.launch {
            val api = Api(prefs.serverUrl, prefs.token)
            try {
                when (cmd) {
                    C_HELLO, C_BATTERY -> {
                        if (num in 1..100) { prefs.watchBattery = num; prefs.watchBatteryAt = System.currentTimeMillis() }
                        if (cmd == C_HELLO) push(context, app)
                    }
                    C_CHECKIN -> {
                        api.checkIn(text.ifBlank { "Checked in from my watch" }, null)
                        result(context, "Checked in")
                    }
                    C_SOS -> {
                        startSos(context, prefs, source = (d[K_KIND] as? String).takeIf { it == "fall" } ?: "watch",
                            heartRate = if (prefs.pebbleHeartRate) num else 0)
                        result(context, "SOS sent")
                        push(context, APP_UUID)
                    }
                    C_SOS_END -> {
                        endSos(context, prefs)
                        result(context, "You're OK")
                        push(context, APP_UUID)
                    }
                    C_REQUEST -> {
                        val kind = if (d[K_KIND] == "call") "call" else "pickup"
                        api.request(id, kind, null)
                        result(context, if (kind == "call") "Asked them to call" else "Asked for a pick-up")
                    }
                    C_SEEN -> {
                        api.sosSeen(id)
                        result(context, "They know you've seen it")
                    }
                    C_SHARING -> {
                        val on = num != 0
                        api.setPaused(!on)
                        prefs.sharing = on
                        LocationService.start(context)
                        result(context, if (on) "Sharing your location" else "Sharing paused")
                        push(context, APP_UUID)
                    }
                }
            } catch (e: IOException) {
                result(context, e.message?.take(60) ?: "Couldn't reach the server")
            }
        }
        return true
    }

    /** The same path as the phone's SOS bar: retried by the service until it gets through. */
    private fun startSos(context: Context, prefs: Prefs, source: String, heartRate: Int) {
        prefs.sosActive = true
        prefs.sosPending = true
        prefs.sosEndPending = false
        prefs.sosSource = source
        prefs.sosHeartRate = heartRate
        prefs.sharing = true
        LocationService.start(context)
    }

    private fun endSos(context: Context, prefs: Prefs) {
        prefs.sosActive = false
        prefs.sosPending = false
        prefs.sosEndPending = true
        LocationService.start(context)
    }

    private suspend fun result(context: Context, text: String) {
        send(context, APP_UUID, mapOf(K_CMD to C_RESULT, K_TEXT to text.take(60)))
    }

    private suspend fun send(context: Context, app: UUID, dict: Map<Int, Any>): Boolean =
        sendLock.withLock { runCatching { PebbleKit.send(context, app, dict) }.getOrDefault(false) }

    /** Sends the current picture (you, then everyone) to the watchapp or watchface. */
    fun push(context: Context, app: UUID = APP_UUID) {
        val prefs = Prefs(context)
        if (!prefs.joined || PebbleKit.pebbleApps(context).isEmpty()) return
        scope.launch {
            val res = try {
                // A cursor ahead of the server's: no alerts are taken from the phone's queue.
                Api(prefs.serverUrl, prefs.token).members(since = Long.MAX_VALUE / 2)
            } catch (e: IOException) {
                Log.i(TAG, "No data for the watch: ${e.message}")
                return@launch
            }
            val me = res.members.find { it.id == prefs.memberId }
            val others = res.members.filter { it.id != prefs.memberId }
                .sortedWith(compareByDescending<Member> { it.sosSince > 0 }.thenBy { it.name.lowercase() })
            if (app == FACE_UUID) {
                send(context, FACE_UUID, mapOf(K_CMD to C_FACE, K_TEXT to faceLine(listOfNotNull(me) + others)))
                return@launch
            }
            var meFlags = 0
            if (prefs.sharing) meFlags = meFlags or M_SHARING
            if (prefs.sosActive) meFlags = meFlags or M_SOS
            if (prefs.pebbleHeartRate) meFlags = meFlags or M_HEART_RATE
            if (prefs.pebbleFalls) meFlags = meFlags or M_FALLS
            val myStatus = when {
                prefs.sosActive -> "SOS on" + (me?.sosSeenBy?.takeIf { it.isNotEmpty() }?.let { " · seen by ${it.joinToString(", ")}" } ?: "")
                !prefs.sharing -> "Sharing paused"
                else -> "Sharing · every ${prefs.intervalMinutes} min"
            }
            send(context, APP_UUID, mapOf(K_CMD to C_ME, K_FLAGS to meFlags, K_TEXT to myStatus, K_NAME to (me?.name ?: "")))
            val list = listOfNotNull(me) + others
            list.forEachIndexed { i, m ->
                send(context, APP_UUID, personMessage(m, i, list.size, m.id == prefs.memberId, me))
            }
            if (list.isEmpty()) send(context, APP_UUID, mapOf(K_CMD to C_PERSON, K_IDX to 0, K_COUNT to 0))
        }
    }

    private fun personMessage(m: Member, idx: Int, count: Int, isMe: Boolean, me: Member?): Map<Int, Any> {
        var flags = 0
        if (isMe) flags = flags or F_ME
        if (m.sosSince > 0) flags = flags or F_SOS
        if (isDriving(m)) flags = flags or F_DRIVING
        val stale = m.loc == null || m.paused || System.currentTimeMillis() - m.loc.time > 30 * 60_000
        if (stale) flags = flags or F_AWAY
        if (m.place.isNotEmpty()) flags = flags or F_PLACE
        return mapOf(
            K_CMD to C_PERSON, K_IDX to idx, K_COUNT to count, K_ID to m.id,
            K_NAME to m.name.take(24), K_INITIAL to m.name.trim().take(1).uppercase().ifEmpty { "?" },
            K_COLOR to (parseColor(m.color) and 0xFFFFFF), K_FLAGS to flags,
            K_STATUS to statusOf(m).take(40), K_TEXT to detailOf(m, me).take(80),
        )
    }

    private fun ago(t: Long): String {
        val min = (System.currentTimeMillis() - t) / 60_000
        return when {
            min < 1 -> "now"
            min < 60 -> "${min}m ago"
            min < 48 * 60 -> "${min / 60}h ago"
            else -> "${min / 1440}d ago"
        }
    }

    private fun statusOf(m: Member): String {
        val loc = m.loc
        return when {
            m.sosSince > 0 -> "SOS!"
            m.paused || loc == null -> "Sharing paused"
            isDriving(m) -> "Driving · ${ago(loc.time)}"
            m.place.isNotEmpty() -> "${m.place} · ${ago(loc.time)}"
            else -> ago(loc.time).replaceFirstChar { it.uppercase() }
        }
    }

    /** "1.2 mi NE · 40% · watch 30%", or who has seen an SOS. */
    private fun detailOf(m: Member, me: Member?): String {
        val parts = mutableListOf<String>()
        val loc = m.loc
        val mine = me?.loc
        if (loc != null && mine != null && m.id != me.id) {
            val out = FloatArray(2)
            Location.distanceBetween(mine.lat, mine.lon, loc.lat, loc.lon, out)
            parts += if (out[0] < 100) "nearby" else "${Units.distance(out[0].toDouble())} ${compass(out[1])}"
        }
        if (loc != null && loc.battery >= 0) parts += "${loc.battery}%" + if (loc.charging) "+" else ""
        if (loc != null && loc.watch > 0) parts += "watch ${loc.watch}%"
        if (m.sosSince > 0 && m.sosSeenBy.isNotEmpty()) parts += "seen by ${m.sosSeenBy.joinToString(", ")}"
        return parts.joinToString(" · ")
    }

    private fun compass(bearing: Float): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(((bearing % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
    }

    /** For the watchface: "S,ff8800,h;M,3355ff,d;" initial, colour, state (s sos, d driving, h at a fence, a away, o out). */
    private fun faceLine(list: List<Member>): String = list.take(8).joinToString("") { m ->
        val state = when {
            m.sosSince > 0 -> "s"
            isDriving(m) -> "d"
            m.paused || m.loc == null || System.currentTimeMillis() - m.loc.time > 30 * 60_000 -> "a"
            m.place.isNotEmpty() -> "h"
            else -> "o"
        }
        val initial = m.name.trim().take(1).uppercase().ifEmpty { "?" }
        "%s,%06x,%s;".format(initial, parseColor(m.color) and 0xFFFFFF, state)
    }

    /**
     * An alert arrived on the phone. SOS opens the watchapp's alarm screen; other
     * alerts refresh it if it's open, and become timeline pins.
     */
    fun onAlert(context: Context, e: AppEvent) {
        val prefs = Prefs(context)
        if (PebbleKit.pebbleApps(context).isEmpty()) return
        scope.launch {
            when (e.type) {
                "sos" -> {
                    PebbleKit.startApp(context, APP_UUID)
                    kotlinx.coroutines.delay(1_500) // let the app open
                    send(context, APP_UUID, mapOf(K_CMD to C_ALERT, K_KIND to "sos", K_ID to e.memberId, K_NAME to e.name.take(24),
                        K_TEXT to (if (e.note.isNotEmpty()) "“${e.note.take(60)}”" else "needs help")))
                }
                "sos_end", "sos_seen", "request" -> if (appOpen) {
                    val text = when (e.type) {
                        "sos_end" -> "is OK"
                        "sos_seen" -> "has seen your SOS"
                        else -> if (e.kind == "call") "asks you to call" else "asks for a pick-up"
                    }
                    send(context, APP_UUID, mapOf(K_CMD to C_ALERT, K_KIND to e.type, K_ID to e.memberId, K_NAME to e.name.take(24), K_TEXT to text))
                }
            }
            if (prefs.pebbleTimeline) pinFor(context, e)
            if (appOpen) push(context, APP_UUID)
            push(context, FACE_UUID)
        }
    }

    private suspend fun pinFor(context: Context, e: AppEvent) {
        val (title, icon) = when (e.type) {
            "arrived" -> "${e.name} arrived at ${e.place}" to "system://images/LOCATION"
            "left" -> "${e.name} left ${e.place}" to "system://images/LOCATION"
            "checkin" -> "${e.name} checked in" to "system://images/NOTIFICATION_FLAG"
            "trip" -> "${e.name} finished a trip" to "system://images/LOCATION"
            "reminder" -> e.note to "system://images/NOTIFICATION_REMINDER"
            "request" -> (if (e.kind == "call") "${e.name} asks you to call" else "${e.name} asks for a pick-up") to "system://images/NOTIFICATION_FLAG"
            "drive_start" -> "${e.name} started driving" to "system://images/LOCATION"
            else -> return
        }
        val body = when (e.type) {
            "checkin" -> e.note
            "trip" -> "${Units.duration(e.duration)} · ${Units.distance(e.distance)}"
            "reminder" -> if (e.kind == "leave") "Leaving ${e.place}" else "At ${e.place}"
            else -> ""
        }
        runCatching {
            PebbleKit.insertPin(context, APP_UUID, "thereabouts-${e.id}", Instant.ofEpochMilli(if (e.time > 0) e.time else System.currentTimeMillis()), title.take(60), body, icon)
        }
    }
}
