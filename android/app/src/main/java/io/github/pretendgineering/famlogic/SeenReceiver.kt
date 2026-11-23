package io.github.pretendgineering.famlogic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notification buttons: "I've seen it" on an SOS (tells the person, and quiets the
 * alarm), and "Send where I am" on a "where are you?" nudge (tells just them).
 */
class SeenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val memberId = intent.getStringExtra(EXTRA_MEMBER) ?: return
        val prefs = Prefs(context)
        if (intent.action == ACTION_NUDGE) {
            NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
            val kind = intent.getStringExtra(EXTRA_KIND) ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runCatching { Api(prefs.serverUrl, prefs.token).request(memberId, kind, null) }
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.action == ACTION_HERE) {
            NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runCatching { Api(prefs.serverUrl, prefs.token).request(memberId, "here", currentLocation(context)) }
                } finally {
                    pending.finish()
                }
            }
            return
        }
        // Stop the ringing straight away; the SOS stays visible in the app.
        NotificationManagerCompat.from(context).cancel(Alerts.sosId(memberId))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { Api(prefs.serverUrl, prefs.token).sosSeen(memberId) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_MEMBER = "member"
        const val EXTRA_ID = "id"
        const val ACTION_HERE = "io.github.pretendgineering.famlogic.HERE"
        const val ACTION_NUDGE = "io.github.pretendgineering.famlogic.NUDGE"
        const val EXTRA_KIND = "kind"
    }
}
