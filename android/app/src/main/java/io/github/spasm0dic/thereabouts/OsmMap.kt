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
    /** Show a whole route (a trip). */
    data class FitPoints(val points: List<TrailPoint>, override val seq: Int) : CameraCommand()
}

/**
 * Tiles from a URL template like https://tile.openstreetmap.org/{z}/{x}/{y}.png.
 * {s} spreads requests over servers a/b/c; {r} asks for sharp "@2x" tiles (512 px).
 */
private class TemplateTileSource(private val template: String, attribution: String, maxZoom: Int) :
    OnlineTileSourceBase(
        "t" + template.hashCode().toUInt(), 0, maxZoom, if ("{r}" in template) 512 else 256, ".png", arrayOf(template), attribution,
    ) {
    override fun getTileURLString(index: Long): String {
        val x = MapTileIndex.getX(index)
        val y = MapTileIndex.getY(index)
        return template
            .replace("{z}", MapTileIndex.getZoom(index).toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            .replace("{s}", "abc"[(x + y) % 3].toString())
            .replace("{r}", "@2x")
    }
}

private class MapState {
    var tileUrl: String? = null
    var dark: Boolean? = null
    var dim: Boolean? = null
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
    tiles: MapStyles.Tiles,
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
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
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
        val tileUrl = tiles.url
        if (state.tileUrl != tileUrl) {
            val source = TemplateTileSource(tileUrl, tiles.attribution, tiles.maxZoom)
            map.maxZoomLevel = tiles.maxZoom.toDouble()
            map.setTileSource(source)
            // Sharp 512 px tiles are drawn at the size they were designed for (256 dp),
            // instead of blowing 256 px tiles up to the screen's density (blurry).
            val density = context.resources.displayMetrics.density
            if (source.tileSizePixels == 512) {
                map.isTilesScaledToDpi = false
                map.tilesScaleFactor = density / 2f
            } else {
                map.isTilesScaledToDpi = true
            }
            state.tileUrl = tileUrl
            // Drop tiles of the previous style from memory so the new one shows at once.
            map.tileProvider.clearTileCache()
        }
        if (state.dim != tiles.dim) {
            // A light map in dark mode: darker and a little less saturated, so it doesn't glare.
            map.overlayManager.tilesOverlay.setColorFilter(if (tiles.dim) DIM_FILTER else null)
            state.dim = tiles.dim
        }
        if (state.dark != dark) {
            // While tiles load: a plain background in the theme, not a grey grid.
            val bg = if (dark) Color.rgb(38, 39, 53) else Color.rgb(241, 236, 250)
            map.overlayManager.tilesOverlay.loadingBackgroundColor = bg
            map.overlayManager.tilesOverlay.loadingLineColor = bg
            map.setBackgroundColor(bg)
            state.dark = dark
            state.drawnPlaces = null // redraw fences in the other style
        }

        if (state.drawnPlaces != places) {
            drawPlaces(map, state, places, dark)
            state.drawnPlaces = places
        }
        if (state.drawnTrail != trail to trailColor) {
            state.trailLine?.let { map.overlays.remove(it) }
            state.trailLine = null
            if (trail.size >= 2) {
                val line = Polyline(map).apply {
                    setPoints(trail.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = parseColor(trailColor)
                    outlinePaint.strokeWidth = 4 * context.resources.displayMetrics.density
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
            marker.icon = BitmapDrawable(context.resources, avatarBitmap(context, m.name, m.color, stale, sos = m.sosSince > 0, emoji = m.emoji))
            marker.title = m.name
            marker.snippet = statusOf(m)
        }

        if (camera != null && camera.seq != state.appliedSeq) {
            state.appliedSeq = camera.seq
            map.post {
                when (camera) {
                    is CameraCommand.Center -> {
                        // One smooth glide (pan and zoom together).
                        map.controller.animateTo(GeoPoint(camera.lat, camera.lon), maxOf(map.zoomLevelDouble, 16.0), 700L)
                    }
                    is CameraCommand.FitPoints -> {
                        val pts = camera.points.map { GeoPoint(it.lat, it.lon) }
                        if (pts.size >= 2) {
                            val box = boxAround(pts, 1.25f)
                            // Room around the route for the pins and the trip summary on top.
                            map.zoomToBoundingBox(box, true, (56 * context.resources.displayMetrics.density).toInt(), 17.0, 700L)
                        }
                    }
                    is CameraCommand.FitAll -> {
                        val pts = located.map { GeoPoint(it.loc!!.lat, it.loc.lon) }
                        when {
                            pts.size == 1 -> map.controller.animateTo(pts[0], 15.0, 700L)
                            pts.size > 1 -> {
                                map.zoomToBoundingBox(boxAround(pts, 1.3f), true, 80, 16.0, null)
                            }
                        }
                    }
                }
            }
        }
        map.invalidate()
    }
}

private val DIM_FILTER = android.graphics.ColorMatrixColorFilter(
    android.graphics.ColorMatrix().apply {
        // Just takes the edge off the white: streets and labels stay easy to read.
        setSaturation(0.9f)
        postConcat(android.graphics.ColorMatrix(floatArrayOf(
            0.84f, 0f, 0f, 0f, 0f,
            0f, 0.84f, 0f, 0f, 0f,
            0f, 0f, 0.86f, 0f, 4f,
            0f, 0f, 0f, 1f, 0f,
        )))
    },
)

/**
 * A box around some points with a little margin. Never zero-sized: a route
 * straight north (or two points on the same spot) would otherwise give osmdroid
 * an impossible zoom.
 */
private fun boxAround(pts: List<GeoPoint>, scale: Float): BoundingBox {
    // Worked out here rather than with osmdroid's helpers, which turn a zero-width
    // box into one that wraps the other way round the world.
    val minSpan = 0.004 // degrees, roughly 400 m
    val north = pts.maxOf { it.latitude }
    val south = pts.minOf { it.latitude }
    val east = pts.maxOf { it.longitude }
    val west = pts.minOf { it.longitude }
    val latHalf = maxOf((north - south) * scale, minSpan) / 2
    val lonHalf = maxOf((east - west) * scale, minSpan) / 2
    val lat = (north + south) / 2
    val lon = (east + west) / 2
    return BoundingBox(
        (lat + latHalf).coerceAtMost(85.0), (lon + lonHalf).coerceAtMost(180.0),
        (lat - latHalf).coerceAtLeast(-85.0), (lon - lonHalf).coerceAtLeast(-180.0),
    )
}

private const val LABEL_MIN_ZOOM = 14.0

/**
 * A fence's marker: its name when zoomed in enough to read it, a small dot
 * when zoomed out (so names don't pile up). Chosen each time the map draws,
 * so it switches the moment you cross the zoom level.
 */
private class FenceMarker(map: MapView, private val label: android.graphics.drawable.Drawable, private val dot: android.graphics.drawable.Drawable) :
    Marker(map) {
    init {
        icon = label
    }

    override fun draw(canvas: Canvas, pj: org.osmdroid.views.Projection) {
        val want = if (pj.zoomLevel >= LABEL_MIN_ZOOM) label else dot
        if (icon !== want) icon = want
        super.draw(canvas, pj)
    }
}

/** The small, quiet dot that marks a fence when zoomed out. */
private fun fenceDotBitmap(context: Context, accent: Int, dark: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (16 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.rgb(38, 39, 53) else Color.WHITE
        setShadowLayer(1.5f * d, 0f, 0.5f * d, Color.argb(if (dark) 120 else 70, 0, 0, 0))
    }
    c.drawCircle(r, r, r - 2.5f * d, ring)
    c.drawCircle(r, r, r - 4.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    return bmp
}

/** A fence's name as a small rounded pill in the theme's colours, with a soft shadow. */
private fun fenceLabelBitmap(context: Context, text: String, accent: Int, dark: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = accent
    }
    val label = if (text.length > 24) text.take(23) + "…" else text
    val padX = 10 * d
    val padY = 5 * d
    val shadow = 3 * d
    val w = paint.measureText(label) + 2 * padX
    val h = paint.fontMetrics.let { it.descent - it.ascent } + 2 * padY
    val bmp = Bitmap.createBitmap((w + 2 * shadow).toInt(), (h + 2 * shadow).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = android.graphics.RectF(shadow, shadow, shadow + w, shadow + h)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.rgb(38, 39, 53) else Color.WHITE
        setShadowLayer(2.5f * d, 0f, d, Color.argb(if (dark) 120 else 60, 0, 0, 0))
    }
    c.drawRoundRect(r, h / 2, h / 2, bg)
    // A thin ring in the fence colour ties it to its circle.
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d
        color = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent))
    }
    c.drawRoundRect(r, h / 2, h / 2, ring)
    c.drawText(label, r.left + padX, r.top + padY - paint.fontMetrics.ascent, paint)
    return bmp
}

