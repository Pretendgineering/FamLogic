package io.github.pretendgineering.famlogic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Someone's SOS, full screen (even over the lock screen): who, where, how long ago,
 * and what to do: call them, get directions, tell them you're on your way, or just
 * let them know you've seen it. The alarm keeps ringing until this is answered.
 */
class SosAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val id = intent.getStringExtra(EXTRA_MEMBER).orEmpty()
        val prefs = Prefs(this)
        setContent { FamLogicTheme { SosScreen(this, prefs, id, intent.getStringExtra(EXTRA_NAME).orEmpty()) } }
    }

    companion object {
        const val EXTRA_MEMBER = "member"
        const val EXTRA_NAME = "name"

        fun intent(context: Context, memberId: String, name: String) =
            Intent(context, SosAlertActivity::class.java).putExtra(EXTRA_MEMBER, memberId).putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@androidx.compose.runtime.Composable
private fun SosScreen(activity: Activity, prefs: Prefs, id: String, fallbackName: String) {
    val scope = rememberCoroutineScope()
    var who by remember { mutableStateOf<Member?>(null) }
    var me by remember { mutableStateOf<Member?>(null) }
    var said by remember { mutableStateOf("") }
    LaunchedEffect(id) {
        while (true) {
            runCatching { Api(prefs.serverUrl, prefs.token).members(since = Long.MAX_VALUE / 2) }.getOrNull()?.let { r ->
                who = r.members.find { it.id == id }
                me = r.members.find { it.id == prefs.memberId }
            }
            delay(10_000)
        }
    }
    fun silence() = NotificationManagerCompat.from(activity).cancel(Alerts.sosId(id))
    val m = who
    val name = m?.name ?: fallbackName.ifEmpty { "Someone" }
    val ended = m != null && m.sosSince == 0L
    Column(
        Modifier.fillMaxSize().background(if (ended) MaterialTheme.colorScheme.surface else Color(0xFF8E1B2B)).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val fg = if (ended) MaterialTheme.colorScheme.onSurface else Color.White
        Text(if (ended) "$name is OK" else "SOS", style = MaterialTheme.typography.displayMedium, color = fg, fontWeight = FontWeight.Bold)
        if (!ended) Text("$name needs help", style = MaterialTheme.typography.headlineSmall, color = fg, textAlign = TextAlign.Center)
        val loc = m?.loc
        val where = when {
            m == null -> "Finding where they are…"
            m.paused || loc == null -> "Their location isn't known."
            m.place.isNotEmpty() -> "At ${m.place}"
            else -> "%.5f, %.5f".format(loc.lat, loc.lon)
        }
        Text(where, style = MaterialTheme.typography.titleLarge, color = fg, textAlign = TextAlign.Center)
        m?.takeIf { it.sosSince > 0 }?.let { Text("Started ${timeAgo(it.sosSince)}", color = fg.copy(alpha = .85f)) }
        if (loc != null && me?.loc != null) {
            val d = FloatArray(1).also { android.location.Location.distanceBetween(me!!.loc!!.lat, me!!.loc!!.lon, loc.lat, loc.lon, it) }[0]
            Text("${Units.distance(d.toDouble())} from you", color = fg.copy(alpha = .85f))
        }
        if (m != null && m.sosSeenBy.isNotEmpty()) Text("Seen by ${m.sosSeenBy.joinToString(", ")}", color = fg.copy(alpha = .85f))
        if (said.isNotEmpty()) Text(said, color = fg, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val big = Modifier.fillMaxWidth().height(60.dp)
            val colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF8E1B2B))
            m?.phone?.takeIf { it.isNotEmpty() }?.let { phone ->
                Button(onClick = { silence(); activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }, big, colors = colors) {
                    Text("Call $name", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (!ended) Button(onClick = {
                silence()
                scope.launch {
                    val api = Api(prefs.serverUrl, prefs.token)
                    runCatching { api.sosSeen(id) }
                    said = if (runCatching { api.request(id, "coming", currentLocation(activity)) }.isSuccess) "They know you're on your way." else "Couldn't reach the server."
                }
            }, big, colors = colors) { Text("I'm on my way", style = MaterialTheme.typography.titleMedium) }
            if (loc != null) Button(onClick = {
                silence()
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${loc.lat},${loc.lon}(${Uri.encode(name)})")))
            }, big, colors = colors) { Text("Directions", style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    silence()
                    scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).sosSeen(id) }; said = "They know you've seen it." }
                }, Modifier.weight(1f).height(52.dp)) { Text("I've seen it", color = fg) }
                OutlinedButton(onClick = {
                    silence()
                    activity.startActivity(Intent(activity, MainActivity::class.java).putExtra(Alerts.EXTRA_FOCUS_MEMBER, id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    activity.finish()
                }, Modifier.weight(1f).height(52.dp)) { Text("Open the map", color = fg) }
            }
        }
    }
}

private fun timeAgo(ms: Long): String {
    val min = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        else -> "${min / 60} h ${min % 60} min ago"
    }
}
