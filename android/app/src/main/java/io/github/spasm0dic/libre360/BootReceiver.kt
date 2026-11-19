package io.github.spasm0dic.libre360

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resume sharing after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Starting a location service from the background needs "Allow all the time".
        if (LocationService.hasBackgroundPermission(context)) LocationService.start(context)
    }
}
