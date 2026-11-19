package io.github.spasm0dic.libre360

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Everything the app remembers, in plain SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("libre360", Context.MODE_PRIVATE)

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

    /** Set when the server says this phone was removed, so the UI can explain. */
    var removedNotice: Boolean
        get() = sp.getBoolean("removed", false)
        set(v) = sp.edit { putBoolean("removed", v) }

    val joined: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty()

    fun clearMembership() = sp.edit {
        remove("token"); remove("member_id"); remove("family"); putBoolean("sharing", true)
    }

    companion object {
        const val DEFAULT_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val DEFAULT_ATTRIBUTION = "© OpenStreetMap contributors"
        val INTERVAL_CHOICES = listOf(1, 2, 5, 15)
    }
}
