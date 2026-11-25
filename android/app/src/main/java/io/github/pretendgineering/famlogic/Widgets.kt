package io.github.pretendgineering.famlogic

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Home-screen widgets. "Family": who's where, at a glance. "Check in": a one-tap
 * check-in, and a button that opens the app's SOS. They refresh when the app does,
 * and every half hour.
 */
class FamilyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Prefs(context)
        val people = if (prefs.joined) runCatching { Api(prefs.serverUrl, prefs.token).members(Long.MAX_VALUE / 2).members }.getOrNull() else null
        provideContent { GlanceTheme { FamilyContent(context, prefs, people) } }
    }
}

@Composable
private fun FamilyContent(context: Context, prefs: Prefs, people: List<Member>?) {
    val open = actionStartActivity(Intent(context, MainActivity::class.java))
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp).clickable(open),
    ) {
        Text(prefs.familyName.ifEmpty { "Family" }, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface))
        Spacer(GlanceModifier.height(6.dp))
        if (people == null) {
            Text("Open FamLogic to see your family", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            return@Column
        }
        people.filter { it.id != prefs.memberId && it.role != "dependent" }.take(6).forEach { m ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, GlanceModifier.width(90.dp), style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = GlanceTheme.colors.onSurface), maxLines = 1)
                Text(widgetStatus(m), style = TextStyle(fontSize = 13.sp, color = if (m.sosSince > 0) ColorProvider(Color(0xFFC7354B)) else GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            }
        }
    }
}

private fun widgetStatus(m: Member): String {
    val loc = m.loc
    return when {
        m.sosSince > 0 -> "SOS"
        m.paused -> "Paused"
        loc == null -> "Not sharing"
        m.place.isNotEmpty() -> "At ${m.place}"
        isDriving(m) -> "Driving"
        else -> {
            val min = (System.currentTimeMillis() - loc.time) / 60_000
            if (min < 10) "Out and about" else if (min < 60) "${min} min ago" else "${min / 60} h ago"
        }
    }
}

class FamilyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FamilyWidget()
}

class QuickWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { QuickContent(context) } }
    }
}

@Composable
private fun QuickContent(context: Context) {
    Row(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            GlanceModifier.defaultWeight().fillMaxSize().background(GlanceTheme.colors.primaryContainer).cornerRadius(16.dp).clickable(actionRunCallback<CheckInAction>()),
            verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
        ) { Text("Check in", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onPrimaryContainer)) }
        Spacer(GlanceModifier.width(8.dp))
        // SOS opens the app's hold-to-send bar (a widget can't safely send one on a single tap).
        Column(
            GlanceModifier.defaultWeight().fillMaxSize().background(ColorProvider(Color(0xFFC7354B))).cornerRadius(16.dp)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
        ) { Text("SOS", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ColorProvider(Color.White))) }
    }
}

class CheckInAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = Prefs(context)
        if (!prefs.joined) return
        val ok = runCatching { Api(prefs.serverUrl, prefs.token).checkIn("Checked in from my home screen", currentLocation(context)) }.isSuccess
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, if (ok) "Checked in" else "Couldn't check in", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

class QuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickWidget()
}

object Widgets {
    /** Refreshes the widgets (the app calls this when it has news). */
    suspend fun refresh(context: Context) {
        runCatching { FamilyWidget().updateAll(context) }
    }
}
