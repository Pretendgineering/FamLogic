package io.github.spasm0dic.libre360

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
) {
    val context = LocalContext.current
    val state = remember { MapState() }
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            controller.setZoom(3.0)
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
            marker.icon = BitmapDrawable(context.resources, avatarBitmap(context, m.name, m.color, stale))
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
fun avatarBitmap(context: Context, name: String, color: String, faded: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (40 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    val alpha = if (faded) 140 else 255
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.argb(70, 0, 0, 0)
    c.drawCircle(r, r + d, r - d, p) // soft shadow
    p.color = Color.WHITE; p.alpha = alpha
    c.drawCircle(r, r, r - 2 * d, p)
    p.color = parseColor(color); p.alpha = alpha
    c.drawCircle(r, r, r - 5 * d, p)
    p.color = Color.WHITE; p.alpha = alpha
    p.textSize = 17 * d
    p.typeface = Typeface.DEFAULT_BOLD
    p.textAlign = Paint.Align.CENTER
    c.drawText(initialOf(name), r, r - (p.descent() + p.ascent()) / 2, p)
    return bmp
}
