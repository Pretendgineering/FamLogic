package io.github.spasm0dic.famlogic

import java.util.Locale

/** Distances and speeds in miles where people use them (US, UK roads, Liberia, Myanmar), else km. */
object Units {
    val imperial get() = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

    /** "350 m", "4.2 km", "0.3 mi", "12 mi" */
    fun distance(meters: Double): String = if (imperial) {
        val mi = meters / 1609.344
        when {
            mi < 0.1 -> "${(meters * 3.28084 / 10).toInt() * 10} ft"
            mi < 10 -> "%.1f mi".format(mi)
            else -> "${mi.toInt()} mi"
        }
    } else {
        when {
            meters < 1000 -> "${(meters / 10).toInt() * 10} m"
            meters < 10_000 -> "%.1f km".format(meters / 1000)
            else -> "${(meters / 1000).toInt()} km"
        }
    }

    /** "48 km/h" or "30 mph", from metres per second. */
    fun speed(mps: Double): String = if (imperial) "${(mps * 2.23694).toInt()} mph" else "${(mps * 3.6).toInt()} km/h"

    /** "12 min", "1 h 5 min" */
    fun duration(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60} min" else ""
    }
}
