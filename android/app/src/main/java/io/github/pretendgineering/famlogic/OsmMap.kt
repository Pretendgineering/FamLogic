package io.github.pretendgineering.famlogic

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

/**
 * Where the last map was looking, so a new map (the home tile, then the map
 * screen) opens there instead of on the whole world.
 */
private object MapMemory {
    var center: GeoPoint? = null
    var zoom = 3.0
}

private class MapState {
    /** False until the map has been placed once: that first move is a jump, not a flight. */
    var placed = false
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
    var onMemberClick: (String) -> Unit = {}
    var onGroupClick: (List<String>) -> Unit = {}
    var onMapTap: () -> Unit = {}
    /** The people last drawn, so pins can regroup when the zoom changes. */
    var located: List<Member> = emptyList()
    var groupZoom = -1.0
    val groups = mutableListOf<Marker>()
    var pulse: SosPulseOverlay? = null
    var inset = 0
    var fence: FenceEditOverlay? = null
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
    /** A person's pin was tapped (their id). */
    onMemberClick: (String) -> Unit = {},
    /** A pin standing for several people in one spot was tapped, and they can't be spread out further. */
    onGroupClick: (List<String>) -> Unit = {},
    /** The map itself was tapped (not a pin or fence). */
    onMapTap: () -> Unit = {},
    /** Pixels at the bottom covered by something (the people sheet): fitting keeps people above it. */
    bottomInset: Int = 0,
    /** A fence being placed or changed on the map (drawn with handles), and its changes. */
    fenceDraft: FenceDraft? = null,
    onFenceDraft: (FenceDraft, done: Boolean) -> Unit = { _, _ -> },
    /** Round the map's own corners (a home tile): the map view draws itself, so Compose's clip alone can miss it. */
    corner: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /** False when something sits under the map (the tile's bar): only the top corners are rounded. */
    roundBottom: Boolean = true,
) {
    val context = LocalContext.current
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { corner.toPx() }
    val state = remember { MapState() }
    val dark = LocalDarkTheme.current
    state.onLongPress = onLongPress
    state.onPlaceClick = onPlaceClick
    state.onMemberClick = onMemberClick
    state.onGroupClick = onGroupClick
    state.onMapTap = onMapTap
    state.inset = bottomInset
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            controller.setZoom(MapMemory.zoom)
            MapMemory.center?.let { controller.setCenter(it) }
            // First overlay = last to see touches, so markers and places get theirs first.
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        state.onMapTap()
                        return false
                    }
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let { state.onLongPress(it.latitude, it.longitude) }
                        return p != null
                    }
                }),
            )
            overlays.add(CopyrightOverlay(context))
            // Zooming changes who is "in the same spot": regroup the pins.
            addMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?) = false
                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                    if (kotlin.math.abs(zoomLevelDouble - state.groupZoom) > 0.25) drawPeople(this@apply, state)
                    return false
                }
            })
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
        if (cornerPx > 0f && !map.clipToOutline) {
            map.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height + if (roundBottom) 0 else cornerPx.toInt(), cornerPx)
            }
            map.clipToOutline = true
            // The tile grows and shrinks (arranging, tiles across): keep the rounded edge with it.
            map.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.invalidateOutline() }
        }
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

        // The fence being edited, on top of everything so its handles get the touch first.
        val fence = state.fence ?: FenceEditOverlay(context.resources.displayMetrics.density).also { state.fence = it }
        fence.accent = if (dark) Color.rgb(189, 147, 249) else Color.rgb(110, 72, 214)
        fence.onChange = onFenceDraft
        if (!fence.dragging) fence.draft = fenceDraft
        map.overlays.remove(fence)
        if (fenceDraft != null) map.overlays.add(fence)

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
        state.located = located
        drawPeople(map, state)

        if (camera != null && camera.seq != state.appliedSeq) {
            state.appliedSeq = camera.seq
            // The first placement jumps straight there: animating from a world view
            // flies across the globe faster than the tiles can load (the jitter).
            val jump = !state.placed && MapMemory.center == null
            val apply: () -> Unit = {
                if (jump) {
                    when (camera) {
                        is CameraCommand.Center -> { map.controller.setZoom(16.0); map.controller.setCenter(aboveInset(GeoPoint(camera.lat, camera.lon), 16.0, state.inset)) }
                        is CameraCommand.FitPoints -> camera.points.map { GeoPoint(it.lat, it.lon) }.takeIf { it.size >= 2 }?.let {
                            map.zoomToBoundingBox(clearOfInset(boxAround(it, 1.25f), map, state.inset), false, (56 * context.resources.displayMetrics.density).toInt(), 17.0, null)
                        }
                        is CameraCommand.FitAll -> {
                            val pts = located.map { GeoPoint(it.loc!!.lat, it.loc.lon) }
                            when {
                                pts.size == 1 -> { map.controller.setZoom(15.0); map.controller.setCenter(aboveInset(pts[0], 15.0, state.inset)) }
                                pts.size > 1 -> map.zoomToBoundingBox(clearOfInset(boxAround(pts, 1.3f), map, state.inset), false, 80, 16.0, null)
                            }
                        }
                    }
                } else when (camera) {
                    is CameraCommand.Center -> {
                        // One smooth glide (pan and zoom together).
                        maxOf(map.zoomLevelDouble, 16.0).let { z -> map.controller.animateTo(aboveInset(GeoPoint(camera.lat, camera.lon), z, state.inset), z, 700L) }
                    }
                    is CameraCommand.FitPoints -> {
                        val pts = camera.points.map { GeoPoint(it.lat, it.lon) }
                        if (pts.size >= 2) {
                            val box = boxAround(pts, 1.25f)
                            // Room around the route for the pins and the trip summary on top.
                            map.zoomToBoundingBox(clearOfInset(box, map, state.inset), true, (56 * context.resources.displayMetrics.density).toInt(), 17.0, 700L)
                        }
                    }
                    is CameraCommand.FitAll -> {
                        val pts = located.map { GeoPoint(it.loc!!.lat, it.loc.lon) }
                        when {
                            pts.size == 1 -> map.controller.animateTo(aboveInset(pts[0], 15.0, state.inset), 15.0, 700L)
                            pts.size > 1 -> {
                                map.zoomToBoundingBox(clearOfInset(boxAround(pts, 1.3f), map, state.inset), true, 80, 16.0, null)
                            }
                        }
                    }
                }
                if (located.isNotEmpty() || camera !is CameraCommand.FitAll) state.placed = true
                MapMemory.center = map.mapCenter as? GeoPoint ?: GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
                MapMemory.zoom = map.zoomLevelDouble
                drawPeople(map, state)
            }
            // Before its first layout the map has no size to fit anything into.
            if (map.width > 0 && map.height > 0) map.post(apply) else map.addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
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
fun avatarBitmap(
    context: Context,
    name: String,
    color: String,
    faded: Boolean,
    sos: Boolean = false,
    emoji: String = "",
    driving: Boolean = false,
): Bitmap {
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
    if (driving) {
        // A small car badge at the bottom right.
        val br = 9 * d
        val cx = size - br - 0.5f * d
        val cy = size - br - 0.5f * d
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            setShadowLayer(1.5f * d, 0f, 0.5f * d, Color.argb(90, 0, 0, 0))
        }
        c.drawCircle(cx, cy, br, badge)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; textAlign = Paint.Align.CENTER }
        c.drawText("🚗", cx, cy - (t.descent() + t.ascent()) / 2, t)
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


/**
 * People's pins. Anyone within a pin's width of someone else (at this zoom) shares
 * one pin with their faces stacked; a tap on it zooms in to spread them out. Each
 * pin has a small "how long ago" tag, an arrow for the way they're heading, and
 * a pulsing red halo during an SOS.
 */
private fun drawPeople(map: MapView, state: MapState) {
    val context = map.context
    val d = context.resources.displayMetrics.density
    val located = state.located
    state.groupZoom = map.zoomLevelDouble
    // Group by distance on screen at the current zoom.
    val proj = map.projection
    val pts = located.associate { it.id to proj.toPixels(GeoPoint(it.loc!!.lat, it.loc.lon), null) }
    val reach = 34 * d
    val groups = mutableListOf<MutableList<Member>>()
    for (m in located.sortedByDescending { it.sosSince > 0 }) {
        val p = pts.getValue(m.id)
        val g = groups.firstOrNull { grp ->
            val q = pts.getValue(grp[0].id)
            val dx = (p.x - q.x).toFloat()
            val dy = (p.y - q.y).toFloat()
            dx * dx + dy * dy < reach * reach
        }
        if (g != null) g += m else groups += mutableListOf(m)
    }
    val singles = groups.filter { it.size == 1 }.map { it[0] }
    val keep = singles.map { it.id }.toSet()
    state.markers.keys.filter { it !in keep }.forEach { id -> map.overlays.remove(state.markers.remove(id)) }
    state.groups.forEach { map.overlays.remove(it) }
    state.groups.clear()
    val now = System.currentTimeMillis()
    for (m in singles) {
        val loc = m.loc!!
        val marker = state.markers.getOrPut(m.id) {
            Marker(map).also { map.overlays.add(it) }
        }
        val stale = now - loc.time > STALE_MS
        val pin = pinBitmap(context, m.name, m.color, stale, sos = m.sosSince > 0, emoji = m.emoji,
            driving = isDriving(m), heading = if (now - loc.time < 10 * 60_000) loc.heading else 0, age = shortAgo(loc.time, now))
        marker.icon = BitmapDrawable(context.resources, pin.bitmap)
        marker.setAnchor(Marker.ANCHOR_CENTER, pin.anchorY)
        marker.position = GeoPoint(loc.lat, loc.lon)
        marker.setInfoWindow(null) // the app shows its own card for the person
        val id = m.id
        marker.setOnMarkerClickListener { _, _ -> state.onMemberClick(id); true }
    }
    for (g in groups.filter { it.size > 1 }) {
        val lat = g.map { it.loc!!.lat }.average()
        val lon = g.map { it.loc!!.lon }.average()
        val marker = Marker(map)
        val newest = g.maxOf { it.loc!!.time }
        val pin = groupBitmap(context, g, shortAgo(newest, now))
        marker.icon = BitmapDrawable(context.resources, pin.bitmap)
        marker.setAnchor(Marker.ANCHOR_CENTER, pin.anchorY)
        marker.position = GeoPoint(lat, lon)
        marker.setInfoWindow(null)
        val ids = g.map { it.id }
        marker.setOnMarkerClickListener { _, mv ->
            val box = BoundingBox.fromGeoPoints(g.map { GeoPoint(it.loc!!.lat, it.loc.lon) })
            val spreadable = box.latitudeSpan > 0.00005 || box.longitudeSpanWithDateLine > 0.00005
            if (spreadable && mv.zoomLevelDouble < mv.maxZoomLevel - 0.5) {
                mv.controller.animateTo(GeoPoint(lat, lon), (mv.zoomLevelDouble + 2.5).coerceAtMost(mv.maxZoomLevel), 500L)
            } else {
                state.onGroupClick(ids) // same spot: say who's there
            }
            true
        }
        map.overlays.add(marker)
        state.groups += marker
    }
    // SOS halos pulse under the pins.
    val sos = located.filter { it.sosSince > 0 }.map { GeoPoint(it.loc!!.lat, it.loc.lon) }
    val pulse = state.pulse ?: SosPulseOverlay(d).also { state.pulse = it }
    pulse.points = sos
    // Just under the first person's pin (above fences and trails).
    map.overlays.remove(pulse)
    val people = state.markers.values.toSet() + state.groups
    val at = map.overlays.indexOfFirst { it in people }
    if (at >= 0) map.overlays.add(at, pulse) else map.overlays.add(pulse)
    map.invalidate()
}

/** "now", "5m", "2h", "3d": short enough to sit under a pin. */
internal fun shortAgo(time: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((now - time) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        min < 48 * 60 -> "${min / 60}h"
        else -> "${min / (24 * 60)}d"
    }
}

/** A pin's picture and where its point is (the face's centre, as a fraction of the height). */
internal class Pin(val bitmap: Bitmap, val anchorY: Float)

/** One person's pin: their face, a direction arrow, an SOS ring, a driving badge and a time tag. */
internal fun pinBitmap(
    context: Context, name: String, color: String, faded: Boolean, sos: Boolean = false, emoji: String = "",
    driving: Boolean = false, heading: Int = 0, age: String = "",
): Pin {
    val d = context.resources.displayMetrics.density
    val margin = 9 * d // room for the arrow or the SOS ring
    val face = avatarBitmap(context, name, color, faded, sos = sos, emoji = emoji, driving = driving)
    val w = (40 * d + 2 * margin).toInt()
    val tagH = if (age.isNotEmpty()) (15 * d).toInt() else 0
    val bmp = Bitmap.createBitmap(w, w + tagH, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = w / 2f
    if (heading > 0) {
        // A small arrow just outside the circle, pointing the way they're going.
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = parseColor(color)
            setShadowLayer(1.5f * d, 0f, 0.5f * d, Color.argb(110, 0, 0, 0))
        }
        val r = 20 * d
        val path = android.graphics.Path().apply {
            moveTo(cx, cy - r - 8 * d)
            lineTo(cx - 6 * d, cy - r + 1 * d)
            lineTo(cx + 6 * d, cy - r + 1 * d)
            close()
        }
        c.save()
        c.rotate(heading.toFloat(), cx, cy)
        c.drawPath(path, p)
        c.restore()
    }
    c.drawBitmap(face, cx - face.width / 2f, cy - face.height / 2f, null)
    if (age.isNotEmpty()) drawTag(c, age, cx, w - 4 * d, d, faded)
    return Pin(bmp, cy / bmp.height)
}

/** Several people in one spot: up to three faces fanned out, and "+n" for more. */
internal fun groupBitmap(context: Context, people: List<Member>, age: String): Pin {
    val d = context.resources.displayMetrics.density
    val shown = people.sortedByDescending { it.sosSince > 0 }.take(3)
    val step = 18 * d
    val faceW = 40 * d
    val w = (faceW + step * (shown.size - 1) + (if (people.size > 3) 22 * d else 0f) + 8 * d).toInt()
    val faceH = (faceW + 14 * d).toInt()
    val tagH = (15 * d).toInt()
    val bmp = Bitmap.createBitmap(w, faceH + tagH, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val now = System.currentTimeMillis()
    shown.forEachIndexed { i, m ->
        val face = avatarBitmap(context, m.name, m.color, now - m.loc!!.time > STALE_MS, sos = m.sosSince > 0, emoji = m.emoji)
        c.drawBitmap(face, 4 * d + i * step - (face.width - faceW) / 2f, faceH / 2f - face.height / 2f, null)
    }
    if (people.size > 3) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.rgb(43, 36, 64); textSize = 13 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val bx = w - 13 * d
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; setShadowLayer(1.5f * d, 0f, 0.5f * d, Color.argb(90, 0, 0, 0)) }
        c.drawCircle(bx, faceH / 2f, 11 * d, bg)
        c.drawText("+${people.size - 3}", bx, faceH / 2f - (p.descent() + p.ascent()) / 2, p)
    }
    drawTag(c, age, w / 2f, faceH - 2 * d, d, false)
    return Pin(bmp, (faceH / 2f) / bmp.height)
}

