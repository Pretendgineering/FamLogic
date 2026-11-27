package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Huddle: family meetings. A parent writes a short agenda (or asks Steward to); the
 * family clicks through it together on the TV, a tablet or the console, handing out
 * jobs (to someone, or asking for a volunteer) and keeping notes; then the jobs go
 * onto the Docket. Each meeting is a note in the vault's Meetings/ folder.
 */
data class MeetingJob(val text: String, val who: String, val sent: Boolean)
data class MeetingSlide(val title: String, val text: String, val jobs: List<MeetingJob>, val notes: List<String>)
data class Meeting(val id: String, val title: String, val date: String, val slides: List<MeetingSlide>)
data class MeetingsData(val meetings: List<Meeting>, val canEdit: Boolean, val canRun: Boolean)

private fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

fun parseMeeting(o: JSONObject): Meeting {
    val slides = o.optJSONArray("slides") ?: JSONArray()
    return Meeting(o.optString("id"), o.optString("title"), o.optString("date"), (0 until slides.length()).map { i ->
        slides.getJSONObject(i).let { s ->
            val jobs = s.optJSONArray("jobs") ?: JSONArray()
            MeetingSlide(s.optString("title"), s.optString("text"),
                (0 until jobs.length()).map { k -> jobs.getJSONObject(k).let { MeetingJob(it.optString("text"), it.optString("who"), it.optBoolean("sent")) } },
                strs(s.optJSONArray("notes")))
        }
    })
}

suspend fun Api.meetings(): MeetingsData {
    val o = call("GET", "/api/meetings")
    val arr = o.optJSONArray("meetings") ?: JSONArray()
    return MeetingsData((0 until arr.length()).map { parseMeeting(arr.getJSONObject(it)) }, o.optBoolean("canEdit"), o.optBoolean("canRun"))
}
private fun Api.meetingFrom(o: JSONObject) = parseMeeting(o.optJSONObject("meeting") ?: JSONObject())
suspend fun Api.meeting(id: String): Meeting = meetingFrom(call("GET", "/api/meetings/$id"))
suspend fun Api.createMeeting(body: JSONObject): Meeting = meetingFrom(call("POST", "/api/meetings", body))
suspend fun Api.saveMeeting(id: String, body: JSONObject): Meeting = meetingFrom(call("PUT", "/api/meetings/$id", body))
suspend fun Api.deleteMeeting(id: String) { call("POST", "/api/meetings/$id/delete") }
suspend fun Api.addMeetingJob(id: String, slide: Int, text: String, who: String = ""): Meeting =
    meetingFrom(call("POST", "/api/meetings/$id/slides/$slide/jobs", JSONObject().put("text", text).put("who", who)))
suspend fun Api.setMeetingJob(id: String, slide: Int, job: Int, who: String, remove: Boolean = false): Meeting =
    meetingFrom(call("POST", "/api/meetings/$id/slides/$slide/jobs/$job", JSONObject().put("who", who).put("remove", remove)))
suspend fun Api.addMeetingNote(id: String, slide: Int, text: String): Meeting =
    meetingFrom(call("POST", "/api/meetings/$id/slides/$slide/notes", JSONObject().put("text", text)))
/** Sends the jobs that have someone to the Docket; returns the meeting and how many went. */
suspend fun Api.sendMeeting(id: String): Pair<Meeting, Int> = call("POST", "/api/meetings/$id/send").let { meetingFrom(it) to it.optInt("sent") }

/**
 * The agenda as plain text, the same as the note: "## Topic" starts a slide, the lines
 * under it are what it says, "- Job @Name" is a job (without @Name it needs a volunteer)
 * and "> " a note.
 */
fun meetingText(m: Meeting, nameOf: (String) -> String?): String = m.slides.joinToString("\n\n") { s ->
    buildString {
        append("## ").append(s.title)
        if (s.text.isNotEmpty()) append("\n").append(s.text)
        s.jobs.forEach { j -> append("\n- ").append(j.text); nameOf(j.who)?.let { append(" @").append(it.replace(' ', '_')) } }
        s.notes.forEach { append("\n> ").append(it) }
    }
}

