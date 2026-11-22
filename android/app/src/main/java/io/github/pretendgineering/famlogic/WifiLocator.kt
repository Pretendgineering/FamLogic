package io.github.pretendgineering.famlogic

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in Wi-Fi positioning for devices that can't find themselves (a Wi-Fi-only
 * tablet, or a phone without network location): looks up the nearby Wi-Fi
 * networks with beaconDB (https://beacondb.net), a free, community-run service.
 * Only network hardware addresses and signal strengths are sent, never who's asking.
 */
object WifiLocator {
    private const val URL_GEOLOCATE = "https://api.beacondb.net/v1/geolocate"

    /** Asks Android for a fresh scan; results arrive in a few seconds. Android limits how often this works. */
    @Suppress("DEPRECATION") // still the only way to ask; harmless when throttled
    fun requestScan(context: Context) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching { wm.startScan() }
    }

    /** Where the nearby Wi-Fi networks say we are, or null if there aren't enough or beaconDB doesn't know them. */
    @SuppressLint("MissingPermission") // the caller has location permission
    suspend fun locate(context: Context): Location? = withContext(Dispatchers.IO) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@withContext null
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        val nowElapsedUs = SystemClock.elapsedRealtimeNanos() / 1000
        val aps = JSONArray()
        results
            // Skip networks that asked not to be mapped, and stale results (older than 5 minutes).
            .filter { r ->
                @Suppress("DEPRECATION") val ssid = r.SSID.orEmpty()
                !ssid.endsWith("_nomap") && nowElapsedUs - r.timestamp < 5 * 60 * 1_000_000L
            }
            .sortedByDescending { it.level }
            .take(20)
            .forEach { r ->
                @Suppress("DEPRECATION") val bssid = r.BSSID ?: return@forEach
                aps.put(JSONObject().put("macAddress", bssid).put("signalStrength", r.level))
            }
        if (aps.length() < 2) return@withContext null // beaconDB needs at least two
        val body = JSONObject().put("wifiAccessPoints", aps).put("considerIp", false).toString()
        val conn = URL(URL_GEOLOCATE).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "FamLogic/${BuildConfig.VERSION_NAME}")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return@withContext null // 404: networks not known
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val loc = o.getJSONObject("location")
            Location("wifi").apply {
                latitude = loc.getDouble("lat")
                longitude = loc.getDouble("lng")
                accuracy = o.optDouble("accuracy", 100.0).toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