/** The small rounded "5m" tag under a pin. */
private fun drawTag(c: Canvas, text: String, cx: Float, top: Float, d: Float, faded: Boolean) {
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (faded) Color.rgb(150, 110, 30) else Color.rgb(43, 36, 64)
        textSize = 10.5f * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val tw = t.measureText(text) + 10 * d
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(1.5f * d, 0f, 0.5f * d, Color.argb(90, 0, 0, 0)) }
    val rect = android.graphics.RectF(cx - tw / 2, top, cx + tw / 2, top + 14 * d)
    c.drawRoundRect(rect, 7 * d, 7 * d, bg)
    c.drawText(text, cx, rect.centerY() - (t.descent() + t.ascent()) / 2, t)
}

/** Pulsing red rings under anyone with an active SOS, so they can't be missed. */
internal class SosPulseOverlay(private val d: Float) : org.osmdroid.views.overlay.Overlay() {
    var points: List<GeoPoint> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val t = (android.os.SystemClock.uptimeMillis() % 1400L) / 1400f
        for (g in points) {
            map.projection.toPixels(g, p)
            // Two rings, half a beat apart, growing and fading.
            for (phase in floatArrayOf(t, (t + .5f) % 1f)) {
                paint.color = Color.argb((150 * (1 - phase)).toInt(), 224, 64, 80)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), 22 * d + phase * 34 * d, paint)
            }
        }
        map.postInvalidateDelayed(33)
    }
}


/** A point moved south so that, centred on it, the real point sits in the middle of the part of the map not covered. */
private fun aboveInset(p: GeoPoint, zoom: Double, inset: Int): GeoPoint {
    if (inset <= 0) return p
    val ts = MapView.getTileSystem()
    val y = ts.getY01FromLatitude(p.latitude, true) + (inset / 2.0) / org.osmdroid.util.TileSystem.MapSize(zoom)
    return GeoPoint(ts.getLatitudeFromY01(y.coerceIn(0.0, 1.0), true), p.longitude)
}

/** A box stretched south by the covered share of the map, so what it holds lands above the cover. */
private fun clearOfInset(box: BoundingBox, map: MapView, inset: Int): BoundingBox {
    val h = map.height
    if (inset <= 0 || h <= 0 || inset >= h * 0.8) return box
    val extra = box.latitudeSpan * inset / (h - inset).toDouble()
    return BoundingBox(box.latNorth, box.lonEast, (box.latSouth - extra).coerceAtLeast(-85.0), box.lonWest)
}