/** The agenda text as slides for the server (names are matched there). */
fun meetingSlidesJson(text: String): JSONArray {
    val slides = JSONArray()
    var cur: JSONObject? = null
    val body = StringBuilder()
    fun flush() { cur?.let { it.put("text", body.toString().trim()); slides.put(it) }; body.clear() }
    for (raw in text.lines()) {
        val l = raw.trimEnd()
        when {
            l.startsWith("#") -> { flush(); cur = JSONObject().put("title", l.trimStart('#').trim()).put("jobs", JSONArray()).put("notes", JSONArray()) }
            cur == null -> if (l.isNotBlank()) { cur = JSONObject().put("title", l.trim()).put("jobs", JSONArray()).put("notes", JSONArray()) }
            l.startsWith("- ") || l.startsWith("* ") -> {
                val t = l.drop(2).removePrefix("[ ] ").trim()
                val m = Regex("""\s@([\p{L}\p{N}_\-]+)""").find(" $t")
                val who = m?.groupValues?.get(1)?.replace('_', ' ').orEmpty()
                val job = if (m != null) (" $t").replace(m.value, "").trim() else t
                if (job.isNotEmpty()) cur!!.getJSONArray("jobs").put(JSONObject().put("text", job).put("who", who))
            }
            l.startsWith(">") -> l.drop(1).trim().takeIf { it.isNotEmpty() }?.let { cur!!.getJSONArray("notes").put(it) }
            else -> body.append(l).append('\n')
        }
    }
    flush()
    return slides
}

object Huddle {
    var open by mutableStateOf(false)
    var data by mutableStateOf<MeetingsData?>(null)
    suspend fun refresh(prefs: Prefs) { data = runCatching { Api(prefs.serverUrl, prefs.token).meetings() }.getOrDefault(data) }
}

