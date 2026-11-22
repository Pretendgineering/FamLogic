package io.github.pretendgineering.famlogic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resume sharing after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // The service decides what it may do from the background: sharing location needs
        // "Allow all the time"; listening for alerts doesn't.
        LocationService.start(context, fromBackground = true)
    }
}
