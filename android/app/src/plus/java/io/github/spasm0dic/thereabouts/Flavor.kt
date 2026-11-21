package io.github.spasm0dic.thereabouts

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** The Plus build: Google Play services extras, used only when Play services works. */
object Flavor {
    const val PLUS = true

    fun playServicesAvailable(context: Context) = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    fun fusedLocation(context: Context): FusedLocation? =
        if (playServicesAvailable(context)) PlayFusedLocation(context.applicationContext) else null

    fun motionSensing(context: Context): MotionSensing? =
        if (playServicesAvailable(context) && Motion.permitted(context)) PlayMotion(context.applicationContext) else null
}

private const val TAG = "ThereaboutsPlus"

private class PlayFusedLocation(context: Context) : FusedLocation {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val main = ContextCompat.getMainExecutor(context)
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // the service checks before asking for location
    override fun start(intervalMs: Long, highAccuracy: Boolean, onFix: (Location) -> Unit, onFailed: () -> Unit): Boolean {
        stop()
        val l = LocationListener { onFix(it) }
        val priority = if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, intervalMs).setMinUpdateIntervalMillis(intervalMs / 2).build()
        return try {
            client.requestLocationUpdates(request, main, l).addOnFailureListener {
                Log.w(TAG, "Fused location refused", it)
                if (listener === l) { listener = null; onFailed() }
            }
            client.lastLocation.addOnSuccessListener { loc -> if (loc != null && listener === l) onFix(loc) }
            listener = l
            true
        } catch (e: Exception) {
            Log.w(TAG, "Fused location unavailable", e)
            false
        }
    }

    override fun stop() {
        listener?.let { runCatching { client.removeLocationUpdates(it) } }
        listener = null
    }
}

private class PlayMotion(private val context: Context) : MotionSensing {
    private val client = ActivityRecognition.getClient(context)
    private val pending = PendingIntent.getBroadcast(
        context, 0, Intent(context, MotionReceiver::class.java),
        // Google adds the result to the intent, so it must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    @SuppressLint("MissingPermission") // Flavor only creates this when permitted
    override fun start(onVehicle: (Boolean) -> Unit, onFailed: () -> Unit): Boolean {
        val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        return try {
            callback = onVehicle
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending).addOnFailureListener {
                Log.w(TAG, "Activity recognition refused", it)
                if (callback === onVehicle) { callback = null; onFailed() }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Activity recognition unavailable", e)
            callback = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        callback = null
        runCatching { client.removeActivityTransitionUpdates(pending) }
    }

    companion object {
        @Volatile var callback: ((Boolean) -> Unit)? = null
    }
}

/** Receives "got into / out of a vehicle" from Google and passes it to the running service. */
class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents
            ?.lastOrNull { it.activityType == DetectedActivity.IN_VEHICLE } ?: return
        PlayMotion.callback?.invoke(last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
    }
}
