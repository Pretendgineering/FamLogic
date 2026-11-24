package io.github.pretendgineering.famlogic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What this person may use, and how this device is used. Parents choose per child
 * (web console → Access & Control), starting from the "younger" or
 * "older" preset; adults can use everything. Kept as Compose state so the whole
 * app follows changes at once, and in Prefs so it holds offline.
 */
object Access {
    /** Sections and features switched on for a child; null = an adult (or not heard yet): everything. */
    var allowed by mutableStateOf<Set<String>?>(null)
    var preset by mutableStateOf("")
    /** Home screen tiles, in order; empty = the default for this person. */
    var home by mutableStateOf<List<String>>(emptyList())
    var pinSet by mutableStateOf(false)
    var isChild by mutableStateOf(false)
    /** "phone", "interactive" (a tablet people use) or "display" (a family dashboard on the wall). */
    var deviceMode by mutableStateOf("phone")
    /** parent, child, grandparent or dependent. */
    var role by mutableStateOf("parent")
    /** Bigger buttons and text; picture icons (a tiny map, a talking face). */
    var big by mutableStateOf(false)
    var pictures by mutableStateOf(false)
    /** "Map, Chat" instead of "Thereabouts, Chitchat" (this device). */
    var plainNames by mutableStateOf(false)
    /** A parent opened settings on this device from afar, until then (ms). */
    var unlockedUntil by mutableStateOf(0L)
    var canSignIn by mutableStateOf(true)

    fun can(id: String): Boolean = allowed?.contains(id) ?: true

    /** A module's level for a child: off, view, add or full ("full" for everyone else). */
    fun level(module: String): String {
        val a = allowed ?: return "full"
        return a.firstOrNull { it.startsWith("level:$module:") }?.substringAfterLast(':') ?: if (module in a) "full" else "full"
    }

    val unlocked get() = unlockedUntil > System.currentTimeMillis()
    /** Younger children have no settings button (a parent opens them: hold the family name, or from afar). */
    val hideSettings get() = isChild && preset == "younger" && !unlocked
    /** Older children get their own small Settings (Customize, Account, About). */
    val limitedSettings get() = isChild && !unlocked

    fun setPlainNames(context: Context, on: Boolean) {
        Prefs(context).plainNames = on
        plainNames = on
    }

    val isDisplay get() = deviceMode == "display"
    /** Settings open with the parent PIN on children's devices and family displays. */
    val settingsLocked get() = (isChild || isDisplay) && !unlocked
    /** A child who can't read yet: bigger pictures, fewer words. */
    val younger get() = isChild && preset == "younger"

    fun load(context: Context) {
        val p = Prefs(context)
        isChild = p.role == "child"
        role = p.role
        big = p.bigUi
        pictures = p.pictureIcons
        plainNames = p.plainNames
        canSignIn = p.canUsePassword
        allowed = if (isChild) p.kidAllowed else null
        preset = p.kidPreset
        home = p.homeTiles
        pinSet = p.pinSet
        deviceMode = p.deviceMode
    }

    /** From the server's reply to /api/members. */
    fun update(context: Context, res: MembersResponse) {
        val p = Prefs(context)
        p.kidAllowed = res.kidAllowed
        p.kidPreset = res.kidPreset
        p.homeTiles = res.home
        p.pinSet = res.pinSet
        p.bigUi = res.big
        p.pictureIcons = res.pictures
        load(context)
    }

    fun setDeviceMode(context: Context, mode: String) {
        Prefs(context).deviceMode = mode
        deviceMode = mode
    }

    /** The default home screen for this person. */
    fun defaultHome(): List<String> = when {
        isDisplay -> listOf("map:big", "chat:half", "calendar:half", "todos:half", "chores:half", "meals:half")
        younger -> listOf("map:wide", "chat:wide", "sos:wide")
        else -> listOf("map:wide", "chat:wide", "checkin:half", "sos:half", "calendar:half", "todos:half", "chores:half", "meals:half")
    }

    /** A module's name: its own (Thereabouts, Chitchat…) or the plain one (Map, Chat…). */
    fun name(id: String): String {
        val (own, plain) = NAMES[id] ?: return id
        return if (plainNames) plain else own
    }

    private val NAMES = mapOf(
        "map" to ("Thereabouts" to "Map"), "thereabouts" to ("Thereabouts" to "Map"),
        "chat" to ("Chitchat" to "Chat"), "calendar" to ("Datebook" to "Calendar"),
        "todos" to ("Odds & Ends" to "Lists"), "chores" to ("Upkeep" to "Chores"),
        "meals" to ("Pantry" to "Meals"), "display" to ("Corkboard" to "Family display"),
        "agents" to ("Steward" to "AI"), "sos" to ("SOS" to "SOS"), "checkin" to ("Check in" to "Check in"),
        "steward" to ("Steward" to "AI"), "routines" to ("Rhythms" to "Routines"), "nudges" to ("Nudges" to "Nudges"),
        "ride" to ("Where's My Ride" to "Where's my ride"), "convo.new" to ("Chat shortcut" to "Chat shortcut"),
    )
}
