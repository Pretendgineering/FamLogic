package io.github.pretendgineering.famlogic

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
    /** Direction of travel (1-360 degrees, 360 = north), 0 if unknown or still. */
    val heading: Int = 0,
)

data class Member(
    val id: String,
    val name: String,
    val color: String,
    val paused: Boolean,
    val loc: MemberLocation?,
    /** Shown instead of their initial, if they picked one. */
    val emoji: String = "",
    /** The voice their messages are read aloud in: "female", "male" or "" (the default). */
    val voice: String = "",
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
    /** "parent", "child" or "dependent" (servers before roles: everyone is a parent). */
    val role: String = "parent",
    /** admin, parent, older_child, younger_child or dependent. */
    val access: String = "parent",
    /** Their phone number, for Call buttons ("" if they haven't given one). */
    val phone: String = "",
    /** Who they're driving to ("On my way"), if anyone. */
    val rideTo: String = "",
)

/** Everyone's phone numbers, as last seen (for Call buttons on notifications). */
object Directory {
    @Volatile var phones: Map<String, String> = emptyMap()
    @Volatile var names: Map<String, String> = emptyMap()
}

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
    /** Chat: the conversation, its title ("" for one-to-one) and the message id. */
    val conv: String = "",
    val title: String = "",
    val message: Long = 0,
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
    /** For a child: the sections and features a parent switched on (null for adults). */
    val kidAllowed: Set<String>? = null,
    val kidPreset: String = "",
    val home: List<String> = emptyList(),
    val pinSet: Boolean = false,
    /** Bigger buttons and text, and picture icons (on by default for younger children). */
    val big: Boolean = false,
    val pictures: Boolean = false,
)

/** What join, sign-in and password resets return: this phone is now signed in as [member]. */
data class JoinResponse(val token: String, val member: Member, val server: ServerInfo, val username: String)

/** What an invite code is for: joining as someone new, or resetting an existing person's sign-in. */
data class InviteInfo(
    val isReset: Boolean,
    val name: String,
    val username: String,
    val server: ServerInfo,
    val circle: String = "",
    /** A younger child's code: no username or password, it just signs this device in. */
    val passwordless: Boolean = false,
)

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

const val OUTDATED_SERVER = "This needs a newer FamLogic server. Ask your family's admin to update it."

