package io.github.pretendgineering.famlogic

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Everything the app remembers, in plain SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("famlogic", Context.MODE_PRIVATE)

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

    /** Dim light maps a little in dark mode. */
    var dimMap: Boolean
        get() = sp.getBoolean("dim_map", true)
        set(v) = sp.edit { putBoolean("dim_map", v) }

    /** The map style picked on this phone (see [MapStyles]). */
    var mapStyle: String
        get() = sp.getString("map_style", MapStyles.SERVER) ?: MapStyles.SERVER
        set(v) = sp.edit { putString("map_style", v) }

    /** Tiles for dark mode ("" = use [tileUrl]). */
    var tileUrlDark: String
        get() = sp.getString("tile_url_dark", "") ?: ""
        set(v) = sp.edit { putString("tile_url_dark", v) }

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

    /** Feature: keep a connection to the server so alerts (SOS above all) arrive in seconds. */
    var instantAlerts: Boolean
        get() = sp.getBoolean("instant_alerts", true)
        set(v) = sp.edit { putBoolean("instant_alerts", v) }

    /** This person has an SOS active (until they say they're OK). */
    var sosActive: Boolean
        get() = sp.getBoolean("sos_active", false)
        set(v) = sp.edit { putBoolean("sos_active", v) }

    /** An SOS / "I'm OK" that still has to reach the server (the service keeps retrying). */
    var sosPending: Boolean
        get() = sp.getBoolean("sos_pending", false)
        set(v) = sp.edit { putBoolean("sos_pending", v) }
    /** The last SOS couldn't be sent at all (server too old); shown until dismissed. */
    var sosFailed: Boolean
        get() = sp.getBoolean("sos_failed", false)
        set(v) = sp.edit { putBoolean("sos_failed", v) }
    var sosEndPending: Boolean
        get() = sp.getBoolean("sos_end_pending", false)
        set(v) = sp.edit { putBoolean("sos_end_pending", v) }

    /** Features: buttons on the map and which alerts to get. */
    var showSosButton: Boolean
        get() = sp.getBoolean("show_sos", true)
        set(v) = sp.edit { putBoolean("show_sos", v) }
    var notifySos: Boolean
        get() = sp.getBoolean("notify_sos", true)
        set(v) = sp.edit { putBoolean("notify_sos", v) }
    var showCheckInButton: Boolean
        get() = sp.getBoolean("show_checkin", true)
        set(v) = sp.edit { putBoolean("show_checkin", v) }
    var notifyCheckIn: Boolean
        get() = sp.getBoolean("notify_checkin", true)
        set(v) = sp.edit { putBoolean("notify_checkin", v) }

    /** Feature: which place alerts this phone shows, and places muted here. */
    var notifyArrivals: Boolean
        get() = sp.getBoolean("notify_arrivals", true)
        set(v) = sp.edit { putBoolean("notify_arrivals", v) }
    var notifyDepartures: Boolean
        get() = sp.getBoolean("notify_departures", true)
        set(v) = sp.edit { putBoolean("notify_departures", v) }
    var mutedPlaces: Set<String>
        get() = sp.getStringSet("muted_places", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet("muted_places", v) }

    /** Feature: draw someone's trail on the map when you tap them. */
    var showTrails: Boolean
        get() = sp.getBoolean("show_trails", true)
        set(v) = sp.edit { putBoolean("show_trails", v) }

    /**
     * Wi-Fi positioning: when there's no GPS or network fix (a Wi-Fi-only tablet,
     * say), look up nearby Wi-Fi networks with beaconDB. Off by default because it
     * sends the networks' hardware addresses to beaconDB.
     */
    var wifiPositioning: Boolean
        get() = sp.getBoolean("wifi_positioning", false)
        set(v) = sp.edit { putBoolean("wifi_positioning", v) }

    /** People whose alerts (all but SOS) this phone doesn't show. */
    var mutedPeople: Set<String>
        get() = sp.getStringSet("muted_people", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet("muted_people", v) }

    /** Groups whose fence alerts this phone doesn't show. */
    var mutedGroups: Set<String>
        get() = sp.getStringSet("muted_groups", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet("muted_groups", v) }

    /** Feature: "Sam's phone hasn't been in touch for hours". */
    var notifySilent: Boolean
        get() = sp.getBoolean("notify_silent", true)
        set(v) = sp.edit { putBoolean("notify_silent", v) }

    /** Feature: "Sam: Home → School, 12 min" when someone finishes a trip. Off by default. */
    var notifyTrips: Boolean
        get() = sp.getBoolean("notify_trips", false)
        set(v) = sp.edit { putBoolean("notify_trips", v) }

    // ---- Pebble ----

    /** Last battery level the Pebble reported (1-100), and when. */
    var watchBattery: Int
        get() = sp.getInt("watch_battery", 0)
        set(v) = sp.edit { putInt("watch_battery", v) }
    var watchBatteryAt: Long
        get() = sp.getLong("watch_battery_at", 0)
        set(v) = sp.edit { putLong("watch_battery_at", v) }

    /** Where a pending SOS came from ("watch", "fall", or "" for the phone) and the heart rate to send. */
    var sosSource: String
        get() = sp.getString("sos_source", "") ?: ""
        set(v) = sp.edit { putString("sos_source", v) }
    var sosHeartRate: Int
        get() = sp.getInt("sos_heart_rate", 0)
        set(v) = sp.edit { putInt("sos_heart_rate", v) }

    /** Send the watch's heart rate with an SOS raised from it. Off by default. */
    var pebbleHeartRate: Boolean
        get() = sp.getBoolean("pebble_heart_rate", false)
        set(v) = sp.edit { putBoolean("pebble_heart_rate", v) }

    /** Watch fall detection (counts down, then sends an SOS). Off by default. */
    var pebbleFalls: Boolean
        get() = sp.getBoolean("pebble_falls", false)
        set(v) = sp.edit { putBoolean("pebble_falls", v) }

    /** Arrivals, check-ins, trips and reminders as timeline pins on the watch. */
    var pebbleTimeline: Boolean
        get() = sp.getBoolean("pebble_timeline", true)
        set(v) = sp.edit { putBoolean("pebble_timeline", v) }

    /** This device's colour palette (see Theme.kt): dracula, purple, paper, forest, ocean or contrast. */
    var appTheme: String
        get() = sp.getString("app_theme", "dracula") ?: "dracula"
        set(v) = sp.edit { putString("app_theme", v) }

    /** "system", "light" or "dark". */
    var darkMode: String
        get() = sp.getString("dark_mode", "system") ?: "system"
        set(v) = sp.edit { putString("dark_mode", v) }

    /** Whether the family has the Thereabouts (location) module on; from the server. */
    var locationModule: Boolean
        get() = sp.getBoolean("module_thereabouts", true)
        set(v) = sp.edit { putBoolean("module_thereabouts", v) }

    /** This person's role in the family, "parent" or "child"; from the server. */
    var role: String
        get() = sp.getString("role", "parent") ?: "parent"
        set(v) = sp.edit { putString("role", v) }

    /** admin, parent, older_child or younger_child; from the server. */
    var access: String
        get() = sp.getString("access", "parent") ?: "parent"
        set(v) = sp.edit { putString("access", v) }

    /** Younger children sign in with a parent's code, never a password. */
    val canUsePassword: Boolean get() = access != "younger_child"

    /** What the family's server can do, and which modules are on (its "features"). */
    var serverFeatures: Set<String>
        get() = sp.getStringSet("server_features", null) ?: emptySet()
        set(v) = sp.edit { putStringSet("server_features", v) }

    /** A child's switched-on sections and features (see Access); empty until the server says. */
    var kidAllowed: Set<String>?
        get() = if (sp.contains("kid_allowed")) sp.getStringSet("kid_allowed", emptySet()) else null
        set(v) = sp.edit { if (v == null) remove("kid_allowed") else putStringSet("kid_allowed", v) }
    var kidPreset: String
        get() = sp.getString("kid_preset", "") ?: ""
        set(v) = sp.edit { putString("kid_preset", v) }
    /** Home screen tiles, in order (empty = default). */
    /** Module tiles this person has already been given (or removed): a new module's tile joins Home once, then it's theirs to keep or remove. Null until first set. */
    var seenTiles: Set<String>?
        get() = if (sp.contains("seen_tiles")) sp.getStringSet("seen_tiles", emptySet())?.toSet() else null
        set(v) = sp.edit { putStringSet("seen_tiles", v ?: emptySet()) }
    var homeTiles: List<String>
        get() = (sp.getString("home_tiles", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit { putString("home_tiles", v.joinToString(",")) }
    /** Whether the family has a parent PIN (it unlocks settings on kids' devices and displays). */
    var pinSet: Boolean
        get() = sp.getBoolean("pin_set", false)
        set(v) = sp.edit { putBoolean("pin_set", v) }
    var bigUi: Boolean
        get() = sp.getBoolean("big_ui", false)
        set(v) = sp.edit { putBoolean("big_ui", v) }
    var pictureIcons: Boolean
        get() = sp.getBoolean("picture_icons", false)
        set(v) = sp.edit { putBoolean("picture_icons", v) }
    /** Plain names (Map, Chat…) instead of the modules' own (Thereabouts, Chitchat…). This device only. */
    var plainNames: Boolean
        get() = sp.getBoolean("plain_names", false)
        set(v) = sp.edit { putBoolean("plain_names", v) }

    /** Voice engine: "auto", "phone" (Android's own speech) or "server" (the family's server). */
    /** Tiles across the home screen: 2 or 3 (0, not chosen, means 3). */
    var homeColumns: Int
        get() = sp.getInt("home_columns", 0)
        set(v) = sp.edit { putInt("home_columns", v) }
    /** The chat last opened on this phone (the chat tile's quick button). */
    var lastChat: String
        get() = sp.getString("last_chat", "") ?: ""
        set(v) = sp.edit { putString("last_chat", v) }
    /** Modules this person has hidden on this phone (the family still has them). */
    var hiddenModules: Set<String>
        get() = sp.getStringSet("hidden_modules", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("hidden_modules", v) }
    var voiceEngine: String
        get() = sp.getString("voice_engine", "auto") ?: "auto"
        set(v) = sp.edit { putString("voice_engine", v) }

    /** How this device is used: "phone" or "display" (a Control Deck). This device only. */
    var deviceMode: String
        get() = sp.getString("device_mode", "phone") ?: "phone"
        set(v) = sp.edit { putString("device_mode", v) }

    /** Muted chats: conversation id → muted until (unix ms; Long.MAX_VALUE = until unmuted). This phone only. */
    fun chatMutedUntil(conv: String): Long = sp.getLong("chat_muted_$conv", 0)
    fun muteChat(conv: String, until: Long) = sp.edit { if (until <= 0) remove("chat_muted_$conv") else putLong("chat_muted_$conv", until) }
    fun chatMuted(conv: String) = chatMutedUntil(conv) > System.currentTimeMillis()

    /** The watch's look: "system" (follow the phone's dark mode), "light" or "dark". */
    var pebbleTheme: String
        get() = sp.getString("pebble_theme", "system") ?: "system"
        set(v) = sp.edit { putString("pebble_theme", v) }

    /** Opening the watchapp with Pebble's Quick Launch goes straight to the SOS screen. */
    var pebbleQuickSos: Boolean
        get() = sp.getBoolean("pebble_quick_sos", false)
        set(v) = sp.edit { putBoolean("pebble_quick_sos", v) }

    /** Feature: "Sam started driving". Off by default. */
    var notifyDriving: Boolean
        get() = sp.getBoolean("notify_driving", false)
        set(v) = sp.edit { putBoolean("notify_driving", v) }

    /** Names of the fences this phone knows (id -> name), for trip alerts. */
    var fenceNames: Map<String, String>
        get() = sp.getStringSet("fence_names", emptySet())!!.mapNotNull { e ->
            e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        set(v) = sp.edit { putStringSet("fence_names", v.map { (id, name) -> "$id\t$name" }.toSet()) }

    /** Update less often while this phone's battery is low (and not charging). */
    /** Google edition: detect driving with Google's activity recognition (when permitted). */
    var motionDriving: Boolean
        get() = sp.getBoolean("motion_driving", true)
        set(v) = sp.edit { putBoolean("motion_driving", v) }

    /** Google edition: whether we've asked for the "Physical activity" permission once already. */
    var motionAsked: Boolean
        get() = sp.getBoolean("motion_asked", false)
        set(v) = sp.edit { putBoolean("motion_asked", v) }

    var batterySaver: Boolean
        get() = sp.getBoolean("battery_saver", true)
        set(v) = sp.edit { putBoolean("battery_saver", v) }

    /** Which circle the map shows: "" for everyone. */
    var circleFilter: String
        get() = sp.getString("circle_filter", "") ?: ""
        set(v) = sp.edit { putString("circle_filter", v) }

    /** This person's username, kept after signing out so the sign-in form can be pre-filled. */
    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit { putString("username", v) }

    /**
     * Why this phone was signed out, shown on the sign-in screen: "" (it wasn't),
     * [SIGNED_OUT_REMOVED] or [SIGNED_OUT_ELSEWHERE].
     */
    var signedOutReason: String
        get() = sp.getString("signed_out", "") ?: ""
        set(v) = sp.edit { putString("signed_out", v) }

    val joined: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty()

    /** Forgets this phone's sign-in. The server address and username stay, for signing in again. */
    fun clearMembership() = sp.edit {
        remove("token"); remove("member_id"); remove("family"); remove("event_cursor"); putBoolean("sharing", true)
        remove("sos_active"); remove("sos_pending"); remove("sos_end_pending")
    }

    /** The server stopped accepting this phone; remember why so the sign-in screen can explain. */
    fun signedOut(e: UnauthorizedException) {
        clearMembership()
        signedOutReason = if (e.signedInElsewhere) SIGNED_OUT_ELSEWHERE else SIGNED_OUT_REMOVED
    }

    companion object {
        const val DEFAULT_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val DEFAULT_ATTRIBUTION = "© OpenStreetMap contributors"
        val INTERVAL_CHOICES = listOf(1, 2, 5, 15)
        val LOW_BATTERY_CHOICES = listOf(15, 10)
        const val SIGNED_OUT_REMOVED = "removed"
        const val SIGNED_OUT_ELSEWHERE = "elsewhere"
    }
}
