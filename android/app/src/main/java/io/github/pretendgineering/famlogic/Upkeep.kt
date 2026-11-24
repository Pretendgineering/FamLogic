package io.github.pretendgineering.famlogic

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Upkeep: the family's chores. Each is a Markdown note in the vault (the server
 * keeps them), so the list here, the console, Obsidian and an AI agent all see the
 * same thing. Children see theirs first, big, with a tick to press; parents also
 * see everyone's and add chores. Stars, when a chore has some, go to whoever did it.
 */
object Upkeep {
    /** The last list loaded, shared by the screen and the home tile. */
    var list by mutableStateOf<ChoreList?>(null)

    suspend fun refresh(prefs: Prefs) {
        list = runCatching { Api(prefs.serverUrl, prefs.token).chores() }.getOrDefault(list)
    }

    /** Due today (or late), and not done today. */
    fun dueNow(c: Chore, today: String) = !c.finished && (c.due.isEmpty() || c.due <= today) && c.done.none { it.first == today && c.every.isNotEmpty() }

    fun isMine(c: Chore, me: String) = c.who.isEmpty() || me in c.who

    fun repeatText(every: String) = when (every) {
        "" -> "Once"
        "day" -> "Every day"
        "weekdays" -> "Weekdays"
        "week" -> "Every week"
        "month" -> "Every month"
        else -> if (every.first().isDigit()) "Every $every" else every.split(",").joinToString(", ") { it.trim().replaceFirstChar { c -> c.uppercase() } }
    }
}

private val REPEATS = listOf("" to "Once", "day" to "Every day", "weekdays" to "Weekdays", "week" to "Every week", "2 weeks" to "Every 2 weeks", "month" to "Every month")