/**
 * Tiny client for the FamLogic server API. Uses only what ships with Android
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
            passwordless = o.optBoolean("passwordless", false),
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

    /** Signs this device in as an existing person (their other devices stay signed in). */
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
        val meID = o.getString("me")
        // Family displays (Corkboard) aren't people: not on the map or in lists.
        val list = (0 until arr.length()).map { parseMember(arr.getJSONObject(it)) }.filter { it.access != "display" || it.id == meID }
        Directory.phones = list.filter { it.phone.isNotEmpty() }.associate { it.id to it.phone }
        Directory.names = list.associate { it.id to it.name }
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
            kidAllowed = settings?.optJSONObject("kid")?.let { k ->
                val on = mutableSetOf<String>()
                listOf("sections", "features").forEach { key ->
                    k.optJSONObject(key)?.let { m -> m.keys().forEach { if (m.optBoolean(it)) on += it } }
                }
                // Module levels: "thereabouts:view" and so on; a module that isn't off is usable.
                k.optJSONObject("modules")?.let { m ->
                    m.keys().forEach { id ->
                        val lvl = m.optString(id)
                        on += "level:$id:$lvl"
                        if (lvl != "off") on += id
                    }
                }
                on
            },
            kidPreset = settings?.optJSONObject("kid")?.optString("preset").orEmpty(),
            home = settings?.optJSONArray("home")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            pinSet = settings?.optBoolean("pinSet", false) ?: false,
            big = settings?.optBoolean("big", false) ?: false,
            pictures = settings?.optBoolean("pictures", false) ?: false,
        )
    }

    /** Speech to text on the family's server: a 16 kHz mono WAV recording → the words ("" if none). */
    suspend fun transcribe(wav: ByteArray): String {
        val (code, bytes) = raw("POST", "/api/voice/transcribe", wav, "audio/wav")
        val text = String(bytes)
        if (code >= 400) throw ApiException(code, runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "Couldn't understand that ($code)")
        return JSONObject(text).optString("text")
    }

    /** Reading aloud on the family's server: text → WAV audio. */
    /** Sends a file's bytes (a picture) with PUT. */
    suspend fun putBytes(path: String, data: ByteArray, type: String) {
        val (code, b) = raw("PUT", path, data, type)
        if (code >= 400) throw ApiException(code, runCatching { org.json.JSONObject(String(b)).optString("error") }.getOrNull()?.ifEmpty { null } ?: "couldn't send it")
    }

    /** A file's bytes, such as a picture. */
    suspend fun bytes(path: String): ByteArray {
        val (code, b) = raw("GET", path, null, null)
        if (code >= 400) throw ApiException(code, "couldn't load it")
        return b
    }

    suspend fun speak(text: String, voice: String = "", slow: Boolean = false): ByteArray {
        val (code, bytes) = raw("POST", "/api/voice/speak", JSONObject().put("text", text).put("voice", voice).put("slow", slow).toString().toByteArray(), "application/json")
        if (code >= 400) throw ApiException(code, "Couldn't read that aloud ($code)")
        return bytes
    }

    /** Checks the family's parent PIN (wrong guesses lock it for a while). */
    suspend fun checkPin(pin: String) {
        request("POST", "/api/pin/check", JSONObject().put("pin", pin))
    }

    /** Your own bigger buttons and picture icons (grandparents and grown-ups). */
    suspend fun setAssist(big: Boolean? = null, pictures: Boolean? = null) {
        request("POST", "/api/me", JSONObject().apply { big?.let { put("big", it) }; pictures?.let { put("pictures", it) } })
    }

    /** Arranges your own home screen (adults). */
    /** The family's modules and which are on. */
    suspend fun modules(): List<ModuleInfo> {
        val arr = request("GET", "/api/modules").optJSONArray("modules") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ModuleInfo(o.optString("id"), o.optString("name"), o.optString("plain"), o.optString("description"), o.optBoolean("available"), o.optBoolean("enabled"))
        }
    }

    /** Admins: switch modules on or off for the whole family. */
    suspend fun setModules(enabled: Map<String, Boolean>): List<ModuleInfo> {
        request("POST", "/api/modules", JSONObject().put("enabled", JSONObject(enabled)))
        return modules()
    }

    suspend fun setHome(tiles: List<String>) {
        request("POST", "/api/me", JSONObject().put("home", org.json.JSONArray(tiles)))
    }

    /** A parent's decision on a chat with a child in it: approve, pause, resume, block, unblock or delete. */
    suspend fun chatModerate(conv: String, action: String) {
        request("POST", "/api/chat/${enc(conv)}/moderate", JSONObject().put("action", action))
    }

    /** Deletes a message (your own, or as a parent one in a child's chat). */
    suspend fun chatDeleteMessage(conv: String, id: Long) {
        request("POST", "/api/chat/${enc(conv)}/messages/$id/delete", JSONObject())
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
        since: Long, saving: Boolean = false, driving: Boolean = false, watchBattery: Int = 0, heading: Int = 0,
    ): EventBatch? {
        val body = JSONObject()
            .put("lat", lat).put("lon", lon)
            .put("acc", accuracy.toDouble()).put("speed", speed.toDouble())
            .put("time", time).put("battery", battery).put("charging", charging)
        if (saving) body.put("saving", true)
        if (driving) body.put("driving", true)
        if (watchBattery > 0) body.put("watch", watchBattery)
        if (heading > 0) body.put("heading", heading)
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

    // ---- Upkeep (chores) and the family feed

    suspend fun chores(): ChoreList {
        val o = request("GET", "/api/chores")
        val arr = o.optJSONArray("chores")
        val stars = o.optJSONObject("stars")
        return ChoreList(
            chores = (0 until (arr?.length() ?: 0)).map { parseChore(arr!!.getJSONObject(it)) },
            stars = stars?.keys()?.asSequence()?.associateWith { stars.optInt(it) } ?: emptyMap(),
            today = o.optString("today"),
            canEdit = o.optBoolean("canEdit"),
            level = o.optString("level", "full"),
        )
    }

    suspend fun addChore(title: String, who: List<String>, every: String, due: String, stars: Int, notes: String = "", whenOfDay: String = ""): Chore =
        parseChore(request("POST", "/api/chores", JSONObject().put("title", title).put("who", org.json.JSONArray(who))
            .put("every", every).put("due", due).put("stars", stars).put("notes", notes).put("when", whenOfDay)))

    suspend fun choreDone(id: String, date: String = "", by: String = ""): Chore {
        val body = JSONObject()
        if (date.isNotEmpty()) body.put("date", date)
        if (by.isNotEmpty()) body.put("by", by)
        return parseChore(request("POST", "/api/chores/" + enc(id) + "/done", body))
    }

    suspend fun choreUndo(id: String, date: String = ""): Chore =
        parseChore(request("POST", "/api/chores/" + enc(id) + "/undo", if (date.isNotEmpty()) JSONObject().put("date", date) else JSONObject()))

    suspend fun retireChore(id: String) {
        request("POST", "/api/chores/" + enc(id) + "/delete")
    }

    suspend fun feed(from: Long, to: Long): List<AppEvent> {
        val o = request("GET", "/api/feed?from=$from&to=$to")
        return parseEvents(JSONObject().put("events", o.optJSONArray("items") ?: org.json.JSONArray()).put("cursor", 0), -1)?.events.orEmpty()
    }

    suspend fun updateChore(id: String, title: String, who: List<String>, every: String, due: String, stars: Int, notes: String, whenOfDay: String): Chore =
        parseChore(request("POST", "/api/chores/" + enc(id), JSONObject().put("title", title).put("who", org.json.JSONArray(who))
            .put("every", every).put("due", due).put("stars", stars).put("notes", notes).put("when", whenOfDay)))

    // ---- Rewards: what stars are for

    suspend fun rewards(): RewardList {
        val o = request("GET", "/api/rewards")
        val rs = o.optJSONArray("rewards")
        val asks = o.optJSONArray("asks")
        return RewardList(
            rewards = (0 until (rs?.length() ?: 0)).map { rs!!.getJSONObject(it).let { r -> Reward(r.optString("id"), r.optString("title"), r.optInt("cost")) } },
            asks = (0 until (asks?.length() ?: 0)).map {
                asks!!.getJSONObject(it).let { a ->
                    RewardAsk(a.optString("id"), a.optString("title"), a.optInt("cost"), a.optString("memberId"), a.optLong("time"), a.optString("status"))
                }
            },
            canEdit = o.optBoolean("canEdit"),
        )
    }

    suspend fun addReward(title: String, cost: Int) { request("POST", "/api/rewards", JSONObject().put("title", title).put("cost", cost)) }
    suspend fun deleteReward(id: String) { request("POST", "/api/rewards/" + enc(id) + "/delete") }
    suspend fun askReward(id: String) { request("POST", "/api/rewards/" + enc(id) + "/ask") }
    suspend fun answerAsk(id: String, approve: Boolean) { request("POST", "/api/rewards/asks/" + enc(id) + if (approve) "/approve" else "/decline") }

    private fun parseChore(o: JSONObject): Chore {
        val who = o.optJSONArray("who")
        val done = o.optJSONArray("done")
        return Chore(
            id = o.optString("id"), title = o.optString("title"),
            who = (0 until (who?.length() ?: 0)).map { who!!.getString(it) },
            every = o.optString("every"), due = o.optString("due"), whenOfDay = o.optString("when"),
            stars = o.optInt("stars"), notes = o.optString("notes"), finished = o.optBoolean("finished"),
            done = (0 until (done?.length() ?: 0)).map { done!!.getJSONObject(it).let { d -> d.optString("date") to d.optString("by") } },
        )
    }

    /** Your phone number, for others' Call buttons ("" removes it). */
    suspend fun setPhone(phone: String) {
        request("POST", "/api/me", JSONObject().put("phone", phone))
    }

    /** The parent-set check-in windows (a child sees their own). */
    suspend fun checkPlans(): List<CheckPlan> {
        val arr = request("GET", "/api/checkplans").optJSONArray("plans")
        return (0 until (arr?.length() ?: 0)).map { i ->
            arr!!.getJSONObject(i).let { o ->
                CheckPlan(o.optString("id"), o.optString("memberId"), o.optString("placeId"),
                    o.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) } }.orEmpty(), o.optString("from"), o.optString("until"))
            }
        }
    }

    suspend fun addCheckPlan(memberId: String, placeId: String, days: List<Int>, from: String, until: String) {
        request("POST", "/api/checkplans", JSONObject().put("memberId", memberId).put("placeId", placeId)
            .put("days", org.json.JSONArray(days)).put("from", from).put("until", until))
    }

    suspend fun deleteCheckPlan(id: String) { request("POST", "/api/checkplans/" + enc(id) + "/delete") }

    /** The voice your messages are read aloud in: "female", "male" or "". */
    suspend fun setVoice(voice: String) {
        request("POST", "/api/me", JSONObject().put("voice", voice))
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
    suspend fun sendStatus(app: String, issues: List<String>, device: String = "") {
        request("POST", "/api/me/status", JSONObject().put("app", app).put("device", device).put("issues", org.json.JSONArray(issues)))
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

    // ---- chat (the chat module)

    suspend fun chats(): List<ChatConversation> {
        val arr = request("GET", "/api/chat").optJSONArray("conversations") ?: return emptyList()
        return (0 until arr.length()).map { parseConversation(arr.getJSONObject(it)) }
    }

    suspend fun chatThread(conv: String, after: Long = 0, before: Long = 0): ChatThread {
        val q = when {
            after > 0 -> "?after=$after"
            before > 0 -> "?before=$before"
            else -> ""
        }
        val o = request("GET", "/api/chat/${enc(conv)}/messages$q")
        val msgs = o.optJSONArray("messages")
        val reads = o.optJSONObject("reads")
        return ChatThread(
            conversation = parseConversation(o.getJSONObject("conversation")),
            messages = (0 until (msgs?.length() ?: 0)).map { parseMessage(msgs!!.getJSONObject(it)) },
            reads = reads?.keys()?.asSequence()?.associateWith { reads.optLong(it) } ?: emptyMap(),
            canPost = o.optBoolean("canPost", false),
            typing = o.optJSONArray("typing")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }

    suspend fun chatSend(conv: String, text: String, replyTo: Long = 0): ChatMessage =
        parseMessage(request("POST", "/api/chat/${enc(conv)}/messages", JSONObject().put("text", text).put("replyTo", replyTo)))

    /** Sends a voice message (AAC in an .m4a file). */
    suspend fun chatSendVoice(conv: String, audio: ByteArray, ms: Long, replyTo: Long = 0): ChatMessage =
        chatSendFile(conv, "audio", "voice.m4a", "audio/mp4", audio, "", replyTo, ms)

    /** Sends a photo (JPEG bytes) with an optional caption. */
    suspend fun chatSendPhoto(conv: String, jpeg: ByteArray, text: String, replyTo: Long = 0): ChatMessage =
        chatSendFile(conv, "photo", "photo.jpg", "image/jpeg", jpeg, text, replyTo, 0)

    private suspend fun chatSendFile(conv: String, field: String, file: String, type: String, data: ByteArray, text: String, replyTo: Long, ms: Long): ChatMessage = withContext(Dispatchers.IO) {
        val boundary = "famlogic" + System.nanoTime()
        val body = java.io.ByteArrayOutputStream().apply {
            fun line(s: String) = write((s + "\r\n").toByteArray())
            fun field(name: String, value: String) { line("--$boundary"); line("Content-Disposition: form-data; name=\"$name\""); line(""); line(value) }
            if (text.isNotBlank()) field("text", text)
            if (replyTo > 0) field("replyTo", replyTo.toString())
            if (ms > 0) field("audioMs", ms.toString())
            line("--$boundary"); line("Content-Disposition: form-data; name=\"$field\"; filename=\"$file\""); line("Content-Type: $type"); line("")
            write(data); line("")
            line("--$boundary--")
        }.toByteArray()
        val (code, bytes) = raw("POST", "/api/chat/${enc(conv)}/messages", body, "multipart/form-data; boundary=$boundary")
        val text2 = String(bytes)
        if (code >= 400) throw ApiException(code, runCatching { JSONObject(text2).getString("error") }.getOrNull() ?: "Couldn't send ($code)")
        parseMessage(JSONObject(text2))
    }

    /** Reacts to a message with a nudge ("" takes the reaction back). */
    suspend fun chatReact(conv: String, id: Long, kind: String) {
        request("POST", "/api/chat/${enc(conv)}/messages/$id/react", JSONObject().put("kind", kind))
    }

    suspend fun chatTyping(conv: String) {
        request("POST", "/api/chat/${enc(conv)}/typing", JSONObject())
    }

    suspend fun chatSearch(q: String): List<ChatMessage> {
        val o = request("GET", "/api/chat/search?q=" + enc(q))
        val arr = o.optJSONArray("messages")
        return (0 until (arr?.length() ?: 0)).map { parseMessage(arr!!.getJSONObject(it)) }
    }

    suspend fun chatRead(conv: String, upTo: Long) {
        request("POST", "/api/chat/${enc(conv)}/read", JSONObject().put("upTo", upTo))
    }

    suspend fun chatDirect(to: String): ChatConversation =
        parseConversation(request("POST", "/api/chat/direct", JSONObject().put("to", to)))

    suspend fun chatGroup(name: String, members: List<String>): ChatConversation =
        parseConversation(request("POST", "/api/chat/groups", JSONObject().put("name", name).put("members", org.json.JSONArray(members))))

    suspend fun chatRename(conv: String, name: String) {
        request("POST", "/api/chat/${enc(conv)}/update", JSONObject().put("name", name))
    }

    /** Sets the family chat's (parents) or a group's icon. */
    suspend fun chatSetIcon(conv: String, emoji: String, color: String) {
        request("POST", "/api/chat/${enc(conv)}/update", JSONObject().put("emoji", emoji).put("color", color))
    }

    suspend fun chatLeave(conv: String, me: String) {
        request("POST", "/api/chat/${enc(conv)}/update", JSONObject().put("remove", org.json.JSONArray(listOf(me))))
    }

    /** A chat photo's bytes (they need this phone's token, so they're loaded here). */
    suspend fun chatPhoto(name: String): ByteArray {
        val (code, bytes) = raw("GET", "/api/chat-media/${enc(name)}", null, null)
        if (code >= 400) throw ApiException(code, "Couldn't load the photo")
        return bytes
    }

    private fun parseMessage(o: JSONObject) = ChatMessage(
        id = o.getLong("id"), conv = o.optString("conv"), sender = o.optString("sender"),
        text = o.optString("text"), photo = o.optString("photo"), time = o.optLong("time"),
        replyTo = o.optLong("replyTo"), replySender = o.optString("replySender"), replyText = o.optString("replyText"),
        audio = o.optString("audio"), audioMs = o.optLong("audioMs"),
        reactions = o.optJSONObject("reactions")?.let { r ->
            r.keys().asSequence().associateWith { k -> r.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() }
        }.orEmpty(),
    )

    private fun parseConversation(o: JSONObject): ChatConversation {
        val members = o.optJSONArray("members")
        return ChatConversation(
            id = o.getString("id"), kind = o.optString("kind"), title = o.optString("title"),
            members = (0 until (members?.length() ?: 0)).map { members!!.getString(it) },
            last = o.optJSONObject("last")?.let { parseMessage(it) },
            unread = o.optInt("unread", 0), oversight = o.optBoolean("oversight", false),
            createdBy = o.optString("createdBy"),
            status = o.optString("status"),
            emoji = o.optString("emoji"),
            color = o.optString("color"),
        )
    }

    /** A request whose body and answer are raw bytes. */
    private suspend fun raw(method: String, path: String, body: ByteArray?, contentType: String?): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "FamLogic-Android/${BuildConfig.VERSION_NAME}")
                if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", contentType)
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code == 401 && token.isNotEmpty()) throw UnauthorizedException(signedInElsewhere = false)
                val bytes = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
                code to bytes
            } finally {
                conn.disconnect()
            }
        }

    /** Like [request], for endpoints that answer with a JSON list. */
    private suspend fun requestArray(method: String, path: String): org.json.JSONArray {
        val wrapped = request(method, path, wrapArray = true)
        return wrapped.getJSONArray("items")
    }

    /** Any call, for features kept in their own files (Steward). */
    suspend fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 130_000): JSONObject = request(method, path, body, readTimeoutMs = timeoutMs)

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
                conn.setRequestProperty("User-Agent", "FamLogic-Android/${BuildConfig.VERSION_NAME}")
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
                conv = e.optString("conv"),
                title = e.optString("title"),
                message = e.optLong("message", 0),
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
            voice = o.optString("voice"),
            sosSince = o.optLong("sosSince", 0),
            sosSeenBy = o.optJSONArray("sosSeenBy")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            circles = o.optJSONArray("circles")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            place = o.optString("place"),
            trail = o.optBoolean("trail", false),
            trips = o.optBoolean("trips", false),
            role = o.optString("role", "parent").ifEmpty { "parent" },
            access = o.optString("access", "parent").ifEmpty { "parent" },
            phone = o.optString("phone"),
            rideTo = o.optString("rideTo"),
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
                    heading = it.optInt("heading", 0),
                )
            },
        )
    }
}

