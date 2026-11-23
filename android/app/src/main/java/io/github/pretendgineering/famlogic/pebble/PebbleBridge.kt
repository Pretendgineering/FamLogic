package io.github.pretendgineering.famlogic.pebble

import android.content.Context
import android.content.res.Configuration
import android.location.Location
import android.util.Log
import io.github.pretendgineering.famlogic.Api
import io.github.pretendgineering.famlogic.AppEvent
import io.github.pretendgineering.famlogic.Alerts
import io.github.pretendgineering.famlogic.LocationService
import io.github.pretendgineering.famlogic.MapStyles
import io.github.pretendgineering.famlogic.Member
import io.github.pretendgineering.famlogic.Place
import io.github.pretendgineering.famlogic.Prefs
import io.github.pretendgineering.famlogic.Units
import io.github.pretendgineering.famlogic.isDriving
import io.github.pretendgineering.famlogic.parseColor
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
 * The phone side of the FamLogic Pebble watchapp and watchface. The watch
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
    const val K_STATE = 12 // "s" SOS, "d" driving, "m" moving, "t" still, "a" away (paused or not heard from)
    const val K_BEARING = 13 // degrees from you, 0 = north
    const val K_DIST = 14 // metres from you, -1 unknown
    const val K_LABEL = 15 // one word for where they are: a fence name, "Driving", "Out", "Paused", "3h"
    const val K_DATA = 16 // map bytes
    const val K_W = 17
    const val K_H = 18
    const val K_STRIDE = 19
    const val K_CHUNK = 20 // the largest map piece the watch can take
    const val K_RADIUS = 21 // a fence's radius (m)

    // Phone -> watch
    const val C_PERSON = 1
    const val C_ME = 2
    const val C_ALERT = 3
    const val C_RESULT = 4
    const val C_FACE = 5
    const val C_FENCE = 6
    const val C_MAP = 7 // a piece of a map picture: K_IDX offset, K_COUNT total, K_DATA bytes
    const val C_MAP_FAIL = 8

    // Watch -> phone
    const val C_HELLO = 20
    const val C_CHECKIN = 21
    const val C_SOS = 22
    const val C_SOS_END = 23
    const val C_REQUEST = 24
    const val C_SEEN = 25
    const val C_SHARING = 26
    const val C_BATTERY = 27
    const val C_MAP_REQ = 28 // K_ID person, K_W/K_H/K_STRIDE, K_KIND "c" colour or "b" black and white, K_NUM zoom

    // Person flags
    const val F_ME = 1
    const val F_SOS = 2
    const val F_DRIVING = 4
    const val F_AWAY = 8 // paused or not heard from lately
    const val F_PLACE = 16 // at a fence
    const val F_ADULT = 32 // you and they are both grown-ups (the grown-up nudges are on offer)

    // "Me" flags
    const val M_SHARING = 1
    const val M_SOS = 2
    const val M_HEART_RATE = 4
    const val M_FALLS = 8
    const val M_LIGHT = 16 // light theme (else dark)
    const val M_QUICK_SOS = 32 // Quick Launch opens the SOS screen
    const val M_MILES = 64 // show distances in miles

    /** Nudges and requests the watch may send. */
    private val REQUEST_KINDS = setOf("pickup", "call", "where", "here") + io.github.pretendgineering.famlogic.Nudges.all.map { it.kind }

    /** Everyone, as last sent to the watch (for check-in replies). */
    @Volatile private var lastMembers: List<Member> = emptyList()

    private fun adult(role: String?) = role == "parent" || role == "grandparent"

    /** The app's colour theme, as the watchface's number for it. */
    private fun themeNumber(prefs: Prefs) = when (prefs.appTheme) {
        "paper", "nightshift", "couch" -> 1 // the watch has five; newer themes use the closest
        "forest" -> 2
        "ocean", "hannah" -> 3
        "contrast", "noir" -> 4
        else -> 0
    }

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
                        val kind = (d[K_KIND] as? String)?.takeIf { it in REQUEST_KINDS } ?: return@launch
                        val loc = if (kind == "pickup" || kind == "here") io.github.pretendgineering.famlogic.currentLocation(context) else null
                        api.request(id, kind, loc)
                        result(context, when (kind) {
                            "call" -> "Asked them to call"
                            "where" -> "Asked where they are"
                            "here" -> "Sent where you are"
                            "pickup" -> "Asked for a pick-up"
                            else -> "Sent"
                        })
                    }
                    C_MAP_REQ -> sendMap(context, prefs, api, id, d)
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

    /** Light or dark on the watch: the user's choice, or the phone's own mode. */
    private fun light(context: Context, prefs: Prefs): Boolean = when (prefs.pebbleTheme) {
        "light" -> true
        "dark" -> false
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
    }

    /** Sends the current picture (you, then everyone, then nearby fences) to the watchapp or watchface. */
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
            lastMembers = res.members
            val me = res.members.find { it.id == prefs.memberId }
            val others = res.members.filter { it.id != prefs.memberId }
                .sortedWith(compareByDescending<Member> { it.sosSince > 0 }.thenBy { it.name.lowercase() })
            val list = listOfNotNull(me) + others
            val lightFlag = if (light(context, prefs)) M_LIGHT else 0
            if (app == FACE_UUID) {
                val sos = others.firstOrNull { it.sosSince > 0 }
                send(context, FACE_UUID, mapOf(
                    K_CMD to C_FACE, K_FLAGS to lightFlag, K_NUM to themeNumber(prefs), K_TEXT to faceLine(list),
                    K_NAME to (sos?.name?.take(24) ?: ""), K_STATUS to (sos?.let { sosWhere(it, me) } ?: ""),
                ))
                return@launch
            }
            var meFlags = lightFlag
            if (prefs.sharing) meFlags = meFlags or M_SHARING
            if (prefs.sosActive) meFlags = meFlags or M_SOS
            if (prefs.pebbleHeartRate) meFlags = meFlags or M_HEART_RATE
            if (prefs.pebbleFalls) meFlags = meFlags or M_FALLS
            if (prefs.pebbleQuickSos) meFlags = meFlags or M_QUICK_SOS
            if (Units.imperial) meFlags = meFlags or M_MILES
            val myStatus = when {
                prefs.sosActive -> "SOS on" + (me?.sosSeenBy?.takeIf { it.isNotEmpty() }?.let { " · seen by ${it.joinToString(", ")}" } ?: "")
                !prefs.sharing -> "Sharing paused"
                else -> "Sharing · every ${prefs.intervalMinutes} min"
            }
            send(context, APP_UUID, mapOf(K_CMD to C_ME, K_FLAGS to meFlags, K_TEXT to myStatus, K_NAME to (me?.name ?: "")))
            list.forEachIndexed { i, m ->
                send(context, APP_UUID, personMessage(m, i, list.size, m.id == prefs.memberId, me))
            }
            if (list.isEmpty()) send(context, APP_UUID, mapOf(K_CMD to C_PERSON, K_IDX to 0, K_COUNT to 0))
            // Fences near you, for the radar.
            val mine = me?.loc
            val fences = if (mine == null) emptyList() else res.places
                .map { it to distance(mine.lat, mine.lon, it.lat, it.lon) }
                .filter { it.second[0] < 50_000 }
                .sortedBy { it.second[0] }
                .take(8)
            fences.forEachIndexed { i, (p, d) -> send(context, APP_UUID, fenceMessage(p, d, i, fences.size)) }
            if (fences.isEmpty()) send(context, APP_UUID, mapOf(K_CMD to C_FENCE, K_IDX to 0, K_COUNT to 0))
        }
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): FloatArray =
        FloatArray(2).also { Location.distanceBetween(lat1, lon1, lat2, lon2, it) }

    private fun fenceMessage(p: Place, d: FloatArray, idx: Int, count: Int): Map<Int, Any> = mapOf(
        K_CMD to C_FENCE, K_IDX to idx, K_COUNT to count, K_NAME to p.name.take(24),
        K_BEARING to ((d[1].toInt() % 360 + 360) % 360), K_DIST to d[0].toInt(), K_RADIUS to p.radius.toInt(),
    )

    private fun personMessage(m: Member, idx: Int, count: Int, isMe: Boolean, me: Member?): Map<Int, Any> {
        var flags = 0
        if (isMe) flags = flags or F_ME
        if (m.sosSince > 0) flags = flags or F_SOS
        if (isDriving(m)) flags = flags or F_DRIVING
        if (stale(m)) flags = flags or F_AWAY
        if (m.place.isNotEmpty()) flags = flags or F_PLACE
        if (!isMe && adult(me?.role) && adult(m.role)) flags = flags or F_ADULT
        var bearing = 0
        var dist = -1
        val loc = m.loc
        val mine = me?.loc
        if (isMe && loc != null) dist = 0
        else if (loc != null && mine != null && !m.paused) {
            val d = distance(mine.lat, mine.lon, loc.lat, loc.lon)
            bearing = (d[1].toInt() % 360 + 360) % 360
            dist = d[0].toInt()
        }
        return mapOf(
            K_CMD to C_PERSON, K_IDX to idx, K_COUNT to count, K_ID to m.id,
            K_NAME to m.name.take(24), K_INITIAL to m.name.trim().take(1).uppercase().ifEmpty { "?" },
            K_COLOR to (parseColor(m.color) and 0xFFFFFF), K_FLAGS to flags,
            K_STATUS to statusOf(m).take(40), K_TEXT to detailOf(m, me).take(80),
            K_STATE to stateOf(m), K_LABEL to labelOf(m).take(24), K_BEARING to bearing, K_DIST to dist,
        )
    }

    private fun stale(m: Member) = m.loc == null || m.paused || System.currentTimeMillis() - m.loc.time > 30 * 60_000

    /** Driving, moving (walking or faster on the last recent fix) or still; away if paused or quiet. */
    private fun stateOf(m: Member): String = when {
        m.sosSince > 0 -> "s"
        isDriving(m) -> "d"
        stale(m) -> "a"
        (m.loc?.speed ?: 0.0) >= 1.0 && System.currentTimeMillis() - (m.loc?.time ?: 0) < 10 * 60_000 -> "m"
        else -> "t"
    }

    /** One word for where someone is. */
    private fun labelOf(m: Member): String {
        val loc = m.loc
        return when {
            m.sosSince > 0 -> "SOS"
            m.paused -> "Paused"
            loc == null -> "Unknown"
            System.currentTimeMillis() - loc.time > 30 * 60_000 -> shortAgo(loc.time)
            m.place.isNotEmpty() -> m.place
            isDriving(m) -> "Driving"
            else -> "Out"
        }
    }

    private fun shortAgo(t: Long): String {
        val min = (System.currentTimeMillis() - t) / 60_000
        return when {
            min < 60 -> "${min}m"
            min < 48 * 60 -> "${min / 60}h"
            else -> "${min / 1440}d"
        }
    }

    /** For an SOS on the watchface: where they are, or were. */
    private fun sosWhere(m: Member, me: Member?): String {
        val loc = m.loc ?: return "Location unknown"
        val where = when {
            m.place.isNotEmpty() -> "At ${m.place}"
            me?.loc != null -> {
                val d = distance(me.loc.lat, me.loc.lon, loc.lat, loc.lon)
                if (d[0] < 100) "Near you" else "${Units.distance(d[0].toDouble())} ${compass(d[1])}"
            }
            else -> "Location shared"
        }
        return "$where · ${ago(loc.time)}"
    }

    private fun ago(t: Long): String = io.github.pretendgineering.famlogic.Seen.short(t).let { if (it == "now") it else "$it ago" }

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
            val out = distance(mine.lat, mine.lon, loc.lat, loc.lon)
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

    /** For the watchface: "S,ff8800,t,Home;M,3355ff,d,Driving;" initial, colour, state, label (up to 8 people). */
    private fun faceLine(list: List<Member>): String = list.take(8).joinToString("") { m ->
        val initial = m.name.trim().take(1).uppercase().ifEmpty { "?" }
        val label = labelOf(m).replace(Regex("[,;]"), " ").take(20)
        "%s,%06x,%s,%s;".format(initial, parseColor(m.color) and 0xFFFFFF, stateOf(m), label)
    }

    /** A street map around someone, sent in pieces the watch's inbox can take. */
    private suspend fun sendMap(context: Context, prefs: Prefs, api: Api, id: String, d: Map<Int, Any>) {
        fun int(k: Int) = (d[k] as? Long)?.toInt() ?: 0
        val w = int(K_W)
        val h = int(K_H)
        val stride = int(K_STRIDE)
        val zoom = int(K_NUM).coerceIn(10, 17)
        val chunk = int(K_CHUNK).coerceIn(200, 8000)
        val color = d[K_KIND] == "c"
        if (w !in 16..300 || h !in 16..300 || stride < (if (color) w else (w + 7) / 8)) return
        fun fail(why: String) = mapOf(K_CMD to C_MAP_FAIL, K_ID to id, K_NUM to zoom, K_TEXT to why)
        val m = try {
            api.members(since = Long.MAX_VALUE / 2).members.find { it.id == id }
        } catch (e: IOException) {
            send(context, APP_UUID, fail("Couldn't reach the server")); return
        }
        val loc = m?.loc
        if (m == null || loc == null || m.paused) { send(context, APP_UUID, fail("Location not shared")); return }
        val tiles = MapStyles.resolve(prefs.mapStyle, false, prefs.tileUrl, prefs.tileUrlDark, "", false).url
        val bytes = runCatching { PebbleMap.render(context, tiles, loc.lat, loc.lon, zoom, w, h, stride, color) }.getOrNull()
        if (bytes == null) { send(context, APP_UUID, fail("Couldn't load the map")); return }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + chunk)
            val ok = send(context, APP_UUID, mapOf(
                K_CMD to C_MAP, K_ID to id, K_NUM to zoom, K_IDX to offset, K_COUNT to bytes.size,
                K_DATA to bytes.copyOfRange(offset, end),
            ))
            if (!ok) return // the watch left the map (or the app)
            offset = end
        }
    }

    /**
     * An alert arrived on the phone. SOS opens the watchapp's alarm screen; other
     * alerts show in it if it's open, and become timeline pins.
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
                        K_TEXT to (if (e.note.isNotEmpty()) "says “${e.note.take(56)}”" else "needs help")))
                }
                // A check-in: the watch offers a nudge back (👍 ❤️ 👋 😊).
                "checkin" -> if (appOpen && e.memberId != prefs.memberId && e.memberId !in prefs.mutedPeople) {
                    val them = lastMembers.find { it.id == e.memberId }
                    val bothAdults = adult(prefs.role) && adult(them?.role)
                    send(context, APP_UUID, mapOf(K_CMD to C_ALERT, K_KIND to if (bothAdults) "checkin_a" else "checkin",
                        K_ID to e.memberId, K_NAME to e.name.take(24),
                        K_TEXT to (if (e.note.isNotEmpty()) "checked in: ${e.note.take(50)}" else "checked in")))
                }
                "sos_end", "sos_seen", "request" -> if (appOpen) {
                    val text = when (e.type) {
                        "sos_end" -> "is OK"
                        "sos_seen" -> "has seen your SOS"
                        else -> when (e.kind) {
                            "call" -> "asks you to call"
                            "where" -> "asks where you are"
                            in io.github.pretendgineering.famlogic.Nudges.all.map { it.kind } ->
                                "sent you " + io.github.pretendgineering.famlogic.Nudges.byKind(e.kind)!!.words
                            "here" -> if (e.place.isNotEmpty()) "is at ${e.place}" else "shared where they are"
                            else -> "asks for a pick-up"
                        }
                    }
                    // "where" gets a reply menu on the watch; the rest are a buzz and a message.
                    val kind = if (e.type == "request" && e.kind == "where") "where" else e.type
                    send(context, APP_UUID, mapOf(K_CMD to C_ALERT, K_KIND to kind, K_ID to e.memberId, K_NAME to e.name.take(24), K_TEXT to text))
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
            "request" -> Alerts.requestText(e).first.replace("❤️", "a heart").replace("👍", "a thumbs up") to "system://images/NOTIFICATION_FLAG"
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
            PebbleKit.insertPin(context, APP_UUID, "famlogic-${e.id}", Instant.ofEpochMilli(if (e.time > 0) e.time else System.currentTimeMillis()), title.take(60), body, icon)
        }
    }
}
