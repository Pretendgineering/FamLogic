package io.github.pretendgineering.famlogic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.IOException

/** A fence being placed or changed on the map. [id] is null for a new one. */
data class FenceDraft(
    val id: String?,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radius: Double,
    val circleId: String,
    val schedule: FenceSchedule? = null,
) {
    fun asPlace() = Place(id ?: "", circleId, name, lat, lon, radius, schedule)

    companion object {
        fun of(p: Place) = FenceDraft(p.id, p.name, p.lat, p.lon, p.radius, p.circleId, p.schedule)
        const val MIN_RADIUS = 30.0
        const val MAX_RADIUS = 5_000.0
    }
}

/**
 * The fence being edited, drawn on the map with two handles: drag anywhere inside
 * to move it, drag the round handle on its edge to make it bigger or smaller.
 * Touches elsewhere still pan and zoom the map.
 */
internal class FenceEditOverlay(private val d: Float) : Overlay() {
    var draft: FenceDraft? = null
    var accent = Color.rgb(110, 72, 214)
    /** Called while dragging (live) and when let go. */
    var onChange: (FenceDraft, done: Boolean) -> Unit = { _, _ -> }
    var dragging = false
        private set

    private var mode = 0 // 0 none, 1 moving, 2 resizing
    private var grabLat = 0.0
    private var grabLon = 0.0
    private val p = Point()
    private val e = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun edge(f: FenceDraft) = GeoPoint(f.lat, f.lon).destinationPoint(f.radius, 90.0)

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        val f = draft ?: return
        if (shadow) return
        map.projection.toPixels(GeoPoint(f.lat, f.lon), p)
        map.projection.toPixels(edge(f), e)
        val r = kotlin.math.hypot((e.x - p.x).toFloat(), (e.y - p.y).toFloat())
        fill.color = Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent))
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, fill)
        stroke.color = accent
        stroke.strokeWidth = 3 * d
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, stroke)
        // The middle: a small cross, to show it moves.
        stroke.pathEffect = null
        stroke.strokeWidth = 2.5f * d
        c.drawLine(p.x - 9 * d, p.y.toFloat(), p.x + 9 * d, p.y.toFloat(), stroke)
        c.drawLine(p.x.toFloat(), p.y - 9 * d, p.x.toFloat(), p.y + 9 * d, stroke)
        // The edge handle: a white dot with the accent ring, easy to grab.
        handle.color = Color.WHITE
        handle.setShadowLayer(2 * d, 0f, d, Color.argb(110, 0, 0, 0))
        c.drawCircle(e.x.toFloat(), e.y.toFloat(), 13 * d, handle)
        handle.clearShadowLayer()
        handle.color = accent
        c.drawCircle(e.x.toFloat(), e.y.toFloat(), 7 * d, handle)
    }

    override fun onTouchEvent(ev: MotionEvent, map: MapView): Boolean {
        val f = draft ?: return false
        if (ev.pointerCount > 1) { mode = 0; dragging = false; return false } // pinch-zoom stays the map's
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                map.projection.toPixels(GeoPoint(f.lat, f.lon), p)
                map.projection.toPixels(edge(f), e)
                val r = kotlin.math.hypot((e.x - p.x).toFloat(), (e.y - p.y).toFloat())
                val toEdge = kotlin.math.hypot(ev.x - e.x, ev.y - e.y)
                val toMid = kotlin.math.hypot(ev.x - p.x, ev.y - p.y)
                mode = when {
                    toEdge < 30 * d -> 2
                    toMid < maxOf(r - 12 * d, 24 * d) -> 1
                    else -> 0
                }
                if (mode == 1) {
                    val g = map.projection.fromPixels(ev.x.toInt(), ev.y.toInt())
                    grabLat = f.lat - g.latitude
                    grabLon = f.lon - g.longitude
                }
                dragging = mode != 0
                return dragging
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return false
                val g = map.projection.fromPixels(ev.x.toInt(), ev.y.toInt())
                val next = if (mode == 1) {
                    f.copy(lat = g.latitude + grabLat, lon = g.longitude + grabLon)
                } else {
                    val dist = GeoPoint(f.lat, f.lon).distanceToAsDouble(GeoPoint(g.latitude, g.longitude))
                    f.copy(radius = roundRadius(dist))
                }
                draft = next
                onChange(next, false)
                map.invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 0) return false
                mode = 0
                dragging = false
                onChange(f, true)
                return true
            }
        }
        return mode != 0
    }

    /** Sizes snap to tidy steps: 5 m up to 200 m, 25 m up to 1 km, then 100 m. */
    private fun roundRadius(m: Double): Double {
        val step = when {
            m < 200 -> 5.0
            m < 1000 -> 25.0
            else -> 100.0
        }
        return (Math.round(m / step) * step).coerceIn(FenceDraft.MIN_RADIUS, FenceDraft.MAX_RADIUS)
    }
}

/**
 * The bar at the bottom while a fence is on the map: its name, size (drag on the
 * map), group, and Save. "More…" opens the full options (alert hours, address
 * search, delete).
 */
@Composable
fun FenceEditBar(
    prefs: Prefs,
    draft: FenceDraft,
    circles: List<CircleInfo>,
    canSchedule: Boolean,
    onChange: (FenceDraft) -> Unit,
    onMore: () -> Unit,
    onDone: (changed: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var busy by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding()) {
            Text(
                if (draft.id == null) "New fence" else "Edit ${draft.name}",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it.take(40))) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Home, School") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Text(
                "Size: ${Units.distance(draft.radius)} from the middle to the edge. Drag the dot on the edge to resize it, or drag inside to move it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (circles.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    circles.forEach { c ->
                        FilterChip(
                            selected = draft.circleId == c.id,
                            onClick = { if (draft.id == null) onChange(draft.copy(circleId = c.id)) },
                            enabled = draft.id == null || draft.circleId == c.id, // a fence stays in its group
                            label = { Text(c.name) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onDone(false) }, enabled = !busy) { Text("Cancel") }
                TextButton(onClick = onMore, enabled = !busy) { Text("More…") }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    enabled = !busy && draft.name.isNotBlank() && draft.circleId.isNotEmpty(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val api = Api(prefs.serverUrl, prefs.token)
                                if (draft.id == null) {
                                    api.createPlace(draft.circleId, draft.name.trim(), draft.lat, draft.lon, draft.radius, draft.schedule, canSchedule)
                                } else {
                                    api.updatePlace(draft.id, draft.name.trim(), draft.radius, draft.lat, draft.lon, draft.schedule, canSchedule)
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                onDone(true)
                            } catch (e: IOException) {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                Toast.makeText(context, e.message ?: "Couldn't save", Toast.LENGTH_LONG).show()
                                busy = false
                            }
                        }
                    },
                ) { Text("Save") }
            }
        }
    }
}
