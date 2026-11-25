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
 * Who's Who: everyone's profile (birthday, allergies, medicines, sleep, likes,
 * sizes, doctor, emergency contacts), kept in their People note. Everyone sees
 * them; parents change anyone's, older children and grandparents their own.
 * Share or print one as a page for a babysitter.
 */
object Profiles {
    /** The Who's Who screen is open (from its home tile). */
    var open by mutableStateOf(false)
}

data class Profile(val id: String, val name: String, val color: String, val emoji: String, val fields: Map<String, String>, val canEdit: Boolean)
data class ProfilesData(val people: List<Profile>, val sections: List<String>, val parents: List<Pair<String, String>>)

suspend fun Api.profiles(): ProfilesData {
    val o = call("GET", "/api/profiles")
    val ps = o.optJSONArray("people") ?: JSONArray()
    val secs = o.optJSONArray("sections") ?: JSONArray()
    val pars = o.optJSONArray("parents") ?: JSONArray()
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
    )
}

/** A profile as plain text (for sharing), with the parents' numbers. */
private fun profileText(p: Profile, d: ProfilesData): String = buildString {
    appendLine(p.name)
    d.sections.filter { !p.fields[it].isNullOrBlank() }.forEach { appendLine(); appendLine(it.uppercase()); appendLine(p.fields[it]!!.trim()) }
    val parents = d.parents.filter { it.first.isNotBlank() }
    if (parents.isNotEmpty()) { appendLine(); appendLine("PARENTS"); parents.forEach { appendLine(it.first + if (it.second.isNotBlank()) ": ${it.second}" else "") } }
}

/** Prints a profile as one plain page (through Android's print screen: a printer or a PDF). */
private fun printProfile(context: Context, p: Profile, d: ProfilesData) {
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val rows = d.sections.filter { !p.fields[it].isNullOrBlank() }.joinToString("") { "<h2>${esc(it)}</h2><p>${esc(p.fields[it]!!.trim()).replace("\n", "<br>")}</p>" }
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

/** Who's Who, full screen (opened from its home tile). */
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
    val api = { Api(prefs.serverUrl, prefs.token) }
    suspend fun load() { runCatching { data = api().profiles() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(Unit) { load() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(Access.name("profiles")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        val d = data
        if (d == null) { Spacer(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(d.people, key = { it.id }) { p ->
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, profileText(p, d)), "Share ${p.name}'s profile"))
                            }) { Icon(androidx.compose.material.icons.Icons.Filled.Share, "Share") }
                            OutlinedButton(onClick = { printProfile(context, p, d) }) { Text("Print") }
                            if (p.canEdit) { Spacer(Modifier.width(6.dp)); FilledTonalButton(onClick = { editing = p }) { Text("Change") } }
                        }
                        val shown = d.sections.filter { !p.fields[it].isNullOrBlank() }
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
    editing?.let { p ->
        val values = remember(p.id) { mutableStateMapOf<String, String>().apply { data?.sections?.forEach { put(it, p.fields[it] ?: "") } } }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(p.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    data?.sections?.forEach { sec ->
                        OutlinedTextField(values[sec] ?: "", { values[sec] = it.take(4000) }, label = { Text(sec) },
                            placeholder = { Text(if (sec == "Birthday") "YYYY-MM-DD" else "One per line") },
                            singleLine = sec == "Birthday", minLines = if (sec == "Birthday") 1 else 2, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val body = JSONObject().put("profile", JSONObject(values.mapValues { it.value.trim() }))
                    editing = null
                    scope.launch {
                        runCatching { api().call("PUT", "/api/profiles/${p.id}", body) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        load()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
