package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Steward: the family's own AI helper, in the app. You say (or type) "tell Sam to
 * unload the dishwasher" or "I finished the dishwasher"; the server asks the
 * family's model, which acts as you, with your permissions. Replies are read aloud
 * when you spoke to it.
 */
object Steward {
    /** The Steward sheet is open (from the corner button or the Home tile). */
    var sheetOpen by mutableStateOf(false)

    /** After Steward changes something, every screen's data catches up. */
    suspend fun refreshAll(prefs: Prefs) {
        runCatching { Upkeep.refresh(prefs) }
        runCatching { OddsEnds.refresh(prefs) }
        runCatching { Datebook.refresh(prefs) }
        runCatching { Rhythms.refresh(prefs) }
        runCatching { Pantry.refresh(prefs, Pantry.data?.week.orEmpty()) }
    }

    fun available(prefs: Prefs): Boolean {
        val f = prefs.serverFeatures
        return "steward" in f && prefs.access != "dependent" && (!Access.isChild || "steward-kids" in f)
    }
}

data class StewardMsg(val role: String, val text: String, val time: Long)

/** The conversation, and what Steward's latest answer changed (which can be undone). */
data class StewardState(val msgs: List<StewardMsg>, val changed: List<String> = emptyList(), val canUndo: Boolean = false)

private fun parseSteward(o: JSONObject): StewardState {
    val arr = o.optJSONArray("messages") ?: JSONArray()
    val ch = o.optJSONArray("changed") ?: JSONArray()
    return StewardState(
        (0 until arr.length()).map { arr.getJSONObject(it).let { m -> StewardMsg(m.optString("role"), m.optString("text"), m.optLong("time")) } },
        (0 until ch.length()).map { ch.getString(it) }, o.optBoolean("canUndo"),
    )
}

suspend fun Api.steward(): StewardState = parseSteward(call("GET", "/api/steward"))
suspend fun Api.stewardSay(text: String, page: String = ""): StewardState = parseSteward(call("POST", "/api/steward", JSONObject().put("text", text).put("page", page)))
suspend fun Api.stewardUndo(): StewardState = parseSteward(call("POST", "/api/steward/undo"))
suspend fun Api.stewardClear() { call("DELETE", "/api/steward") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StewardScreen(prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var clear by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Access.name("steward")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).stewardClear() }; clear++ } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Start over")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.runtime.key(clear) { StewardChat(prefs, "", Modifier.padding(padding).fillMaxSize().imePadding()) }
    }
}

/**
 * The conversation with Steward: messages, a box to type in and the talk button.
 * [page] says where it was opened from ("meals", "chores"...), so "this" makes sense to it.
 * After each answer the screens' data is refreshed, so changes show at once.
 */
@Composable
fun StewardChat(prefs: Prefs, page: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<StewardMsg>>(emptyList()) }
    var changed by remember { mutableStateOf<List<String>>(emptyList()) }
    var canUndo by remember { mutableStateOf(false) }
    fun show(st: StewardState) { msgs = st.msgs; changed = st.changed; canUndo = st.canUndo }
    var draft by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val api = { Api(prefs.serverUrl, prefs.token) }
    LaunchedEffect(Unit) { runCatching { show(api().steward()) } }
    LaunchedEffect(msgs.size, thinking) { if (msgs.isNotEmpty()) list.animateScrollToItem(0) }

    fun say(text: String, spoken: Boolean) {
        val t = text.trim()
        if (t.isEmpty() || thinking) return
        draft = ""
        msgs = msgs + StewardMsg("user", t, System.currentTimeMillis())
        canUndo = false
        thinking = true
        scope.launch {
            try {
                show(api().stewardSay(t, page))
                if (spoken || Access.younger) msgs.lastOrNull()?.takeIf { it.role == "assistant" }?.let { Voice.speak(context, prefs, it.text) }
                Steward.refreshAll(prefs)
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't reach ${Access.name("steward")}: ${e.message}", Toast.LENGTH_LONG).show()
            }
            thinking = false
        }
    }

    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // What the latest answer changed, with Undo (shown under it; the list is reversed).
            if (canUndo && !thinking && msgs.lastOrNull()?.role == "assistant") item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Changed: " + changed.joinToString(", "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch {
                            runCatching { api().stewardUndo() }.onSuccess { show(it); Steward.refreshAll(prefs) }
                                .onFailure { Toast.makeText(context, it.message ?: "Couldn't undo", Toast.LENGTH_SHORT).show() }
                        }
                    }) { Text("Undo") }
                }
            }
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
                    if (Access.isChild) "Tell me when you've done a job, or ask what's next."
                    else "Tell me what to add, change or remove: \"add milk and eggs to Shopping\", \"move the dentist to Friday at 3\", \"save this recipe\", \"Biscuit had his shots today\".",
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

/**
 * Steward from any screen: a small round button in the corner (not on Chat, which has
 * its own voice, or the map) that opens Steward in a sheet, knowing which screen it's on.
 * Only when Steward is set up for this person.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun androidx.compose.foundation.layout.BoxScope.StewardButton(prefs: Prefs, page: String) {
    if (!Steward.available(prefs)) return
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = { Steward.sheetOpen = true },
        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) { Icon(FamIcons.Spark, contentDescription = "Ask ${Access.name("steward")}") }
    if (Steward.sheetOpen) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { Steward.sheetOpen = false }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(Access.name("steward"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            StewardChat(prefs, page, Modifier.fillMaxWidth().height(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .7f).imePadding())
        }
    }
}

/**
 * "Where's my ride?": whoever said "On my way" to you, how far they are and about
 * how long until they get there (from their speed, or 40 km/h if they're stopped).
 */
@Composable
fun RideBanner(members: List<Member>, me: String, onOpen: (String) -> Unit) {
    val mine = members.find { it.id == me }?.loc ?: return
    members.filter { it.rideTo == me && it.loc != null && !it.paused }.forEach { d ->
        val loc = d.loc!!
        val dist = FloatArray(1).also { android.location.Location.distanceBetween(mine.lat, mine.lon, loc.lat, loc.lon, it) }[0].toDouble()
        val speed = maxOf(loc.speed, 11.0) // m/s; 40 km/h when stopped at lights
        val mins = maxOf(1, (dist / speed / 60).toInt())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer).clickable { onOpen(d.id) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(d.name, d.emoji, d.color, 40)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("${d.name} is on the way", style = MaterialTheme.typography.titleMedium)
                Text("${Units.distance(dist)} away · about $mins min", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
