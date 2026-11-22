package io.github.pretendgineering.famlogic.pebble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.pretendgineering.famlogic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Small street maps for the watch: the phone downloads the same map tiles the app
 * uses, crops them around a person, and converts them to the watch's 64 colours
 * (or black and white) as raw Pebble bitmap bytes.
 */
object PebbleMap {
    private const val TILE = 256
    private const val CACHE_DAYS = 7

    /**
     * Returns [h] rows of [stride] bytes: GBitmapFormat8Bit (one 0b11rrggbb byte per pixel)
     * when [color], else GBitmapFormat1Bit (1 = white, least significant bit first).
     */
    suspend fun render(
        context: Context, tileUrl: String, lat: Double, lon: Double, zoom: Int,
        w: Int, h: Int, stride: Int, color: Boolean,
    ): ByteArray = withContext(Dispatchers.IO) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(0xf2, 0xef, 0xe9))
        val n = 1 shl zoom
        // The person's position in "world pixels" at this zoom (Web Mercator).
        val cx = (lon + 180.0) / 360.0 * n * TILE
        val latR = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val cy = (1.0 - ln(tan(latR) + 1.0 / kotlin.math.cos(latR)) / PI) / 2.0 * n * TILE
        val left = cx - w / 2.0
        val top = cy - h / 2.0
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (tx in floor(left / TILE).toInt()..floor((left + w) / TILE).toInt()) {
            for (ty in floor(top / TILE).toInt()..floor((top + h) / TILE).toInt()) {
                if (ty < 0 || ty >= n) continue
                val tile = tile(context, tileUrl, zoom, Math.floorMod(tx, n), ty) ?: continue
                canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), paint)
                tile.recycle()
            }
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        if (color) toColor(px, w, h, stride) else toBlackAndWhite(px, w, h, stride)
    }

    private fun tile(context: Context, template: String, z: Int, x: Int, y: Int): Bitmap? {
        val dir = File(context.cacheDir, "pebble-tiles").apply { mkdirs() }
        val f = File(dir, "${template.hashCode()}_${z}_${x}_$y.png")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_DAYS * 86_400_000L) {
            BitmapFactory.decodeFile(f.path)?.let { return it }
        }
        val url = template.replace("{s}", "a").replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "FamLogic-Android/${BuildConfig.VERSION_NAME}")
            try {
                if (conn.responseCode != 200) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                f.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Map colours are pale; squeezed into 64 colours they'd all turn white. Stretch
     * lightness (so the background turns light grey and streets stay white) and
     * boost colour, keeping near-greys grey.
     */
    private fun adjust(c: Int): FloatArray {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(c, hsl)
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        hsl[2] = (1f - (1f - hsl[2]) * 3f).coerceIn(0f, 1f)
        hsl[1] = if (chroma < 0.12f) 0f else (hsl[1] * 1.6f).coerceAtMost(1f)
        val out = androidx.core.graphics.ColorUtils.HSLToColor(hsl)
        return floatArrayOf(Color.red(out).toFloat(), Color.green(out).toFloat(), Color.blue(out).toFloat())
    }

    /** 0..255 to the nearest of the watch's 4 levels per channel (0..3). */
    private fun level(v: Float) = ((v + 42.5f) / 85f).toInt().coerceIn(0, 3)

    private fun toColor(px: IntArray, w: Int, h: Int, stride: Int): ByteArray {
        val out = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = adjust(px[y * w + x])
            out[y * stride + x] = (0xC0 or (level(c[0]) shl 4) or (level(c[1]) shl 2) or level(c[2])).toByte()
        }
        return out
    }

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private fun toBlackAndWhite(px: IntArray, w: Int, h: Int, stride: Int): ByteArray {
        val out = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = adjust(px[y * w + x])
            val lum = (0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]) / 255f
            val threshold = (BAYER[(y % 4) * 4 + x % 4] + 0.5f) / 16f
            if (lum > threshold) {
                val i = y * stride + x / 8
                out[i] = (out[i].toInt() or (1 shl (x % 8))).toByte()
            }
        }
        return out
    }
}
