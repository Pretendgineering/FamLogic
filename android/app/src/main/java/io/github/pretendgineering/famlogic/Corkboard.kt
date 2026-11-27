package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Corkboard from a parent's phone: start quiet time (nap time) on the TV and family
 * displays, or send them a message ("Inside voices please, the baby's asleep").
 */
object Corkboard {
    var open by mutableStateOf(false)
    var quietOn by mutableStateOf(false)
    var quietUntil by mutableStateOf(0L)
    /** Which display quiet time is on ("" = all of them). */
    var quietFor by mutableStateOf("")
    /** The family's displays: id to name. */
    var displays by mutableStateOf<List<Pair<String, String>>>(emptyList())
    /** The newest message on the TV ("" if none), and who sent it. */
    var lastMessage by mutableStateOf("")
    var lastFrom by mutableStateOf("")
    suspend fun refresh(prefs: Prefs) {
        runCatching { Api(prefs.serverUrl, prefs.token).call("GET", "/api/display/board") }.onSuccess {
            val q = it.optJSONObject("quiet")
            quietOn = q?.optBoolean("on") == true
            quietUntil = q?.optLong("until") ?: 0L
            quietFor = q?.optString("for").orEmpty()
            val msg = it.optJSONArray("messages")?.optJSONObject(0)
            lastMessage = msg?.optString("text").orEmpty(); lastFrom = msg?.optString("from").orEmpty()
            displays = it.optJSONArray("displays")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { d -> d.optString("id") to d.optString("name") } } } ?: emptyList()
        }
    }
    fun mayControl(prefs: Prefs) = prefs.role == "parent" || prefs.role == "grandparent"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CorkboardDialog(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    // With more than one display, first choose which ("" = all of them).
    var target by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Corkboard.refresh(prefs) }
    val choose = Corkboard.displays.size > 1 && target == null
    val to = target.orEmpty()
    val name = Corkboard.displays.find { it.first == to }?.second ?: if (Corkboard.displays.size > 1) "All displays" else "The TV"
    val api = { Api(prefs.serverUrl, prefs.token) }
    fun quiet(minutes: Int?) = scope.launch {
        runCatching { if (minutes == null) api().call("DELETE", "/api/display/quiet") else api().call("POST", "/api/display/quiet", JSONObject().put("minutes", minutes).put("to", to)) }
            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        Corkboard.refresh(prefs)
    }
    AlertDialog(
        onDismissRequest = { Corkboard.open = false },
        title = { Text(if (choose) "Which display?" else name) },
        text = {
            if (choose) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("" to "All displays") + Corkboard.displays).forEach { (id, n) ->
                    OutlinedButton(onClick = { target = id }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(n) }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Quiet time", style = MaterialTheme.typography.titleMedium)
                if (Corkboard.quietOn && (Corkboard.quietFor.isEmpty() || Corkboard.quietFor == to)) {
                    Text(if (Corkboard.quietUntil > 0) "On until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(Corkboard.quietUntil))}" else "On until someone ends it",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { quiet(null) }) { Text("End quiet time") }
                } else {
                    Text("The TV and displays show a calm quiet-time screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30 to "30 min", 60 to "1 hour", 120 to "2 hours", 0 to "Until I end it").forEach { (m, t) ->
                            OutlinedButton(onClick = { quiet(m) }) { Text(t) }
                        }
                    }
                }
                Text("Message", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(text, { text = it.take(200) }, placeholder = { Text("e.g. Inside voices please") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TalkButton(prefs, 44.dp, onText = { if (it.isNotBlank()) text = it.trim() })
                    TextButton(enabled = text.isNotBlank(), onClick = {
                        val t = text.trim()
                        scope.launch {
                            runCatching { api().call("POST", "/api/display/messages", JSONObject().put("text", t).put("to", to)) }
                                .onSuccess { text = ""; Toast.makeText(context, "Sent", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Send") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Corkboard.open = false }) { Text(if (choose) "Cancel" else "Done") } },
        dismissButton = if (!choose && Corkboard.displays.size > 1) ({ TextButton(onClick = { target = null }) { Text("Back") } }) else null,
    )
}
