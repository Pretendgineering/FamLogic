package io.github.spasm0dic.thereabouts

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun SetupScreen(prefs: Prefs, initialServer: String, initialCode: String, onJoined: () -> Unit) {
    var server by remember(initialServer) { mutableStateOf(initialServer) }
    var code by remember(initialCode) { mutableStateOf(initialCode) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val removed = remember { prefs.removedNotice }
    val scope = rememberCoroutineScope()

    val url = normalizeServerUrl(server)
    val insecure = url.startsWith("http://") && !isPrivateHost(Uri.parse(url).host.orEmpty())

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Thereabouts", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Private location sharing for your family, on your own server.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (removed) {
                Text(
                    "This phone was removed from the family. Ask for a new invite to join again.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Tap the invite link you were sent, or type the details from it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it; error = null },
                label = { Text("Server address") },
                placeholder = { Text("https://family.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (insecure) {
                Text(
                    "Warning: this address isn't encrypted (http). Use https for a server on the internet.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; error = null },
                label = { Text("Invite code") },
                placeholder = { Text("ABCD-EFGH") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Your name (optional)") },
                supportingText = { Text("Leave empty to use the name on the invite") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && url.isNotEmpty() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val res = Api(url).join(code.trim(), name.trim())
                            prefs.serverUrl = url
                            prefs.token = res.token
                            prefs.memberId = res.member.id
                            prefs.familyName = res.server.name
                            prefs.tileUrl = res.server.tileUrl
                            prefs.tileAttribution = res.server.tileAttribution
                            prefs.sharing = true
                            prefs.removedNotice = false
                            onJoined()
                        } catch (e: ApiException) {
                            error = e.message
                        } catch (e: IOException) {
                            error = "Couldn't reach the server. Check the address and your connection. (${e.message})"
                        } catch (e: Exception) {
                            error = "That doesn't look like a Thereabouts server."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Join")
            }
        }
    }
}

/** Accept "family.example.com" or "192.168.1.5:8080" and add a scheme. */
fun normalizeServerUrl(input: String): String {
    val s = input.trim().trimEnd('/')
    if (s.isEmpty()) return ""
    if (s.startsWith("http://") || s.startsWith("https://")) return s
    val host = s.substringBefore('/').substringBefore(':')
    return (if (isPrivateHost(host)) "http://" else "https://") + s
}

/** Home network, VPN (Tailscale) or local names, where plain http is reasonable. */
fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")) return true
    val parts = h.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    val (a, b) = parts
    return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
}
