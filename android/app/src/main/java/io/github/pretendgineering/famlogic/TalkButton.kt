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
import androidx.compose.ui.graphics.StrokeCap
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
 * Push to talk: a smiley with an open mouth and sound waves. Press and hold to
 * speak: it pops out and glows while it listens, so it's obvious it's being held;
 * let go and the words come back as text ([onText], "" if nothing was understood).
 */
@Composable
fun TalkButton(prefs: Prefs, size: Dp, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var listening by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val accent = MaterialTheme.colorScheme.primary

    val pop by animateFloatAsState(if (listening) 1.18f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
    val glow by animateFloatAsState(if (listening) 1f else 0f, tween(180), label = "glow")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val beat by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "beat")

    Box(
        modifier.size(size)
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
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size).scale(pop)) {
            // The glow: a soft halo in the theme's colour, breathing while held.
            if (glow > 0f) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = .55f * glow), accent.copy(alpha = .18f * glow), Color.Transparent),
                        center = center, radius = this.size.minDimension / 2,
                    ),
                    radius = this.size.minDimension / 2,
                )
            }
            talkingFace(mouth = if (listening) 0.6f + 0.4f * beat else 0.55f, waves = if (listening) beat else 0f, listening = listening, accent = accent)
        }
        if (thinking) CircularProgressIndicator(Modifier.size(size * 0.9f), strokeWidth = 5.dp)
    }
}

private val INK = Color(0xFF2B2440)

/** A round yellow face, mouth open, with sound waves coming out. */
private fun DrawScope.talkingFace(mouth: Float, waves: Float, listening: Boolean, accent: Color) {
    val s = size.minDimension
    val c = Offset(s * .44f, s * .52f)
    val r = s * .3f
    drawCircle(Color(0xFFFFCC3D), r, c)
    drawCircle(INK.copy(alpha = .35f), r, c, style = Stroke(s * .02f))
    // Eyes (a little wider while listening).
    val eye = s * (if (listening) .032f else .026f)
    drawCircle(INK, eye, Offset(c.x - r * .38f, c.y - r * .28f))
    drawCircle(INK, eye, Offset(c.x + r * .32f, c.y - r * .28f))
    // The open mouth, with a tongue.
    val mw = r * .62f
    val mh = r * .62f * mouth
    drawOval(Color(0xFFB3263A), Offset(c.x - mw / 2, c.y + r * .12f), Size(mw, mh))
    drawOval(Color(0xFFFF8A9B), Offset(c.x - mw * .28f, c.y + r * .12f + mh * .55f), Size(mw * .56f, mh * .4f))
    // Sound waves, rippling outward while held.
    for (i in 0..2) {
        val grow = s * (.12f + i * .085f) + s * .03f * waves
        drawArc(
            if (listening) accent else Color(0xFF6F5AA8), -38f, 76f, false,
            topLeft = Offset(c.x + r * .9f - grow, c.y - grow),
            size = Size(grow * 2, grow * 2),
            style = Stroke(s * .035f, cap = StrokeCap.Round),
            alpha = if (listening) (1f - i * .22f) else (.8f - i * .2f),
        )
    }
}
