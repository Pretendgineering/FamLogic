package io.github.spasm0dic.thereabouts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/** Something the map should do once: zoom to everyone, or to one person. */
sealed class CameraCommand {
    abstract val seq: Int
    data class FitAll(override val seq: Int) : CameraCommand()
    data class Center(val lat: Double, val lon: Double, override val seq: Int) : CameraCommand()
}

/** Tiles from a URL template like https://tile.openstreetmap.org/{z}/{x}/{y}.png */
private class TemplateTileSource(private val template: String, attribution: String) :
    OnlineTileSourceBase("t" + template.hashCode().toUInt(), 0, 19, 256, ".png", arrayOf(template), attribution) {
    override fun getTileURLString(index: Long): String = template
        .replace("{z}", MapTileIndex.getZoom(index).toString())
        .replace("{x}", MapTileIndex.getX(index).toString())
        .replace("{y}", MapTileIndex.getY(index).toString())
        .replace("{s}", "a")
}

private class MapState {
    var tileUrl: String? = null
    var appliedSeq = -1
    val markers = mutableMapOf<String, Marker>()
    val places = mutableMapOf<String, Pair<Polygon, Marker>>()
    var drawnPlaces: List<Place>? = null
    var trailLine: Polyline? = null
    var drawnTrail: Any? = null
    // The latest callbacks, read by long-lived osmdroid listeners.
    var onLongPress: (Double, Double) -> Unit = { _, _ -> }
    var onPlaceClick: (Place) -> Unit = {}
}

const val STALE_MS = 30 * 60 * 1000L

@Composable
fun OsmMap(
    members: List<Member>,
    tileUrl: String,
    attribution: String,
    camera: CameraCommand?,
    statusOf: (Member) -> String,
    modifier: Modifier = Modifier,
    places: List<Place> = emptyList(),
    /** A trail to draw (someone's recent path), and its colour. */
    trail: List<TrailPoint> = emptyList(),
    trailColor: String = "#4363d8",
    onLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    onPlaceClick: (Place) -> Unit = {},
) {
    val context = LocalContext.current
    val state = remember { MapState() }
    state.onLongPress = onLongPress
    state.onPlaceClick = onPlaceClick
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            controller.setZoom(3.0)
            // First overlay = last to see touches, so markers and places get theirs first.
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let { state.onLongPress(it.latitude, it.longitude) }
                        return p != null
                    }
                }),
            )
            overlays.add(CopyrightOverlay(context))
        }
    }

    // osmdroid needs to know when the screen is paused/resumed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDetach()
        }
    }

    // clipToBounds: osmdroid paints tiles past its edges, which would cover the banners above it.
    AndroidView(factory = { mapView }, modifier = modifier.clipToBounds()) { map ->
        if (state.tileUrl != tileUrl) {
            map.setTileSource(TemplateTileSource(tileUrl, attribution))
            state.tileUrl = tileUrl
        }

        if (state.drawnPlaces != places) {
            drawPlaces(map, state, places)
            state.drawnPlaces = places
        }
        if (state.drawnTrail != trail to trailColor) {
            state.trailLine?.let { map.overlays.remove(it) }
            state.trailLine = null
            if (trail.size >= 2) {
                val line = Polyline(map).apply {
                    setPoints(trail.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = parseColor(trailColor)
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.alpha = 200
                }
                // Above places, below people.
                map.overlays.add(1 + 2 * state.places.size, line)
                state.trailLine = line
            }
            state.drawnTrail = trail to trailColor
        }

        val located = members.filter { it.loc != null && !it.paused }
        val keep = located.map { it.id }.toSet()
        state.markers.keys.filter { it !in keep }.forEach { id ->
            map.overlays.remove(state.markers.remove(id))
        }
        for (m in located) {
            val loc = m.loc!!
            val marker = state.markers.getOrPut(m.id) {
                Marker(map).also {
                    it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    map.overlays.add(it)
                }
            }
            val stale = System.currentTimeMillis() - loc.time > STALE_MS
            marker.position = GeoPoint(loc.lat, loc.lon)
            marker.icon = BitmapDrawable(context.resources, avatarBitmap(context, m.name, m.color, stale, sos = m.sosSince > 0))
            marker.title = m.name
            marker.snippet = statusOf(m)
        }

        if (camera != null && camera.seq != state.appliedSeq) {
            state.appliedSeq = camera.seq
            map.post {
                when (camera) {
                    is CameraCommand.Center -> {
                        map.controller.setZoom(maxOf(map.zoomLevelDouble, 16.0))
                        map.controller.animateTo(GeoPoint(camera.lat, camera.lon))
                    }
                    is CameraCommand.FitAll -> {
                        val pts = located.map { GeoPoint(it.loc!!.lat, it.loc.lon) }
                        when {
                            pts.size == 1 -> {
                                map.controller.setZoom(15.0)
                                map.controller.setCenter(pts[0])
                            }
                            pts.size > 1 -> {
                                val box = BoundingBox.fromGeoPointsSafe(pts)
                                map.zoomToBoundingBox(box.increaseByScale(1.3f), true, 80, 16.0, null)
                            }
                        }
                    }
                }
            }
        }
        map.invalidate()
    }
}

