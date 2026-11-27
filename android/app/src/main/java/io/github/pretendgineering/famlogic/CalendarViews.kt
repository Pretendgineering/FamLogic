package io.github.pretendgineering.famlogic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Calendar views shared by the phone app and the TV: 3 days, the work week, the
 * week (days side by side, each day's events as coloured chips) and the month (a
 * grid with a coloured mark per event). Each event's colour is its outside
 * calendar's, or its person's; otherwise the accent.
 */
enum class CalMode(val label: String) { List("List"), Three("3 days"), WorkWeek("Work week"), Week("Week"), Month("Month") }

/** The colours a calendar view draws with (each app passes its own). */
data class CalColors(val card: Color, val ink: Color, val soft: Color, val accent: Color, val onAccent: Color = Color.White)

object CalDays {
    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    fun day(t: Long): Long = cal(t).timeInMillis
    fun addDays(t: Long, n: Int): Long = cal(t).apply { add(Calendar.DAY_OF_YEAR, n) }.timeInMillis
    fun addMonths(t: Long, n: Int): Long = cal(t).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, n) }.timeInMillis
    private fun weekStart(t: Long): Long {
        val c = cal(t)
        while (c.get(Calendar.DAY_OF_WEEK) != c.firstDayOfWeek) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }
    private fun monday(t: Long): Long {
        val c = cal(t)
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }

    /** The days a view shows, from [anchor] (any time in the period). */
    fun days(mode: CalMode, anchor: Long): List<Long> = when (mode) {
        CalMode.Three -> (0 until 3).map { addDays(day(anchor), it) }
        CalMode.WorkWeek -> (0 until 5).map { addDays(monday(anchor), it) }
        CalMode.Week -> (0 until 7).map { addDays(weekStart(anchor), it) }
        CalMode.Month -> {
            val first = cal(anchor).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            (0 until 42).map { addDays(weekStart(first), it) }
        }
        CalMode.List -> (0 until 31).map { addDays(day(anchor), it) }
    }

    /** Moves the period on (+1) or back (-1). */
    fun step(mode: CalMode, anchor: Long, by: Int): Long = when (mode) {
        CalMode.Three -> addDays(anchor, 3 * by)
        CalMode.WorkWeek, CalMode.Week -> addDays(anchor, 7 * by)
        CalMode.Month -> addMonths(anchor, by)
        CalMode.List -> addDays(anchor, 31 * by)
    }

    /** "November 2026", "30 Nov – 6 Dec". */
    fun title(mode: CalMode, anchor: Long): String {
        if (mode == CalMode.Month) return SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(Date(anchor))
        val d = days(mode, anchor)
        val f = SimpleDateFormat("d MMM", Locale.getDefault())
        return "${f.format(Date(d.first()))} – ${f.format(Date(d.last()))}"
    }

    /** The events on a day (an event across several days is on each). */
    fun on(events: List<CalOccurrence>, day: Long): List<CalOccurrence> {
        val end = addDays(day, 1)
        return events.filter { o -> o.start < end && maxOf(o.end, o.start + 1) > day }
    }

    fun isToday(day: Long) = day == day(System.currentTimeMillis())
}

fun calColor(o: CalOccurrence, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(o.color)) }.getOrDefault(fallback)

private fun timeOf(o: CalOccurrence) = if (o.allDay) "All day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(o.start))

/**
 * Days side by side (3 days, work week, week). [dayModifier] lets each app make a day
 * selectable its own way (a tap on the phone, the remote's focus on the TV).
 */
@Composable
fun CalDayColumns(
    mode: CalMode, anchor: Long, events: List<CalOccurrence>, colors: CalColors, modifier: Modifier = Modifier,
    dayModifier: @Composable (day: Long) -> Modifier = { Modifier },
) {
    val days = CalDays.days(mode, anchor)
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { d ->
            Column(Modifier.weight(1f).fillMaxHeight().then(dayModifier(d)).clip(RoundedCornerShape(16.dp)).background(colors.card).padding(8.dp)) {
                DayHeading(d, colors)
                Spacer(Modifier.height(6.dp))
                val list = CalDays.on(events, d)
                if (list.isEmpty()) Text("—", color = colors.soft, fontSize = 13.sp)
                list.forEach { o -> EventChip(o, colors) }
            }
        }
    }
}

