package io.github.pretendgineering.famlogic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Every SOS label: a plain bold sans, so it reads as an alarm, not part of the page's serif voice. */
val SosType = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)

/**
 * FamLogic's own icons, where Material has nothing that fits. Same 24-unit grid and
 * weight as the console's icons, so the app and console look alike.
 */
object FamIcons {
    /**
     * SOS: a round beacon with an exclamation mark, sending out signal waves. Soft
     * and round to match the rest of the app, but still unmistakably "alert".
     */
    val Sos: ImageVector by lazy {
        ImageVector.Builder("Sos", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // The beacon.
                moveTo(12f, 6.2f)
                arcToRelative(5.8f, 5.8f, 0f, true, true, 0f, 11.6f)
                arcToRelative(5.8f, 5.8f, 0f, true, true, 0f, -11.6f)
                close()
                // The exclamation mark, cut out of it.
                moveTo(11.05f, 9.1f)
                arcToRelative(0.95f, 0.95f, 0f, false, true, 1.9f, 0f)
                lineTo(12.75f, 12.6f)
                arcToRelative(0.75f, 0.75f, 0f, false, true, -1.5f, 0f)
                close()
                moveTo(12f, 13.9f)
                arcToRelative(1.05f, 1.05f, 0f, true, true, 0f, 2.1f)
                arcToRelative(1.05f, 1.05f, 0f, true, true, 0f, -2.1f)
                close()
            }
            // Signal waves either side.
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(18.9f, 8.2f); arcTo(8f, 8f, 0f, false, true, 18.9f, 15.8f)
                moveTo(5.1f, 15.8f); arcTo(8f, 8f, 0f, false, true, 5.1f, 8.2f)
                moveTo(21.4f, 6.4f); arcTo(11f, 11f, 0f, false, true, 21.4f, 17.6f)
                moveTo(2.6f, 17.6f); arcTo(11f, 11f, 0f, false, true, 2.6f, 6.4f)
            }
        }.build()
    }

    /**
     * Upkeep: a little to-do scroll. A sheet with a ticked line and two to go,
     * rolled at the top and bottom, the rolls offset so it seems to float.
     */
    val Upkeep: ImageVector by lazy {
        ImageVector.Builder("Upkeep", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Top roll, a little to the left, curling under at its left end.
                moveTo(17f, 3.6f); lineTo(6.6f, 3.6f)
                arcTo(1.6f, 1.6f, 0f, false, false, 6.6f, 6.8f)
                lineTo(7.6f, 6.8f)
                // The sheet.
                moveTo(7.6f, 5f); lineTo(7.6f, 17.2f)
                moveTo(17f, 3.6f); lineTo(17f, 17.2f)
                // Bottom roll, a little to the right, curling under at its right end.
                moveTo(7f, 20.4f); lineTo(17.4f, 20.4f)
                arcTo(1.6f, 1.6f, 0f, false, false, 17.4f, 17.2f)
                lineTo(7.6f, 17.2f)
                // The list: a tick and two lines.
                moveTo(9.9f, 9f); lineTo(10.8f, 9.9f); lineTo(12.4f, 8.2f)
                moveTo(13.6f, 9.1f); lineTo(14.8f, 9.1f)
                moveTo(9.9f, 12.2f); lineTo(14.8f, 12.2f)
                moveTo(9.9f, 15f); lineTo(13.2f, 15f)
            }
        }.build()
    }
}
