package io.github.spasm0dic.famlogic

import android.content.Context

/** The FOSS build: no Google Play services. */
object Flavor {
    const val GOOGLE = false
    fun playServicesAvailable(context: Context) = false
    fun fusedLocation(context: Context): FusedLocation? = null
    fun motionSensing(context: Context): MotionSensing? = null
}
