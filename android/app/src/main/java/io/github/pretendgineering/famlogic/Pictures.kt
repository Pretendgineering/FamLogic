package io.github.pretendgineering.famlogic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
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
    fun Talk(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { envelopeAndPencil() }

    @Composable
    fun Sos(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { alarm() }

    @Composable
    fun Home(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { cosyHouse() }

    @Composable
    fun HereIAm(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { wavingHand() }

    /** Upkeep: a cream to-do scroll with a green tick, rolled at both ends. */
    @Composable
    fun Upkeep(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { broom() }

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

    @Composable fun Dossier(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { idCard() }
    @Composable fun Driveway(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { carAndPump() }
    @Composable fun Vitals(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { sneaker() }
    @Composable fun Ledger(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { piggyBank() }
    @Composable fun Huddle(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { speechBubbles() }
    @Composable fun Corkboard(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { tvWithPin() }
    @Composable fun Steward(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { helperStar() }
    @Composable fun Pets(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { paw() }
    @Composable fun Contacts(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { addressBook() }
    @Composable fun Feed(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { newspaper() }
    @Composable fun Today(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { sunny() }
    @Composable fun Weather(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { sunAndCloud() }
    @Composable fun Clock(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { alarmClock() }
    @Composable fun Countdown(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { hourglass() }
    @Composable fun Note(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { stickyNote() }
    @Composable fun QuickAdd(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { plusOnList() }
    @Composable fun Quiet(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { sleepyMoon() }
    @Composable fun WhosHome(size: Dp = 40.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { houseWithFaces() }

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
        "profiles" -> { d -> Dossier(d) }
        "garage" -> { d -> Driveway(d) }
        "outings" -> { d -> Vitals(d) }
        "ledger" -> { d -> Ledger(d) }
        "meetings" -> { d -> Huddle(d) }
        "display" -> { d -> Corkboard(d) }
        "steward", "agents" -> { d -> Steward(d) }
        "pets" -> { d -> Pets(d) }
        "contacts" -> { d -> Contacts(d) }
        "feed" -> { d -> Feed(d) }
        "today" -> { d -> Today(d) }
        "weather" -> { d -> Weather(d) }
        "clock" -> { d -> Clock(d) }
        "countdown" -> { d -> Countdown(d) }
        "note" -> { d -> Note(d) }
        "quickadd" -> { d -> QuickAdd(d) }
        "quiet" -> { d -> Quiet(d) }
        "startroutine" -> { d -> Rhythms(d) }
        "whoshome" -> { d -> WhosHome(d) }
        else -> null
    }
}

/** Every picture with its name: a preview under Settings → Look and Feel → Picture icons. */
@Composable
fun PictureGallery() {
    val all = listOf(
        "map" to "Thereabouts", "chat" to "Chitchat", "sos" to "SOS", "checkin" to "Check in", "ride" to "Pickup",
        "nudges" to "Nudges", "chores" to "Upkeep", "calendar" to "Datebook", "todos" to "Docket", "routines" to "Rhythms",
        "meals" to "Pantry", "homestead" to "Homestead", "profiles" to "Dossier", "pets" to "Pets", "contacts" to "Contacts",
        "garage" to "Driveway", "outings" to "Vitals", "ledger" to "Ledger", "meetings" to "Huddle", "display" to "Corkboard",
        "steward" to "Steward", "feed" to "Feed", "today" to "Today", "weather" to "Weather", "clock" to "Clock",
        "countdown" to "Countdown", "note" to "Sticky note", "quickadd" to "Quick add", "quiet" to "Quiet time", "whoshome" to "Who's home",
    )
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        all.chunked(4).forEach { row ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                row.forEach { (id, name) ->
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Pictures.forModule(id)?.invoke(52.dp)
                        androidx.compose.material3.Text(name, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
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


// ---- 0.51's pictures

/** Chitchat: a plain envelope with a yellow pencil across it: writing a message. */
private fun DrawScope.envelopeAndPencil() {
    val s = size.minDimension
    val env = Color(0xFFF7F3EA)
    drawRoundRect(env, Offset(s * .08f, s * .26f), Size(s * .7f, s * .5f), CornerRadius(s * .06f))
    drawRoundRect(INK.copy(alpha = .35f), Offset(s * .08f, s * .26f), Size(s * .7f, s * .5f), CornerRadius(s * .06f), style = Stroke(s * .035f))
    val flap = Path().apply { moveTo(s * .1f, s * .29f); lineTo(s * .43f, s * .53f); lineTo(s * .76f, s * .29f) }
    drawPath(flap, INK.copy(alpha = .45f), style = Stroke(s * .04f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    // The pencil, corner to corner over the envelope's right side.
    rotate(-45f, Offset(s * .66f, s * .5f)) { scale(1.35f, Offset(s * .62f, s * .5f)) {
        drawRoundRect(Color(0xFFFFC83D), Offset(s * .42f, s * .44f), Size(s * .44f, s * .13f), CornerRadius(s * .02f))
        drawRect(Color(0xFFF08FA5), Offset(s * .82f, s * .44f), Size(s * .07f, s * .13f)) // the eraser
        drawRect(Color(0xFFB8B8C0), Offset(s * .79f, s * .44f), Size(s * .04f, s * .13f))
        val tip = Path().apply { moveTo(s * .42f, s * .44f); lineTo(s * .32f, s * .505f); lineTo(s * .42f, s * .57f); close() }
        drawPath(tip, Color(0xFFF2D2A0))
        drawCircle(INK, s * .025f, Offset(s * .335f, s * .505f))
    } }
}

/** Upkeep: a broom, sweeping up a little grey puff of dust. */
private fun DrawScope.broom() {
    val s = size.minDimension
    // Dust, behind the bristles.
    listOf(Offset(.2f, .8f) to .1f, Offset(.12f, .7f) to .07f, Offset(.3f, .86f) to .07f, Offset(.08f, .84f) to .05f)
        .forEach { (o, r) -> drawCircle(Color(0xFFB9BCC6), s * r, Offset(s * o.x, s * o.y)) }
    rotate(30f, Offset(s * .55f, s * .5f)) {
        drawRoundRect(Color(0xFFB0773F), Offset(s * .52f, s * .02f), Size(s * .07f, s * .56f), CornerRadius(s * .035f)) // handle
        drawRoundRect(Color(0xFFE5533D), Offset(s * .47f, s * .54f), Size(s * .17f, s * .07f), CornerRadius(s * .02f)) // band
        val bristles = Path().apply { moveTo(s * .45f, s * .6f); lineTo(s * .66f, s * .6f); lineTo(s * .74f, s * .9f); lineTo(s * .37f, s * .9f); close() }
        drawPath(bristles, Color(0xFFF4C542))
        for (i in 0..3) drawLine(Color(0xFFD9A520), Offset(s * (.47f + i * .055f), s * .64f), Offset(s * (.43f + i * .085f), s * .88f), s * .02f)
    }
}

/** Home: a cosy house: roof with a chimney, two windows with frames, a door with a knob, a bush. */
private fun DrawScope.cosyHouse() {
    val s = size.minDimension
    drawRect(Color(0xFF8A3A28), Offset(s * .66f, s * .08f), Size(s * .11f, s * .26f)) // chimney
    drawRect(Color(0xFF5A2A1C), Offset(s * .64f, s * .06f), Size(s * .15f, s * .04f))
    drawRoundRect(Color(0xFFFFE3A3), Offset(s * .16f, s * .44f), Size(s * .68f, s * .46f), CornerRadius(s * .03f))
    val roof = Path().apply { moveTo(s * .04f, s * .5f); lineTo(s * .5f, s * .1f); lineTo(s * .96f, s * .5f); close() }
    drawPath(roof, Color(0xFFE5533D))
    drawPath(roof, Color(0xFFB0472F), style = Stroke(s * .03f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    for (x in listOf(.23f, .63f)) {
        drawRoundRect(Color(0xFF59B7F0), Offset(s * x, s * .55f), Size(s * .14f, s * .13f), CornerRadius(s * .02f))
        drawLine(Color.White, Offset(s * (x + .07f), s * .55f), Offset(s * (x + .07f), s * .68f), s * .02f)
        drawLine(Color.White, Offset(s * x, s * .615f), Offset(s * (x + .14f), s * .615f), s * .02f)
    }
    drawRoundRect(Color(0xFF7A4A2A), Offset(s * .43f, s * .64f), Size(s * .15f, s * .26f), CornerRadius(s * .04f))
    drawCircle(Color(0xFFFFC83D), s * .018f, Offset(s * .55f, s * .78f))
    drawCircle(Color(0xFF3DBE6A), s * .08f, Offset(s * .12f, s * .86f))
    drawCircle(Color(0xFF3DBE6A), s * .06f, Offset(s * .88f, s * .87f))
}

/** Dossier: an ID card with a face and a few lines. */
private fun DrawScope.idCard() {
    val s = size.minDimension
    drawRoundRect(Color(0xFFE8F1FF), Offset(s * .06f, s * .2f), Size(s * .88f, s * .6f), CornerRadius(s * .08f))
    drawRoundRect(Color(0xFF59B7F0), Offset(s * .06f, s * .2f), Size(s * .88f, s * .14f), CornerRadius(s * .08f))
    drawRect(Color(0xFF59B7F0), Offset(s * .06f, s * .28f), Size(s * .88f, s * .06f))
    drawCircle(Color(0xFFF5C28B), s * .1f, Offset(s * .27f, s * .5f))
    drawArc(Color(0xFF6F5AA8), 180f, 180f, true, Offset(s * .13f, s * .58f), Size(s * .28f, s * .2f))
    for ((i, w) in listOf(.38f, .3f, .34f).withIndex()) drawRoundRect(INK.copy(alpha = .4f), Offset(s * .48f, s * (.44f + i * .1f)), Size(s * w, s * .045f), CornerRadius(s * .02f))
}

/** Driveway: a little blue car beside a fuel pump. */
private fun DrawScope.carAndPump() {
    val s = size.minDimension
    drawRoundRect(Color(0xFF3DBE6A), Offset(s * .7f, s * .22f), Size(s * .2f, s * .56f), CornerRadius(s * .04f))
    drawRect(Color.White, Offset(s * .74f, s * .3f), Size(s * .12f, s * .1f))
    drawLine(INK, Offset(s * .9f, s * .4f), Offset(s * .96f, s * .5f), s * .03f, cap = StrokeCap.Round)
    drawRoundRect(Color(0xFF3B8FD0), Offset(s * .14f, s * .4f), Size(s * .36f, s * .18f), CornerRadius(s * .08f))
    drawRoundRect(Color(0xFF3B8FD0), Offset(s * .04f, s * .52f), Size(s * .58f, s * .18f), CornerRadius(s * .08f))
    drawRoundRect(Color(0xFFBFE6FF), Offset(s * .2f, s * .44f), Size(s * .11f, s * .09f), CornerRadius(s * .02f))
    drawRoundRect(Color(0xFFBFE6FF), Offset(s * .34f, s * .44f), Size(s * .11f, s * .09f), CornerRadius(s * .02f))
    listOf(.17f, .5f).forEach { x -> drawCircle(INK, s * .08f, Offset(s * x, s * .72f)); drawCircle(Color(0xFFBDBDBD), s * .035f, Offset(s * x, s * .72f)) }
}

/** Vitals: a running shoe, with a little heartbeat line. */
private fun DrawScope.sneaker() {
    val s = size.minDimension
    val beat = Path().apply { moveTo(s * .08f, s * .26f); lineTo(s * .3f, s * .26f); lineTo(s * .38f, s * .12f); lineTo(s * .46f, s * .38f); lineTo(s * .54f, s * .26f); lineTo(s * .92f, s * .26f) }
    drawPath(beat, Color(0xFFE5533D), style = Stroke(s * .045f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    val shoe = Path().apply {
        moveTo(s * .1f, s * .76f); lineTo(s * .12f, s * .5f); lineTo(s * .38f, s * .48f); lineTo(s * .52f, s * .6f)
        lineTo(s * .82f, s * .64f); quadraticTo(s * .94f, s * .7f, s * .9f, s * .76f); close()
    }
    drawPath(shoe, Color(0xFF59B7F0))
    drawRoundRect(Color.White, Offset(s * .08f, s * .74f), Size(s * .84f, s * .1f), CornerRadius(s * .05f))
    for (i in 0..2) drawLine(Color.White, Offset(s * (.26f + i * .07f), s * .52f), Offset(s * (.32f + i * .07f), s * .58f), s * .025f, cap = StrokeCap.Round)
}

/** Ledger: a pink piggy bank with a coin going in. */
private fun DrawScope.piggyBank() {
    val s = size.minDimension
    val pink = Color(0xFFF59AB5)
    drawOval(pink, Offset(s * .12f, s * .34f), Size(s * .72f, s * .48f))
    drawCircle(pink, s * .1f, Offset(s * .84f, s * .56f))
    drawCircle(Color(0xFFD9708F), s * .025f, Offset(s * .86f, s * .55f))
    listOf(.28f, .6f).forEach { x -> drawRoundRect(pink, Offset(s * x, s * .74f), Size(s * .1f, s * .14f), CornerRadius(s * .03f)) }
    drawCircle(INK, s * .03f, Offset(s * .66f, s * .5f))
    drawRoundRect(Color(0xFFB0476A), Offset(s * .38f, s * .36f), Size(s * .16f, s * .035f), CornerRadius(s * .02f))
    drawCircle(Color(0xFFFFC83D), s * .1f, Offset(s * .46f, s * .2f))
    drawCircle(Color(0xFFE0A820), s * .1f, Offset(s * .46f, s * .2f), style = Stroke(s * .025f))
}

/** Huddle: two speech bubbles, talking it over. */
private fun DrawScope.speechBubbles() {
    val s = size.minDimension
    drawRoundRect(Color(0xFF6F5AA8), Offset(s * .06f, s * .14f), Size(s * .56f, s * .38f), CornerRadius(s * .14f))
    drawPath(Path().apply { moveTo(s * .16f, s * .48f); lineTo(s * .12f, s * .62f); lineTo(s * .3f, s * .5f); close() }, Color(0xFF6F5AA8))
    drawRoundRect(Color(0xFF59B7F0), Offset(s * .38f, s * .42f), Size(s * .56f, s * .36f), CornerRadius(s * .14f))
    drawPath(Path().apply { moveTo(s * .84f, s * .74f); lineTo(s * .9f, s * .9f); lineTo(s * .7f, s * .76f); close() }, Color(0xFF59B7F0))
    listOf(.22f, .33f, .44f).forEach { x -> drawCircle(Color.White, s * .035f, Offset(s * x, s * .33f)) }
    listOf(.55f, .66f, .77f).forEach { x -> drawCircle(Color.White, s * .035f, Offset(s * x, s * .6f)) }
}

/** Corkboard: the family's TV, with a red pin in the corner. */
private fun DrawScope.tvWithPin() {
    val s = size.minDimension
    drawRoundRect(INK, Offset(s * .06f, s * .16f), Size(s * .88f, s * .58f), CornerRadius(s * .08f))
    drawRoundRect(Color(0xFFFFE3A3), Offset(s * .12f, s * .22f), Size(s * .76f, s * .46f), CornerRadius(s * .04f))
    drawRoundRect(Color(0xFF6F5AA8), Offset(s * .18f, s * .3f), Size(s * .3f, s * .06f), CornerRadius(s * .03f))
    drawRoundRect(Color(0xFF3DBE6A), Offset(s * .18f, s * .42f), Size(s * .46f, s * .05f), CornerRadius(s * .03f))
    drawRoundRect(Color(0xFF59B7F0), Offset(s * .18f, s * .52f), Size(s * .36f, s * .05f), CornerRadius(s * .03f))
    drawRoundRect(INK, Offset(s * .38f, s * .76f), Size(s * .24f, s * .06f), CornerRadius(s * .03f))
    drawCircle(Color(0xFFE5533D), s * .07f, Offset(s * .82f, s * .2f))
    drawCircle(Color.White.copy(alpha = .6f), s * .02f, Offset(s * .8f, s * .18f))
}

/** Steward: a friendly yellow sparkle in a purple circle. */
private fun DrawScope.helperStar() {
    val s = size.minDimension
    drawCircle(Color(0xFF6F5AA8), s * .44f, Offset(s * .5f, s * .5f))
    fun sparkle(cx: Float, cy: Float, r: Float) {
        val p = Path().apply {
            moveTo(s * cx, s * (cy - r)); quadraticTo(s * cx, s * cy, s * (cx + r), s * cy)
            quadraticTo(s * cx, s * cy, s * cx, s * (cy + r)); quadraticTo(s * cx, s * cy, s * (cx - r), s * cy)
            quadraticTo(s * cx, s * cy, s * cx, s * (cy - r)); close()
        }
        drawPath(p, Color(0xFFFFC83D))
    }
    sparkle(.46f, .52f, .26f)
    sparkle(.72f, .28f, .11f)
}

/** Pets: a paw print. */
private fun DrawScope.paw() {
    val s = size.minDimension
    val c = Color(0xFFB0773F)
    drawOval(c, Offset(s * .27f, s * .46f), Size(s * .46f, s * .4f))
    listOf(Offset(.2f, .36f), Offset(.38f, .2f), Offset(.62f, .2f), Offset(.8f, .36f)).forEach { o -> drawOval(c, Offset(s * (o.x - .09f), s * (o.y - .11f)), Size(s * .18f, s * .22f)) }
}

/** Contacts: an address book with a tab. */
private fun DrawScope.addressBook() {
    val s = size.minDimension
    drawRoundRect(Color(0xFF3DBE6A), Offset(s * .18f, s * .08f), Size(s * .62f, s * .84f), CornerRadius(s * .08f))
    drawRoundRect(Color.White, Offset(s * .28f, s * .18f), Size(s * .44f, s * .64f), CornerRadius(s * .05f))
    drawCircle(Color(0xFFF5C28B), s * .08f, Offset(s * .5f, s * .36f))
    drawArc(Color(0xFF6F5AA8), 180f, 180f, true, Offset(s * .38f, s * .44f), Size(s * .24f, s * .16f))
    drawRoundRect(INK.copy(alpha = .4f), Offset(s * .36f, s * .64f), Size(s * .28f, s * .04f), CornerRadius(s * .02f))
    listOf(.24f, .42f, .6f).forEach { y -> drawRoundRect(Color(0xFFFFC83D), Offset(s * .78f, s * y), Size(s * .1f, s * .12f), CornerRadius(s * .03f)) }
}

/** Feed: a little newspaper. */
private fun DrawScope.newspaper() {
    val s = size.minDimension
    drawRoundRect(Color(0xFFF7F3EA), Offset(s * .1f, s * .16f), Size(s * .8f, s * .68f), CornerRadius(s * .06f))
    drawRoundRect(INK.copy(alpha = .3f), Offset(s * .1f, s * .16f), Size(s * .8f, s * .68f), CornerRadius(s * .06f), style = Stroke(s * .03f))
    drawRoundRect(Color(0xFFE5533D), Offset(s * .18f, s * .24f), Size(s * .64f, s * .1f), CornerRadius(s * .02f))
    drawRoundRect(Color(0xFF59B7F0), Offset(s * .18f, s * .42f), Size(s * .26f, s * .32f), CornerRadius(s * .03f))
    for (i in 0..3) drawRoundRect(INK.copy(alpha = .4f), Offset(s * .5f, s * (.43f + i * .085f)), Size(s * .32f, s * .04f), CornerRadius(s * .02f))
}

/** Today: a bright sun. */
private fun DrawScope.sunny() {
    val s = size.minDimension
    val c = Offset(s * .5f, s * .5f)
    for (i in 0 until 8) rotate(i * 45f, c) { drawRoundRect(Color(0xFFFFB020), Offset(s * .47f, s * .04f), Size(s * .06f, s * .16f), CornerRadius(s * .03f)) }
    drawCircle(Color(0xFFFFC83D), s * .25f, c)
}

/** Weather: the sun peeking out from behind a cloud. */
private fun DrawScope.sunAndCloud() {
    val s = size.minDimension
    drawCircle(Color(0xFFFFC83D), s * .22f, Offset(s * .62f, s * .36f))
    val cloud = Color(0xFFDCE6F2)
    drawCircle(cloud, s * .16f, Offset(s * .34f, s * .6f))
    drawCircle(cloud, s * .2f, Offset(s * .52f, s * .54f))
    drawCircle(cloud, s * .14f, Offset(s * .7f, s * .64f))
    drawRoundRect(cloud, Offset(s * .18f, s * .6f), Size(s * .66f, s * .18f), CornerRadius(s * .09f))
}

/** Clock: a round alarm clock with two bells. */
private fun DrawScope.alarmClock() {
    val s = size.minDimension
    listOf(.2f, .8f).forEach { x -> drawCircle(Color(0xFFE5533D), s * .11f, Offset(s * x, s * .2f)) }
    drawCircle(Color(0xFFE5533D), s * .38f, Offset(s * .5f, s * .54f))
    drawCircle(Color.White, s * .3f, Offset(s * .5f, s * .54f))
    drawLine(INK, Offset(s * .5f, s * .54f), Offset(s * .5f, s * .34f), s * .05f, cap = StrokeCap.Round)
    drawLine(INK, Offset(s * .5f, s * .54f), Offset(s * .64f, s * .62f), s * .05f, cap = StrokeCap.Round)
}

/** Countdown: an hourglass with sand running. */
private fun DrawScope.hourglass() {
    val s = size.minDimension
    val wood = Color(0xFFB0773F)
    drawRoundRect(wood, Offset(s * .2f, s * .08f), Size(s * .6f, s * .08f), CornerRadius(s * .03f))
    drawRoundRect(wood, Offset(s * .2f, s * .84f), Size(s * .6f, s * .08f), CornerRadius(s * .03f))
    val glass = Path().apply { moveTo(s * .28f, s * .16f); lineTo(s * .72f, s * .16f); lineTo(s * .52f, s * .5f); lineTo(s * .72f, s * .84f); lineTo(s * .28f, s * .84f); lineTo(s * .48f, s * .5f); close() }
    drawPath(glass, Color(0xFFDCEFFF))
    drawPath(Path().apply { moveTo(s * .36f, s * .26f); lineTo(s * .64f, s * .26f); lineTo(s * .5f, s * .46f); close() }, Color(0xFFFFC83D))
    drawPath(Path().apply { moveTo(s * .34f, s * .84f); lineTo(s * .66f, s * .84f); lineTo(s * .5f, s * .66f); close() }, Color(0xFFFFC83D))
    drawLine(Color(0xFFFFC83D), Offset(s * .5f, s * .48f), Offset(s * .5f, s * .7f), s * .02f)
}

/** Sticky note: a yellow note with a red pin. */
private fun DrawScope.stickyNote() {
    val s = size.minDimension
    rotate(-6f, Offset(s * .5f, s * .5f)) {
        drawRoundRect(Color(0xFFFFE07A), Offset(s * .14f, s * .14f), Size(s * .72f, s * .72f), CornerRadius(s * .04f))
        for (i in 0..2) drawRoundRect(Color(0xFFC9A93A), Offset(s * .24f, s * (.4f + i * .13f)), Size(s * (.52f - i * .12f), s * .04f), CornerRadius(s * .02f))
    }
    drawCircle(Color(0xFFE5533D), s * .07f, Offset(s * .5f, s * .18f))
}

/** Quick add: a list with a green plus. */
private fun DrawScope.plusOnList() {
    val s = size.minDimension
    drawRoundRect(Color(0xFFF7F3EA), Offset(s * .1f, s * .12f), Size(s * .6f, s * .72f), CornerRadius(s * .06f))
    for (i in 0..3) drawRoundRect(INK.copy(alpha = .4f), Offset(s * .18f, s * (.24f + i * .14f)), Size(s * .42f, s * .045f), CornerRadius(s * .02f))
    drawCircle(Color(0xFF3DBE6A), s * .2f, Offset(s * .72f, s * .72f))
    drawLine(Color.White, Offset(s * .72f, s * .62f), Offset(s * .72f, s * .82f), s * .06f, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(s * .62f, s * .72f), Offset(s * .82f, s * .72f), s * .06f, cap = StrokeCap.Round)
}

/** Quiet time: a sleepy crescent moon and a little z. */
private fun DrawScope.sleepyMoon() {
    val s = size.minDimension
    val moon = Path().apply {
        op(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(s * .44f, s * .56f), s * .34f)) },
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(s * .62f, s * .44f), s * .28f)) },
            androidx.compose.ui.graphics.PathOperation.Difference)
    }
    drawPath(moon, Color(0xFF90A4EE))
    val z = Path().apply { moveTo(s * .66f, s * .12f); lineTo(s * .86f, s * .12f); lineTo(s * .66f, s * .32f); lineTo(s * .86f, s * .32f) }
    drawPath(z, Color(0xFF6F5AA8), style = Stroke(s * .05f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** Who's home: a house with two little faces in the door and window. */
private fun DrawScope.houseWithFaces() {
    val s = size.minDimension
    drawRoundRect(Color(0xFFFFE3A3), Offset(s * .14f, s * .44f), Size(s * .72f, s * .46f), CornerRadius(s * .03f))
    drawPath(Path().apply { moveTo(s * .04f, s * .5f); lineTo(s * .5f, s * .1f); lineTo(s * .96f, s * .5f); close() }, Color(0xFFE5533D))
    drawCircle(Color(0xFF6F5AA8), s * .12f, Offset(s * .34f, s * .66f))
    drawCircle(Color(0xFF3DBE6A), s * .12f, Offset(s * .66f, s * .66f))
    listOf(.34f, .66f).forEach { x ->
        drawCircle(Color.White, s * .02f, Offset(s * (x - .04f), s * .64f)); drawCircle(Color.White, s * .02f, Offset(s * (x + .04f), s * .64f))
        drawArc(Color.White, 20f, 140f, false, Offset(s * (x - .05f), s * .64f), Size(s * .1f, s * .07f), style = Stroke(s * .02f, cap = StrokeCap.Round))
    }
}
