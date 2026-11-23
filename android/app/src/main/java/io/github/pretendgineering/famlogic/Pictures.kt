package io.github.pretendgineering.famlogic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Picture icons: literal, colourful drawings for people who can't read yet (or
 * prefer pictures): a tiny map instead of a pin, a talking face instead of an
 * envelope, a red alarm for SOS. On by default for younger children, an option for
 * grandparents (Customize → Easier to Use, or Access & Control in the console).
 */
object Pictures {
    @Composable
    fun Map(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { tinyMap() }

    @Composable
    fun Talk(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { talkingFace() }

    @Composable
    fun Sos(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { alarm() }

    @Composable
    fun Home(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { house() }

    @Composable
    fun HereIAm(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { wavingHand() }

    @Composable
    fun Camera(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { camera() }
}

private val INK = Color(0xFF2B2440)

/** A little square of map: grass, a river, two roads and a red dot where someone is. */
private fun DrawScope.tinyMap() {
    val s = size.minDimension
    val r = CornerRadius(s * .18f)
    drawRoundRect(Color(0xFFBFE6A8), size = Size(s, s), cornerRadius = r)
    val river = Path().apply {
        moveTo(0f, s * .72f); cubicTo(s * .3f, s * .55f, s * .5f, s * .95f, s, s * .7f)
        lineTo(s, s * .86f); cubicTo(s * .5f, s * 1.08f, s * .3f, s * .7f, 0f, s * .88f); close()
    }
    drawPath(river, Color(0xFF59B7F0))
    drawLine(Color.White, Offset(s * .12f, 0f), Offset(s * .42f, s), strokeWidth = s * .09f)
    drawLine(Color.White, Offset(0f, s * .32f), Offset(s, s * .22f), strokeWidth = s * .08f)
    drawRect(Color(0xFFF4C95D), Offset(s * .62f, s * .38f), Size(s * .2f, s * .16f))
    drawCircle(Color(0xFFE53950), radius = s * .13f, center = Offset(s * .3f, s * .42f))
    drawCircle(Color.White, radius = s * .05f, center = Offset(s * .3f, s * .42f))
    drawRoundRect(INK.copy(alpha = .25f), size = Size(s, s), cornerRadius = r, style = Stroke(s * .04f))
}

/** A round yellow face with an open mouth and sound lines: talking. */
private fun DrawScope.talkingFace() {
    val s = size.minDimension
    val c = Offset(s * .42f, s * .52f)
    val rad = s * .38f
    drawCircle(Color(0xFFFFCC3D), rad, c)
    drawCircle(INK.copy(alpha = .3f), rad, c, style = Stroke(s * .035f))
    drawCircle(INK, s * .045f, Offset(c.x - rad * .38f, c.y - rad * .25f))
    drawCircle(INK, s * .045f, Offset(c.x + rad * .3f, c.y - rad * .25f))
    drawOval(Color(0xFFB3263A), Offset(c.x - rad * .28f, c.y + rad * .12f), Size(rad * .56f, rad * .5f))
    for (i in 0..2) {
        drawArc(
            Color(0xFF6F5AA8), -35f, 70f, false,
            topLeft = Offset(c.x + rad * .55f + i * s * .09f - s * .14f, c.y - s * .14f - i * s * .06f),
            size = Size(s * .28f + i * s * .12f, s * .28f + i * s * .12f),
            style = Stroke(s * .05f, cap = StrokeCap.Round),
        )
    }
}

/** A red alarm light with rays: "help!". */
private fun DrawScope.alarm() {
    val s = size.minDimension
    val red = Color(0xFFE53950)
    val dome = Path().apply {
        moveTo(s * .22f, s * .78f); lineTo(s * .22f, s * .52f)
        cubicTo(s * .22f, s * .22f, s * .78f, s * .22f, s * .78f, s * .52f); lineTo(s * .78f, s * .78f); close()
    }
    drawPath(dome, red)
    drawRoundRect(Color(0xFFFF9AA8), Offset(s * .36f, s * .42f), Size(s * .09f, s * .26f), CornerRadius(s * .05f))
    drawRoundRect(INK, Offset(s * .14f, s * .78f), Size(s * .72f, s * .13f), CornerRadius(s * .04f))
    val ray = Color(0xFFFFB020)
    listOf(Offset(s * .5f, s * .04f) to Offset(s * .5f, s * .16f), Offset(s * .12f, s * .2f) to Offset(s * .2f, s * .28f),
        Offset(s * .88f, s * .2f) to Offset(s * .8f, s * .28f), Offset(s * .02f, s * .5f) to Offset(s * .12f, s * .5f),
        Offset(s * .98f, s * .5f) to Offset(s * .88f, s * .5f)).forEach { (a, b) ->
        drawLine(ray, a, b, strokeWidth = s * .06f, cap = StrokeCap.Round)
    }
}

/** A house with a red roof and a door. */
private fun DrawScope.house() {
    val s = size.minDimension
    drawRect(Color(0xFFFFE3A3), Offset(s * .2f, s * .45f), Size(s * .6f, s * .45f))
    val roof = Path().apply { moveTo(s * .08f, s * .5f); lineTo(s * .5f, s * .1f); lineTo(s * .92f, s * .5f); close() }
    drawPath(roof, Color(0xFFE5533D))
    drawRoundRect(Color(0xFF7A4A2A), Offset(s * .42f, s * .62f), Size(s * .17f, s * .28f), CornerRadius(s * .03f))
    drawRect(Color(0xFF59B7F0), Offset(s * .26f, s * .55f), Size(s * .11f, s * .11f))
}

/** A waving hand: "I'm here!". */
private fun DrawScope.wavingHand() {
    val s = size.minDimension
    val skin = Color(0xFFF5C28B)
    drawRoundRect(skin, Offset(s * .28f, s * .42f), Size(s * .42f, s * .46f), CornerRadius(s * .16f))
    for (i in 0..3) drawRoundRect(skin, Offset(s * (.28f + i * .105f), s * (.16f + (if (i == 0 || i == 3) .06f else 0f))), Size(s * .09f, s * .36f), CornerRadius(s * .045f))
    drawRoundRect(skin, Offset(s * .12f, s * .5f), Size(s * .22f, s * .1f), CornerRadius(s * .05f))
    drawArc(Color(0xFF6F5AA8), 200f, 60f, false, Offset(s * .74f, s * .08f), Size(s * .22f, s * .3f), style = Stroke(s * .05f, cap = StrokeCap.Round))
    drawArc(Color(0xFF6F5AA8), 200f, 60f, false, Offset(s * .04f, s * .08f), Size(s * .22f, s * .3f), style = Stroke(s * .05f, cap = StrokeCap.Round))
}

/** A bright camera. */
private fun DrawScope.camera() {
    val s = size.minDimension
    drawRoundRect(Color(0xFF59B7F0), Offset(s * .08f, s * .3f), Size(s * .84f, s * .56f), CornerRadius(s * .12f))
    drawRoundRect(Color(0xFF3B8FD0), Offset(s * .32f, s * .18f), Size(s * .3f, s * .16f), CornerRadius(s * .05f))
    drawCircle(Color.White, s * .2f, Offset(s * .5f, s * .58f))
    drawCircle(INK, s * .12f, Offset(s * .5f, s * .58f))
    drawCircle(Color(0xFFFFCC3D), s * .05f, Offset(s * .78f, s * .4f))
}
