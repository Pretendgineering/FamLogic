package io.github.pretendgineering.famlogic

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/*
 * Trading chores for points: a child offers one of their chores to a brother or
 * sister for some points; the other says yes or no; the points move when the chore's
 * done. Parents see it all in the feed (and undo a trade in the console).
 */

data class Barter(val id: String, val choreId: String, val choreTitle: String, val from: String, val to: String, val points: Int, val status: String)

suspend fun Api.barters(): List<Barter> {
    val a = call("GET", "/api/barters").optJSONArray("barters") ?: JSONArray()
    return (0 until a.length()).map { i ->
        a.getJSONObject(i).let { o -> Barter(o.optString("id"), o.optString("choreId"), o.optString("choreTitle"), o.optString("from"), o.optString("to"), o.optInt("points"), o.optString("status")) }
    }
}

suspend fun Api.offerBarter(choreId: String, to: String, points: Int) {
    call("POST", "/api/barters", JSONObject().put("choreId", choreId).put("to", to).put("points", points))
}

suspend fun Api.barterAction(id: String, action: String) { call("POST", "/api/barters/$id/$action") }

private val GOLD = Color(0xFFE0A82E)

/** A child's own points at the top of Upkeep: how many, this week, and the next reward. */
@Composable
fun KidPointsCard(points: Int, doneThisWeek: Int, next: Reward?, big: Boolean) {
    ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("★ $points", style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, color = GOLD, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(if (points == 1) "1 point" else "$points points", style = MaterialTheme.typography.titleMedium)
                Text("This week: $doneThisWeek ${if (doneThisWeek == 1) "job" else "jobs"} done", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (next != null) {
                    Text(if (points >= next.cost) "You can ask for ${next.title}!" else "${next.cost - points} more for ${next.title}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    androidx.compose.material3.LinearProgressIndicator(progress = { (points.toFloat() / next.cost).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = GOLD)
                }
            }
        }
    }
}

/** Offers to me (yes or no), and the ones I made that are still open (call it off). */
fun LazyListScope.barterCards(barters: List<Barter>, me: String, people: List<Member>, big: Boolean, act: (Barter, String) -> Unit) {
    val name = { id: String -> people.find { it.id == id }?.name ?: "Someone" }
    val toMe = barters.filter { it.to == me && it.status == "offered" }
    val mine = barters.filter { it.from == me && (it.status == "offered" || it.status == "accepted") }
    items(toMe, key = { "bo" + it.id }) { b ->
        val from = people.find { it.id == b.from }
        ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (from != null) Avatar(from.name, from.emoji, from.color, if (big) 48 else 32)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${name(b.from)} will give you ★ ${b.points}", style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
                    Text("to do “${b.choreTitle}”", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { act(b, "decline") }) { Text("No") }
                FilledTonalButton(onClick = { act(b, "accept") }) { Text("Yes") }
            }
        }
    }
    items(mine, key = { "bm" + it.id }) { b ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (b.status == "offered") "You offered “${b.choreTitle}” to ${name(b.to)} for ★ ${b.points}: waiting"
                else "${name(b.to)} is doing “${b.choreTitle}” for ★ ${b.points}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (b.status == "offered") TextButton(onClick = { act(b, "cancel") }) { Text("Call it off") }
        }
    }
}

/** Pick one of my jobs, who to ask, and how many points to give. */
@Composable
fun TradeDialog(myChores: List<Chore>, siblings: List<Member>, points: Int, onDismiss: () -> Unit, onOffer: (Chore, Member, Int) -> Unit) {
    var chore by remember { mutableStateOf(myChores.firstOrNull()) }
    var who by remember { mutableStateOf(siblings.firstOrNull()) }
    var give by remember { mutableIntStateOf(minOf(points, 2).coerceAtLeast(1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trade a job") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Which job?", style = MaterialTheme.typography.labelLarge)
                myChores.forEach { c -> FilterChip(selected = chore == c, onClick = { chore = c }, label = { Text(c.title) }) }
                Text("Who would do it?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    siblings.forEach { m -> FilterChip(selected = who == m, onClick = { who = m }, label = { Text(m.name) }, leadingIcon = { Avatar(m.name, m.emoji, m.color, 22) }) }
                }
                Text("Points you'll give them", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { give = (give - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("★ $give", style = MaterialTheme.typography.headlineSmall, color = GOLD, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { give = (give + 1).coerceAtMost(points) }) { Text("+") }
                }
                Text("You have ★ $points. They get them when the job's done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = chore != null && who != null && give in 1..points, onClick = { onOffer(chore!!, who!!, give) }) { Text("Offer it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
