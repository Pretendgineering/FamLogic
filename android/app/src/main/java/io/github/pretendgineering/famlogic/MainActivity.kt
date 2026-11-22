package io.github.pretendgineering.famlogic

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
    private var features by mutableStateOf<Set<String>>(emptySet()) // what the server does, and which modules are on
    private var tab by mutableStateOf(Tab.Map)
    private var openChat by mutableStateOf<String?>(null) // from tapping a chat notification

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        ThemeState.load(this)
        joined = prefs.joined
        features = prefs.serverFeatures
        handleIntent(intent)

        setContent {
            FamLogicTheme {
                // Status and navigation bar icons follow the app's light/dark choice, not just the system's.
                val dark = LocalDarkTheme.current
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                if (joined) {
                    HomeShell(
                        prefs = prefs,
                        features = features,
                        tab = tab,
                        onTab = { tab = it },
                    ) { current ->
                        when (current) {
                            Tab.Chat -> ChatScreen(prefs, openRequest = openChat, onOpenHandled = { openChat = null })
                            Tab.Map -> MapScreen(
                                prefs = prefs,
                                onLeft = { joined = false },
                                focusMember = focusMember,
                                onFocusHandled = { focusMember = null },
                                joinCircleCode = joinCircleCode,
                                onJoinCircleHandled = { joinCircleCode = null },
                                onFeatures = { features = it },
                            )
                        }
                    }
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
        intent?.getStringExtra(Alerts.EXTRA_FOCUS_MEMBER)?.let { focusMember = it; tab = Tab.Map }
        intent?.getStringExtra(ChatNotify.EXTRA_OPEN_CHAT)?.let { openChat = it; tab = Tab.Chat }
        val data: Uri = intent?.data ?: return
        if (data.scheme != "famlogic" || data.host != "join") return
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
