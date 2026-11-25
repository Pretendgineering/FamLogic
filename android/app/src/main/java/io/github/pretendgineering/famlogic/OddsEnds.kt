package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Odds & Ends: shared lists (shopping, packing) and to-dos. Each is a Markdown note
 * in the vault (Lists/, To-dos/), so the console, Obsidian and an AI agent all see
 * the same items.
 */
data class ListItem(val id: String, val text: String, val done: Boolean, val who: String, val due: String, val section: String)
data class FamilyList(val id: String, val title: String, val kind: String, val who: List<String>, val items: List<ListItem>)
data class ListsData(val lists: List<FamilyList>, val canEdit: Boolean, val canAdd: Boolean)

private fun parseList(o: JSONObject): FamilyList {
    val items = o.optJSONArray("items") ?: JSONArray()
    val who = o.optJSONArray("who") ?: JSONArray()
    return FamilyList(
        o.optString("id"), o.optString("title"), o.optString("kind"),
        (0 until who.length()).map { who.getString(it) },
        (0 until items.length()).map { i ->
            items.getJSONObject(i).let { ListItem(it.optString("id"), it.optString("text"), it.optBoolean("done"), it.optString("who"), it.optString("due"), it.optString("section")) }
        },
    )
}

suspend fun Api.lists(): ListsData {
    val o = call("GET", "/api/lists")
    val arr = o.optJSONArray("lists") ?: JSONArray()
    return ListsData((0 until arr.length()).map { parseList(arr.getJSONObject(it)) }, o.optBoolean("canEdit"), o.optBoolean("canAdd"))
}
suspend fun Api.createList(title: String, kind: String, who: List<String>): FamilyList =
    parseList(call("POST", "/api/lists", JSONObject().put("title", title).put("kind", kind).put("who", JSONArray(who))))
suspend fun Api.addListItem(id: String, text: String, who: String = "", section: String = ""): FamilyList =
    parseList(call("POST", "/api/lists/$id/items", JSONObject().put("text", text).put("who", who).put("section", section)))
suspend fun Api.tickListItem(id: String, item: String, done: Boolean): FamilyList =
    parseList(call("POST", "/api/lists/$id/items/$item", JSONObject().put("done", done)))
suspend fun Api.removeListItem(id: String, item: String): FamilyList = parseList(call("POST", "/api/lists/$id/items/$item/delete"))
suspend fun Api.clearList(id: String): FamilyList = parseList(call("POST", "/api/lists/$id/clear"))
suspend fun Api.archiveList(id: String) { call("POST", "/api/lists/$id/delete") }

