package io.github.spasm0dic.thereabouts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The "I've seen it" button on an SOS notification: tells the person, and quiets the alarm. */
class SeenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val memberId = intent.getStringExtra(EXTRA_MEMBER) ?: return
        val prefs = Prefs(context)
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
    }
}
