package io.github.spasm0dic.libre360

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    // Which screen is showing. Kept here so a deep link can switch to setup.
    private var joined by mutableStateOf(false)
    private var invite by mutableStateOf<Pair<String, String>?>(null) // server, code from an invite link

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        joined = prefs.joined
        handleIntent(intent)

        setContent {
            Libre360Theme {
                if (joined) {
                    MapScreen(
                        prefs = prefs,
                        onLeft = { joined = false },
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
        val data: Uri = intent?.data ?: return
        if (data.scheme != "libre360" || data.host != "join") return
        val server = data.getQueryParameter("server").orEmpty()
        val code = data.getQueryParameter("code").orEmpty()
        if (server.isEmpty() && code.isEmpty()) return
        // Only switch to the join screen if we're not already in a family; otherwise
        // the person can leave from settings first. Never silently replace a membership.
        if (!prefs.joined) invite = server to code
        else android.widget.Toast.makeText(this, "Leave your current family in Settings before joining another.", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun Libre360Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF6D9BFF))
        else -> lightColorScheme(primary = Color(0xFF2F6FED))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
