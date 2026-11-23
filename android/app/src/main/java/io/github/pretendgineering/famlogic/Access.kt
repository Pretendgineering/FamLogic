package io.github.pretendgineering.famlogic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What this person may use, and how this device is used. Parents choose per child
 * (web console → Family → What they can use), starting from the "younger" or
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

    fun can(id: String): Boolean = allowed?.contains(id) ?: true

    val isDisplay get() = deviceMode == "display"
    /** Settings open with the parent PIN on children's devices and family displays. */
    val settingsLocked get() = isChild || isDisplay
    /** A child who can't read yet: bigger pictures, fewer words. */
    val younger get() = isChild && preset == "younger"

    fun load(context: Context) {
        val p = Prefs(context)
        isChild = p.role == "child"
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
        load(context)
    }

    fun setDeviceMode(context: Context, mode: String) {
        Prefs(context).deviceMode = mode
        deviceMode = mode
    }

    /** The default home screen for this person. */
    fun defaultHome(): List<String> = when {
        isDisplay -> listOf("map", "chat", "calendar", "todos", "chores", "meals")
        younger -> listOf("chat", "sos", "map", "checkin")
        else -> listOf("map", "chat", "checkin", "sos", "calendar", "todos", "chores", "meals")
    }
}