@Composable
private fun DayHeading(d: Long, colors: CalColors) {
    val today = CalDays.isToday(d)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(SimpleDateFormat("EEE", Locale.getDefault()).format(Date(d)), color = colors.soft, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (today) colors.accent else Color.Transparent), contentAlignment = Alignment.Center) {
            Text(SimpleDateFormat("d", Locale.getDefault()).format(Date(d)), color = if (today) colors.onAccent else colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** An event as a small chip: a stripe in its colour, its time and title. */
@Composable
fun EventChip(o: CalOccurrence, colors: CalColors, modifier: Modifier = Modifier) {
    val c = calColor(o, colors.accent)
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = .16f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(36.dp).background(c))
        Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(timeOf(o), color = colors.soft, fontSize = 11.sp, maxLines = 1)
            Text(o.title, color = colors.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The month: weeks in rows, each day with a coloured mark per event (with titles
 * when the cells are big enough). [selected] is the chosen day, highlighted.
 */
@Composable
fun CalMonthGrid(
    anchor: Long, events: List<CalOccurrence>, colors: CalColors, selected: Long?, modifier: Modifier = Modifier,
    dayModifier: @Composable (day: Long) -> Modifier = { Modifier },
) {
    val days = CalDays.days(CalMode.Month, anchor)
    val month = Calendar.getInstance().apply { timeInMillis = anchor }.get(Calendar.MONTH)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            days.take(7).forEach { d ->
                Text(SimpleDateFormat("EEE", Locale.getDefault()).format(Date(d)), Modifier.weight(1f).padding(bottom = 4.dp), color = colors.soft, fontSize = 12.sp)
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { d ->
                    val inMonth = Calendar.getInstance().apply { timeInMillis = d }.get(Calendar.MONTH) == month
                    val list = CalDays.on(events, d)
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp).then(dayModifier(d)).clip(RoundedCornerShape(10.dp))
                            .background(if (d == selected) colors.accent.copy(alpha = .22f) else colors.card.copy(alpha = if (inMonth) 1f else .45f))
                            .padding(5.dp),
                    ) {
                        val cellHeight = maxHeight
                        val roomy = cellHeight >= 58.dp
                        Column {
                            val today = CalDays.isToday(d)
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (today) colors.accent else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(SimpleDateFormat("d", Locale.getDefault()).format(Date(d)), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    color = when { today -> colors.onAccent; inMonth -> colors.ink; else -> colors.soft })
                            }
                            if (roomy) {
                                // Big cells: a bar per event with its title (as many as fit, then "+2").
                                val fit = ((cellHeight - 26.dp) / 15.dp).toInt().coerceAtLeast(1)
                                val shown = if (list.size > fit) list.take(fit - 1) else list
                                shown.forEach { o ->
                                    val c = calColor(o, colors.accent)
                                    Row(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = .2f)).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(3.dp).height(13.dp).background(c))
                                        Text(o.title, Modifier.padding(start = 3.dp), color = colors.ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (list.size > shown.size) Text("+${list.size - shown.size}", color = colors.soft, fontSize = 10.sp)
                            } else if (list.isNotEmpty()) {
                                // Small cells: a dot per event, in its colour.
                                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    list.take(4).forEach { o -> Box(Modifier.size(6.dp).clip(CircleShape).background(calColor(o, colors.accent))) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A day's events, as a simple list (under the month, or the TV's list view). */
@Composable
fun CalDayList(day: Long, events: List<CalOccurrence>, colors: CalColors, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(day)), color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val list = CalDays.on(events, day)
        if (list.isEmpty()) Text("Nothing on.", color = colors.soft, fontSize = 14.sp)
        list.forEach { o -> EventChip(o, colors) }
    }
}
