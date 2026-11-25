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

private val WHENS = listOf("" to "None", "morning" to "Morning", "after school" to "Afternoon", "evening" to "Evening", "bedtime" to "Bedtime")

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
    var editing by remember { mutableStateOf<Chore?>(null) }
    var pickWho by remember { mutableStateOf<Chore?>(null) }
    var rewards by remember { mutableStateOf<RewardList?>(null) }
    var addingReward by remember { mutableStateOf(false) }
    val refreshAll: suspend () -> Unit = {
        Upkeep.refresh(prefs)
        rewards = runCatching { Api(prefs.serverUrl, prefs.token).rewards() }.getOrDefault(rewards)
    }
    LaunchedEffect(Unit) {
        while (true) {
            refreshAll()
            delay(20_000)
        }
    }
    val api = { Api(prefs.serverUrl, prefs.token) }
    // A parent's menu on a chore: change it, or retire it (its note stays in the vault).
    val manage: (Chore) -> Pair<() -> Unit, () -> Unit>? = { c ->
        if (Upkeep.list?.canEdit == true) ({ editing = c } to {
            scope.launch {
                runCatching { api().retireChore(c.id) }.onFailure { Toast.makeText(context, "Couldn't retire it: ${it.message}", Toast.LENGTH_LONG).show() }
                refreshAll()
            }
        }) else null
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
            if (mineNow.isEmpty()) item { Text("Nothing to do right now.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge) }
            items(mineNow, key = { "m" + it.id }) { c ->
                ChoreCard(c, people, today, big = big, canUndo = data.canEdit, manage = manage(c)) { scope.launch { tick(context, prefs, c) } }
            }
            if (othersNow.isNotEmpty() && !Access.younger) {
                item { SectionTitle("Everyone else") }
                items(othersNow, key = { "o" + it.id }) { c ->
                    ChoreCard(c, people, today, big = false, canUndo = data.canEdit, canTick = data.canEdit, manage = manage(c)) {
                        // A parent ticking someone's chore: the stars go to whoever did it
                        // (asked when it's shared).
                        if (c.who.size > 1) pickWho = c
                        else scope.launch { tick(context, prefs, c, by = c.who.firstOrNull().orEmpty()) }
                    }
                }
            }
            if (doneToday.isNotEmpty()) {
                item { SectionTitle("Done today") }
                items(doneToday, key = { "d" + it.id }) { c ->
                    // Grown-ups can take back anyone's tick; children their own (tap the tick).
                    val mineToday = c.done.any { it.first == today && it.second == me }
                    ChoreCard(c, people, today, big = big && mineToday, done = true, canUndo = data.canEdit || mineToday, manage = manage(c), onUndo = {
                        scope.launch {
                            runCatching { Api(prefs.serverUrl, prefs.token).choreUndo(c.id, today) }
                                .onSuccess { if (it.stars > 0) Toast.makeText(context, "Taken back: −${it.stars} ★", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "Couldn't take it back: ${it.message}", Toast.LENGTH_LONG).show() }
                            refreshAll()
                        }
                    }) {}
                }
            }
            if (later.isNotEmpty() && !Access.younger) {
                item { SectionTitle("Coming up") }
                items(later, key = { "l" + it.id }) { c -> ChoreCard(c, people, today, big = false, canTick = false, canUndo = false, manage = manage(c)) {} }
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
            // Rewards: what stars are for. Children ask; a parent says yes (the stars come off) or no.
            val rw = rewards
            if (rw != null && (rw.rewards.isNotEmpty() || rw.canEdit)) {
                item { SectionTitle("Rewards") }
                val pending = rw.asks.filter { it.status == "asked" }
                if (rw.canEdit) items(pending, key = { "a" + it.id }) { a ->
                    val who = people.find { it.id == a.memberId }
                    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (who != null) Avatar(who.name, who.emoji, who.color, 32)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text("${who?.name ?: "Someone"} asked for ${a.title}", style = MaterialTheme.typography.titleSmall)
                                Text("★ ${a.cost} · has ★ ${data.stars[a.memberId] ?: 0}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { scope.launch { runCatching { api().answerAsk(a.id, false) }; refreshAll() } }) { Text("No") }
                            androidx.compose.material3.FilledTonalButton(onClick = {
                                scope.launch {
                                    runCatching { api().answerAsk(a.id, true) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                    refreshAll()
                                }
                            }) { Text("Yes") }
                        }
                    }
                }
                items(rw.rewards, key = { "r" + it.id }) { r ->
                    val myStars = data.stars[me] ?: 0
                    val asked = rw.asks.any { it.title == r.title && it.memberId == me && it.status == "asked" }
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .then(if (rw.canEdit) Modifier.clickable { menu = true } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.title, Modifier.weight(1f), style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                        Text("★ ${r.cost}", color = Color(0xFFE0A82E), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                        if (!rw.canEdit) {
                            if (asked) Text("Asked", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else androidx.compose.material3.FilledTonalButton(enabled = myStars >= r.cost, onClick = {
                                scope.launch {
                                    runCatching { api().askReward(r.id) }
                                    Toast.makeText(context, "Asked for ${r.title}", Toast.LENGTH_SHORT).show()
                                    refreshAll()
                                }
                            }) { Text("Ask") }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove ${r.title}") }, onClick = {
                                menu = false
                                scope.launch { runCatching { api().deleteReward(r.id) }; refreshAll() }
                            })
                        }
                    }
                }
                // A child's latest answers.
                if (!rw.canEdit) items(rw.asks.filter { it.status != "asked" }.take(3), key = { "h" + it.id }) { a ->
                    Text((if (a.status == "approved") "Yes to " else "Not this time: ") + a.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rw.canEdit) item { TextButton(onClick = { addingReward = true }) { Text("Add a reward") } }
            }
        }
    }
    if (adding) AddChoreDialog(prefs, people, data, onDone = { adding = false; scope.launch { refreshAll() } })
    editing?.let { c -> AddChoreDialog(prefs, people, data, existing = c, onDone = { editing = null; scope.launch { refreshAll() } }) }
    pickWho?.let { c ->
        AlertDialog(
            onDismissRequest = { pickWho = null },
            title = { Text("Who did it?") },
            text = {
                Column {
                    c.who.mapNotNull { id -> people.find { it.id == id } }.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                pickWho = null
                                scope.launch { tick(context, prefs, c, by = p.id) }
                            }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p.name, p.emoji, p.color, 32)
                            Text(p.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickWho = null }) { Text("Cancel") } },
        )
    }
    if (addingReward) {
        var title by remember { mutableStateOf("") }
        var cost by remember { mutableIntStateOf(10) }
        AlertDialog(
            onDismissRequest = { addingReward = false },
            title = { Text("Add a reward") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(60) }, label = { Text("Reward") }, placeholder = { Text("e.g. 30 minutes of screen time") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    StarPicker(cost, 1, 1000) { cost = it }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    addingReward = false
                    scope.launch {
                        runCatching { api().addReward(title.trim(), cost) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        refreshAll()
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingReward = false }) { Text("Cancel") } },
        )
    }
}

