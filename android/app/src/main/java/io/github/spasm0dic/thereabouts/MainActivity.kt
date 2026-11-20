package io.github.spasm0dic.thereabouts

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    // Which screen is showing. Kept here so a deep link can switch to setup.
    private var joined by mutableStateOf(false)
    private var invite by mutableStateOf<Pair<String, String>?>(null) // server, code from an invite link
    private var focusMember by mutableStateOf<String?>(null) // from tapping an alert about someone
    private var joinCircleCode by mutableStateOf<String?>(null) // an invite link opened while signed in

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        joined = prefs.joined
        handleIntent(intent)

        setContent {
            ThereaboutsTheme {
                if (joined) {
                    MapScreen(
                        prefs = prefs,
                        onLeft = { joined = false },
                        focusMember = focusMember,
                        onFocusHandled = { focusMember = null },
                        joinCircleCode = joinCircleCode,
                        onJoinCircleHandled = { joinCircleCode = null },
                    )
                } else {
                    SetupScreen(
                        prefs = prefs,
                        initialServer = invite?.first ?: prefs.serverUrl,
                        initialCode = invite?.second ?: "",
                        onJoined = {
                            invite = null
                            joined = true
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The service may have found out we were removed while the app was closed.
        joined = prefs.joined
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(Alerts.EXTRA_FOCUS_MEMBER)?.let { focusMember = it }
        val data: Uri = intent?.data ?: return
        // libre360:// is from before the project was renamed; old invite links still work.
        if (data.scheme !in setOf("thereabouts", "libre360") || data.host != "join") return
        val server = data.getQueryParameter("server").orEmpty()
        val code = data.getQueryParameter("code").orEmpty()
        if (server.isEmpty() && code.isEmpty()) return
        // Not signed in: the join screen. Signed in on the same server: offer to join
        // that invite's circle as yourself. A different server: we can't mix them.
        when {
            !prefs.joined -> invite = server to code
            normalizeServerUrl(server).trimEnd('/') == prefs.serverUrl.trimEnd('/') || server.isEmpty() -> joinCircleCode = code
            else -> android.widget.Toast.makeText(
                this, "That invite is for a different server. Leave this one in Settings first to join it.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
