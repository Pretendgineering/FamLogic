package io.github.pretendgineering.famlogic

import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
    private var tab by mutableStateOf(Tab.Home)
    private var settingsRequest by mutableStateOf<String?>(null) // the home screen asked for settings: "full" or "limited"
    private var openChat by mutableStateOf<String?>(null) // from tapping a chat notification

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        ThemeState.load(this)
        Access.load(this)
        joined = prefs.joined
        features = prefs.serverFeatures
        handleIntent(intent)

        setContent {
            FamLogicTheme {
                // The system bars follow the app's light/dark choice, not the phone's, so a light app on
                // a dark phone doesn't get dark blocks (or the wrong clock colour) at the top.
                val dark = LocalDarkTheme.current
                androidx.compose.runtime.LaunchedEffect(dark) {
                    val clear = android.graphics.Color.TRANSPARENT
                    val style = if (dark) androidx.activity.SystemBarStyle.dark(clear) else androidx.activity.SystemBarStyle.light(clear, clear)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                // The app starts below the status bar (and any camera cutout): nothing is drawn under the clock.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top))),
                ) {
                if (joined) {
                    DisplayModeEffects(window)
                    HomeShell(
                        prefs = prefs,
                        features = features,
                        tab = tab,
                        onTab = { tab = it },
                    ) { current ->
                        // Settings → Arrange home screen: back to Home, where the editor opens.
                        LaunchedEffect(HomeEditor.requested) { if (HomeEditor.requested) tab = Tab.Home }
                        when (current) {
                            Tab.Home -> HomeScreen(
                                prefs = prefs,
                                features = features,
                                onOpen = { tab = it },
                                onOpenSettings = { full -> settingsRequest = if (full) "full" else "limited"; tab = Tab.Map },
                                onOpenChat = { id -> openChat = id; tab = Tab.Chat },
                            )
                            Tab.Chat -> ChatScreen(prefs, openRequest = openChat, onOpenHandled = { openChat = null })
                            Tab.Map -> MapScreen(
                                prefs = prefs,
                                onLeft = { joined = false },
                                focusMember = focusMember,
                                onFocusHandled = { focusMember = null },
                                joinCircleCode = joinCircleCode,
                                onJoinCircleHandled = { joinCircleCode = null },
                                onFeatures = { features = it },
                                openSettings = settingsRequest,
                                onSettingsHandled = { settingsRequest = null },
                                // Someone without the map came here only for settings: back home after.
                                onSettingsClosed = { if (Tab.Map !in visibleTabs(features, prefs)) tab = Tab.Home },
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

    /**
     * A family display: full screen (no status or navigation bars) and, while it's
     * charging, the screen stays on.
     */
    @Composable
    private fun DisplayModeEffects(window: android.view.Window) {
        val display = Access.isDisplay
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.DisposableEffect(display) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (display) {
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            fun keepOn(plugged: Boolean) {
                if (display && plugged) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: Intent) {
                    keepOn(i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0)
                }
            }
            val sticky = androidx.core.content.ContextCompat.registerReceiver(
                context, receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            keepOn((sticky?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            onDispose {
                context.unregisterReceiver(receiver)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(Alerts.EXTRA_FOCUS_MEMBER)?.let { focusMember = it; tab = if (Access.can("map")) Tab.Map else Tab.Home }
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