fun parseColor(hex: String): Int = runCatching { Color.parseColor(hex) }.getOrDefault(Color.parseColor("#4363d8"))

fun initialOf(name: String) = name.trim().take(1).uppercase().ifEmpty { "?" }

/** A coloured circle with the person's emoji or initial, used as their map pin. */
fun avatarBitmap(context: Context, name: String, color: String, faded: Boolean, sos: Boolean = false, emoji: String = ""): Bitmap {
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
        p.color = Color.rgb(199, 53, 75)
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
    p.textAlign = Paint.Align.CENTER
    if (emoji.isNotEmpty()) {
        p.textSize = 19 * d
        c.drawText(emoji, r, r - (p.descent() + p.ascent()) / 2, p)
    } else {
        p.textSize = 17 * d
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(initialOf(name), r, r - (p.descent() + p.ascent()) / 2, p)
    }
    return bmp
}


/** Places as shaded circles with their name, drawn under the people. */
private fun drawPlaces(map: MapView, state: MapState, places: List<Place>, dark: Boolean) {
    state.places.values.forEach { (poly, label) ->
        map.overlays.remove(poly)
        map.overlays.remove(label)
    }
    state.places.clear()
    // The theme's purple: deeper on light maps, lilac on dark ones.
    val accent = if (dark) Color.rgb(189, 147, 249) else Color.rgb(110, 72, 214)
    val d = map.context.resources.displayMetrics.density
    places.forEachIndexed { i, p ->
        val center = GeoPoint(p.lat, p.lon)
        val poly = Polygon(map).apply {
            points = Polygon.pointsAsCircle(center, p.radius)
            fillPaint.color = Color.argb(if (dark) 40 else 30, Color.red(accent), Color.green(accent), Color.blue(accent))
            outlinePaint.color = accent
            outlinePaint.alpha = 200
            outlinePaint.strokeWidth = 1.5f * d
            outlinePaint.isAntiAlias = true
            title = p.name
            setOnClickListener { _, _, _ -> state.onPlaceClick(p); true }
        }
        val label = FenceMarker(
            map,
            BitmapDrawable(map.context.resources, fenceLabelBitmap(map.context, p.name, accent, dark)),
            BitmapDrawable(map.context.resources, fenceDotBitmap(map.context, accent, dark)),
        ).apply {
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null) // no pop-up; tapping opens the fence instead
            setOnMarkerClickListener { _, _ -> state.onPlaceClick(p); true }
        }
        // Just above the touch overlay (index 0), below everything else.
        map.overlays.add(1 + 2 * i, poly)
        map.overlays.add(2 + 2 * i, label)
        state.places[p.id] = poly to label
    }
}
