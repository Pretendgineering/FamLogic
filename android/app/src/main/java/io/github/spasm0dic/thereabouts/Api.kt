package io.github.spasm0dic.thereabouts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class MemberLocation(
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val battery: Int,
    val charging: Boolean,
    val time: Long,
    /** Their phone is updating less often to save its low battery. */
    val saving: Boolean = false,
    /** Their phone thinks they're driving. */
    val driving: Boolean = false,
    /** Their Pebble watch's battery, or 0 if none. */
    val watch: Int = 0,
    /** Speed at this fix (m/s), 0 if unknown. */
    val speed: Double = 0.0,
)

data class Member(
    val id: String,
    val name: String,
    val color: String,
    val paused: Boolean,
    val loc: MemberLocation?,
    /** Shown instead of their initial, if they picked one. */
    val emoji: String = "",
    /** When this person's active SOS started (unix ms), or 0 if none. */
    val sosSince: Long = 0,
    /** Who has seen their SOS (names). */
    val sosSeenBy: List<String> = emptyList(),
    /** Circles this person shares with you (for yourself: all of yours). */
    val circles: List<String> = emptyList(),
    /** The place they're at (e.g. "Home"), if any. */
    val place: String = "",
    /** Whether they keep a trail you can look at, and trips. */
    val trail: Boolean = false,
    val trips: Boolean = false,
)

/** A note to yourself for arriving at (or leaving) a fence. */
data class PlaceReminder(val id: String, val placeId: String, val text: String, val onLeave: Boolean)

/** Webhook links for a Pebble Index 01 ring, one per action, and the optional signing secret. */
data class IndexLinks(val sos: String, val checkin: String, val remind: String, val signingSecret: String)

/** An address search result. */
data class GeoResult(val name: String, val lat: Double, val lon: Double)

/** One spot on someone's trail. */
data class TrailPoint(val lat: Double, val lon: Double, val time: Long)

/** One journey between two stops. [from]/[to] are fence names; [fromNear]/[toNear] the nearest street. */
data class Trip(
    val start: Long,
    val end: Long,
    val distance: Double,
    val from: String,
    val to: String,
    val fromNear: String,
    val toNear: String,
    val avgSpeed: Double,
    val topSpeed: Double,
    val ongoing: Boolean,
    val points: List<TrailPoint>,
) {
    /** "Home → School", "Home → near Main Street", "Somewhere → …" */
    fun title(): String {
        val a = from.ifEmpty { fromNear.ifEmpty { "Start" }.let { if (fromNear.isNotEmpty()) "near $it" else it } }
        val b = when {
            ongoing -> "on the way"
            to.isNotEmpty() -> to
            toNear.isNotEmpty() -> "near $toNear"
            else -> "a stop"
        }
        return "$a → $b"
    }
}

/** A named area (Home, School...) in one of your circles. */
data class Place(
    val id: String,
    val circleId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radius: Double,
    /** When it announces arrivals and departures; null = always. */
    val schedule: FenceSchedule? = null,
)

/** Alert hours for a fence, e.g. weekdays 07:00-17:00. Days: 0 = Sunday ... 6 = Saturday. */
data class FenceSchedule(val days: Set<Int>, val from: String, val to: String, val tz: String) {
    fun toJson(): JSONObject = JSONObject().put("days", org.json.JSONArray(days.sorted())).put("from", from).put("to", to).put("tz", tz)

    companion object {
        fun parse(o: JSONObject?): FenceSchedule? {
            if (o == null) return null
            val arr = o.optJSONArray("days") ?: return null
            return FenceSchedule((0 until arr.length()).map { arr.getInt(it) }.toSet(), o.optString("from"), o.optString("to"), o.optString("tz"))
        }
    }
}

/** A circle you're in, and whether you share your location with it. */
data class CircleInfo(val id: String, val name: String, val sharing: Boolean)

/** [features] lists what the server can do; null for servers too old to say (see [Api.inferFeatures]). */
data class ServerInfo(
    val name: String,
    val tileUrl: String,
    val tileAttribution: String,
    val features: Set<String>? = null,
    /** Tiles for dark mode; "" if the server has no dark style. */
    val tileUrlDark: String = "",
    val version: String = "",
)