object OddsEnds {
    var data by mutableStateOf<ListsData?>(null)
    suspend fun refresh(prefs: Prefs) {
        data = runCatching { Api(prefs.serverUrl, prefs.token).lists() }.getOrDefault(data)
    }
    fun replace(l: FamilyList) {
        val d = data ?: return
        data = d.copy(lists = d.lists.map { if (it.id == l.id) l else it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(prefs: Prefs, openList: String? = null) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(openList) }
    var making by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<Member>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { people = Api(prefs.serverUrl, prefs.token).members(Long.MAX_VALUE / 2).members } }
    LaunchedEffect(Unit) {
        while (true) {
            OddsEnds.refresh(prefs)
            delay(15_000)
        }
    }
    val data = OddsEnds.data
    data?.lists?.find { it.id == open }?.let { l ->
        ListView(prefs, l, data, people) { open = null }
        return
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(Access.name("todos")) }) },
        floatingActionButton = {
            if (data?.canEdit == true || data?.canAdd == true) {
                ExtendedFloatingActionButton(onClick = { making = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New list") })
            }
        },
    ) { padding ->
        if (data == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data.lists.isEmpty()) item { Text("No lists yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(data.lists, key = { it.id }) { l ->
                val left = l.items.count { !it.done }
                ElevatedCard(onClick = { open = l.id }, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(l.title, style = MaterialTheme.typography.titleLarge)
                        val whose = l.who.mapNotNull { id -> people.find { it.id == id }?.name }.joinToString(", ")
                        Text(listOfNotNull(if (l.kind == "todos") "To-dos" else "List", whose.ifEmpty { null }, if (left == 0) "all done" else "$left to go").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        l.items.filter { !it.done }.take(3).forEach { Text("• " + it.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                    }
                }
            }
        }
    }
    if (making) NewListDialog(prefs, people, data?.canEdit == true) { made ->
        making = false
        if (made != null) scope.launch { OddsEnds.refresh(prefs); open = made.id }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ListView(prefs: Prefs, l: FamilyList, data: ListsData, people: List<Member>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val api = { Api(prefs.serverUrl, prefs.token) }
    val big = Access.big || Access.younger
    fun act(block: suspend () -> FamilyList) = scope.launch {
        runCatching { block() }.onSuccess { OddsEnds.replace(it) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (data.canAdd) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Clear ticked items") }, onClick = { menu = false; act { api().clearList(l.id) } })
                            if (data.canEdit) DropdownMenuItem(text = { Text("Retire this list") }, onClick = {
                                menu = false
                                scope.launch { runCatching { api().archiveList(l.id) }; OddsEnds.refresh(prefs); onBack() }
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp)) {
                val sections = (l.items.filter { !it.done } + l.items.filter { it.done }).groupBy { if (it.done) "\u0000done" else it.section }
                sections.forEach { (section, its) ->
                    if (section == "\u0000done") item(key = "hd") { Text("Done", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                    else if (section.isNotEmpty()) item(key = "h$section") { Text(section, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                    items(its, key = { it.id }) { it ->
                        var itemMenu by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(onClick = { act { api().tickListItem(l.id, it.id, !it.done) } }, onLongClick = { if (data.canAdd) itemMenu = true })
                                    .padding(vertical = if (big) 12.dp else 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TickCircle(it.done, if (big) 34.dp else 26.dp)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(it.text, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                                        textDecoration = if (it.done) TextDecoration.LineThrough else null,
                                        color = if (it.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                    val sub = listOfNotNull(people.find { p -> p.id == it.who }?.name, it.due.ifEmpty { null }).joinToString(" · ")
                                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            DropdownMenu(expanded = itemMenu, onDismissRequest = { itemMenu = false }) {
                                DropdownMenuItem(text = { Text("Remove") }, onClick = { itemMenu = false; act { api().removeListItem(l.id, it.id) } })
                            }
                        }
                    }
                }
                if (l.items.isEmpty()) item { Text("Nothing on this list yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (data.canAdd) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it.take(200) }, Modifier.weight(1f), placeholder = { Text("Add an item") }, singleLine = true, shape = RoundedCornerShape(24.dp))
                if (text.isBlank() && Access.can("voice") && Voice.listener(context, prefs.serverFeatures) != null) {
                    TalkButton(prefs, 48.dp, onText = { said -> if (said.isNotBlank()) act { api().addListItem(l.id, said) } })
                } else IconButton(onClick = { val t = text.trim(); text = ""; if (t.isNotEmpty()) act { api().addListItem(l.id, t) } }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add")
                }
            }
        }
    }
}

@Composable
fun TickCircle(done: Boolean, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (done) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
            .border(2.5.dp, if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.65f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewListDialog(prefs: Prefs, people: List<Member>, canEdit: Boolean, onDone: (FamilyList?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(if (canEdit) "list" else "todos") }
    var who by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("New list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("Name") }, placeholder = { Text("e.g. Shopping, Beach trip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (canEdit) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = kind == "list", onClick = { kind = "list" }, label = { Text("Shared list") })
                        FilterChip(selected = kind == "todos", onClick = { kind = "todos" }, label = { Text("To-dos") })
                    }
                    if (kind == "todos") {
                        Text("Whose (none: the family's)", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            people.filter { it.role != "dependent" }.forEach { p ->
                                FilterChip(selected = p.id in who, onClick = { who = if (p.id in who) who - p.id else who + p.id }, label = { Text(p.name) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                scope.launch {
                    runCatching { Api(prefs.serverUrl, prefs.token).createList(title.trim(), kind, who.toList()) }
                        .onSuccess { onDone(it) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                }
            }) { Text("Make it") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

/** The home tile: the first list's open items (a mini checklist when big enough). */
@Composable
fun ListsTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            OddsEnds.refresh(prefs)
            delay(60_000)
        }
    }
    val l = OddsEnds.data?.lists?.firstOrNull()
    val open = l?.items?.filter { !it.done }.orEmpty()
    Column(Modifier.fillMaxWidth().height(height).clickable(onClickLabel = "Open ${Access.name("todos")}", onClick = onOpen).padding(14.dp)) {
        TileHead(wide, big, l?.title ?: Access.name("todos"), icon = { Icon(tileIcon("todos"), contentDescription = null, tint = Accent2, modifier = Modifier.size(if (big) 32.dp else 26.dp)) })
        if (l == null) {
            Spacer(Modifier.weight(1f)); Text("No lists yet", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column
        }
        if (tall >= 2 || wide >= 2) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                val fit = (maxHeight / 32.dp).toInt().coerceAtLeast(1) * (if (tall == 1) 1 else 1)
                Column {
                    if (open.isEmpty()) Text("All done", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val n = if (open.size > fit) fit - 1 else fit
                    open.take(n).forEach { it ->
                        Row(Modifier.fillMaxWidth().height(32.dp).clickable {
                            scope.launch { runCatching { Api(prefs.serverUrl, prefs.token).tickListItem(l.id, it.id, true) }.onSuccess { OddsEnds.replace(it) } }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            TickCircle(false, 22.dp)
                            Text(it.text, Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (open.size > n) Text("and ${open.size - n} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text(if (open.isEmpty()) "All done" else "${open.size} to get", style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
        }
    }
}
