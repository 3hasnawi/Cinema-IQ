package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cinemaplus.rebuilt.data.iptv.IptvPopupPosition
import com.cinemaplus.rebuilt.ui.Brand
import com.cinemaplus.rebuilt.ui.Label

@Composable internal fun IptvTrackOption(label: String, modifier: Modifier = Modifier, compact: Boolean, click: () -> Unit) {
    if (!compact) { IptvButton(label, modifier, onClick = click); return }
    Button(click, modifier.fillMaxWidth().heightIn(min = 38.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = .2f),
            contentColor = Color.White, focusedContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { Label(label, 13, lines = 1) }
    }
}

/** Focus stays inside the popover; its caller restores the anchor only after the exit animation. */
@Composable internal fun IptvTrackPopup(title: String, anchor: Rect, first: FocusRequester,
    dismissed: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val density = LocalDensity.current
    val height = LocalView.current.rootView.height
    val maxHeight = with(density) { (height * .55f).toDp() }.coerceAtLeast(120.dp)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val transition = updateTransition(visible, label = "iptv-track-popup")
    val scale by transition.animateFloat(transitionSpec = {
        if (targetState) spring(dampingRatio = .85f, stiffness = 650f) else tween(130)
    }, label = "drop-scale") { if (it) 1f else .72f }
    val alpha by transition.animateFloat(transitionSpec = { tween(140) }, label = "drop-opacity") { if (it) 1f else 0f }
    var pivot by remember { mutableFloatStateOf(.5f) }
    val close = { visible.targetState = false; Unit }
    val currentDismiss by rememberUpdatedState(dismissed)
    LaunchedEffect(visible.isIdle, visible.currentState, visible.targetState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) currentDismiss()
    }
    val position = remember(anchor, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize): IntOffset {
                val margin = with(density) { 16.dp.roundToPx() }; val gap = with(density) { 5.dp.roundToPx() }
                val placed = IptvPopupPosition.above(windowSize.width, windowSize.height, anchor.left.toInt(),
                    anchor.top.toInt(), anchor.right.toInt(), popupContentSize.width, popupContentSize.height, margin, gap)
                pivot = placed.pivotX
                return IntOffset(placed.x, placed.y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = close,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true, clippingEnabled = false)) {
        val window = LocalWindowInfo.current
        var focused by remember { mutableStateOf(false) }
        LaunchedEffect(window.isWindowFocused) {
            if (window.isWindowFocused && !focused) repeat(24) {
                withFrameNanos { }
                if (runCatching { first.requestFocus() }.getOrDefault(false)) { focused = true; return@LaunchedEffect }
            }
        }
        val shape = remember(pivot, density) { DropShape(pivot, with(density) { 18.dp.toPx() }, with(density) { 9.dp.toPx() }) }
        Surface(Modifier.widthIn(min = 155.dp, max = 300.dp).width(IntrinsicSize.Max).heightIn(max = maxHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha; transformOrigin = TransformOrigin(pivot, 1f) },
            shape = shape, color = Brand.Surface.copy(alpha = .78f), border = BorderStroke(1.dp, Color.White.copy(alpha = .15f))) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(title, 13, Brand.TextTertiary, bold = true, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                content(close)
            }
        }
    }
}

private class DropShape(val pivot: Float, val radius: Float, val tail: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width; val bottom = size.height - tail; val r = radius.coerceAtMost(minOf(w, bottom) / 3)
        val tip = (w * pivot).coerceIn(r + tail, maxOf(r + tail, w - r - tail))
        return Outline.Generic(Path().apply {
            moveTo(r, 0f); lineTo(w - r, 0f); quadraticBezierTo(w, 0f, w, r)
            lineTo(w, bottom - r); quadraticBezierTo(w, bottom, w - r, bottom)
            lineTo(tip + tail, bottom); quadraticBezierTo(tip + tail / 2, bottom, tip, size.height)
            quadraticBezierTo(tip - tail / 2, bottom, tip - tail, bottom)
            lineTo(r, bottom); quadraticBezierTo(0f, bottom, 0f, bottom - r)
            lineTo(0f, r); quadraticBezierTo(0f, 0f, r, 0f); close()
        })
    }
}