// ---- chat

data class ChatMessage(
    val id: Long, val conv: String, val sender: String, val text: String, val photo: String, val time: Long,
    /** A reply: the message it answers, who wrote that and a glimpse of it. */
    val replyTo: Long = 0, val replySender: String = "", val replyText: String = "",
    /** A voice message (a file under /api/chat-media/) and its length. */
    val audio: String = "", val audioMs: Long = 0,
    /** Reactions: kind (a nudge) → who. */
    val reactions: Map<String, List<String>> = emptyMap(),
)

data class ChatConversation(
    val id: String,
    /** "family", "group" or "direct". */
    val kind: String,
    /** "Family", the group's name, or the other person's name. */
    val title: String,
    val members: List<String>,
    val last: ChatMessage?,
    val unread: Int,
    /** A parent looking at a child's chat: read only. */
    val oversight: Boolean,
    val createdBy: String = "",
    /** "" (open), "pending" (waiting for a parent's OK), "paused" or "blocked". */
    val status: String = "",
    /** The family chat's or a group's icon ("" = the default). */
    val emoji: String = "",
    val color: String = "",
)

data class ChatThread(
    val conversation: ChatConversation,
    val messages: List<ChatMessage>,
    /** How far each person has read (message id), for "Seen by". */
    val reads: Map<String, Long>,
    val canPost: Boolean,
    /** Who's typing right now (not you). */
    val typing: List<String> = emptyList(),
)

