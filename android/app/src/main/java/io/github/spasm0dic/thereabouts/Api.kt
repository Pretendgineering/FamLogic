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
)

data class Member(
    val id: String,
    val name: String,
    val color: String,
    val paused: Boolean,
    val loc: MemberLocation?,
    /** When this person's active SOS started (unix ms), or 0 if none. */
    val sosSince: Long = 0,
)

data class ServerInfo(val name: String, val tileUrl: String, val tileAttribution: String)

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
)

/** What join, sign-in and password resets return: this phone is now signed in as [member]. */
data class JoinResponse(val token: String, val member: Member, val server: ServerInfo, val username: String)

/** What an invite code is for: joining as someone new, or resetting an existing person's sign-in. */
data class InviteInfo(val isReset: Boolean, val name: String, val username: String, val server: ServerInfo)

/** The server rejected our token: this phone was removed from the family. */
/**
 * The server no longer accepts this phone. [signedInElsewhere] is true when that's because
 * the same person signed in on another phone (the latest sign-in wins), false when an admin
 * removed them.
 */
class UnauthorizedException(val signedInElsewhere: Boolean) :
    IOException(if (signedInElsewhere) "You signed in on another device" else "This phone is no longer part of the family")

/** The server answered with an error message meant for people. */
class ApiException(val status: Int, message: String) : IOException(message)

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
            events = parseEvents(o, since),
        )
    }

    /** Sends a fix; the reply carries any alerts newer than [since]. */
    suspend fun sendLocation(
        lat: Double, lon: Double, accuracy: Float, speed: Float, time: Long, battery: Int, charging: Boolean,
        since: Long,
    ): EventBatch? {
        val body = JSONObject()
            .put("lat", lat).put("lon", lon)
            .put("acc", accuracy.toDouble()).put("speed", speed.toDouble())
            .put("time", time).put("battery", battery).put("charging", charging)
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

    /** Raises (or re-sends) an SOS, with where the phone is if known. */
    suspend fun sos(loc: android.location.Location?) {
        request("POST", "/api/sos", JSONObject().put("loc", loc?.let { locationJson(it) }))
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

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 20_000,
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
                        ?: "Server error ($code)"
                    throw ApiException(code, msg)
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
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
    )

    private fun parseMember(o: JSONObject): Member {
        val l = o.optJSONObject("loc")
        return Member(
            id = o.getString("id"),
            name = o.optString("name", "?"),
            color = o.optString("color", "#4363d8"),
            paused = o.optBoolean("paused", false),
            sosSince = o.optLong("sosSince", 0),
            loc = l?.let {
                MemberLocation(
                    lat = it.getDouble("lat"),
                    lon = it.getDouble("lon"),
                    accuracy = it.optDouble("acc", 0.0),
                    battery = it.optInt("battery", -1),
                    charging = it.optBoolean("charging", false),
                    time = it.optLong("time", 0),
                )
            },
        )
    }
}
