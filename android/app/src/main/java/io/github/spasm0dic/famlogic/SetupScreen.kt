package io.github.spasm0dic.famlogic

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.IOException

/** Must match the server's minimum (server/password.go). */
const val MIN_PASSWORD_LENGTH = 12

private enum class Mode { Join, SignIn }

/**
 * The first screen: join a family with an invite, or sign back in as yourself
 * (new phone, reinstall). A reset link from the admin also lands here and asks
 * for a new password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(prefs: Prefs, initialServer: String, initialCode: String, onJoined: () -> Unit) {
    val signedOut = remember { prefs.signedOutReason }
    var mode by remember(initialCode) {
        mutableStateOf(
            when {
                initialCode.isNotBlank() -> Mode.Join
                prefs.username.isNotEmpty() || signedOut == Prefs.SIGNED_OUT_ELSEWHERE -> Mode.SignIn
                else -> Mode.Join
            },
        )
    }
    var server by remember(initialServer) { mutableStateOf(initialServer) }
    var code by remember(initialCode) { mutableStateOf(initialCode) }
    var invite by remember { mutableStateOf<InviteInfo?>(null) } // set once the code is checked
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(prefs.username) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val autofill = LocalAutofillManager.current

    val url = normalizeServerUrl(server)
    val insecure = url.startsWith("http://") && !isPrivateHost(Uri.parse(url).host.orEmpty())

    /** Runs a server call with a spinner and friendly errors. */
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: IOException) {
                error = "Couldn't reach the server. Check the address and your connection. (${e.message})"
            } catch (e: Exception) {
                error = "That doesn't look like a FamLogic server."
            } finally {
                busy = false
            }
        }
    }

    fun signedIn(res: JoinResponse) {
        prefs.serverUrl = url
        prefs.token = res.token
        prefs.memberId = res.member.id
        prefs.username = res.username
        prefs.familyName = res.server.name
        prefs.tileUrl = res.server.tileUrl
        prefs.tileUrlDark = res.server.tileUrlDark
        prefs.tileAttribution = res.server.tileAttribution
        prefs.sharing = true
        prefs.signedOutReason = ""
        autofill?.commit() // lets a password manager offer to save the new login
        onJoined()
    }

    fun checkCode() = run {
        val info = Api(url).inviteInfo(code)
        invite = info
        name = info.name
        if (info.isReset) username = info.username
        else if (username.isBlank()) username = suggestUsername(info.name)
    }

    // Opened from an invite link: look the code up straight away.
    LaunchedEffect(initialCode) {
        if (initialCode.isNotBlank() && url.isNotEmpty()) checkCode()
    }

    val passwordProblem = when {
        password.isEmpty() -> null
        password.length < MIN_PASSWORD_LENGTH -> "At least $MIN_PASSWORD_LENGTH characters (${password.length} so far)"
        confirm.isNotEmpty() && confirm != password -> "The passwords don't match"
        else -> null
    }
    val newPasswordReady = password.length >= MIN_PASSWORD_LENGTH && confirm == password

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text("FamLogic", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Private location sharing for your family, on your own server.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (signedOut) {
                Prefs.SIGNED_OUT_ELSEWHERE -> Text(
                    "You signed in on another device, so this one was signed out. Sign in again to use FamLogic here.",
                    color = MaterialTheme.colorScheme.error,
                )
                Prefs.SIGNED_OUT_REMOVED -> Text(
                    "This phone was removed from the family. Ask for a new invite to join again.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (invite == null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == Mode.Join,
                        onClick = { mode = Mode.Join; error = null },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Join with invite") }
                    SegmentedButton(
                        selected = mode == Mode.SignIn,
                        onClick = { mode = Mode.SignIn; error = null },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Sign in") }
                }
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
            }

            when {
                // ---- sign in as an existing person ----
                mode == Mode.SignIn -> {
                    Text("Use the username and password you chose when you joined.", style = MaterialTheme.typography.bodyMedium)
                    UsernameField(username, { username = it; error = null }, ContentType.Username)
                    PasswordField(password, { password = it; error = null }, "Password", ContentType.Password, ImeAction.Done)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    BusyButton("Sign in", busy, enabled = url.isNotEmpty() && username.isNotBlank() && password.isNotEmpty()) {
                        run { signedIn(Api(url).signIn(username.trim(), password)) }
                    }
                    Text(
                        "Forgot your password? Ask your family's admin for a sign-in reset link. You'll keep your place in the family.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---- step 1 of joining: which invite? ----
                invite == null -> {
                    Text("Tap the invite link you were sent, or type the code from it here.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; error = null },
                        label = { Text("Invite code") },
                        placeholder = { Text("ABCD-EFGH") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    BusyButton("Continue", busy, enabled = url.isNotEmpty() && code.isNotBlank()) { checkCode() }
                }

                // ---- step 2: a reset link from the admin ----
                invite!!.isReset -> {
                    val inv = invite!!
                    Text("Welcome back, ${inv.name}", style = MaterialTheme.typography.titleLarge)
                    Text("Choose a new password. You'll keep your place in ${inv.server.name}, and any other phone you were signed in on will be signed out.")
                    if (inv.username.isNotEmpty()) {
                        Text("Username: ${inv.username}", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        UsernameField(username, { username = it; error = null }, ContentType.NewUsername)
                    }
                    NewPasswordFields(password, { password = it; error = null }, confirm, { confirm = it; error = null }, passwordProblem)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    BusyButton("Save password and sign in", busy, enabled = newPasswordReady && (inv.username.isNotEmpty() || username.isNotBlank())) {
                        run { signedIn(Api(url).join(code.trim(), "", username.trim(), password)) }
                    }
                    TextButton(onClick = { invite = null; error = null }) { Text("Use a different code") }
                }

                // ---- step 2: joining as someone new ----
                else -> {
                    val inv = invite!!
                    Text(
                        if (inv.circle.isNotEmpty() && inv.circle != inv.server.name) "Joining ${inv.circle} in ${inv.server.name}"
                        else "Joining ${inv.server.name}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40); error = null },
                        label = { Text("Your name") },
                        supportingText = { Text("Shown to your family on the map") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    UsernameField(username, { username = it; error = null }, ContentType.NewUsername)
                    NewPasswordFields(password, { password = it; error = null }, confirm, { confirm = it; error = null }, passwordProblem)
                    Text(
                        "You'll use your username and password to sign back in on a new phone. A password manager is the easiest way to keep them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    BusyButton("Join", busy, enabled = newPasswordReady && username.isNotBlank() && name.isNotBlank()) {
                        run { signedIn(Api(url).join(code.trim(), name.trim(), username.trim(), password)) }
                    }
                    TextButton(onClick = { invite = null; error = null }) { Text("Use a different code") }
                }
            }
        }
    }
}

@Composable
private fun BusyButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(enabled = !busy && enabled, modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(label)
    }
}

@Composable
private fun UsernameField(value: String, onChange: (String) -> Unit, contentType: ContentType) {
    OutlinedTextField(
        value = value,
        // Usernames are lower-case letters, numbers, dots, dashes and underscores.
        onValueChange = { v -> onChange(v.lowercase().filter { it.isLetterOrDigit() || it in "._-" }.take(32)) },
        label = { Text("Username") },
        supportingText = { Text("Letters, numbers, dots, dashes or underscores") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().semantics { this.contentType = contentType },
    )
}

/** A password box with a show/hide switch, tagged so password managers can fill or save it. */
@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    contentType: ContentType,
    imeAction: ImeAction = ImeAction.Next,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth().semantics { this.contentType = contentType },
    )
}

/** "New password" + "Confirm", with the length rule shown as you type. */
@Composable
fun NewPasswordFields(
    password: String,
    onPassword: (String) -> Unit,
    confirm: String,
    onConfirm: (String) -> Unit,
    problem: String?,
    label: String = "Password",
) {
    PasswordField(
        password, onPassword, label, ContentType.NewPassword,
        supportingText = "At least $MIN_PASSWORD_LENGTH characters. A few random words work well.",
    )
    PasswordField(
        confirm, onConfirm, "Confirm password", ContentType.NewPassword, ImeAction.Done,
        supportingText = problem, isError = problem != null,
    )
}

/** "Taylor Smith" -> "taylor.smith" */
fun suggestUsername(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), ".").filter { it.isLetterOrDigit() || it in "._-" }.take(32)

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