@Composable
fun HuddleDialog(prefs: Prefs) {
    Dialog(onDismissRequest = { Huddle.open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HuddleScreen(prefs) { Huddle.open = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuddleScreen(prefs: Prefs, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<Member>>(emptyList()) }
    LaunchedEffect(Unit) {
        Huddle.refresh(prefs)
        runCatching { people = Api(prefs.serverUrl, prefs.token).members(Long.MAX_VALUE / 2).members.filter { it.access != "display" && it.role != "dependent" } }
    }
    var editing by remember { mutableStateOf<Meeting?>(null) }
    var making by remember { mutableStateOf(false) }
    var presenting by remember { mutableStateOf<Meeting?>(null) }
    val d = Huddle.data
    presenting?.let { m -> MeetingPresenter(prefs, m, people, d?.canRun == true) { presenting = null; scope.launch { Huddle.refresh(prefs) } }; return }
    if (making || editing != null) {
        MeetingEditor(prefs, editing, people) { making = false; editing = null; scope.launch { Huddle.refresh(prefs) } }
        return
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(Access.name("meetings")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        floatingActionButton = { if (d?.canEdit == true) ExtendedFloatingActionButton(onClick = { making = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New meeting") }, modifier = Modifier.pageFab()) },
    ) { pad ->
        if (d == null) { Text("Loading…", Modifier.padding(pad).padding(16.dp)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d.meetings.isEmpty()) item {
                Text(if (d.canEdit) "No meetings yet. Make one here, or ask Steward (\"set up a family meeting about the holidays and chores\")." else "No meetings yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(d.meetings, key = { it.id }) { m ->
                ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleLarge)
                        val jobs = m.slides.sumOf { it.jobs.size }
                        Text(listOfNotNull(m.date.ifEmpty { null }, "${m.slides.size} ${if (m.slides.size == 1) "topic" else "topics"}", if (jobs > 0) "$jobs ${if (jobs == 1) "job" else "jobs"}" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(m.slides.joinToString(" · ") { it.title }, Modifier.padding(top = 4.dp), maxLines = 2)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { presenting = m }) { Text("Start") }
                            if (d.canEdit) OutlinedButton(onClick = { editing = m }) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingEditor(prefs: Prefs, existing: Meeting?, people: List<Member>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: "Family meeting") }
    var date by remember { mutableStateOf(existing?.date ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())) }
    var text by remember { mutableStateOf(existing?.let { m -> meetingText(m) { id -> people.find { it.id == id }?.name } } ?: "## \n") }
    var busy by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (existing == null) "New meeting" else "Edit meeting") }, navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(enabled = !busy && title.isNotBlank(), onClick = {
                    busy = true
                    val body = JSONObject().put("title", title.trim()).put("date", date.trim()).put("slides", meetingSlidesJson(text))
                    scope.launch {
                        runCatching { Api(prefs.serverUrl, prefs.token).let { if (existing == null) it.createMeeting(body) else it.saveMeeting(existing.id, body) } }
                            .onSuccess { onDone() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        busy = false
                    }
                }) { Text("Save") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
            OutlinedTextField(date, { date = it.take(10) }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
            OutlinedTextField(text, { text = it }, label = { Text("Topics") }, modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp), shape = RoundedCornerShape(18.dp))
            Text("Each topic starts with ## and becomes a slide. Lines under it are what it says. \"- Job @Name\" hands out a job (without @Name it asks for a volunteer); \"> \" is a note. Names: " +
                people.joinToString(", ") { it.name.replace(' ', '_') } + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (existing != null) TextButton(onClick = {
                scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).deleteMeeting(existing.id) }; onDone() }
            }) { Text("Remove this meeting", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Clicking through a meeting on a phone or tablet: jobs handed out, notes kept, then onto the Docket. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MeetingPresenter(prefs: Prefs, start: Meeting, people: List<Member>, canRun: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var m by remember { mutableStateOf(start) }
    var at by remember { mutableIntStateOf(0) }
    var newJob by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }
    val api = { Api(prefs.serverUrl, prefs.token) }
    fun act(f: suspend Api.() -> Meeting) = scope.launch {
        runCatching { api().f() }.onSuccess { m = it }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(m.title) }, navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (m.slides.isEmpty()) { Text("This meeting has no topics yet.", Modifier.padding(pad).padding(16.dp)); return@Scaffold }
        val s = m.slides[at.coerceIn(0, m.slides.size - 1)]
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${at + 1} of ${m.slides.size}", color = Accent2, style = MaterialTheme.typography.labelLarge)
            Text(s.title, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp)
            if (s.text.isNotEmpty()) Text(s.text, style = MaterialTheme.typography.bodyLarge)
            if (s.jobs.isNotEmpty() || canRun) Text("Jobs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            s.jobs.forEachIndexed { k, j ->
                var pick by remember(at, k) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable(enabled = canRun) { pick = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (j.sent) "📋" else "○", Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(j.text, style = MaterialTheme.typography.bodyLarge)
                        Text(people.find { it.id == j.who }?.name ?: "Needs a volunteer", style = MaterialTheme.typography.bodySmall,
                            color = if (j.who.isEmpty()) Accent2 else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                        people.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { pick = false; act { setMeetingJob(m.id, at, k, p.id) } }) }
                        DropdownMenuItem(text = { Text("Needs a volunteer") }, onClick = { pick = false; act { setMeetingJob(m.id, at, k, "") } })
                        DropdownMenuItem(text = { Text("Remove the job") }, onClick = { pick = false; act { setMeetingJob(m.id, at, k, "", remove = true) } })
                    }
                }
            }
            if (canRun) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newJob, { newJob = it.take(120) }, placeholder = { Text("Add a job") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp))
                TextButton(enabled = newJob.isNotBlank(), onClick = { val t = newJob.trim(); newJob = ""; act { addMeetingJob(m.id, at, t) } }) { Text("Add") }
            }
            if (s.notes.isNotEmpty() || canRun) Text("Notes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            s.notes.forEach { Text("• $it") }
            if (canRun) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newNote, { newNote = it.take(300) }, placeholder = { Text("Add a note") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp))
                TalkButton(prefs, 44.dp, onText = { if (it.isNotBlank()) newNote = it.trim() })
                TextButton(enabled = newNote.isNotBlank(), onClick = { val t = newNote.trim(); newNote = ""; act { addMeetingNote(m.id, at, t) } }) { Text("Add") }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = at > 0, onClick = { at-- }) { Text("‹ Back") }
                FilledTonalButton(enabled = at < m.slides.size - 1, onClick = { at++ }) { Text("Next ›") }
                if (canRun && at == m.slides.size - 1) FilledTonalButton(onClick = {
                    scope.launch {
                        runCatching { api().sendMeeting(m.id) }.onSuccess { (mm, n) ->
                            m = mm
                            Toast.makeText(context, if (n == 0) "Nothing new to send" else "$n ${if (n == 1) "job" else "jobs"} on the Docket", Toast.LENGTH_SHORT).show()
                        }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Send jobs to the Docket") }
            }
        }
    }
}
