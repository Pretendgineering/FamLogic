package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Steward: the family's own AI helper, in the app. You say (or type) "tell Sam to
 * unload the dishwasher" or "I finished the dishwasher"; the server asks the
 * family's model, which acts as you, with your permissions. Replies are read aloud
 * when you spoke to it.
 */
object Steward {
    fun available(prefs: Prefs): Boolean {
        val f = prefs.serverFeatures
        return "steward" in f && prefs.access != "dependent" && (!Access.isChild || "steward-kids" in f)
    }
}

data class StewardMsg(val role: String, val text: String, val time: Long)

private fun parseSteward(o: JSONObject): List<StewardMsg> {
    val arr = o.optJSONArray("messages") ?: return emptyList()
    return (0 until arr.length()).map { arr.getJSONObject(it).let { m -> StewardMsg(m.optString("role"), m.optString("text"), m.optLong("time")) } }
}

suspend fun Api.steward(): List<StewardMsg> = parseSteward(call("GET", "/api/steward"))
suspend fun Api.stewardSay(text: String): List<StewardMsg> = parseSteward(call("POST", "/api/steward", JSONObject().put("text", text)))
suspend fun Api.stewardClear() { call("DELETE", "/api/steward") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StewardScreen(prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<StewardMsg>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val api = { Api(prefs.serverUrl, prefs.token) }
    LaunchedEffect(Unit) { runCatching { msgs = api().steward() } }
    LaunchedEffect(msgs.size, thinking) { if (msgs.isNotEmpty()) list.animateScrollToItem(0) }

    fun say(text: String, spoken: Boolean) {
        val t = text.trim()
        if (t.isEmpty() || thinking) return
        draft = ""
        msgs = msgs + StewardMsg("user", t, System.currentTimeMillis())
        thinking = true
        scope.launch {
            try {
                msgs = api().stewardSay(t)
                if (spoken || Access.younger) msgs.lastOrNull()?.takeIf { it.role == "assistant" }?.let { Voice.speak(context, prefs, it.text) }
                Upkeep.refresh(prefs)
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't reach Steward: ${e.message}", Toast.LENGTH_LONG).show()
            }
            thinking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Access.name("steward")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (msgs.isNotEmpty()) IconButton(onClick = { scope.launch { runCatching { api().stewardClear() }; msgs = emptyList() } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Start over")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (thinking) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                items(msgs.reversed(), key = { it.time.toString() + it.role }) { m ->
                    val mine = m.role == "user"
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(
                            m.text,
                            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            style = if (Access.big || Access.younger) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (msgs.isEmpty() && !thinking) item {
                    Text(
                        if (Access.isChild) "Tell Steward when you've done a job, or ask what's next."
                        else "Ask Steward to add a chore for someone, tick one off, or tell you what happened today.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(draft, { draft = it.take(1000) }, Modifier.weight(1f), placeholder = { Text("Say or type…") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
                if (draft.isBlank()) TalkButton(prefs, 52.dp, onText = { if (it.isNotBlank()) say(it, spoken = true) })
                else IconButton(onClick = { say(draft, spoken = false) }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}
