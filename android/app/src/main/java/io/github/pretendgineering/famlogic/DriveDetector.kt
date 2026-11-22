package io.github.pretendgineering.famlogic

import android.location.Location

/**
 * Decides whether this phone's person is driving, from GPS speed alone (no
 * Google activity recognition, so it works on de-Googled phones).
 *
 * One fast fix (over ~25 km/h) makes it a "candidate": the phone then updates
 * every minute to check. A second fast fix at least 20 s later confirms it.
 * It stays "driving" through stops of up to 3 minutes (lights, traffic).
 *
 * In the Google edition, Google's activity recognition can take over ([useMotion]):
 * then "in a vehicle" from the phone's motion sensors decides, and GPS speed is ignored.
 */
class DriveDetector {
    var driving = false
        private set
    var candidate = false
        private set
    private var firstFastAt = 0L
    private var lastFastAt = 0L
    private var prev: Location? = null

    /** True while Google's activity recognition decides (Google edition); GPS speed is then ignored. */
    var useMotion = false
        set(v) {
            if (field != v) { driving = false; candidate = false }
            field = v
        }

    /** Activity recognition says we got into (true) or out of (false) a vehicle; returns true if [driving] changed. */
    fun onVehicle(inVehicle: Boolean): Boolean {
        if (!useMotion) return false
        val changed = driving != inVehicle
        driving = inVehicle
        candidate = false
        return changed
    }

    /** Feeds a fix; returns true if [driving] or [candidate] changed. */
    fun onFix(loc: Location): Boolean {
        val before = driving to candidate
        val speed = speedOf(loc, prev)
        if (prev == null || loc.time > prev!!.time) prev = loc
        if (useMotion) return false
        val now = loc.time
        if (speed != null && speed >= FAST_MPS) {
            when {
                !driving && !candidate -> { candidate = true; firstFastAt = now }
                candidate && now - firstFastAt >= CONFIRM_MS -> { driving = true; candidate = false }
            }
            lastFastAt = now
        } else if (now - lastFastAt > HOLD_MS) {
            driving = false
            candidate = false
        }
        return before != (driving to candidate)
    }

    fun reset() {
        driving = false
        candidate = false
        prev = null
    }

    companion object {
        const val FAST_MPS = 7f // about 25 km/h or 15 mph
        const val CONFIRM_MS = 20_000L
        const val HOLD_MS = 3 * 60_000L

        /** The phone's own speed if it has one, else worked out from the last fix. */
        fun speedOf(loc: Location, prev: Location?): Float? {
            if (loc.accuracy > 100) return null
            if (loc.hasSpeed() && loc.speed > 0f) return loc.speed
            val p = prev ?: return null
            val dt = (loc.time - p.time) / 1000f
            if (dt < 15 || dt > 600 || p.accuracy > 100) return null
            return p.distanceTo(loc) / dt
        }
    }
}
