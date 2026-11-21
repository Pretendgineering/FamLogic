package io.github.spasm0dic.thereabouts

import android.content.Context

/** The FOSS build: no Google Play services. */
object Flavor {
    const val PLUS = false
    fun playServicesAvailable(context: Context) = false
    fun fusedLocation(context: Context): FusedLocation? = null
    fun motionSensing(context: Context): MotionSensing? = null
}
