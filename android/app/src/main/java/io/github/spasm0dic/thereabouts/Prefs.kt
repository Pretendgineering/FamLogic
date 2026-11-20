package io.github.spasm0dic.thereabouts

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Everything the app remembers, in plain SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("thereabouts", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit { putString("server", v) }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit { putString("token", v) }

    var memberId: String
        get() = sp.getString("member_id", "") ?: ""
        set(v) = sp.edit { putString("member_id", v) }

    var familyName: String
        get() = sp.getString("family", "") ?: ""
        set(v) = sp.edit { putString("family", v) }

    var tileUrl: String
        get() = sp.getString("tile_url", DEFAULT_TILES) ?: DEFAULT_TILES
        set(v) = sp.edit { putString("tile_url", v) }

    var tileAttribution: String
        get() = sp.getString("tile_attr", DEFAULT_ATTRIBUTION) ?: DEFAULT_ATTRIBUTION
        set(v) = sp.edit { putString("tile_attr", v) }

    /** True while the person wants to share; false when they pressed pause. */
    var sharing: Boolean
        get() = sp.getBoolean("sharing", true)
        set(v) = sp.edit { putBoolean("sharing", v) }

    /** How often to send a location, in minutes. */
    var intervalMinutes: Int
        get() = sp.getInt("interval", 2)
        set(v) = sp.edit { putInt("interval", v) }

    /** Last alert this phone has seen (server event id); -1 = none yet (fresh install). */
    var eventCursor: Long
        get() = sp.getLong("event_cursor", -1L)
        set(v) = sp.edit { putLong("event_cursor", v) }

    /** Feature: get a notification when someone else's battery is low. */
    var notifyLowBattery: Boolean
        get() = sp.getBoolean("notify_low_battery", true)
        set(v) = sp.edit { putBoolean("notify_low_battery", v) }

    /** Which low-battery level to be notified about: 15 or 10 (%). */
    var lowBatteryThreshold: Int
        get() = sp.getInt("low_battery_threshold", 15)
        set(v) = sp.edit { putInt("low_battery_threshold", v) }

    /** Set when the server says this phone was removed, so the UI can explain. */
    var removedNotice: Boolean
        get() = sp.getBoolean("removed", false)
        set(v) = sp.edit { putBoolean("removed", v) }

    val joined: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty()

    fun clearMembership() = sp.edit {
        remove("token"); remove("member_id"); remove("family"); remove("event_cursor"); putBoolean("sharing", true)
    }

    companion object {
        const val DEFAULT_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val DEFAULT_ATTRIBUTION = "© OpenStreetMap contributors"
        val INTERVAL_CHOICES = listOf(1, 2, 5, 15)
        val LOW_BATTERY_CHOICES = listOf(15, 10)
    }
}
