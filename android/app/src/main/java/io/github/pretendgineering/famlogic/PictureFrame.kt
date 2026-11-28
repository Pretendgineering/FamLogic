package io.github.pretendgineering.famlogic

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * The picture frame tile: a gentle slideshow on the home screen. Each device picks its
 * own photos (a teenager's frame isn't a parent's): one or more photos from this
 * phone, an Immich shared-album link (no key, works for whoever owns the album), or
 * the family album on the server (the TV's screen saver photos).
 */

private const val FRAME_SECONDS = 20L

/** Where each photo comes from: a phone photo, a web address, or a server path. */
private sealed class FrameItem {
    data class Local(val uri: Uri) : FrameItem()
    data class Web(val url: String) : FrameItem()
    data class Server(val path: String) : FrameItem()
}

private suspend fun frameItems(context: Context, prefs: Prefs): List<FrameItem> {
    val src = prefs.frameSource
    return when {
        src.startsWith("photos:") -> src.removePrefix("photos:").split("|").filter { it.isNotBlank() }.map { FrameItem.Local(Uri.parse(it)) }
        src.startsWith("immich:") -> immichPhotos(src.removePrefix("immich:")).map { FrameItem.Web(it) }
        src == "family" -> runCatching {
            val photos = Api(prefs.serverUrl, prefs.token).call("GET", "/api/display/board").optJSONArray("photos")
            (0 until (photos?.length() ?: 0)).map { FrameItem.Server("/api/display/photos/" + photos!!.getJSONObject(it).optString("id")) }
        }.getOrDefault(emptyList())
        else -> emptyList()
    }
}

/** An Immich shared link (https://photos.example/share/KEY) to its photos' preview addresses. */
private suspend fun immichPhotos(link: String): List<String> = withContext(Dispatchers.IO) {
    runCatching {
        val u = URL(link.trim())
        val key = link.trim().trimEnd('/').substringAfterLast("/").substringBefore("?")
        val host = "${u.protocol}://${u.authority}"
        val conn = URL("$host/api/shared-links/me?key=$key").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        val o = JSONObject(conn.inputStream.bufferedReader().readText())
        val assets = o.optJSONObject("album")?.optJSONArray("assets")?.takeIf { it.length() > 0 } ?: o.optJSONArray("assets")
        (0 until (assets?.length() ?: 0)).mapNotNull { i ->
            assets!!.getJSONObject(i).takeIf { it.optString("type", "IMAGE") == "IMAGE" }?.let { "$host/api/assets/${it.optString("id")}/thumbnail?size=preview&key=$key" }
        }
    }.getOrDefault(emptyList())
}

private suspend fun loadFrame(context: Context, prefs: Prefs, item: FrameItem, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = when (item) {
            is FrameItem.Local -> context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
            is FrameItem.Web -> (URL(item.url).openConnection() as HttpURLConnection).run { connectTimeout = 15_000; readTimeout = 30_000; inputStream.use { it.readBytes() } }
            is FrameItem.Server -> Api(prefs.serverUrl, prefs.token).bytes(item.path)
        } ?: return@runCatching null
        // Decoded small enough for a tile.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

@Composable
fun PictureFrameTile(prefs: Prefs, height: Dp) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(prefs.frameSource) }
    var items by remember { mutableStateOf<List<FrameItem>>(emptyList()) }
    var at by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(source) { items = frameItems(context, prefs).shuffled(); at = 0 }
    // The next photo every 20 seconds, cross-fading.
    LaunchedEffect(items, at) {
        if (items.isEmpty()) { shown = null; return@LaunchedEffect }
        shown = loadFrame(context, prefs, items[at % items.size], 1200) ?: shown
        delay(FRAME_SECONDS * 1000)
        at = (at + 1) % maxOf(1, items.size)
    }
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { if (items.isEmpty()) choosing = true else at = (at + 1) % items.size },
        contentAlignment = Alignment.Center,
    ) {
        val b = shown
        if (b != null) Crossfade(b, animationSpec = tween(1200), label = "frame") { bmp ->
            Image(bmp.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Accent2, modifier = Modifier.size(32.dp))
            Text(if (source.isEmpty()) "Tap to choose photos" else "Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // A small button to change the photos.
        if (b != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .7f)).clickable { choosing = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose photos", modifier = Modifier.size(18.dp))
        }
    }
    if (choosing) FrameChooser(prefs, onDone = { choosing = false; source = prefs.frameSource })
}

@Composable
private fun FrameChooser(prefs: Prefs, onDone: () -> Unit) {
    val context = LocalContext.current
    var immich by remember { mutableStateOf(prefs.frameSource.removePrefix("immich:").takeIf { prefs.frameSource.startsWith("immich:") }.orEmpty()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { uris ->
        if (uris.isNotEmpty()) {
            // Keep access to the photos after the phone restarts, where it allows.
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            prefs.frameSource = "photos:" + uris.joinToString("|")
            onDone()
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Picture frame") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Just for this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Photos from this phone (one or many)")
                }
                OutlinedButton(onClick = { prefs.frameSource = "family"; onDone() }, modifier = Modifier.fillMaxWidth()) { Text("The family album (the TV's photos)") }
                OutlinedTextField(immich, { immich = it.trim() }, label = { Text("Immich shared-album link") }, placeholder = { Text("https://photos…/share/…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("In Immich, open an album, Share, and copy the link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = immich.startsWith("http"), onClick = { prefs.frameSource = "immich:$immich"; onDone() }) { Text("Use the link") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