fun parseColor(hex: String): Int = runCatching { Color.parseColor(hex) }.getOrDefault(Color.parseColor("#4363d8"))

fun initialOf(name: String) = name.trim().take(1).uppercase().ifEmpty { "?" }

/** A coloured circle with the person's initial, used as their map pin. */
fun avatarBitmap(context: Context, name: String, color: String, faded: Boolean, sos: Boolean = false): Bitmap {
    val d = context.resources.displayMetrics.density
    // An SOS pin is bigger, with a thick red ring, so it stands out on the map.
    val ring = if (sos) 7 * d else 0f
    val size = (40 * d + 2 * ring).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    val alpha = if (faded && !sos) 140 else 255
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    if (sos) {
        p.color = Color.rgb(211, 47, 47)
        c.drawCircle(r, r, r, p)
    }
    val inner = r - ring
    p.color = Color.argb(70, 0, 0, 0)
    c.drawCircle(r, r + d, inner - d, p) // soft shadow
    p.color = Color.WHITE; p.alpha = alpha
    c.drawCircle(r, r, inner - 2 * d, p)
    p.color = parseColor(color); p.alpha = alpha
    c.drawCircle(r, r, inner - 5 * d, p)
    p.color = Color.WHITE; p.alpha = alpha
    p.textSize = 17 * d
    p.typeface = Typeface.DEFAULT_BOLD
    p.textAlign = Paint.Align.CENTER
    c.drawText(initialOf(name), r, r - (p.descent() + p.ascent()) / 2, p)
    return bmp
}


/** Places as shaded circles with their name, drawn under the people. */
private fun drawPlaces(map: MapView, state: MapState, places: List<Place>) {
    state.places.values.forEach { (poly, label) ->
        map.overlays.remove(poly)
        map.overlays.remove(label)
    }
    state.places.clear()
    val blue = Color.rgb(47, 111, 237)
    places.forEachIndexed { i, p ->
        val center = GeoPoint(p.lat, p.lon)
        val poly = Polygon(map).apply {
            points = Polygon.pointsAsCircle(center, p.radius)
            fillPaint.color = Color.argb(28, 47, 111, 237)
            outlinePaint.color = blue
            outlinePaint.strokeWidth = 4f
            title = p.name
            setOnClickListener { _, _, _ -> state.onPlaceClick(p); true }
        }
        val label = Marker(map).apply {
            position = center
            setTextIcon(p.name)
            textLabelForegroundColor = blue
            textLabelBackgroundColor = Color.argb(200, 255, 255, 255)
            textLabelFontSize = 34
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> state.onPlaceClick(p); true }
        }
        // Just above the touch overlay (index 0), below everything else.
        map.overlays.add(1 + 2 * i, poly)
        map.overlays.add(2 + 2 * i, label)
        state.places[p.id] = poly to label
    }
}
