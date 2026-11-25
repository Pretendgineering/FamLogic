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
 * prefer pictures): a tiny map instead of a pin, a paper airplane instead of an
 * envelope, a red alarm for SOS. On by default for younger children, an option for
 * grandparents (Customize → Easier to Use, or Access & Control in the console).
 */
object Pictures {
    @Composable
    fun Map(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { tinyMap() }

    @Composable
    /** Chitchat: a paper airplane carrying a message (not a face, so it can't be mixed up with the talk button). */
    fun Talk(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { paperPlane() }

    @Composable
    fun Sos(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { alarm() }

    @Composable
    fun Home(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { house() }

    @Composable
    fun HereIAm(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { wavingHand() }

    /** Upkeep: a cream to-do scroll with a green tick, rolled at both ends. */
    @Composable
    fun Upkeep(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { scroll() }

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
/** A bright paper airplane flying up and to the right, with a dotted trail behind it. */
private fun DrawScope.paperPlane() {
    val s = size.minDimension
    fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)
    fun tri(a: androidx.compose.ui.geometry.Offset, b: androidx.compose.ui.geometry.Offset, c: androidx.compose.ui.geometry.Offset, color: Color) =
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, color)
    // The dotted trail, curving in from the bottom left.
    listOf(.06f to .9f, .13f to .84f, .2f to .77f, .27f to .71f).forEachIndexed { i, (x, y) ->
        drawCircle(Color(0xFF90A4AE).copy(alpha = .5f + i * .12f), s * (.028f + i * .004f), p(x, y))
    }
    tri(p(.18f, .5f), p(.92f, .14f), p(.46f, .62f), Color(0xFF4FC3F7)) // top wing
    tri(p(.46f, .62f), p(.92f, .14f), p(.58f, .86f), Color(0xFF0288D1)) // lower wing
    tri(p(.46f, .62f), p(.58f, .86f), p(.47f, .78f), Color(0xFF01579B)) // the fold underneath
    // A crisp edge between the wings.
    drawLine(Color.White.copy(alpha = .85f), p(.46f, .62f), p(.92f, .14f), s * .025f)
}

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

/** A cream to-do scroll: the sheet, tan rolls top and bottom, a green tick and two lines to go. */
private fun DrawScope.scroll() {
    val k = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * k, y * k)
    val paper = Color(0xFFFFF1CF)
    val roll = Color(0xFFD9A066)
    // The sheet.
    drawRect(paper, o(7.6f, 4.4f), Size(9.4f * k, 13.6f * k))
    drawLine(INK.copy(alpha = .25f), o(7.6f, 4.4f), o(7.6f, 17.6f), 1f * k)
    drawLine(INK.copy(alpha = .25f), o(17f, 4.4f), o(17f, 17.6f), 1f * k)
    // The rolls, offset so it seems to float.
    drawRoundRect(roll, o(5f, 2.6f), Size(13f * k, 3.4f * k), CornerRadius(1.7f * k))
    drawCircle(Color(0xFFB97A45), 1.05f * k, o(6.7f, 4.3f))
    drawRoundRect(roll, o(6.6f, 17.4f), Size(13f * k, 3.4f * k), CornerRadius(1.7f * k))
    drawCircle(Color(0xFFB97A45), 1.05f * k, o(17.9f, 19.1f))
    // The list.
    val tick = Path().apply { moveTo(9.4f * k, 8.6f * k); lineTo(10.6f * k, 9.8f * k); lineTo(12.6f * k, 7.6f * k) }
    drawPath(tick, Color(0xFF3FA568), style = Stroke(1.5f * k, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(INK.copy(alpha = .55f), o(13.5f, 8.8f), o(15.3f, 8.8f), 1.3f * k, StrokeCap.Round)
    drawLine(INK.copy(alpha = .55f), o(9.6f, 12f), o(15.3f, 12f), 1.3f * k, StrokeCap.Round)
    drawLine(INK.copy(alpha = .55f), o(9.6f, 15f), o(13.6f, 15f), 1.3f * k, StrokeCap.Round)
}
