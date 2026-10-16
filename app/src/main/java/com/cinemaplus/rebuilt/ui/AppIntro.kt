package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** The mark is drawn in the coordinates of the launcher artwork, then scaled to fit. */
private const val MarkLeft = 130f
private const val MarkTop = 200f
private const val MarkWidth = 880f
private const val MarkHeight = 560f
private val IntroPlum = Color(0xFF241B29)
private val Peach = Color(0xFFFFE0C0)
private val Cream = Color(0xFFFFF3E6)
private val Overshoot = CubicBezierEasing(.2f, 1.4f, .4f, 1f)

/**
 * Opening animation, about 3.7 seconds: the arc draws itself, the letters and play mark pop in, the name fades up
 * with a light sweep, then everything fades to black and the app opens. It always plays in full and never waits
 * for a key.
 */
@Composable
fun AppIntro(onFinished: () -> Unit) {
    val arc = remember { Animatable(0f) }
    val pops = remember { List(4) { Animatable(0f) } }
    val glow = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            async { delay(200); arc.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
            listOf(1000L, 1150L, 1300L, 1550L).forEachIndexed { i, at ->
                async { delay(at); pops[i].animateTo(1f, tween(450, easing = Overshoot)) }
            }
            async { delay(1200); glow.animateTo(1f, tween(1400)) }
            async { delay(1900); name.animateTo(1f, tween(600)) }
            async { delay(2200); tagline.animateTo(1f, tween(600)) }
            async { delay(2300); sweep.animateTo(1f, tween(900)) }
            async { delay(3200); fade.animateTo(1f, tween(500)) }
        }
        onFinished()
    }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        0f to Color(0xFF3A2538), .55f to IntroPlum, 1f to Color(0xFF15101A)))) {
        // Warm glow behind the mark.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = glow.value }) {
            drawCircle(Brush.radialGradient(listOf(Color(0x4DFF8A2B), Color.Transparent),
                center = Offset(size.width / 2, size.height * .45f), radius = size.minDimension * .5f),
                radius = size.minDimension * .5f, center = Offset(size.width / 2, size.height * .45f))
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val logoWidth = minOf(300.dp, maxWidth * .6f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(logoWidth, logoWidth * (MarkHeight / MarkWidth))) {
                    drawMark(size.width / MarkWidth, arc.value, pops.map { it.value })
                }
                Spacer(Modifier.height(6.dp))
                Text("سينما آي كيو", Modifier.graphicsLayer { alpha = name.value; translationY = (1f - name.value) * 14.dp.toPx() },
                    color = Cream, fontFamily = Cairo, fontWeight = FontWeight.Bold, fontSize = 46.sp)
                Text("CINEMA iQ", Modifier.graphicsLayer { alpha = tagline.value; translationY = (1f - tagline.value) * 14.dp.toPx() },
                    color = Color(0xFFFFB470), fontFamily = Cairo, fontSize = 18.sp, letterSpacing = 5.sp)
            }
        }
        // The developer's name sits at the bottom and appears with the tagline.
        Text("تطوير Emad Hasnawi", Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).graphicsLayer { alpha = tagline.value * .8f },
            color = Color(0xFFB5A9BC), fontFamily = Cairo, fontSize = 15.sp)
        // A soft band of light passes over once the name is up.
        Canvas(Modifier.fillMaxSize()) {
            val x = (-size.width + 2 * size.width * sweep.value)
            if (sweep.value in 0.01f..0.99f) drawRect(Brush.horizontalGradient(
                listOf(Color.Transparent, Color(0x1AFFFFFF), Color.Transparent),
                startX = x + size.width * .40f, endX = x + size.width * .60f))
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.Black))
    }
}

private fun DrawScope.drawMark(scale: Float, arc: Float, pops: List<Float>) {
    fun p(x: Float, y: Float) = Offset((x - MarkLeft) * scale, (y - MarkTop) * scale)
    // The arc: a circle of radius 330 centred at (516, 540), open on the right, drawn counter-clockwise.
    val radius = 330f * scale
    val centre = p(516.1f, 540f)
    drawArc(Brush.linearGradient(listOf(Color(0xFFFFB470), Color(0xFFFF7A1A)), p(210f, 210f), p(850f, 870f)),
        startAngle = -39.97f, sweepAngle = -280.06f * arc, useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(120f * scale))
    fun popped(index: Int, centre: Offset, block: DrawScope.(Float) -> Unit) {
        val t = pops[index]
        if (t <= 0f) return
        withTransform({ scale(.4f + .6f * t, .4f + .6f * t, centre) }) { block(t.coerceIn(0f, 1f)) }
    }
    // "i"
    popped(0, p(376f, 546f)) { a ->
        drawLine(Peach.copy(alpha = a), p(376f, 542f), p(376f, 622f), 44f * scale, StrokeCap.Round)
        drawCircle(Peach.copy(alpha = a), 28f * scale, p(376f, 470f))
    }
    // ring of the "Q"
    popped(1, p(540f, 552f)) { a ->
        drawCircle(Peach.copy(alpha = a), 82f * scale, p(540f, 552f), style = Stroke(40f * scale))
    }
    // tail of the "Q"
    popped(2, p(611f, 625f)) { a ->
        drawLine(Color(0xFFFF8A2B).copy(alpha = a), p(582f, 596f), p(640f, 654f), 42f * scale, StrokeCap.Round)
    }
    // play mark
    popped(3, p(890f, 540f)) { a ->
        val triangle = Path().apply {
            moveTo(p(826f, 440f).x, p(826f, 440f).y); lineTo(p(826f, 640f).x, p(826f, 640f).y)
            lineTo(p(936f, 540f).x, p(936f, 540f).y); close()
        }
        drawPath(triangle, Cream.copy(alpha = a), style = Fill)
        drawPath(triangle, Cream.copy(alpha = a), style = Stroke(36f * scale, join = StrokeJoin.Round))
    }
}
