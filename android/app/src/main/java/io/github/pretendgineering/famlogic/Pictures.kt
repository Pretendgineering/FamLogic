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

    /** Datebook: a calendar page with a red top and a circled day. */
    @Composable
    fun Calendar(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { calendarPage() }

    /** Docket: a yellow notepad with coloured ticks. */
    @Composable
    fun Docket(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { notepad() }

    /** Rhythms: a clock face with a little sun and moon. */
    @Composable
    fun Rhythms(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { dayClock() }

    /** Pantry: a plate with a fork and spoon. */
    @Composable
    fun Pantry(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { plate() }

    /** Nudges: a big pink heart with a little sparkle. */
    @Composable
    fun Nudge(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { heart() }

    /** Pickup: a little red car. */
    @Composable
    fun Pickup(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { car() }

    /** The picture for a module, for the bright picture icons (children, Easier to Use); null if it has none. */
    fun forModule(id: String): (@Composable (Dp) -> Unit)? = when (id) {
        "map", "thereabouts" -> { d -> Map(d) }
        "chat" -> { d -> Talk(d) }
        "sos" -> { d -> Sos(d) }
        "checkin" -> { d -> HereIAm(d) }
        "chores" -> { d -> Upkeep(d) }
        "calendar" -> { d -> Calendar(d) }
        "todos" -> { d -> Docket(d) }
        "routines" -> { d -> Rhythms(d) }
        "meals" -> { d -> Pantry(d) }
        "homestead" -> { d -> Home(d) }
        "nudges" -> { d -> Nudge(d) }
        "ride" -> { d -> Pickup(d) }
        else -> null
    }
}

/** A tile's icon: the bright picture where pictures are on (and the module has one), else the plain icon. */
@Composable
fun ModuleIcon(id: String, size: Dp, tint: Color = Accent2) {
    val pic = if (Access.pictures) Pictures.forModule(id) else null
    if (pic != null) pic(size + 6.dp) else androidx.compose.material3.Icon(tileIcon(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))
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

/** A cream scroll, tan rolls at the top left and bottom right, a green tick and a line to go. */
private fun DrawScope.scroll() {
    val k = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * k, y * k)
    val roll = Color(0xFFC98A4F)
    drawRect(Color(0xFFFFF1CF), o(8f, 4.5f), Size(8.5f * k, 15f * k))
    val line = Stroke(2f * k, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    drawPath(Path().apply {
        moveTo(17.5f * k, 4.5f * k); lineTo(7f * k, 4.5f * k); arcTo(androidx.compose.ui.geometry.Rect(5f * k, 4.5f * k, 9f * k, 8.5f * k), 270f, -180f, false); lineTo(8f * k, 8.5f * k)
        moveTo(8f * k, 6.5f * k); lineTo(8f * k, 19.5f * k); lineTo(18.5f * k, 19.5f * k)
        moveTo(16.5f * k, 4.5f * k); lineTo(16.5f * k, 17.5f * k); arcTo(androidx.compose.ui.geometry.Rect(16.5f * k, 15.5f * k, 20.5f * k, 19.5f * k), 180f, -180f, false); lineTo(20.5f * k, 16.5f * k); lineTo(18.5f * k, 16.5f * k)
    }, roll, style = line)
    drawPath(Path().apply { moveTo(10.2f * k, 10.4f * k); lineTo(11.4f * k, 11.6f * k); lineTo(13.8f * k, 9f * k) }, Color(0xFF3FA568), style = line)
    drawLine(Color(0xFF2B2440).copy(alpha = .55f), o(10.4f, 14.5f), o(14f, 14.5f), 1.6f * k, StrokeCap.Round)
}


/** A calendar page: white with a red top, two rings, a grid of days and one circled. */
private fun DrawScope.calendarPage() {
    val s = size.minDimension
    val r = CornerRadius(s * .12f)
    drawRoundRect(Color.White, Offset(s * .1f, s * .16f), Size(s * .8f, s * .74f), r)
    drawRoundRect(Color(0xFFE5533D), Offset(s * .1f, s * .16f), Size(s * .8f, s * .22f), r)
    drawRect(Color(0xFFE5533D), Offset(s * .1f, s * .28f), Size(s * .8f, s * .1f))
    listOf(.32f, .68f).forEach { x -> drawRoundRect(INK, Offset(s * x - s * .03f, s * .08f), Size(s * .06f, s * .16f), CornerRadius(s * .03f)) }
    for (row in 0..2) for (col in 0..3) {
        val c = Offset(s * (.24f + col * .17f), s * (.5f + row * .13f))
        if (row == 1 && col == 2) drawCircle(Color(0xFF59B7F0), s * .07f, c)
        drawCircle(INK.copy(alpha = .55f), s * .025f, c)
    }
    drawRoundRect(INK.copy(alpha = .25f), Offset(s * .1f, s * .16f), Size(s * .8f, s * .74f), r, style = Stroke(s * .035f))
}

/** A yellow notepad with three lines, each with a coloured tick or box. */
private fun DrawScope.notepad() {
    val s = size.minDimension
    drawRoundRect(Color(0xFFFFE07A), Offset(s * .16f, s * .1f), Size(s * .68f, s * .82f), CornerRadius(s * .1f))
    drawRoundRect(Color(0xFFF4B740), Offset(s * .16f, s * .1f), Size(s * .68f, s * .12f), CornerRadius(s * .1f))
    val ticks = listOf(Color(0xFF3DBE6A), Color(0xFF3DBE6A), Color(0xFF59B7F0))
    ticks.forEachIndexed { i, c ->
        val y = s * (.36f + i * .19f)
        if (i < 2) {
            drawLine(c, Offset(s * .25f, y), Offset(s * .3f, y + s * .05f), s * .05f, cap = StrokeCap.Round)
            drawLine(c, Offset(s * .3f, y + s * .05f), Offset(s * .39f, y - s * .05f), s * .05f, cap = StrokeCap.Round)
        } else drawRoundRect(c, Offset(s * .25f, y - s * .05f), Size(s * .12f, s * .12f), CornerRadius(s * .03f), style = Stroke(s * .04f))
        drawLine(INK.copy(alpha = .45f), Offset(s * .45f, y), Offset(s * .74f, y), s * .04f, cap = StrokeCap.Round)
    }
}

/** A clock face, with a little sun on one side and a moon on the other: the day's routines. */
private fun DrawScope.dayClock() {
    val s = size.minDimension
    val c = Offset(s * .5f, s * .54f)
    drawCircle(Color(0xFFFFF4D6), s * .34f, c)
    drawCircle(Color(0xFF6F5AA8), s * .34f, c, style = Stroke(s * .06f))
    drawLine(INK, c, Offset(c.x, c.y - s * .2f), s * .05f, cap = StrokeCap.Round)
    drawLine(INK, c, Offset(c.x + s * .14f, c.y + s * .06f), s * .05f, cap = StrokeCap.Round)
    drawCircle(Color(0xFFFFB020), s * .12f, Offset(s * .17f, s * .17f))
    drawCircle(Color(0xFF90A4EE), s * .1f, Offset(s * .84f, s * .16f))
    drawCircle(Color(0xFFFFF4D6), s * .08f, Offset(s * .89f, s * .12f))
}

/** A plate with a fork and a spoon either side. */
private fun DrawScope.plate() {
    val s = size.minDimension
    val c = Offset(s * .5f, s * .52f)
    drawCircle(Color.White, s * .3f, c)
    drawCircle(Color(0xFF3DBE6A), s * .19f, c)
    drawCircle(INK.copy(alpha = .2f), s * .3f, c, style = Stroke(s * .03f))
    val metal = Color(0xFF8E9AAF)
    drawLine(metal, Offset(s * .1f, s * .2f), Offset(s * .1f, s * .88f), s * .05f, cap = StrokeCap.Round)
    for (dx in listOf(-.04f, 0f, .04f)) drawLine(metal, Offset(s * (.1f + dx), s * .14f), Offset(s * (.1f + dx), s * .32f), s * .025f, cap = StrokeCap.Round)
    drawOval(metal, Offset(s * .84f, s * .12f), Size(s * .1f, s * .16f))
    drawLine(metal, Offset(s * .89f, s * .26f), Offset(s * .89f, s * .88f), s * .05f, cap = StrokeCap.Round)
}

/** A pink heart, with a white shine and a yellow sparkle. */
private fun DrawScope.heart() {
    val s = size.minDimension
    val p = Path().apply {
        moveTo(s * .5f, s * .86f)
        cubicTo(s * .1f, s * .6f, s * .02f, s * .32f, s * .24f, s * .2f)
        cubicTo(s * .38f, s * .13f, s * .47f, s * .22f, s * .5f, s * .3f)
        cubicTo(s * .53f, s * .22f, s * .62f, s * .13f, s * .76f, s * .2f)
        cubicTo(s * .98f, s * .32f, s * .9f, s * .6f, s * .5f, s * .86f)
        close()
    }
    drawPath(p, Color(0xFFF0507A))
    drawCircle(Color.White.copy(alpha = .7f), s * .05f, Offset(s * .3f, s * .33f))
    drawCircle(Color(0xFFFFC83D), s * .06f, Offset(s * .86f, s * .12f))
}

/** A red car with windows and two wheels. */
private fun DrawScope.car() {
    val s = size.minDimension
    drawRoundRect(Color(0xFFE5533D), Offset(s * .26f, s * .3f), Size(s * .48f, s * .26f), CornerRadius(s * .1f))
    drawRoundRect(Color(0xFFE5533D), Offset(s * .08f, s * .46f), Size(s * .84f, s * .24f), CornerRadius(s * .1f))
    drawRoundRect(Color(0xFFBFE6FF), Offset(s * .32f, s * .35f), Size(s * .16f, s * .12f), CornerRadius(s * .03f))
    drawRoundRect(Color(0xFFBFE6FF), Offset(s * .52f, s * .35f), Size(s * .16f, s * .12f), CornerRadius(s * .03f))
    drawCircle(Color(0xFFFFE07A), s * .04f, Offset(s * .86f, s * .55f))
    listOf(.28f, .72f).forEach { x ->
        drawCircle(INK, s * .1f, Offset(s * x, s * .72f))
        drawCircle(Color(0xFFBDBDBD), s * .045f, Offset(s * x, s * .72f))
    }
}
