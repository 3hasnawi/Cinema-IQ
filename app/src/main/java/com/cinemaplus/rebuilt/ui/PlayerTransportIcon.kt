package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One consistent vector icon set for the player: same 28dp box, same stroke width and round caps.
 * Symbols are kept as the existing string keys so call sites do not change.
 */
@Composable
internal fun PlayerTransportIcon(symbol: String, tint: Color, iconSize: Dp = 28.dp) {
    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(iconSize)) {
            val w = size.width
            val stroke = Stroke(w * .085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (symbol) {
                "▶" -> triangle(tint, w * .30f, w * .20f, w * .80f)
                "Ⅱ" -> {
                    drawRoundRect(tint, Offset(w * .26f, w * .20f), Size(w * .17f, w * .60f), CornerRadius(w * .05f))
                    drawRoundRect(tint, Offset(w * .57f, w * .20f), Size(w * .17f, w * .60f), CornerRadius(w * .05f))
                }
                "▶|" -> {
                    triangle(tint, w * .22f, w * .22f, w * .66f)
                    drawRoundRect(tint, Offset(w * .70f, w * .22f), Size(w * .09f, w * .56f), CornerRadius(w * .04f))
                }
                "↶10", "10↷" -> skip(tint, stroke, forward = symbol == "10↷")
                "☷" -> for (i in 0..2) {
                    val y = w * (.28f + i * .22f)
                    drawCircle(tint, w * .045f, Offset(w * .22f, y))
                    drawLine(tint, Offset(w * .36f, y), Offset(w * .80f, y), w * .085f, StrokeCap.Round)
                }
                "CC" -> {
                    drawRoundRect(tint, Offset(w * .10f, w * .20f), Size(w * .80f, w * .60f), CornerRadius(w * .12f), style = stroke)
                    drawLine(tint, Offset(w * .24f, w * .46f), Offset(w * .42f, w * .46f), w * .085f, StrokeCap.Round)
                    drawLine(tint, Offset(w * .52f, w * .46f), Offset(w * .76f, w * .46f), w * .085f, StrokeCap.Round)
                    drawLine(tint, Offset(w * .24f, w * .62f), Offset(w * .58f, w * .62f), w * .085f, StrokeCap.Round)
                    drawLine(tint, Offset(w * .68f, w * .62f), Offset(w * .76f, w * .62f), w * .085f, StrokeCap.Round)
                }
                // Subtitle style: a large and a small letter "a".
                "Aa" -> {
                    val big = Path().apply {
                        moveTo(w * .10f, w * .76f); lineTo(w * .31f, w * .24f); lineTo(w * .52f, w * .76f)
                    }
                    drawPath(big, tint, style = stroke)
                    drawLine(tint, Offset(w * .18f, w * .60f), Offset(w * .44f, w * .60f), w * .085f, StrokeCap.Round)
                    drawCircle(tint, w * .11f, Offset(w * .72f, w * .62f), style = stroke)
                    drawLine(tint, Offset(w * .83f, w * .50f), Offset(w * .83f, w * .76f), w * .085f, StrokeCap.Round)
                }
                // Quality: three slider tracks with knobs.
                "HD" -> listOf(.28f to .64f, .50f to .34f, .72f to .56f).forEach { (y, knob) ->
                    drawLine(tint, Offset(w * .14f, w * y), Offset(w * .86f, w * y), w * .085f, StrokeCap.Round)
                    drawCircle(tint, w * .115f, Offset(w * knob, w * y))
                }
                // Scissors: two rings on the left, blades crossing to the right.
                "✂" -> {
                    drawCircle(tint, w * .11f, Offset(w * .24f, w * .30f), style = stroke)
                    drawCircle(tint, w * .11f, Offset(w * .24f, w * .70f), style = stroke)
                    drawLine(tint, Offset(w * .33f, w * .35f), Offset(w * .86f, w * .72f), w * .085f, StrokeCap.Round)
                    drawLine(tint, Offset(w * .33f, w * .65f), Offset(w * .86f, w * .28f), w * .085f, StrokeCap.Round)
                }
                "×" -> {
                    drawLine(tint, Offset(w * .26f, w * .26f), Offset(w * .74f, w * .74f), w * .09f, StrokeCap.Round)
                    drawLine(tint, Offset(w * .74f, w * .26f), Offset(w * .26f, w * .74f), w * .09f, StrokeCap.Round)
                }
                else -> Unit
            }
        }
        if (symbol == "↶10" || symbol == "10↷") Label("10", 9, tint, true, lines = 1)
    }
}

/** Filled triangle with softly rounded corners, optically centered. */
private fun DrawScope.triangle(tint: Color, left: Float, top: Float, right: Float) {
    val h = size.width
    val path = Path().apply {
        moveTo(left, top); lineTo(right, h * .5f); lineTo(left, h - top); close()
    }
    drawPath(path, tint, style = Fill)
    drawPath(path, tint, style = Stroke(h * .06f, join = StrokeJoin.Round, cap = StrokeCap.Round))
}

/** 270 degree circular arrow used by the 10 second skip buttons. */
private fun DrawScope.skip(tint: Color, stroke: Stroke, forward: Boolean) {
    val w = size.width
    drawArc(tint, if (forward) -60f else -120f, if (forward) 300f else -300f, false,
        Offset(w * .12f, w * .16f), Size(w * .76f, w * .76f), style = stroke)
    val x = if (forward) w * .62f else w * .38f
    val d = if (forward) 1f else -1f
    val head = Path().apply {
        moveTo(x, w * .02f); lineTo(x + d * w * .17f, w * .16f); lineTo(x, w * .30f)
    }
    drawPath(head, tint, style = stroke)
}
