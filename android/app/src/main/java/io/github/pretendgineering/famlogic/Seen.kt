package io.github.pretendgineering.famlogic

import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * How long ago someone's location is from, worded the same way everywhere (the
 * map, the home screen, the watch). Honest about old locations: once a phone has
 * been quiet for a while it says "last seen", so nobody mistakes an old spot for
 * where someone is now.
 */
object Seen {
    /** "just now", "5 min ago", "2 h ago", "yesterday 18:40", "Tue 09:12". */
    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((now - time) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 12 * 60 -> "${min / 60} h ago"
            else -> {
                val clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
                val then = Calendar.getInstance().apply { timeInMillis = time }
                val today = Calendar.getInstance().apply { timeInMillis = now }
                val days = today.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) +
                    365 * (today.get(Calendar.YEAR) - then.get(Calendar.YEAR))
                when (days) {
                    0 -> "today $clock"
                    1 -> "yesterday $clock"
                    in 2..6 -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(time)) + " $clock"
                    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
                }
            }
        }
    }

    /** "now", "5m", "2h", "3d": for pins and the watch. */
    fun short(time: Long, now: Long = System.currentTimeMillis()): String = shortAgo(time, now)

    /** Whether a location is old enough that it may no longer be where they are. */
    fun stale(time: Long, now: Long = System.currentTimeMillis()) = now - time > STALE_MS

    /**
     * Where someone is and how fresh that is: "At School · 5 min ago", "Driving ·
     * just now", "Last seen at Home 3 h ago", "Last seen yesterday 18:40".
     */
    fun where(m: Member, now: Long = System.currentTimeMillis()): String {
        if (m.paused) return "Location sharing paused"
        val loc = m.loc ?: return "No location yet"
        val ago = ago(loc.time, now)
        return when {
            stale(loc.time, now) -> if (m.place.isNotEmpty()) "Last seen at ${m.place} $ago" else "Last seen $ago"
            isDriving(m) -> "Driving · $ago"
            m.place.isNotEmpty() -> "At ${m.place} · $ago"
            else -> ago.replaceFirstChar { it.uppercase() }
        }
    }
}
