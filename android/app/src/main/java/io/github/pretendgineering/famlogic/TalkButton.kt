package io.github.pretendgineering.famlogic

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Push to talk: a happy, open-mouthed smiley. Press and hold to speak: it pops
 * out and glows while it listens, so it's obvious it's being held; let go and the
 * words come back as text ([onText], "" if nothing was understood).
 *
 * [bar]: a wide bar (the smiley and "Hold to talk"), for children and tablets,
 * above the message box. Otherwise a round button beside it.
 */
@Composable
fun TalkButton(
    prefs: Prefs, size: Dp, onText: (String) -> Unit, modifier: Modifier = Modifier, bar: Boolean = false, label: String = "Hold to talk",
    /** What letting go does, for the bar's "Listening… let go to …": send it, or type it into the box. */
    letGo: String = "send",
    /** A plain microphone (grown-ups); otherwise the talking smiley (children). */
    mic: Boolean = !Access.isChild,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var listening by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val accent = MaterialTheme.colorScheme.primary

    val pop by animateFloatAsState(if (listening) (if (bar) 1.04f else 1.18f) else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
    val glow by animateFloatAsState(if (listening) 1f else 0f, tween(180), label = "glow")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val beat by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "beat")
    val mouth = if (listening) 0.65f + 0.35f * beat else 0.8f

    val press = Modifier
        .semantics { contentDescription = "Hold to talk, let go to send" }
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                if (thinking) return@detectTapGestures
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                    return@detectTapGestures
                }
                Voice.stop() // don't record the phone reading something aloud
                val take = Voice.start(context, prefs)
                if (take == null) {
                    Voice.oops(context)
                    return@detectTapGestures
                }
                listening = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val released = tryAwaitRelease()
                listening = false
                if (!released) {
                    take.cancel()
                    return@detectTapGestures
                }
                thinking = true
                scope.launch {
                    val text = runCatching { take.finish() }.getOrDefault("")
                    thinking = false
                    onText(text.trim())
                }
            })
        }

    if (bar) {
        val shape = RoundedCornerShape(size / 2)
        Box(
            modifier.fillMaxWidth().height(size).scale(pop)
                .clip(shape)
                .background(lerp(MaterialTheme.colorScheme.primaryContainer, accent, glow * .85f))
                .border(2.dp, accent.copy(alpha = .5f + .5f * glow), shape)
                .then(press),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(size * 0.82f), contentAlignment = Alignment.Center) {
                    if (mic) Icon(Icons.Filled.Mic, contentDescription = null, tint = if (glow > .5f) MaterialTheme.colorScheme.onPrimary else accent, modifier = Modifier.fillMaxSize(0.7f))
                    else Canvas(Modifier.fillMaxSize()) { smiley(mouth, listening, waves = beat) }
                    if (thinking) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 4.dp)
                }
                Text(
                    when { thinking -> "Just a moment…"; listening -> "Listening… let go to $letGo"; else -> label },
                    Modifier.padding(start = 14.dp),
                    style = if (size >= 72.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (glow > .5f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        return
    }
    Box(modifier.size(size).then(press), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).scale(pop)) {
            // The glow: a soft halo in the theme's colour while held.
            if (glow > 0f) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = .55f * glow), accent.copy(alpha = .18f * glow), Color.Transparent),
                        center = center, radius = this.size.minDimension / 2,
                    ),
                    radius = this.size.minDimension / 2,
                )
            }
            if (!mic) smiley(mouth, listening, scale = .8f, waves = if (listening) beat else 0f)
        }
        if (mic) {
            // Grown-ups: a plain microphone in a circle, filled while it listens.
            Box(
                Modifier.size(size * 0.82f).scale(pop).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(lerp(MaterialTheme.colorScheme.secondaryContainer, accent, glow)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = if (glow > .5f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.46f))
            }
        }
        if (thinking) CircularProgressIndicator(Modifier.size(size * 0.9f), strokeWidth = 5.dp)
    }
}

private val INK = Color(0xFF2B2440)

/** A round yellow face with a big, happy, open-mouthed grin (it talks while listening). */
private fun DrawScope.smiley(open: Float, listening: Boolean, scale: Float = .92f, waves: Float = 0f) {
    // The face sits a little left, with sound waves coming out to the right, so it
    // reads as "talking", not just a smiley.
    val r = size.minDimension / 2 * scale * .74f
    val c = Offset(size.width / 2 - r * .34f, size.height / 2)
    for (i in 0..2) {
        val reach = r * (1.15f + i * .22f) + r * .06f * waves
        drawArc(
            Color(0xFF6F5AA8).copy(alpha = (if (listening) 1f else .85f) - i * .22f), -35f, 70f, false,
            topLeft = Offset(c.x - reach, c.y - reach), size = Size(reach * 2, reach * 2),
            style = Stroke(r * .12f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
    drawCircle(Color(0xFFFFCC3D), r, c)
    drawCircle(Color(0xFFE0A82E), r, c, style = Stroke(r * .06f))
    // Rosy cheeks.
    drawCircle(Color(0xFFFF8A80).copy(alpha = .45f), r * .14f, Offset(c.x - r * .58f, c.y + r * .12f))
    drawCircle(Color(0xFFFF8A80).copy(alpha = .45f), r * .14f, Offset(c.x + r * .58f, c.y + r * .12f))
    // Eyes: tall ovals, a little bigger while listening, each with a sparkle.
    val ew = r * (if (listening) .2f else .17f)
    val eh = r * (if (listening) .3f else .26f)
    listOf(-1f, 1f).forEach { side ->
        val e = Offset(c.x + side * r * .34f, c.y - r * .26f)
        drawOval(INK, Offset(e.x - ew / 2, e.y - eh / 2), Size(ew, eh))
        drawCircle(Color.White, ew * .2f, Offset(e.x + ew * .15f, e.y - eh * .2f))
    }
    // The grin: a wide "D" on its side (flat top, round bottom), with a tongue.
    val top = c.y + r * .04f
    val w = r * .62f
    val h = r * .62f * open
    val mouth = Path().apply {
        moveTo(c.x - w, top)
        lineTo(c.x + w, top)
        arcTo(Rect(c.x - w, top - h, c.x + w, top + h), 0f, 180f, false)
        close()
    }
    drawPath(mouth, Color(0xFF8E1F2F))
    clipPath(mouth) {
        drawOval(Color(0xFFFF6F7F), Offset(c.x - w * .55f, top + h * .45f), Size(w * 1.1f, h * .9f))
        // Top teeth.
        drawRect(Color.White, Offset(c.x - w * .8f, top), Size(w * 1.6f, h * .18f))
    }
}
