package io.github.pretendgineering.famlogic

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dossier: everyone's profile (birthday, allergies, medicines, sleep, likes,
 * sizes, doctor, emergency contacts), kept in their People note. Everyone sees
 * them; parents change anyone's, older children and grandparents their own.
 * Share or print one as a page for a babysitter.
 */
object Profiles {
    /** The Dossier screen is open (from its home tile). */
    var open by mutableStateOf(false)
}

/** A person, a pet or a contact ([kind]: "person", "pet" or "contact"). */
data class Profile(val id: String, val name: String, val color: String, val emoji: String, val fields: Map<String, String>, val canEdit: Boolean, val kind: String = "person")
data class ProfilesData(
    val people: List<Profile>, val sections: List<String>, val parents: List<Pair<String, String>>,
    val pets: List<Profile> = emptyList(), val contacts: List<Profile> = emptyList(),
    val petSections: List<String> = emptyList(), val contactSections: List<String> = emptyList(), val canEditCards: Boolean = false,
) {
    fun sectionsFor(kind: String) = when (kind) { "pet" -> petSections; "contact" -> contactSections; else -> sections }
}

suspend fun Api.profiles(): ProfilesData {
    val o = call("GET", "/api/profiles")
    val ps = o.optJSONArray("people") ?: JSONArray()
    val secs = o.optJSONArray("sections") ?: JSONArray()
    val pars = o.optJSONArray("parents") ?: JSONArray()
    val canCards = o.optBoolean("canEditCards")
    fun strs(key: String) = (o.optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }
    fun cards(key: String, kind: String) = (o.optJSONArray(key) ?: JSONArray()).let { a ->
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { p ->
                val f = p.optJSONObject("profile") ?: JSONObject()
                Profile(p.optString("id"), p.optString("name"), if (kind == "pet") "#B0773F" else "#3DBE6A", p.optString("emoji"),
                    f.keys().asSequence().associateWith { f.optString(it) }, canCards, kind)
            }
        }
    }
    return ProfilesData(
        (0 until ps.length()).map { i ->
            ps.getJSONObject(i).let { p ->
                val f = p.optJSONObject("profile") ?: JSONObject()
                Profile(p.optString("id"), p.optString("name"), p.optString("color"), p.optString("emoji"),
                    f.keys().asSequence().associateWith { f.optString(it) }, p.optBoolean("canEdit"))
            }
        },
        (0 until secs.length()).map { secs.getString(it) },
        (0 until pars.length()).map { pars.getJSONObject(it).let { p -> p.optString("name") to p.optString("phone") } },
        cards("pets", "pet"), cards("contacts", "contact"), strs("petSections"), strs("contactSections"), canCards,
    )
}

/** A profile as plain text (for sharing), with the parents' numbers. */
private fun profileText(p: Profile, d: ProfilesData): String = buildString {
    appendLine(p.name)
    d.sectionsFor(p.kind).filter { !p.fields[it].isNullOrBlank() }.forEach { appendLine(); appendLine(it.uppercase()); appendLine(p.fields[it]!!.trim()) }
    val parents = d.parents.filter { it.first.isNotBlank() }
    if (parents.isNotEmpty()) { appendLine(); appendLine("PARENTS"); parents.forEach { appendLine(it.first + if (it.second.isNotBlank()) ": ${it.second}" else "") } }
}

