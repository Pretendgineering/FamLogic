package io.github.pretendgineering.famlogic

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The nudges that work as reactions to a message. */
val REACTIONS = listOf("thumbs", "heart", "smile", "wave", "cowboy", "alien")

fun reactionEmoji(kind: String): String = Nudges.all.find { it.kind == kind }?.emoji ?: "?"

/** The quoted message at the top of a reply. */
@Composable
fun ReplyQuote(name: String, text: String, onBubble: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).clip(RoundedCornerShape(10.dp)).background(onBubble.copy(alpha = .1f))) {
        Box(Modifier.width(3.dp).height(40.dp).background(Accent2))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = onBubble.copy(alpha = .9f), maxLines = 1)
            Text(text, style = MaterialTheme.typography.bodySmall, color = onBubble.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Reactions under a message: each kind with how many; yours outlined. Tap to add or take back yours. */
@Composable
fun ReactionChips(reactions: Map<String, List<String>>, me: String, onToggle: (String) -> Unit) {
    if (reactions.isEmpty()) return
    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { (kind, who) ->
            val mine = me in who
            Row(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (mine) Accent2.copy(alpha = .22f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(if (mine) 1.dp else 0.dp, if (mine) Accent2 else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onToggle(if (mine) "" else kind) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reactionEmoji(kind))
                if (who.size > 1) Text(" ${who.size}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** What you can do with a message: react, reply, copy, delete. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MessageActions(
    onReact: (String) -> Unit, onReply: (() -> Unit)?, onCopy: (() -> Unit)?, onDelete: (() -> Unit)?, onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            REACTIONS.forEach { k ->
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onReact(k); onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text(reactionEmoji(k), style = MaterialTheme.typography.headlineSmall) }
            }
        }
        val row: @Composable (androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit) -> Unit = { icon, label, go ->
            Row(Modifier.fillMaxWidth().clickable { go(); onDismiss() }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        onReply?.let { row(Icons.AutoMirrored.Filled.Reply, "Reply", it) }
        onCopy?.let { row(Icons.Filled.ContentCopy, "Copy", it) }
        onDelete?.let { row(Icons.Filled.Delete, "Delete", it) }
        Box(Modifier.height(24.dp))
    }
}

// ---- voice messages

/** Records a voice message (AAC, .m4a) into the app's cache. */
class VoiceRecording(context: Context) {
    val file: File = File(File(context.cacheDir, "voice").apply { mkdirs() }, "rec-${System.currentTimeMillis()}.m4a")
    private val started = System.currentTimeMillis()
    private val recorder: MediaRecorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioEncodingBitRate(48_000)
        setAudioSamplingRate(32_000)
        setMaxDuration(3 * 60_000)
        setOutputFile(file.path)
        prepare()
        start()
    }

    val elapsed get() = System.currentTimeMillis() - started

    /** Stops and returns the recording (null if it was too short to keep). */
    fun finish(): Pair<ByteArray, Long>? {
        val ms = elapsed
        runCatching { recorder.stop() }
        recorder.release()
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return if (bytes == null || ms < 700) null else bytes to ms
    }

    fun cancel() {
        runCatching { recorder.stop() }
        recorder.release()
        file.delete()
    }
}

fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** A voice message in a bubble: play or pause, and how far along. */
@Composable
fun VoiceBubble(prefs: Prefs, name: String, ms: Long, fg: Color) {
    val context = LocalContext.current
    var player by remember(name) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(name) { mutableStateOf(false) }
    var progress by remember(name) { mutableFloatStateOf(0f) }
    DisposableEffect(name) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p -> runCatching { progress = p.currentPosition.toFloat() / maxOf(1, p.duration) } }
            delay(150)
        }
    }
    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).width(220.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            val p = player
            if (playing && p != null) { p.pause(); playing = false; return@IconButton }
            if (p != null) { p.start(); playing = true; return@IconButton }
            Voice.stop()
            val mp = MediaPlayer()
            runCatching {
                mp.setDataSource(context, Uri.parse(prefs.serverUrl.trimEnd('/') + "/api/chat-media/" + name), mapOf("Authorization" to "Bearer " + prefs.token))
                mp.setOnPreparedListener { it.start(); playing = true }
                mp.setOnCompletionListener { playing = false; progress = 0f; it.seekTo(0) }
                mp.prepareAsync()
                player = mp
            }.onFailure { Toast.makeText(context, "Couldn't play it", Toast.LENGTH_SHORT).show() }
        }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = fg)
        }
        LinearProgressIndicator(progress = { progress }, Modifier.weight(1f), color = fg, trackColor = fg.copy(alpha = .25f))
        Text(formatClock(if (playing) (progress * ms).toLong() else ms), Modifier.padding(start = 8.dp, end = 6.dp), color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

// ---- photos

/** A photo full screen: pinch to zoom, drag to look around, and Save to the phone's gallery. */
@Composable
fun PhotoViewer(bitmap: Bitmap, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().transformable(state)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                IconButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { savePhoto(context, bitmap) }
                        Toast.makeText(context, if (ok) "Saved to your photos" else "Couldn't save it", Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Filled.Download, contentDescription = "Save", tint = Color.White) }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            }
        }
    }
}

private fun savePhoto(context: Context, bitmap: Bitmap): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "FamLogic-${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FamLogic")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return false
    true
}.getOrDefault(false)

@Suppress("unused")
private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
