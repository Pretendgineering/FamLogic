package io.github.pretendgineering.famlogic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Home, with the family feed to its left: swipe right from Home to get there
 * (like a phone's news feed beside its home screen). Swiping is off while tiles
 * are being arranged.
 */
@Composable
fun HomePager(prefs: Prefs, onOpen: (AppEvent) -> Unit = {}, home: @Composable () -> Unit) {
    // Children have the feed only if a parent switched it on for them (console → Access).
    if (!Access.can("feed")) { home(); return }
    val pager = rememberPagerState(initialPage = 1, pageCount = { 2 })
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Back from the feed goes to Home.
    androidx.activity.compose.BackHandler(pager.currentPage == 0) { scope.launch { pager.animateScrollToPage(1) } }
    HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !HomeEditor.arranging, beyondViewportPageCount = 0) { page ->
        if (page == 1) home() else FeedScreen(prefs, visible = pager.currentPage == 0, onOpen = onOpen)
    }
}

/** Local midnight, [daysAgo] days back. */
private fun midnight(daysAgo: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, -daysAgo)
}.timeInMillis

/**
 * The family feed: what happened today, as a timeline ("Sam got to School",
 * "Mia unloaded the dishwasher ★2"), with earlier days a tap away. It shows what
 * your alerts would (arrivals only from the groups people share with you).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(prefs: Prefs, visible: Boolean, onOpen: (AppEvent) -> Unit = {}) {
    var days by remember { mutableIntStateOf(1) } // how many days are shown
    var items by remember { mutableStateOf<List<AppEvent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(days, visible) {
        if (!visible && items != null) return@LaunchedEffect
        while (true) {
            try {
                items = Api(prefs.serverUrl, prefs.token).feed(midnight(days - 1), System.currentTimeMillis() + 60_000)
                failed = false
            } catch (_: Exception) {
                failed = true
            }
            if (!visible) return@LaunchedEffect
            delay(30_000)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Today") }) }) { padding ->
        val list = items
        if (list == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (failed) "Couldn't reach the server." else "Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        // Grouped by day, newest first.
        val byDay = list.sortedByDescending { it.time }.groupBy { e -> (0 until days).first { e.time >= midnight(it) || it == days - 1 } }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp)) {
            for (d in 0 until days) {
                item(key = "h$d") {
                    Text(
                        when (d) { 0 -> "Today"; 1 -> "Yesterday"; else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(midnight(d))) },
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                }
                val today = byDay[d].orEmpty()
                if (today.isEmpty()) item(key = "e$d") {
                    Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(today, key = { "i${it.type}${it.id}${it.time}" }) { e -> FeedRow(prefs, e) { onOpen(e) } }
            }
            if (days < 8) item(key = "more") {
                TextButton(onClick = { days++ }, modifier = Modifier.padding(top = 8.dp)) { Text("Show the day before") }
            }
        }
    }
}

/** A feed entry in words: what happened, and a detail line ("" if none). Shared by the feed and its tile. */
fun feedText(prefs: Prefs, e: AppEvent): Pair<String, String> {
    val names = prefs.fenceNames
    return when (e.type) {
        "arrived" -> "${e.name} got to ${e.place.ifEmpty { names[e.placeId] ?: "a place" }}" to ""
        "left" -> "${e.name} left ${e.place.ifEmpty { names[e.placeId] ?: "a place" }}" to ""
        "checkin" -> "${e.name} checked in" to e.note
        "sos" -> "${e.name} sent an SOS" to ""
        "sos_end" -> "${e.name} is OK" to ""
        "drive_start" -> "${e.name} started driving" to ""
        "trip" -> {
            val to = names[e.placeId]
            "${e.name} finished a trip" + (to?.let { " to $it" } ?: "") to "${Units.duration(e.duration)} · ${Units.distance(e.distance)}"
        }
        "chore_done" -> "${e.name} did “${e.title}”" to (if (e.level > 0) "★ ${e.level}" else "")
        "reward_spent" -> "${e.name} got “${e.title}”" to "★ ${e.level} spent"
        // e.note: the other child's name.
        "barter" -> when (e.kind) {
            "offered" -> "${e.name} offered “${e.title}” to ${e.note}" to "★ ${e.level}"
            "accepted" -> "${e.note} is doing “${e.title}” for ${e.name}" to "★ ${e.level}"
            "done" -> "${e.note} did “${e.title}” for ${e.name}" to "★ ${e.level} paid"
            else -> "A trade between ${e.name} and ${e.note} was undone" to ""
        }
        else -> "${e.name}: ${e.type}" to ""
    }
}

/** One moment on the timeline: the time, a dot in the person's colour, and what happened. */
@Composable
private fun FeedRow(prefs: Prefs, e: AppEvent, onClick: () -> Unit) {
    val (text, sub) = feedText(prefs, e)
    val color = when (e.type) {
        "sos" -> SOS_RED
        "chore_done", "reward_spent" -> androidx.compose.ui.graphics.Color(0xFFE0A82E)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.time)),
            Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        ElevatedCard(onClick = onClick, modifier = Modifier.padding(start = 10.dp).weight(1f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
