package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Glance tiles: the home screen's "at a glance" tiles that aren't a module of their
// own. Each grows from a single fact at 1 x 1 to the whole picture at its biggest.

private val muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun GlanceFrame(title: String, icon: ImageVector, height: Dp, wide: Int, big: Boolean, onClick: (() -> Unit)?, body: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(height).then(if (onClick != null) Modifier.clickable(onClickLabel = title, onClick = onClick) else Modifier).padding(14.dp),
    ) {
        TileHead(wide, big, title, icon = { Icon(icon, contentDescription = null, tint = Accent2, modifier = Modifier.size(if (big) 32.dp else 26.dp)) })
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) { body() }
    }
}

/** A big line of text that shrinks to fit (a time, a temperature, a count). */
@Composable
private fun BigFact(text: String, color: Color = MaterialTheme.colorScheme.onSurface, max: Int = 40) {
    val style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold, color = color, fontSize = max.sp)
    androidx.compose.foundation.text.BasicText(text, style = style, maxLines = 1,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = max.sp, stepSize = 1.sp))
}

// ---- Today: routines on now, chores due, the next event

@Composable
fun TodayTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: (Tab) -> Unit) {
    LaunchedEffect(Unit) { while (true) { Upkeep.refresh(prefs); Rhythms.refresh(prefs); Datebook.refresh(prefs); delay(60_000) } }
    val me = prefs.memberId
    val up = Upkeep.list
    val due = up?.chores?.filter { Upkeep.isMine(it, me) && Upkeep.dueNow(it, up.today) }.orEmpty()
    val rh = Rhythms.data
    val routine = rh?.routines?.find { it.list.id == rh.now[me] }
    val now = System.currentTimeMillis()
    val tonight = Datebook.midnight(1)
    val events = Datebook.data?.events?.filter { it.end > now && it.start < tonight }.orEmpty()
    GlanceFrame("Today", Icons.Filled.WbSunny, height, wide, big, onClick = null) {
        if (wide == 1 && tall == 1) {
            // One square: how much is left today, all told.
            val n = due.size + events.size + (routine?.list?.items?.count { !it.done } ?: 0)
            Column(Modifier.clickable { onOpen(Tab.Chores) }) {
                BigFact(if (n == 0) "Clear" else "$n")
                Text(if (n == 0) "nothing left" else "to do today", color = muted, style = MaterialTheme.typography.bodySmall)
            }
            return@GlanceFrame
        }
        @Composable fun Section(label: String, tab: Tab, lines: List<String>, max: Int) {
            if (lines.isEmpty()) return
            Column(Modifier.fillMaxWidth().clickable { onOpen(tab) }.padding(vertical = 3.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Accent2)
                lines.take(max).forEach { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                if (lines.size > max) Text("and ${lines.size - max} more", color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        val per = (tall * 2).coerceAtLeast(1)
        val sections = @Composable { m: Modifier ->
            Section(routine?.list?.title ?: "", Tab.Routines, routine?.list?.items?.filter { !it.done }?.map { it.text }.orEmpty(), per)
            Section("Chores", Tab.Chores, due.map { it.title }, per)
            Section("On today", Tab.Calendar, events.map { Datebook.timeText(it) + "  " + it.title }, per)
            if (routine == null && due.isEmpty() && events.isEmpty()) Text("A clear day.", color = muted)
        }
        // Wide: the three side by side; otherwise one under another.
        if (wide >= 4) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) { Section(routine?.list?.title ?: "Routine", Tab.Routines, routine?.list?.items?.filter { !it.done }?.map { it.text }.orEmpty(), per * 2) }
            Column(Modifier.weight(1f)) { Section("Chores", Tab.Chores, due.map { it.title }, per * 2) }
            Column(Modifier.weight(1f)) { Section("On today", Tab.Calendar, events.map { Datebook.timeText(it) + "  " + it.title }, per * 2) }
        } else Column { sections(Modifier) }
    }
}

// ---- the feed: today so far

@Composable
fun FeedTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    var events by remember { mutableStateOf<List<AppEvent>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            events = runCatching { Api(prefs.serverUrl, prefs.token).feed(Datebook.midnight(), System.currentTimeMillis() + 60_000) }.getOrDefault(events)
            delay(60_000)
        }
    }
    val latest = events.sortedByDescending { it.time }
    GlanceFrame("Feed", Icons.Filled.Cloud, height, wide, big, onOpen) {
        if (latest.isEmpty()) { Text("Nothing yet today.", color = muted); return@GlanceFrame }
        if (wide == 1 && tall == 1) {
            Column { BigFact("${latest.size}"); Text("today", color = muted, style = MaterialTheme.typography.bodySmall) }
            return@GlanceFrame
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fit = (maxHeight / 34.dp).toInt().coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                latest.take(fit).forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.time)), Modifier.width(if (wide >= 2) 64.dp else 0.dp),
                            style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
                        Text(feedText(prefs, e).first, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ---- who's home

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhosHomeTile(members: List<Member>, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    val people = members.filter { it.role != "dependent" }
    val home = people.filter { !it.paused && it.place.equals("Home", ignoreCase = true) }
    GlanceFrame("Who's home", Icons.Filled.WbCloudy, height, wide, big, onOpen) {
        if (wide == 1 && tall == 1) {
            Column { BigFact("${home.size}/${people.size}"); Text("at home", color = muted, style = MaterialTheme.typography.bodySmall) }
            return@GlanceFrame
        }
        val face = if (big || tall >= 2) 44 else 34
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            people.sortedBy { if (it in home) 0 else 1 }.forEach { m ->
                val isHome = m in home
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.alpha(if (isHome) 1f else .4f)) { Avatar(m.name, m.emoji, m.color, face) }
                    if (tall >= 2 || wide >= 3) Text(if (isHome) m.name else Seen.where(m).substringBefore(" ·"), style = MaterialTheme.typography.labelSmall,
                        color = if (isHome) MaterialTheme.colorScheme.onSurface else muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width((face + 30).dp))
                }
            }
        }
    }
}