/** A FamLogic module (Thereabouts, Chitchat…): whether it's built yet, and whether the family has it on. */
data class ModuleInfo(val id: String, val name: String, val plain: String, val description: String, val available: Boolean, val enabled: Boolean)

/** A chore (Upkeep): kept as a Markdown note in the family's vault. */
data class Chore(
    val id: String,
    val title: String,
    /** Member ids it's for; empty = anyone. */
    val who: List<String>,
    /** "" = once; day, weekdays, week, "2 weeks", month, "mon,thu". */
    val every: String,
    /** The next date it's due (YYYY-MM-DD). */
    val due: String,
    val whenOfDay: String,
    val stars: Int,
    val notes: String,
    val finished: Boolean,
    /** The times it was done, latest first: (date, member id). */
    val done: List<Pair<String, String>>,
)

/** A check-in window: a child should be at a fence between two times on some days. */
data class CheckPlan(val id: String, val memberId: String, val placeId: String, val days: List<Int>, val from: String, val until: String)

data class Reward(val id: String, val title: String, val cost: Int)
data class RewardAsk(val id: String, val title: String, val cost: Int, val memberId: String, val time: Long, val status: String)
data class RewardList(val rewards: List<Reward>, val asks: List<RewardAsk>, val canEdit: Boolean)

data class ChoreList(val chores: List<Chore>, val stars: Map<String, Int>, val today: String, val canEdit: Boolean, val level: String)
