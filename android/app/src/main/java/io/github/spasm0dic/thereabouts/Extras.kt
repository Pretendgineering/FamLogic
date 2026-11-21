package io.github.spasm0dic.thereabouts

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat

/*
 * Google Play services extras. The Google edition (src/google) provides them; the FOSS
 * build (src/foss) has none, and the app uses Android's own location and GPS speed.
 * The Google edition also falls back to those whenever Google Play services is missing
 * or something fails, so it always works.
 */

/** Google's fused location provider. */
interface FusedLocation {
    /** Starts (or restarts) updates. [onFailed] is called if Google refuses later. False if it can't start. */
    fun start(intervalMs: Long, highAccuracy: Boolean, onFix: (Location) -> Unit, onFailed: () -> Unit): Boolean
    fun stop()
}

/** Google's activity recognition: getting into and out of a vehicle. */
interface MotionSensing {
    /** Starts listening. [onFailed] is called if Google refuses later. False if it can't start. */
    fun start(onVehicle: (Boolean) -> Unit, onFailed: () -> Unit): Boolean
    fun stop()
}

object Motion {
    /** "Physical activity" permission, needed for activity recognition from Android 10. */
    const val PERMISSION = "android.permission.ACTIVITY_RECOGNITION" // Manifest.permission.ACTIVITY_RECOGNITION (API 29)

    fun permitted(context: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
