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
/** A vote's choice, and who picked it. */
data class MeetingOption(val text: String, val votes: List<String>)
data class MeetingSlide(val title: String, val text: String, val jobs: List<MeetingJob>, val notes: List<String>, val kind: String = "", val options: List<MeetingOption> = emptyList())
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
                strs(s.optJSONArray("notes")), s.optString("kind"),
                (s.optJSONArray("options") ?: JSONArray()).let { a -> (0 until a.length()).map { k -> a.getJSONObject(k).let { MeetingOption(it.optString("text"), strs(it.optJSONArray("votes"))) } } })
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
suspend fun Api.meetingVote(id: String, slide: Int, option: Int, who: String): Meeting =
    meetingFrom(call("POST", "/api/meetings/$id/slides/$slide/vote", JSONObject().put("option", option).put("who", who)))
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

/** A slide being built: its kind (wins, coming, jobs, vote or "" for a topic) and what's in it. */
private class DraftSlide(kind: String, title: String, text: String = "", jobs: List<Pair<String, String>> = emptyList(), options: List<String> = emptyList(), val notes: List<String> = emptyList()) {
    val kind = kind
    var title by mutableStateOf(title)
    var text by mutableStateOf(text)
    val jobs = androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply { addAll(jobs) } // text, who
    val options = androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(options) }
}

private val SECTIONS = listOf("wins" to "Wins and thank-yous", "coming" to "Coming up", "jobs" to "Jobs to hand out", "vote" to "A vote", "" to "A topic")

/** The meeting builder: sections added with buttons and filled in by tapping. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MeetingEditor(prefs: Prefs, existing: Meeting?, people: List<Member>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: "Family meeting") }
    var date by remember { mutableStateOf(existing?.date ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())) }
    val slides = remember {
        androidx.compose.runtime.mutableStateListOf<DraftSlide>().apply {
            existing?.slides?.forEach { sl -> add(DraftSlide(sl.kind, sl.title, sl.text, sl.jobs.map { it.text to it.who }, sl.options.map { it.text }, sl.notes)) }
        }
    }
    var busy by remember { mutableStateOf(false) }
    val sectionName = { k: String -> SECTIONS.find { it.first == k }?.second ?: "A topic" }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (existing == null) "New meeting" else "Edit meeting") }, navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(enabled = !busy && title.isNotBlank() && slides.isNotEmpty(), onClick = {
                    busy = true
                    val arr = JSONArray()
                    slides.forEach { sl ->
                        arr.put(JSONObject().put("title", sl.title.ifBlank { sectionName(sl.kind) }).put("kind", sl.kind).put("text", sl.text)
                            .put("jobs", JSONArray(sl.jobs.filter { it.first.isNotBlank() }.map { JSONObject().put("text", it.first).put("who", it.second) }))
                            .put("options", JSONArray(sl.options.filter { it.isNotBlank() })).put("notes", JSONArray(sl.notes)))
                    }
                    val body = JSONObject().put("title", title.trim()).put("date", date.trim()).put("slides", arr)
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
            slides.forEachIndexed { i, sl ->
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sectionName(sl.kind), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Accent2)
                            TextButton(enabled = i > 0, onClick = { slides.add(i - 1, slides.removeAt(i)) }) { Text("↑") }
                            TextButton(enabled = i < slides.size - 1, onClick = { slides.add(i + 1, slides.removeAt(i)) }) { Text("↓") }
                            TextButton(onClick = { slides.removeAt(i) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                        OutlinedTextField(sl.title, { sl.title = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (sl.kind == "" || sl.kind == "coming") OutlinedTextField(sl.text, { sl.text = it.take(2000) }, label = { Text("What to talk about") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        if (sl.kind == "jobs" || sl.kind == "") {
                            sl.jobs.forEachIndexed { k, (jt, who) ->
                                var menu by remember { mutableStateOf(false) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(jt, { sl.jobs[k] = it.take(120) to who }, placeholder = { Text("A job") }, singleLine = true, modifier = Modifier.weight(1f))
                                    androidx.compose.foundation.layout.Box {
                                        TextButton(onClick = { menu = true }) { Text(people.find { it.id == who }?.name ?: "Volunteer") }
                                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            androidx.compose.material3.DropdownMenuItem(text = { Text("Ask for a volunteer") }, onClick = { sl.jobs[k] = jt to ""; menu = false })
                                            people.forEach { p -> androidx.compose.material3.DropdownMenuItem(text = { Text(p.name) }, onClick = { sl.jobs[k] = jt to p.id; menu = false }) }
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { sl.jobs.add("" to "") }) { Text("+ Add a job") }
                        }
                        if (sl.kind == "vote") {
                            sl.options.forEachIndexed { k, o -> OutlinedTextField(o, { sl.options[k] = it.take(80) }, placeholder = { Text("Choice ${k + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                            TextButton(onClick = { sl.options.add("") }) { Text("+ Add a choice") }
                        }
                        if (sl.kind == "wins") Text("During the meeting, each person's win or thank-you is added here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Add a section", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SECTIONS.forEach { (kind, name) ->
                    OutlinedButton(onClick = {
                        when (kind) {
                            "vote" -> slides.add(DraftSlide(kind, name, options = listOf("", "")))
                            "jobs" -> slides.add(DraftSlide(kind, name, jobs = listOf("" to "")))
                            "coming" -> {
                                val sl = DraftSlide(kind, name)
                                slides.add(sl)
                                // Next week's events, from the Datebook.
                                scope.launch {
                                    runCatching {
                                        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }
                                        val ev = Api(prefs.serverUrl, prefs.token).call("GET", "/api/events?from=${cal.timeInMillis}&to=${cal.timeInMillis + 8 * 86_400_000L}").optJSONArray("events") ?: JSONArray()
                                        val fmt = java.text.SimpleDateFormat("EEE d, h:mm a", java.util.Locale.getDefault())
                                        sl.text = (0 until minOf(ev.length(), 15)).joinToString("\n") { k -> ev.getJSONObject(k).let { "- ${fmt.format(java.util.Date(it.optLong("start")))}: ${it.optString("title")}" } }
                                            .ifEmpty { "Nothing on the calendar next week." }
                                    }
                                }
                            }
                            else -> slides.add(DraftSlide(kind, name))
                        }
                    }) { Text("+ $name") }
                }
            }
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
            // A vote: tap your choice (tap another to change it).
            if (s.options.isNotEmpty()) {
                val slideAt = at.coerceIn(0, m.slides.size - 1)
                s.options.forEachIndexed { k, o ->
                    val mine = prefs.memberId in o.votes
                    val names = o.votes.mapNotNull { id -> people.find { it.id == id }?.name }
                    androidx.compose.material3.OutlinedCard(onClick = { act { meetingVote(m.id, slideAt, k, prefs.memberId) } }, modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(if (mine) 2.dp else 1.dp, if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(o.text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(if (names.isEmpty()) "" else "${names.size} · ${names.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
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