/** Stars with −10, −5, −1, +1, +5 and +10 buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StarPicker(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Column {
        Text("★ $value", color = Color(0xFFE0A82E), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-10, -5, -1, 1, 5, 10).forEach { d ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { onChange((value + d).coerceIn(min, max)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp),
                ) { Text(if (d > 0) "+$d" else "−${-d}") }
            }
        }
    }
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
    canTick: Boolean = true, canUndo: Boolean, onUndo: () -> Unit = {},
    /** A parent's Change and Retire, or null. */
    manage: Pair<() -> Unit, () -> Unit>? = null,
    onTick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val late = !done && c.due.isNotEmpty() && c.due < today
    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
            .combinedClickable(onClick = {}, onLongClick = { if ((done && canUndo) || manage != null) menu = true }),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = if (big) 16.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val ring = if (big) 52.dp else 36.dp
            Box(
                Modifier.size(ring).clip(CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(3.dp, if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .then(
                        when {
                            !done && canTick -> Modifier.clickable(onClickLabel = "Done") { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onTick() }
                            // Tapping a done tick takes it back (a misclick, or not quite done).
                            done && canUndo -> Modifier.clickable(onClickLabel = "Not done") { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onUndo() }
                            else -> Modifier
                        },
                    ),
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
            if (done && canUndo) DropdownMenuItem(text = { Text("Not really done: undo") }, onClick = { menu = false; onUndo() })
            manage?.let { (edit, retire) ->
                DropdownMenuItem(text = { Text("Change") }, onClick = { menu = false; edit() })
                DropdownMenuItem(text = { Text("Retire") }, onClick = { menu = false; retire() })
            }
        }
    }
}