/** An alert from the server, e.g. someone's battery is low. */
data class AppEvent(
    val id: Long,
    val type: String,
    val memberId: String,
    val name: String,
    val battery: Int,
    val level: Int,
    val time: Long,
    /** Check-in note. */
    val note: String = "",
    /** Place alerts: the place's name and id, and the circle it belongs to. */
    val place: String = "",
    val placeId: String = "",
    val circleId: String = "",
    /** Trip alerts: where it started (a fence id), how far (m) and how long (ms). */
    val fromPlaceId: String = "",
    val distance: Double = 0.0,
    val duration: Long = 0,
    /** Meant for one person only (receipts, requests, reminders). */
    val to: String = "",
    /** request: "pickup" or "call"; sos: "ring", "watch" or "fall"; reminder: "arrive" or "leave". */
    val kind: String = "",
    /** SOS from a watch: heart rate, if shared. */
    val heartRate: Int = 0,
    /** Where it happened (SOS, check-in), or 0/0 if unknown. */
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

/**
 * New alerts plus the cursor to send next time. [sentSince] is the cursor we asked with,
 * so a reply whose cursor is lower than that means the server's data was reset.
 */
data class EventBatch(val events: List<AppEvent>, val cursor: Long, val sentSince: Long)

data class MembersResponse(
    val me: String,
    val members: List<Member>,
    val server: ServerInfo,
    val shareLowBattery: Boolean,
    val events: EventBatch?,
    /** This person's username; empty if they joined before accounts existed. */
    val username: String,
    val hasPassword: Boolean,
    val circles: List<CircleInfo> = emptyList(),
    val places: List<Place> = emptyList(),
    val shareArrivals: Boolean = true,
    /** What the server can do (e.g. "sos", "places"). */
    val features: Set<String> = emptySet(),
    /** How many hours of trail you keep for others (0 = off). */
    val trailHours: Int = 0,
    /** How many days of trips you keep (0 = off). */
    val tripDays: Int = 0,
)

/** What join, sign-in and password resets return: this phone is now signed in as [member]. */
data class JoinResponse(val token: String, val member: Member, val server: ServerInfo, val username: String)

/** What an invite code is for: joining as someone new, or resetting an existing person's sign-in. */
data class InviteInfo(val isReset: Boolean, val name: String, val username: String, val server: ServerInfo, val circle: String = "")

/** The server rejected our token: this phone was removed from the family. */
/**
 * The server no longer accepts this phone. [signedInElsewhere] is true when that's because
 * the same person signed in on another phone (the latest sign-in wins), false when an admin
 * removed them.
 */
class UnauthorizedException(val signedInElsewhere: Boolean) :
    IOException(if (signedInElsewhere) "You signed in on another device" else "This phone is no longer part of the family")

/** The server answered with an error message meant for people. */
class ApiException(val status: Int, message: String) : IOException(message) {
    /** The server doesn't have this endpoint at all: it's older than this app. */
    val serverTooOld get() = message == OUTDATED_SERVER
}

const val OUTDATED_SERVER = "This needs a newer Thereabouts server. Ask your family's admin to update it."

/**
 * Tiny client for the Thereabouts server API. Uses only what ships with Android
 * (HttpURLConnection + org.json) so there are no extra dependencies.
 */
class Api(private val baseUrl: String, private val token: String = "") {

    suspend fun info(): ServerInfo = parseServer(request("GET", "/api/info"))

    /** Looks up what an invite code is for, without using it up. */
    suspend fun inviteInfo(code: String): InviteInfo {
        val o = request("GET", "/api/invites/" + java.net.URLEncoder.encode(code.trim(), "UTF-8"))
        return InviteInfo(
            isReset = o.optString("kind") == "reset",
            name = o.optString("name"),
            username = o.optString("username"),
            server = parseServer(o.getJSONObject("server")),
            circle = o.optString("circle"),
        )
    }

    /**
     * Uses an invite code. For a normal invite this creates the person (with [name], [username]
     * and [password]); for a reset link it sets a new [password] for the existing person.
     */
    suspend fun join(code: String, name: String, username: String, password: String): JoinResponse {
        val body = JSONObject().put("code", code).put("name", name)
            .put("username", username).put("password", password)
        return parseSignedIn(request("POST", "/api/join", body))
    }

    /** Signs this phone in as an existing person. Any other phone they were using is signed out. */
    suspend fun signIn(username: String, password: String): JoinResponse {
        val body = JSONObject().put("username", username).put("password", password)
        return parseSignedIn(request("POST", "/api/signin", body))
    }

    /**
     * Sets a password (people who joined before accounts existed, who also pick a [username])
     * or changes it (needs [currentPassword]). Returns the username.
     */
    suspend fun setPassword(currentPassword: String, newPassword: String, username: String): String {
        val body = JSONObject().put("currentPassword", currentPassword)
            .put("newPassword", newPassword).put("username", username)
        return request("POST", "/api/me/password", body).optString("username")
    }

    private fun parseSignedIn(o: JSONObject) = JoinResponse(
        token = o.getString("token"),
        member = parseMember(o.getJSONObject("member")),
        server = parseServer(o.getJSONObject("server")),
        username = o.optString("username"),
    )

    /** Everyone's latest position, plus any alerts newer than [since]. */
    suspend fun members(since: Long): MembersResponse {
        val o = request("GET", "/api/members?since=$since")
        val arr = o.getJSONArray("members")
        val list = (0 until arr.length()).map { parseMember(arr.getJSONObject(it)) }
        val settings = o.optJSONObject("settings")
        return MembersResponse(
            me = o.getString("me"),
            members = list,
            server = parseServer(o.getJSONObject("server")),
            shareLowBattery = settings?.optBoolean("shareLowBattery", true) ?: true,
            username = settings?.optString("username", "").orEmpty(),
            hasPassword = settings?.optBoolean("hasPassword", false) ?: false,
            circles = o.optJSONArray("circles")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    CircleInfo(c.getString("id"), c.optString("name"), c.optBoolean("sharing", true))
                }
            } ?: emptyList(),
            events = parseEvents(o, since),
            places = o.optJSONArray("places")?.let { arr -> (0 until arr.length()).map { parsePlace(arr.getJSONObject(it)) } }
                ?: emptyList(),
            shareArrivals = settings?.optBoolean("shareArrivals", true) ?: true,
            features = inferFeatures(o),
            trailHours = settings?.optInt("trailHours", 0) ?: 0,
            tripDays = settings?.optInt("tripDays", 0) ?: 0,
        )
    }

    /**
     * What the server supports. Newer servers say so; for older ones it's worked out
     * from what their replies contain.
     */
    private fun inferFeatures(o: JSONObject): Set<String> {
        o.optJSONObject("server")?.optJSONArray("features")?.let { arr ->
            return (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
        return when {
            o.has("places") -> setOf("accounts", "instant-alerts", "sos", "checkin", "circles", "places", "trails")
            o.has("circles") -> setOf("accounts", "instant-alerts", "sos", "checkin", "circles")
            o.optJSONObject("settings")?.has("hasPassword") == true -> setOf("accounts")
            else -> emptySet()
        }
    }

    private fun parsePlace(p: JSONObject) = Place(
        id = p.getString("id"),
        circleId = p.optString("circleId"),
        name = p.optString("name"),
        lat = p.getDouble("lat"),
        lon = p.getDouble("lon"),
        radius = p.optDouble("radius", 150.0),
        schedule = FenceSchedule.parse(p.optJSONObject("schedule")),
    )

    /** [withSchedule]: false for servers without fence schedules (the field is left out). */
    suspend fun createPlace(circleId: String, name: String, lat: Double, lon: Double, radius: Double, schedule: FenceSchedule?, withSchedule: Boolean): Place {
        val body = JSONObject().put("circleId", circleId).put("name", name).put("lat", lat).put("lon", lon).put("radius", radius)
        if (withSchedule && schedule != null) body.put("schedule", schedule.toJson())
        return parsePlace(request("POST", "/api/places", body))
    }

    suspend fun updatePlace(id: String, name: String, radius: Double, lat: Double, lon: Double, schedule: FenceSchedule?, withSchedule: Boolean): Place {
        val body = JSONObject().put("name", name).put("radius", radius).put("lat", lat).put("lon", lon)
        if (withSchedule) body.put("schedule", schedule?.toJson() ?: JSONObject.NULL)
        return parsePlace(request("POST", "/api/places/" + enc(id), body))
    }

    /** Address search (if the server offers it): "123 Main St" -> places to choose from. */
    suspend fun geocode(query: String): List<GeoResult> {
        val arr = requestArray("GET", "/api/geocode?q=" + enc(query))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            GeoResult(o.optString("name"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    suspend fun deletePlace(id: String) {
        request("DELETE", "/api/places/" + enc(id))
    }

    /** Someone's trail, oldest point first (empty if they don't keep one). */
    suspend fun trail(memberId: String): List<TrailPoint> {
        val arr = request("GET", "/api/members/" + enc(memberId) + "/trail").optJSONArray("points") ?: return emptyList()
        return (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            TrailPoint(p.getDouble("lat"), p.getDouble("lon"), p.optLong("time"))
        }
    }

    /** Someone's trips, newest first. */
    suspend fun trips(memberId: String): List<Trip> {
        val arr = request("GET", "/api/members/" + enc(memberId) + "/trips").optJSONArray("trips") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val pts = t.optJSONArray("points")
            Trip(
                start = t.optLong("start"), end = t.optLong("end"), distance = t.optDouble("distance", 0.0),
                from = t.optString("from"), to = t.optString("to"),
                fromNear = t.optString("fromNear"), toNear = t.optString("toNear"),
                avgSpeed = t.optDouble("avgSpeed", 0.0), topSpeed = t.optDouble("topSpeed", 0.0),
                ongoing = t.optBoolean("ongoing", false),
                points = (0 until (pts?.length() ?: 0)).map {
                    val p = pts!!.getJSONObject(it)
                    TrailPoint(p.getDouble("lat"), p.getDouble("lon"), p.optLong("time"))
                },
            )
        }
    }

    /** Keep your trips for 1, 3 or 7 days, or 0 to turn them off (and delete them). */
    suspend fun setTripDays(days: Int) {
        request("POST", "/api/me", JSONObject().put("tripDays", days))
    }

    /** Keep a trail of your own for 6, 12 or 24 hours, or 0 to turn it off (and delete it). */
    suspend fun setTrailHours(hours: Int) {
        request("POST", "/api/me", JSONObject().put("trailHours", hours))
    }

    suspend fun setShareArrivals(on: Boolean) {
        request("POST", "/api/me", JSONObject().put("shareArrivals", on))
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Sends a fix; the reply carries any alerts newer than [since]. */
    suspend fun sendLocation(
        lat: Double, lon: Double, accuracy: Float, speed: Float, time: Long, battery: Int, charging: Boolean,
        since: Long, saving: Boolean = false, driving: Boolean = false, watchBattery: Int = 0,
    ): EventBatch? {
        val body = JSONObject()
            .put("lat", lat).put("lon", lon)
            .put("acc", accuracy.toDouble()).put("speed", speed.toDouble())
            .put("time", time).put("battery", battery).put("charging", charging)
        if (saving) body.put("saving", true)
        if (driving) body.put("driving", true)
        if (watchBattery > 0) body.put("watch", watchBattery)
        return parseEvents(request("POST", "/api/location?since=$since", body), since)
    }

    suspend fun setShareLowBattery(on: Boolean) {
        request("POST", "/api/me", JSONObject().put("shareLowBattery", on))
    }

    suspend fun setPaused(paused: Boolean) {
        request("POST", "/api/me", JSONObject().put("paused", paused))
    }

    suspend fun setName(name: String) {
        request("POST", "/api/me", JSONObject().put("name", name))
    }

    /** Your map pin: an emoji ("" = your initial) on a colour like #6e48d6. */
    suspend fun setIcon(emoji: String, color: String) {
        request("POST", "/api/me", JSONObject().put("emoji", emoji).put("color", color))
    }

    /** One emoji shown instead of your initial; "" goes back to the initial. */
    suspend fun setEmoji(emoji: String) {
        request("POST", "/api/me", JSONObject().put("emoji", emoji))
    }

    /** Tells the server this phone's app version and anything that could stop it sharing. */
    suspend fun sendStatus(app: String, issues: List<String>) {
        request("POST", "/api/me/status", JSONObject().put("app", app).put("issues", org.json.JSONArray(issues)))
    }

    suspend fun leave() {
        request("DELETE", "/api/me")
    }

    /**
     * Waits (up to [waitSeconds]) for new alerts. The server answers the moment something
     * happens, so keeping one of these open gets alerts within seconds without a push service.
     */
    suspend fun waitEvents(since: Long, waitSeconds: Int): EventBatch? =
        parseEvents(
            request("GET", "/api/events/wait?since=$since&wait=$waitSeconds", readTimeoutMs = (waitSeconds + 25) * 1000),
            since,
        )

    /** Shares (or stops sharing) your location with one circle. */
    suspend fun setCircleSharing(circleId: String, sharing: Boolean) {
        request("POST", "/api/me/circles/" + java.net.URLEncoder.encode(circleId, "UTF-8"), JSONObject().put("sharing", sharing))
    }

    /** Uses an invite code to join another circle while signed in. Returns the circle's name. */
    suspend fun joinCircle(code: String): String =
        request("POST", "/api/me/circles/join", JSONObject().put("code", code.trim())).optString("name")

    /** Raises (or re-sends) an SOS, with where the phone is if known. */
    suspend fun sos(loc: android.location.Location?, source: String = "", heartRate: Int = 0, note: String = "") {
        val body = JSONObject().put("loc", loc?.let { locationJson(it) })
        if (source.isNotEmpty()) body.put("source", source)
        if (heartRate > 0) body.put("heartRate", heartRate)
        if (note.isNotEmpty()) body.put("note", note)
        request("POST", "/api/sos", body)
    }

    /** Tells someone with an SOS that you've seen it. */
    suspend fun sosSeen(memberId: String) {
        request("POST", "/api/sos/" + enc(memberId) + "/seen", JSONObject())
    }

    /** Asks one person to pick you up ("pickup") or call you ("call"). */
    suspend fun request(to: String, kind: String, loc: android.location.Location?) {
        request("POST", "/api/requests", JSONObject().put("to", to).put("kind", kind).put("loc", loc?.let { locationJson(it) }))
    }

    suspend fun reminders(): List<PlaceReminder> {
        val arr = request("GET", "/api/reminders").optJSONArray("reminders") ?: return emptyList()
        return (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            PlaceReminder(r.getString("id"), r.optString("placeId"), r.optString("text"), r.optBoolean("onLeave", false))
        }
    }

    suspend fun addReminder(placeId: String, text: String, onLeave: Boolean) {
        request("POST", "/api/reminders", JSONObject().put("placeId", placeId).put("text", text).put("onLeave", onLeave))
    }

    suspend fun deleteReminder(id: String) {
        request("POST", "/api/reminders/" + enc(id) + "/delete", JSONObject())
    }

    /** Your Pebble Index 01 ring webhook links ([reset] makes new ones). */
    suspend fun indexLinks(reset: Boolean = false): IndexLinks {
        val o = if (reset) request("POST", "/api/me/index/reset", JSONObject()) else request("GET", "/api/me/index")
        return IndexLinks(o.optString("sos"), o.optString("checkin"), o.optString("remind"), o.optString("signingSecret"))
    }

    /** Ends this person's SOS and tells everyone they're OK. */
    suspend fun sosEnd() {
        request("POST", "/api/sos/end", JSONObject())
    }

    /** "I'm here", with an optional note. */
    suspend fun checkIn(note: String, loc: android.location.Location?) {
        request("POST", "/api/checkin", JSONObject().put("note", note).put("loc", loc?.let { locationJson(it) }))
    }

    private fun locationJson(l: android.location.Location) = JSONObject()
        .put("lat", l.latitude).put("lon", l.longitude).put("acc", l.accuracy.toDouble()).put("time", l.time)

    /** Like [request], for endpoints that answer with a JSON list. */
    private suspend fun requestArray(method: String, path: String): org.json.JSONArray {
        val wrapped = request(method, path, wrapArray = true)
        return wrapped.getJSONArray("items")
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 20_000,
        wrapArray: Boolean = false,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Thereabouts-Android/${BuildConfig.VERSION_NAME}")
                if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 401 && token.isNotEmpty()) {
                    val reason = runCatching { JSONObject(text).optString("reason") }.getOrNull()
                    throw UnauthorizedException(signedInElsewhere = reason == "signed_in_elsewhere")
                }
                if (code >= 400) {
                    val msg = runCatching { JSONObject(text).getString("error") }.getOrNull()
                        ?: if (code == 404 || code == 405) OUTDATED_SERVER else "Server error ($code)"
                    throw ApiException(code, msg)
                }
                when {
                    wrapArray -> JSONObject().put("items", org.json.JSONArray(text.ifBlank { "[]" }))
                    text.isBlank() -> JSONObject()
                    else -> JSONObject(text)
                }
            } finally {
                conn.disconnect()
            }
        }

    /** Null when the server is too old to send alerts. */
    private fun parseEvents(o: JSONObject, since: Long): EventBatch? {
        if (!o.has("cursor")) return null
        val arr = o.optJSONArray("events")
        val events = (0 until (arr?.length() ?: 0)).map {
            val e = arr!!.getJSONObject(it)
            AppEvent(
                id = e.getLong("id"),
                type = e.optString("type"),
                memberId = e.optString("memberId"),
                name = e.optString("name", "Someone"),
                battery = e.optInt("battery", -1),
                level = e.optInt("level", 0),
                time = e.optLong("time", 0),
                note = e.optString("note"),
                place = e.optString("place"),
                placeId = e.optString("placeId"),
                circleId = e.optString("circleId"),
                fromPlaceId = e.optString("fromPlaceId"),
                to = e.optString("to"),
                kind = e.optString("kind"),
                heartRate = e.optInt("heartRate", 0),
                distance = e.optDouble("distance", 0.0),
                duration = e.optLong("duration", 0),
                lat = e.optDouble("lat", 0.0),
                lon = e.optDouble("lon", 0.0),
            )
        }
        return EventBatch(events, o.getLong("cursor"), since)
    }

    private fun parseServer(o: JSONObject) = ServerInfo(
        name = o.optString("name", "Family"),
        tileUrl = o.optString("tileUrl", Prefs.DEFAULT_TILES),
        tileAttribution = o.optString("tileAttribution", Prefs.DEFAULT_ATTRIBUTION),
        tileUrlDark = o.optString("tileUrlDark"),
        features = o.optJSONArray("features")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() },
        version = o.optString("version"),
    )

    private fun parseMember(o: JSONObject): Member {
        val l = o.optJSONObject("loc")
        return Member(
            id = o.getString("id"),
            name = o.optString("name", "?"),
            color = o.optString("color", "#4363d8"),
            paused = o.optBoolean("paused", false),
            emoji = o.optString("emoji"),
            sosSince = o.optLong("sosSince", 0),
            sosSeenBy = o.optJSONArray("sosSeenBy")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            circles = o.optJSONArray("circles")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            place = o.optString("place"),
            trail = o.optBoolean("trail", false),
            trips = o.optBoolean("trips", false),
            loc = l?.let {
                MemberLocation(
                    lat = it.getDouble("lat"),
                    lon = it.getDouble("lon"),
                    accuracy = it.optDouble("acc", 0.0),
                    battery = it.optInt("battery", -1),
                    charging = it.optBoolean("charging", false),
                    time = it.optLong("time", 0),
                    saving = it.optBoolean("saving", false),
                    driving = it.optBoolean("driving", false),
                    watch = it.optInt("watch", 0),
                    speed = it.optDouble("speed", 0.0),
                )
            },
        )
    }
}