/** Ticks a chore off (for me, or for someone else when a parent does it), with a little celebration. */
private suspend fun tick(context: android.content.Context, prefs: Prefs, c: Chore, by: String = "") {
    try {
        val after = Api(prefs.serverUrl, prefs.token).choreDone(c.id, by = by)
        Upkeep.refresh(prefs)
        val stars = if (after.stars > 0) "  +${after.stars} ★" else ""
        Toast.makeText(context, "Done: ${c.title}$stars", Toast.LENGTH_SHORT).show()
        if (Access.younger) Voice.sent(context)
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't tick it off: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UpkeepScreen(prefs: Prefs) {
    var people by remember { mutableStateOf<List<Member>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { people = Api(prefs.serverUrl, prefs.token).members(0).members } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            Upkeep.refresh(prefs)
            delay(20_000)
        }
    }
    val data = Upkeep.list
    val me = prefs.memberId
    val big = Access.big || Access.younger
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Access.name("chores")) },
                actions = {
                    val mine = data?.stars?.get(me) ?: 0
                    if (mine > 0 || Access.isChild) Text("★ $mine", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.titleLarge, color = Color(0xFFE0A82E))
                },
            )
        },
        floatingActionButton = {
            if (data?.canEdit == true || data?.level == "add") {
                ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add a chore") })
            }
        },
    ) { padding ->
        if (data == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
            return@Scaffold
        }
        val today = data.today
        val mineNow = data.chores.filter { Upkeep.isMine(it, me) && Upkeep.dueNow(it, today) }
        val othersNow = data.chores.filter { !Upkeep.isMine(it, me) && Upkeep.dueNow(it, today) }
        val doneToday = data.chores.filter { c -> c.done.any { it.first == today } }
        val later = data.chores.filter { !it.finished && it.due > today && it.done.none { d -> d.first == today } }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(if (Access.isChild) "Your jobs" else "Yours") }
            if (mineNow.isEmpty()) item { Text("Nothing to do right now. 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge) }
            items(mineNow, key = { "m" + it.id }) { c ->
                ChoreCard(c, people, today, big = big, canUndo = data.canEdit) { scope.launch { tick(context, prefs, c) } }
            }
            if (othersNow.isNotEmpty() && !Access.younger) {
                item { SectionTitle("Everyone else") }
                items(othersNow, key = { "o" + it.id }) { c ->
                    ChoreCard(c, people, today, big = false, canUndo = data.canEdit, canTick = data.canEdit) {
                        // A parent ticking someone's chore: it goes to the person it was for.
                        scope.launch { tick(context, prefs, c, by = c.who.firstOrNull().orEmpty()) }
                    }
                }
            }
            if (doneToday.isNotEmpty()) {
                item { SectionTitle("Done today") }
                items(doneToday, key = { "d" + it.id }) { c ->
                    ChoreCard(c, people, today, big = false, done = true, canUndo = data.canEdit, onUndo = {
                        scope.launch {
                            runCatching { Api(prefs.serverUrl, prefs.token).choreUndo(c.id, today) }
                            Upkeep.refresh(prefs)
                        }
                    }) {}
                }
            }
            if (later.isNotEmpty() && !Access.younger) {
                item { SectionTitle("Coming up") }
                items(later, key = { "l" + it.id }) { c -> ChoreCard(c, people, today, big = false, canTick = false, canUndo = false) {} }
            }
            // Stars, for parents: who has how many.
            if (data.canEdit && data.stars.isNotEmpty()) {
                item { SectionTitle("Stars") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.stars.forEach { (id, n) ->
                            val p = people.find { it.id == id } ?: return@forEach
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(p.name, p.emoji, p.color, 28)
                                Text("  ${p.name}  ★ $n", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddChoreDialog(prefs, people, data, onDone = { adding = false; scope.launch { Upkeep.refresh(prefs) } })
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/**
 * One chore as a card: a round tick to press, its name, who and when. Children's
 * are big; done ones are crossed through, and a parent can hold one to undo it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChoreCard(
    c: Chore, people: List<Member>, today: String, big: Boolean, done: Boolean = false,
    canTick: Boolean = true, canUndo: Boolean, onUndo: () -> Unit = {}, onTick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val late = !done && c.due.isNotEmpty() && c.due < today
    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
            .combinedClickable(onClick = {}, onLongClick = { if (done && canUndo) menu = true }),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = if (big) 16.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val ring = if (big) 52.dp else 36.dp
            Box(
                Modifier.size(ring).clip(CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(3.dp, if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .then(if (!done && canTick) Modifier.clickable(onClickLabel = "Done") { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onTick() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(ring * 0.6f))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    c.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                val who = c.who.mapNotNull { id -> people.find { it.id == id }?.name }.joinToString(", ").ifEmpty { "Anyone" }
                val sub = listOfNotNull(
                    if (!Access.younger) who else null,
                    Upkeep.repeatText(c.every),
                    c.whenOfDay.takeIf { it.isNotEmpty() },
                    if (late) "late (${c.due})" else if (!done && c.due > today) "from ${c.due}" else null,
                ).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (late) SOS_RED else MaterialTheme.colorScheme.onSurfaceVariant)
                if (big && c.notes.isNotEmpty()) Text(c.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (c.stars > 0) Text("★ ${c.stars}", style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall, color = Color(0xFFE0A82E), fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Not really done: undo") }, onClick = { menu = false; onUndo() })
        }
    }
}

/** A parent (or a child allowed to add their own) adds a chore. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddChoreDialog(prefs: Prefs, people: List<Member>, data: ChoreList?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ownOnly = data?.canEdit != true
    var title by remember { mutableStateOf("") }
    var who by remember { mutableStateOf(if (ownOnly) setOf(prefs.memberId) else emptySet()) }
    var every by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(data?.today ?: "") }
    var stars by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add a chore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("What needs doing") }, placeholder = { Text("e.g. Unload the dishwasher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!ownOnly) {
                    Text("For", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        people.filter { it.role != "dependent" }.forEach { p ->
                            FilterChip(selected = p.id in who, onClick = { who = if (p.id in who) who - p.id else who + p.id },
                                label = { Text(p.name) }, leadingIcon = { Avatar(p.name, p.emoji, p.color, 20) })
                        }
                    }
                }
                Text("Repeats", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REPEATS.forEach { (id, label) -> FilterChip(selected = every == id, onClick = { every = id }, label = { Text(label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From ", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        val parts = due.split("-").mapNotNull { it.toIntOrNull() }
                        val now = java.util.Calendar.getInstance()
                        DatePickerDialog(context, { _, y, m, d -> due = "%04d-%02d-%02d".format(y, m + 1, d) },
                            parts.getOrNull(0) ?: now.get(java.util.Calendar.YEAR), (parts.getOrNull(1) ?: (now.get(java.util.Calendar.MONTH) + 1)) - 1,
                            parts.getOrNull(2) ?: now.get(java.util.Calendar.DAY_OF_MONTH)).show()
                    }) { Text(if (due == data?.today) "Today" else due) }
                }
                if (!ownOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stars", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { stars = maxOf(0, stars - 1) }) { Text("−") }
                    Text("★ $stars", color = Color(0xFFE0A82E), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { stars = minOf(100, stars + 1) }) { Text("+") }
                }
                OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        Api(prefs.serverUrl, prefs.token).addChore(title.trim(), who.toList(), every, due, stars, notes)
                        onDone()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Couldn't add it: ${e.message}", Toast.LENGTH_LONG).show()
                        busy = false
                    }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * The home tile. Small: how many jobs are waiting. Big enough (2 by 2 or more): a
 * mini checklist of what's due, ticked right there. (The same idea for every module
 * tile: a shortcut when small, a little live version when big.)
 */
@Composable
fun UpkeepTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            Upkeep.refresh(prefs)
            delay(60_000)
        }
    }
    val data = Upkeep.list
    val me = prefs.memberId
    val due = data?.chores?.filter { Upkeep.isMine(it, me) && Upkeep.dueNow(it, data.today) }.orEmpty()
    Column(Modifier.fillMaxWidth().height(height).clickable(onClickLabel = "Open ${Access.name("chores")}", onClick = onOpen).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tileIcon("chores"), contentDescription = null, tint = Accent2, modifier = Modifier.size(if (big) 32.dp else 26.dp))
            Text(Access.name("chores"), Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val stars = data?.stars?.get(me) ?: 0
            if (stars > 0) Text("★ $stars", color = Color(0xFFE0A82E), fontWeight = FontWeight.Bold)
        }
        if (wide >= 2 && tall >= 2) {
            Spacer(Modifier.height(6.dp))
            if (due.isEmpty()) Text("All done! 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant)
            due.take(5).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).border(2.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable(onClickLabel = "Done") { scope.launch { tick(context, prefs, c) } },
                    )
                    Text(c.title, Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.stars > 0) Text("★${c.stars}", color = Color(0xFFE0A82E), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (due.size > 5) Text("and ${due.size - 5} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Spacer(Modifier.weight(1f))
            Text(
                when (due.size) { 0 -> "All done"; 1 -> "1 to do"; else -> "${due.size} to do" },
                style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            )
        }
    }
}
