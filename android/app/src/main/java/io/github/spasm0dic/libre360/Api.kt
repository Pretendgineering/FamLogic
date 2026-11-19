package io.github.spasm0dic.libre360

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
)

data class ServerInfo(val name: String, val tileUrl: String, val tileAttribution: String)

data class MembersResponse(val me: String, val members: List<Member>, val server: ServerInfo)

data class JoinResponse(val token: String, val member: Member, val server: ServerInfo)

/** The server rejected our token: this phone was removed from the family. */
class UnauthorizedException : IOException("This phone is no longer part of the family")

/** The server answered with an error message meant for people. */
class ApiException(val status: Int, message: String) : IOException(message)

/**
 * Tiny client for the Libre360 server API. Uses only what ships with Android
 * (HttpURLConnection + org.json) so there are no extra dependencies.
 */
class Api(private val baseUrl: String, private val token: String = "") {

    suspend fun info(): ServerInfo = parseServer(request("GET", "/api/info"))

    suspend fun join(code: String, name: String): JoinResponse {
        val body = JSONObject().put("code", code).put("name", name)
        val o = request("POST", "/api/join", body)
        return JoinResponse(o.getString("token"), parseMember(o.getJSONObject("member")), parseServer(o.getJSONObject("server")))
    }

    suspend fun members(): MembersResponse {
        val o = request("GET", "/api/members")
        val arr = o.getJSONArray("members")
        val list = (0 until arr.length()).map { parseMember(arr.getJSONObject(it)) }
        return MembersResponse(o.getString("me"), list, parseServer(o.getJSONObject("server")))
    }

    suspend fun sendLocation(
        lat: Double, lon: Double, accuracy: Float, speed: Float, time: Long, battery: Int, charging: Boolean,
    ) {
        val body = JSONObject()
            .put("lat", lat).put("lon", lon)
            .put("acc", accuracy.toDouble()).put("speed", speed.toDouble())
            .put("time", time).put("battery", battery).put("charging", charging)
        request("POST", "/api/location", body)
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

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Libre360-Android/${BuildConfig.VERSION_NAME}")
                if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 401 && token.isNotEmpty()) throw UnauthorizedException()
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