// ---- countdown: the next birthday, holiday or trip

private data class Countdown(val title: String, val date: Calendar)

@Composable
fun CountdownTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    var birthdays by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        Datebook.refresh(prefs)
        birthdays = runCatching { Api(prefs.serverUrl, prefs.token).profiles().people.mapNotNull { p -> p.fields["Birthday"]?.takeIf { it.length >= 10 }?.let { p.name to it } } }.getOrDefault(emptyList())
    }
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val items = buildList {
        birthdays.forEach { (name, date) ->
            val p = date.split("-")
            if (p.size >= 3) {
                val c = today.clone() as Calendar
                c.set(Calendar.MONTH, (p[1].toIntOrNull() ?: 1) - 1); c.set(Calendar.DAY_OF_MONTH, p[2].take(2).toIntOrNull() ?: 1)
                if (c.before(today)) c.add(Calendar.YEAR, 1)
                add(Countdown("$name's birthday", c))
            }
        }
        // All-day things ahead (holidays, trips, a day out) count down too.
        Datebook.data?.events?.filter { it.allDay && it.start > today.timeInMillis }?.distinctBy { it.title }?.forEach { e ->
            add(Countdown(e.title, Calendar.getInstance().apply { timeInMillis = e.start }))
        }
    }.sortedBy { it.date.timeInMillis }
    fun days(c: Calendar) = ((c.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
    GlanceFrame("Countdown", Icons.Filled.WbSunny, height, wide, big, onOpen) {
        val next = items.firstOrNull()
        if (next == null) { Text("Add birthdays in Dossier, or all-day events in Datebook.", color = muted, style = MaterialTheme.typography.bodySmall); return@GlanceFrame }
        Column {
            val d = days(next.date)
            BigFact(if (d == 0) "Today" else if (d == 1) "1 day" else "$d days", Accent2)
            Text(next.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (tall >= 2) items.drop(1).take(tall * 2).forEach {
                Text("${days(it.date)} days · ${it.title}", color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- clock

@Composable
fun ClockTile(height: Dp, wide: Int, tall: Int, big: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L * (60 - (now / 1000) % 60)) } }
    Column(Modifier.fillMaxWidth().height(height).padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BigFact(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), max = 24 + 14 * minOf(tall, wide).coerceAtMost(4))
        if (wide >= 2 || tall >= 2) Text(java.text.SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)), color = muted,
            style = if (tall >= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

// ---- the sticky note on the fridge

@Composable
fun NoteTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var by by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    suspend fun load() {
        runCatching { Api(prefs.serverUrl, prefs.token).call("GET", "/api/fridge") }.onSuccess { text = it.optString("text"); by = it.optString("by") }
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(60_000) } }
    // A sticky note: soft yellow, whatever the theme.
    Column(
        Modifier.fillMaxSize().background(Color(0xFFFFF1A8)).clickable(enabled = !Access.isDisplay, onClickLabel = "Change the note") { editing = true }.padding(14.dp),
    ) {
        Text(text.ifEmpty { "Tap to leave the family a note." }, Modifier.weight(1f), color = if (text.isEmpty()) Color(0xFF8A7A30) else Color(0xFF3A3210),
            style = if (wide * tall >= 4) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge, overflow = TextOverflow.Ellipsis)
        if (by.isNotEmpty() && (tall >= 2 || wide >= 2)) Text("— $by", color = Color(0xFF6B5E20), style = MaterialTheme.typography.labelMedium)
    }
    if (editing) {
        var draft by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("The family's note") },
            text = { OutlinedTextField(draft, { draft = it.take(300) }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    scope.launch {
                        runCatching { Api(prefs.serverUrl, prefs.token).call("PUT", "/api/fridge", JSONObject().put("text", draft)) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        load()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

// ---- weather at home

private fun weatherIcon(code: Int): ImageVector = when {
    code == 0 -> Icons.Filled.WbSunny
    code <= 3 -> Icons.Filled.WbCloudy
    code == 45 || code == 48 -> Icons.Filled.Dehaze
    code in 71..77 || code == 85 || code == 86 -> Icons.Filled.AcUnit
    code >= 95 -> Icons.Filled.Thunderstorm
    code >= 51 -> Icons.Filled.Umbrella
    else -> Icons.Filled.Cloud
}

@Composable
fun WeatherTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean) {
    var wx by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf("") }
    val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Api(prefs.serverUrl, prefs.token).call("GET", "/api/weather?unit=" + if (fahrenheit) "f" else "c") }
                .onSuccess { wx = it; err = "" }.onFailure { if (wx == null) err = it.message ?: "No weather" }
            delay(15 * 60_000)
        }
    }
    val w = wx
    Column(Modifier.fillMaxWidth().height(height).padding(14.dp)) {
        if (w == null) { Text(err.ifEmpty { "Weather…" }, color = muted, style = MaterialTheme.typography.bodySmall); return@Column }
        val unit = w.optString("unit")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(weatherIcon(w.optInt("code")), contentDescription = null, tint = Accent2, modifier = Modifier.size(if (tall >= 2 || big) 40.dp else 28.dp))
            Spacer(Modifier.width(8.dp))
            BigFact("${w.optDouble("temp").toInt()}${unit.take(1)}")
        }
        if (wide >= 2 || tall >= 2) {
            Text(w.optString("text") + " · " + "${w.optDouble("high").toInt()}° / ${w.optDouble("low").toInt()}°", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (w.optInt("rain") >= 20) Text("${w.optInt("rain")}% chance of rain", color = muted, style = MaterialTheme.typography.bodySmall)
        }
        // Wide: the next hours in a row.
        val hours = w.optJSONArray("hours")
        if (wide >= 3 && hours != null && hours.length() > 0) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0 until minOf(hours.length(), wide + 1)) {
                    val h = hours.getJSONObject(i)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(java.text.SimpleDateFormat("ha", Locale.getDefault()).format(Date(h.optLong("time"))).lowercase(), color = muted, style = MaterialTheme.typography.labelSmall)
                        Icon(weatherIcon(h.optInt("code")), contentDescription = null, tint = Accent2.copy(alpha = .8f), modifier = Modifier.size(20.dp))
                        Text("${h.optDouble("temp").toInt()}°", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ---- Quick add and Quiet time: one-button tiles' pop-ups

/** Quick add: type (or say) something, then where it goes: Shopping or your to-dos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddPopup(prefs: Prefs, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var lists by remember { mutableStateOf<List<FamilyList>>(emptyList()) }
    LaunchedEffect(Unit) { lists = runCatching { Api(prefs.serverUrl, prefs.token).lists().lists }.getOrDefault(emptyList()) }
    val me = prefs.memberId
    // Shopping first, then this person's to-dos, then the rest.
    val targets = lists.sortedBy { l -> when { l.title.equals("Shopping", true) -> 0; l.kind == "todos" && me in l.who -> 1; else -> 2 } }.take(4)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Quick add") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it.take(200) }, placeholder = { Text("Milk, call the dentist…") }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                    if (Access.can("voice")) TalkButton(prefs, 44.dp, onText = { if (it.isNotBlank()) text = it.trim() })
                }
                if (targets.isEmpty()) Text("No lists yet: make one in ${Access.name("todos")}.", color = muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { l ->
                        OutlinedButton(enabled = text.isNotBlank(), onClick = {
                            scope.launch {
                                val msg = runCatching { Api(prefs.serverUrl, prefs.token).addListItem(l.id, text.trim()); "Added to ${l.title}" }.getOrElse { it.message ?: "Couldn't add it" }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                onDone()
                            }
                        }, shape = RoundedCornerShape(16.dp)) { Text(l.title) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** Quiet time on the TV in one tap: how long. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuietPopup(prefs: Prefs, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Corkboard.refresh(prefs) }
    fun send(minutes: Int?) = scope.launch {
        val api = Api(prefs.serverUrl, prefs.token)
        runCatching { if (minutes == null) api.call("DELETE", "/api/display/quiet") else api.call("POST", "/api/display/quiet", JSONObject().put("minutes", minutes)) }
            .onSuccess { Toast.makeText(context, if (minutes == null) "Quiet time is over" else "Quiet time on the TV", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Quiet time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (Corkboard.quietOn) "Quiet time is on now." else "The TV and family displays show a calm quiet-time screen.", color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Corkboard.quietOn) OutlinedButton(onClick = { send(null) }, shape = RoundedCornerShape(16.dp)) { Text("End it") }
                    else listOf(30 to "30 min", 60 to "1 hour", 120 to "2 hours", 0 to "Until I end it").forEach { (m, t) ->
                        OutlinedButton(onClick = { send(m) }, shape = RoundedCornerShape(16.dp)) { Text(t) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---- the bigger views of module tiles that are otherwise shortcuts

@Composable
private fun ModuleGlance(id: String, height: Dp, wide: Int, big: Boolean, onOpen: () -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().height(height).clickable(onClickLabel = "Open ${Access.name(id)}", onClick = onOpen).padding(14.dp)) {
        TileHead(wide, big, Access.name(id), icon = { ModuleIcon(id, if (big) 32.dp else 26.dp) })
        Column(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) { body() }
    }
}

/** Dossier: everyone's face, with what matters in a hurry (allergies, medicines) as badges. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DossierTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    var people by remember { mutableStateOf<List<Profile>>(emptyList()) }
    LaunchedEffect(Unit) { people = runCatching { Api(prefs.serverUrl, prefs.token).profiles().people }.getOrDefault(emptyList()) }
    ModuleGlance("profiles", height, wide, big, onOpen) {
        if (people.isEmpty()) { Text("Allergies, medicines, contacts", color = muted, style = MaterialTheme.typography.bodySmall); return@ModuleGlance }
        @Composable fun Badge(text: String, color: Color) =
            Text(text, Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = .18f)).padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        val lines = { p: Profile, k: String -> p.fields[k].orEmpty().lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() } }
        if (wide >= 3) {
            // Wide: a row each, with the allergies and medicines spelled out.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fit = (maxHeight / 44.dp).toInt().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.take(fit).forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.name, p.emoji, p.color, 34)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                val a = lines(p, "Allergies"); val m = lines(p, "Medicines")
                                if (a.isNotEmpty() || m.isNotEmpty()) Text((a.map { "Allergic: $it" } + m).joinToString(" · "), color = muted,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            people.forEach { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(p.name, p.emoji, p.color, 36)
                    if (lines(p, "Allergies").isNotEmpty()) Badge("Allergy", Color(0xFFE5533D))
                    if (lines(p, "Medicines").isNotEmpty()) Badge("Meds", Color(0xFF59B7F0))
                }
            }
        }
    }
}

/** Homestead: the home's next jobs, and bin day. */
@Composable
fun HomesteadTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    LaunchedEffect(Unit) { Homestead.refresh(prefs) }
    val d = Homestead.data
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    ModuleGlance("homestead", height, wide, big, onOpen) {
        if (d == null) return@ModuleGlance
        if (d.bins.isNotEmpty()) Text("Bins go out on " + d.bins.replaceFirstChar { it.uppercase() } + (if (d.recycling.isNotEmpty()) " · recycling " + d.recycling.replaceFirstChar { it.uppercase() } else ""),
            color = Accent2, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 4.dp)) {
            val fit = (maxHeight / 26.dp).toInt().coerceAtLeast(1)
            Column {
                if (d.jobs.isEmpty()) Text("No home jobs yet.", color = muted)
                d.jobs.take(fit).forEach { j ->
                    Row {
                        Text(j.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            color = if (j.due.isNotEmpty() && j.due <= today) Accent2 else MaterialTheme.colorScheme.onSurface)
                        if (j.due.isNotEmpty()) Text(if (j.due <= today) "now" else j.due.drop(5), color = muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Huddle: the next family meeting, and its topics. */
@Composable
fun HuddleTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    LaunchedEffect(Unit) { Huddle.refresh(prefs) }
    val m = Huddle.data?.meetings?.firstOrNull()
    ModuleGlance("meetings", height, wide, big, onOpen) {
        if (m == null) { Text("No meetings yet.", color = muted); return@ModuleGlance }
        Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(m.date, color = muted, style = MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 4.dp)) {
            val fit = (maxHeight / 24.dp).toInt().coerceAtLeast(1)
            Column { m.slides.take(fit).forEachIndexed { i, sl -> Text("${i + 1}. ${sl.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}

/** Corkboard: whether it's quiet time on the TV, and its newest message. */
@Composable
fun CorkboardTile(prefs: Prefs, height: Dp, wide: Int, tall: Int, big: Boolean, onOpen: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { Corkboard.refresh(prefs); delay(60_000) } }
    ModuleGlance("display", height, wide, big, onOpen) {
        Text(if (Corkboard.quietOn) "Quiet time is on" else "No quiet time", style = MaterialTheme.typography.titleMedium,
            color = if (Corkboard.quietOn) Accent2 else MaterialTheme.colorScheme.onSurface)
        if (Corkboard.quietOn && Corkboard.quietUntil > 0) Text("until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Corkboard.quietUntil)), color = muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        if (Corkboard.lastMessage.isNotEmpty()) {
            Text("On the TV:", color = muted, style = MaterialTheme.typography.labelSmall)
            Text("“${Corkboard.lastMessage}”", maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (Corkboard.lastFrom.isNotEmpty()) Text("from ${Corkboard.lastFrom}", color = muted, style = MaterialTheme.typography.labelSmall)
        } else Text("Tap for quiet time and messages.", color = muted, style = MaterialTheme.typography.bodySmall)
    }
}