/** Prints a profile as one plain page (through Android's print screen: a printer or a PDF). */
private fun printProfile(context: Context, p: Profile, d: ProfilesData) {
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val rows = d.sectionsFor(p.kind).filter { !p.fields[it].isNullOrBlank() }.joinToString("") { "<h2>${esc(it)}</h2><p>${esc(p.fields[it]!!.trim()).replace("\n", "<br>")}</p>" }
    val parents = d.parents.filter { it.first.isNotBlank() }.joinToString("") { "<li>${esc(it.first)}${if (it.second.isNotBlank()) ": " + esc(it.second) else ""}</li>" }
    val html = """<!doctype html><meta charset="utf-8"><style>body{font:15px/1.45 sans-serif;margin:32px;color:#222}
        h1{font-family:serif;margin:0 0 4px}h2{font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:#555;margin:18px 0 2px}p{margin:0}
        .box{border:2px solid #222;border-radius:12px;padding:10px 14px;margin-top:20px}</style>
        <h1>${esc(p.name)}</h1>${rows.ifEmpty { "<p>No details yet.</p>" }}${if (parents.isNotEmpty()) "<div class=box><h2 style=margin-top:0>Parents</h2><ul>$parents</ul></div>" else ""}"""
    val web = WebView(context)
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print("FamLogic - ${p.name}", view.createPrintDocumentAdapter(p.name), PrintAttributes.Builder().build())
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

/** Dossier, full screen (opened from its home tile). */
@Composable
fun ProfilesDialog(prefs: Prefs) {
    Dialog(onDismissRequest = { Profiles.open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ProfilesScreen(prefs) { Profiles.open = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ProfilesData?>(null) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    // People, pets or contacts (Menagerie and the address book live in Dossier now).
    var tab by rememberSaveable { mutableStateOf("person") }
    var adding by remember { mutableStateOf(false) }
    val api = { Api(prefs.serverUrl, prefs.token) }
    suspend fun load() { runCatching { data = api().profiles() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(Unit) { load() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(Access.name("profiles")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }, floatingActionButton = {
        if (tab != "person" && data?.canEditCards == true) ExtendedFloatingActionButton(onClick = { adding = true }, modifier = Modifier.pageFab()) {
            Icon(androidx.compose.material.icons.Icons.Filled.Add, contentDescription = null); Text(if (tab == "pet") "Add a pet" else "Add a contact", Modifier.padding(start = 8.dp))
        }
    }) { pad ->
        val d = data
        if (d == null) { Spacer(Modifier.padding(pad)); return@Scaffold }
        val shownList = when (tab) { "pet" -> d.pets; "contact" -> d.contacts; else -> d.people }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "tabs") {
                androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val tabs = listOf("person" to "People", "pet" to "Pets", "contact" to "Contacts")
                    tabs.forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = tab == k, onClick = { tab = k },
                            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, tabs.size)) { Text(label) }
                    }
                }
            }
            if (shownList.isEmpty()) item(key = "none") {
                Text(if (tab == "pet") "No pets yet." else if (tab == "contact") "No contacts yet: the vet, the plumber, Grandma." else "Nobody yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            items(shownList, key = { it.id }) { p ->
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.kind != "person") Text(p.emoji.ifEmpty { if (p.kind == "pet") "🐾" else "📇" }, fontSize = 26.sp, modifier = Modifier.padding(end = 10.dp))
                            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, profileText(p, d)), "Share ${p.name}'s profile"))
                            }) { Icon(androidx.compose.material.icons.Icons.Filled.Share, "Share") }
                            OutlinedButton(onClick = { printProfile(context, p, d) }) { Text("Print") }
                            if (p.canEdit) { Spacer(Modifier.width(6.dp)); FilledTonalButton(onClick = { editing = p }) { Text("Change") } }
                        }
                        val shown = d.sectionsFor(p.kind).filter { !p.fields[it].isNullOrBlank() }
                        if (shown.isEmpty()) Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        shown.forEach { sec ->
                            Text(sec.uppercase(), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium, color = Accent2, fontWeight = FontWeight.SemiBold)
                            Text(p.fields[sec]!!.trim(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("") }
        var extra by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(if (tab == "pet") "A new pet" else "A new contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(emoji, { emoji = it.take(8) }, label = { Text("Picture (an emoji)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(extra, { extra = it.take(200) }, label = { Text(if (tab == "pet") "What kind" else "Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val body = JSONObject().put("kind", tab).put("name", name.trim()).put("emoji", emoji.trim())
                        .put("profile", JSONObject().put(if (tab == "pet") "Kind" else "Phone", extra.trim()))
                    adding = false
                    scope.launch {
                        runCatching { api().call("POST", "/api/profiles/cards", body) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        load()
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    editing?.let { p ->
        val secs = data?.sectionsFor(p.kind).orEmpty()
        val values = remember(p.id) { mutableStateMapOf<String, String>().apply { secs.forEach { put(it, p.fields[it] ?: "") } } }
        var newName by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(p.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.kind != "person") OutlinedTextField(newName, { newName = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    secs.forEach { sec ->
                        OutlinedTextField(values[sec] ?: "", { values[sec] = it.take(4000) }, label = { Text(sec) },
                            placeholder = { Text(if (sec == "Birthday") "YYYY-MM-DD" else "One per line") },
                            singleLine = sec == "Birthday", minLines = if (sec == "Birthday") 1 else 2, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val body = JSONObject().put("profile", JSONObject(values.mapValues { it.value.trim() }))
                    if (p.kind != "person" && newName.isNotBlank()) body.put("name", newName.trim())
                    editing = null
                    scope.launch {
                        runCatching { api().call("PUT", if (p.kind == "person") "/api/profiles/${p.id}" else "/api/profiles/cards/${p.id}", body) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        load()
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (p.kind != "person") TextButton(onClick = {
                        editing = null
                        scope.launch {
                            runCatching { api().call("DELETE", "/api/profiles/cards/${p.id}") }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                            load()
                        }
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
