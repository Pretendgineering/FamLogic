package io.github.spasm0dic.thereabouts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.spasm0dic.thereabouts.pebble.PebbleBridge
import io.github.spasm0dic.thereabouts.pebble.PebbleKit
import kotlinx.coroutines.launch
import java.io.IOException

private const val WATCHAPP = "https://github.com/Spasm0dic/Thereabouts/releases/latest/download/thereabouts-watch.pbw"
private const val WATCHFACE = "https://github.com/Spasm0dic/Thereabouts/releases/latest/download/thereabouts-face.pbw"

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "No browser to open $url", Toast.LENGTH_LONG).show() }
}

/** A numbered setup step; **bold** marks words to look for on screen. */
@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$n.", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp))
        Text(bold(text), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun bold(text: String) = androidx.compose.ui.text.buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(part) }
        else append(part)
    }
}

/** Settings > Pebble & Index: the watch app, its options, and the Index 01 ring links. */
@Composable
internal fun PebblePage(prefs: Prefs, canIndex: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasPebbleApp = remember { PebbleKit.pebbleApps(context).isNotEmpty() }
    var timeline by remember { mutableStateOf(prefs.pebbleTimeline) }
    var heartRate by remember { mutableStateOf(prefs.pebbleHeartRate) }
    var falls by remember { mutableStateOf(prefs.pebbleFalls) }

    SettingsCard("Set up your watch") {
        Step(1, if (hasPebbleApp) "The Pebble app is installed ✓" else "Install the Pebble app (by Core Devices) and pair your watch.")
        Step(2, "Download the watchapp. When it's downloaded, tap it (or find it in Downloads) and choose **Open with Pebble**: the Pebble app installs it on your watch.")
        FilledTonalButton(onClick = { open(context, WATCHAPP) }, modifier = Modifier.padding(start = 28.dp, bottom = 4.dp)) { Text("Download the watchapp") }
        Step(3, "Optional: the Family watchface, the same way.")
        OutlinedButton(onClick = { open(context, WATCHFACE) }, modifier = Modifier.padding(start = 28.dp, bottom = 4.dp)) { Text("Download the watchface") }
        Step(4, "Optional, for a faster SOS: on the watch, open Settings → Quick Launch, pick the Up (or Down) button and choose Thereabouts. Then turn on \"Quick Launch opens SOS\" below.")
        if (hasPebbleApp) {
            TextButton(onClick = { scope.launch { PebbleKit.startApp(context, PebbleBridge.APP_UUID) } }) { Text("Open Thereabouts on the watch") }
        }
        if (prefs.watchBattery > 0) Hint("Watch battery: ${prefs.watchBattery}% (shown to the people who can see you)")
    }
    SettingsCard("Watch look") {
        var theme by remember { mutableStateOf(prefs.pebbleTheme) }
        listOf("system" to "Same as this phone", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = theme == id) {
                    theme = id
                    prefs.pebbleTheme = id
                    PebbleBridge.push(context)
                    PebbleBridge.push(context, PebbleBridge.FACE_UUID)
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = theme == id, onClick = null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
        Hint("Black-and-white watches (Pebble 2 Duo and older) always use black and white.")
    }
    SettingsCard("On the watch") {
        SwitchRow(
            title = "Timeline pins",
            detail = "Arrivals, check-ins, trips and reminders appear in the watch's timeline.",
            checked = timeline,
            onChange = { timeline = it; prefs.pebbleTimeline = it },
        )
        var quickSos by remember { mutableStateOf(prefs.pebbleQuickSos) }
        SwitchRow(
            title = "Quick Launch opens SOS",
            detail = "Opening Thereabouts with the watch's Quick Launch (holding Up or Down on the watchface) goes straight to the SOS screen: then hold Select. Anywhere in the watchapp, holding Select also starts an SOS.",
            checked = quickSos,
            onChange = { quickSos = it; prefs.pebbleQuickSos = it; PebbleBridge.push(context) },
        )
        SwitchRow(
            title = "Send my heart rate with an SOS",
            detail = "When you raise an SOS from a watch with a heart rate sensor (Pebble Time 2).",
            checked = heartRate,
            onChange = { heartRate = it; prefs.pebbleHeartRate = it; PebbleBridge.push(context) },
        )
        SwitchRow(
            title = "Fall detection",
            detail = "If the watch feels a hard fall, it vibrates and counts down 30 seconds, then sends an SOS. Press any button to cancel. False alarms can happen.",
            checked = falls,
            onChange = { falls = it; prefs.pebbleFalls = it; PebbleBridge.push(context) },
        )
    }
    if (canIndex) IndexRingCard(prefs)
}

@Composable
private fun IndexRingCard(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf<IndexLinks?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSecret by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            links = Api(prefs.serverUrl, prefs.token).indexLinks()
        } catch (e: IOException) {
            error = e.message
        }
    }
    SettingsCard("Pebble Index 01 ring") {
        Step(1, "Copy one of the links below (each does one thing).")
        Step(2, "In the Pebble app, open your Index 01's settings and go to **Webhook**.")
        Step(3, "Pick a gesture (for example **Double-click and hold** for the discreet SOS, **Hold and talk** for check-ins), paste the link, and choose **Transcription only** (or **Both**).")
        Step(4, "Set that gesture to **Webhook only**, so it does just this.")
        Step(5, "Optional: turn on **Sign requests** and paste the signing secret from below.")
        Step(6, "If the Pebble app offers a test, use it: Thereabouts answers that it got the test, and nothing is sent to your family.")
        val l = links
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            l == null -> Text("Loading…")
            else -> {
                LinkRow(context, "Discreet SOS", "Hold to raise a silent SOS; what you say goes with it.", l.sos)
                LinkRow(context, "Voice check-in", "\"Made it to practice\" becomes a check-in where you are.", l.checkin)
                LinkRow(context, "Place reminder", "\"When I get home, take the bins out.\" Name one of your fences.", l.remind)
                Text("Signing secret (optional)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Hint("Turn on \"Sign requests\" in the Pebble app and paste this, and the server checks every request.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showSecret) l.signingSecret else "••••••••••••",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") }
                    TextButton(onClick = { copy(context, l.signingSecret) }) { Text("Copy") }
                }
                TextButton(onClick = { confirmReset = true }) { Text("Make new links") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Make new ring links?") },
            text = { Text("The old links stop working. Paste the new ones into the Pebble app afterwards.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { links = runCatching { Api(prefs.serverUrl, prefs.token).indexLinks(reset = true) }.getOrNull() ?: links }
                }) { Text("Make new links") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LinkRow(context: Context, title: String, detail: String, url: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(detail)
        }
        TextButton(onClick = { copy(context, url) }) { Text("Copy link") }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Thereabouts", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** Settings > Fences: notes to yourself for arriving at or leaving a fence. */
@Composable
internal fun RemindersCard(prefs: Prefs, places: List<Place>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var reminders by remember { mutableStateOf<List<PlaceReminder>?>(null) }
    var adding by remember { mutableStateOf(false) }
    suspend fun load() {
        reminders = runCatching { Api(prefs.serverUrl, prefs.token).reminders() }.getOrNull() ?: reminders
    }
    LaunchedEffect(Unit) { load() }
    SettingsCard("My reminders") {
        Hint("A note to yourself for when you arrive at or leave a fence. Only you see it, and it goes off once.")
        val names = places.associate { it.id to it.name }
        reminders?.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.text, style = MaterialTheme.typography.bodyLarge)
                    Hint((if (r.onLeave) "Leaving " else "Arriving at ") + (names[r.placeId] ?: "a fence"))
                }
                TextButton(onClick = {
                    scope.launch {
                        runCatching { Api(prefs.serverUrl, prefs.token).deleteReminder(r.id) }
                        load()
                    }
                }) { Text("Delete") }
            }
        }
        if (reminders?.isEmpty() == true) Text("None yet.", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = { adding = true }, enabled = places.isNotEmpty(), modifier = Modifier.padding(top = 8.dp)) {
            Text("Add a reminder…")
        }
    }
    if (adding) {
        var placeId by remember { mutableStateOf(places.firstOrNull()?.id ?: "") }
        var onLeave by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New reminder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !onLeave, onClick = { onLeave = false }, label = { Text("When I arrive") })
                        FilterChip(selected = onLeave, onClick = { onLeave = true }, label = { Text("When I leave") })
                    }
                    places.forEach { p ->
                        Row(Modifier.fillMaxWidth().selectable(selected = placeId == p.id) { placeId = p.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = placeId == p.id, onClick = null)
                            Text(p.name, Modifier.padding(start = 8.dp))
                        }
                    }
                    OutlinedTextField(value = text, onValueChange = { text = it.take(140) }, label = { Text("Remind me to…") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && placeId.isNotEmpty(), onClick = {
                    scope.launch {
                        try {
                            Api(prefs.serverUrl, prefs.token).addReminder(placeId, text.trim(), onLeave)
                            adding = false
                            load()
                        } catch (e: IOException) {
                            Toast.makeText(context, e.message ?: "Couldn't save", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