/** A parent (or a child allowed to add their own) adds a chore. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddChoreDialog(prefs: Prefs, people: List<Member>, data: ChoreList?, existing: Chore? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ownOnly = data?.canEdit != true
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var who by remember { mutableStateOf(existing?.who?.toSet() ?: if (ownOnly) setOf(prefs.memberId) else emptySet()) }
    var every by remember { mutableStateOf(existing?.every ?: "") }
    var due by remember { mutableStateOf(existing?.due?.ifEmpty { null } ?: data?.today ?: "") }
    var stars by remember { mutableIntStateOf(existing?.stars ?: 0) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var whenOfDay by remember { mutableStateOf(existing?.whenOfDay ?: "") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Add a chore" else "Change chore") },
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
                // A time of day: the chore's reminder goes out then.
                Text("Reminder", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WHENS.forEach { (id, label) -> FilterChip(selected = whenOfDay == id, onClick = { whenOfDay = id }, label = { Text(label) }) }
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
                if (!ownOnly) {
                    Text("Stars", style = MaterialTheme.typography.labelLarge)
                    StarPicker(stars, 0, 100) { stars = it }
                }
                OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val api = Api(prefs.serverUrl, prefs.token)
                        if (existing == null) api.addChore(title.trim(), who.toList(), every, due, stars, notes, whenOfDay)
                        else api.updateChore(existing.id, title.trim(), who.toList(), every, due, stars, notes, whenOfDay)
                        onDone()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Couldn't save it: ${e.message}", Toast.LENGTH_LONG).show()
                        busy = false
                    }
                }
            }) { Text(if (existing == null) "Add" else "Save") }
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
            if (Access.pictures) Pictures.Upkeep(if (big) 38.dp else 32.dp) else Icon(tileIcon("chores"), contentDescription = null, tint = Accent2, modifier = Modifier.size(if (big) 32.dp else 26.dp))
            Text(Access.name("chores"), Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val stars = data?.stars?.get(me) ?: 0
            if (stars > 0) Text("★ $stars", color = Color(0xFFE0A82E), fontWeight = FontWeight.Bold)
        }
        val tickBox: @Composable (Chore) -> Unit = { c ->
            Box(
                Modifier.size(26.dp).clip(CircleShape).border(2.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClickLabel = "Done") { scope.launch { tick(context, prefs, c) } },
            )
        }
        when {
            // A wide strip: the next few chores side by side, each with its tick.
            tall == 1 && wide >= 2 -> {
                Spacer(Modifier.weight(1f))
                if (due.isEmpty()) Text("All done", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val n = if (due.size > wide) wide - 1 else wide
                    due.take(n).forEach { c ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            tickBox(c)
                            Text(c.title, Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (due.size > n) Text("+${due.size - n}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Tall (one wide and two up) or big: a checklist, as many as fit.
            tall >= 2 -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                val fit = ((maxHeight + 0.dp) / 34.dp).toInt().coerceAtLeast(1)
                Column {
                    if (due.isEmpty()) Text("All done", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val n = if (due.size > fit) fit - 1 else fit
                    due.take(n).forEach { c ->
                        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                            tickBox(c)
                            Text(c.title, Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.stars > 0 && wide >= 2) Text("★${c.stars}", color = Color(0xFFE0A82E), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (due.size > n) Text("and ${due.size - n} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                Spacer(Modifier.weight(1f))
                Text(
                    when (due.size) { 0 -> "All done"; 1 -> "1 to do"; else -> "${due.size} to do" },
                    style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}
