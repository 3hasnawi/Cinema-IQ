package com.cinemaplus.rebuilt.ui.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.Player
import com.cinemaplus.rebuilt.ui.Accent
import com.cinemaplus.rebuilt.ui.Label
import com.cinemaplus.rebuilt.ui.Muted
import com.cinemaplus.rebuilt.ui.OnAccent
import com.cinemaplus.rebuilt.ui.PlayerTransportIcon
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Touch versions of the TV player controls: the same round glass buttons, thin gold scrubber, small badges and the
 * panel that grows out of the button which opened it. The TV player files are not touched.
 */

/** Translucent panel colour shared by the option popups. */
private val PanelGlass = Color(0xD9170F1C)

/** The panels that open above a control button. */
enum class MobilePanel { Quality, Tracks, SubtitleStyle }

/** hh:mm:ss like the TV player. */
fun formatMobileTime(value: Long): String {
    val seconds = value.coerceAtLeast(0L) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

/** Position for a touch at [x] on a bar [width] pixels wide. */
fun mobileSeekTarget(x: Float, width: Int, duration: Long): Long =
    if (duration <= 0 || width <= 0) 0L else ((x / width).coerceIn(0f, 1f) * duration).toLong()

/** Round glass button; one size for every control so the rows share a centre line (larger for play). */
@Composable
fun MobileRoundControl(
    label: String, symbol: String, modifier: Modifier = Modifier, main: Boolean = false, enabled: Boolean = true,
    active: Boolean = false, onPlaced: (IntRect) -> Unit = {}, onClick: () -> Unit
) {
    val size = if (main) 60.dp else 44.dp
    val container = if (active) Accent else Color.White.copy(alpha = if (enabled) .20f else .08f)
    val tint = when { !enabled -> Color.White.copy(alpha = .35f); active -> OnAccent; else -> Color.White }
    Box(modifier.size(size)
        .onGloballyPositioned { box ->
            val r = box.boundsInWindow()
            onPlaced(IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
        }
        .clip(CircleShape).background(container)
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        PlayerTransportIcon(symbol, tint, if (main) 28.dp else 20.dp)
    }
}

/** Small outlined tag (quality, channels) under the title. */
@Composable
fun MobileBadge(text: String) {
    Label(text, 10, Color.White.copy(alpha = .85f), true,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = .4f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp))
}

/**
 * Thin gold scrubber. Tap to jump, drag to scrub; the video seeks when the finger lifts. It reads the player
 * itself, so only this bar recomposes while the position moves.
 */
@Composable
fun MobileSeekBar(player: Player, onScrubbing: (Boolean) -> Unit, onInteract: () -> Unit, modifier: Modifier = Modifier) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var seekable by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player) {
        while (true) {
            if (preview == null) position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            buffered = player.bufferedPosition.coerceAtLeast(0L)
            seekable = MobilePlayerActions.canSeek(player)
            delay(250)
        }
    }
    val shown = preview ?: position
    val previewing = preview != null
    Column(modifier.semantics { contentDescription = "موضع الفيديو: ${formatMobileTime(shown)} من ${formatMobileTime(duration)}" }) {
        // A fixed slot for the time bubble keeps the layout still while scrubbing.
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
            if (previewing) Box(Modifier.background(PanelGlass, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 2.dp)) {
                Label(formatMobileTime(shown), 12, Color.White, true)
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val thickness = if (previewing) 7.dp else 4.dp
            BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(duration, seekable) {
                    detectTapGestures { offset ->
                        if (seekable && MobilePlayerActions.canSeek(player)) {
                            onInteract()
                            val target = mobileSeekTarget(offset.x, size.width, duration)
                            player.seekTo(target); position = target
                        }
                    }
                }
                .pointerInput(duration, seekable) {
                    if (!seekable) return@pointerInput
                    try { detectHorizontalDragGestures(
                        onDragStart = { offset -> onScrubbing(true); preview = mobileSeekTarget(offset.x, size.width, duration) },
                        onHorizontalDrag = { change, _ -> change.consume(); onInteract(); preview = mobileSeekTarget(change.position.x, size.width, duration) },
                        onDragEnd = { preview?.let {
                            if (MobilePlayerActions.canSeek(player)) {
                                player.seekTo(it.coerceIn(0L, player.duration)); position = it
                            }
                        }; preview = null; onScrubbing(false) },
                        onDragCancel = { preview = null; onScrubbing(false) })
                    } finally { preview = null; onScrubbing(false) }
                }, contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(thickness).clip(CircleShape).background(Color.White.copy(alpha = .22f)))
                Box(Modifier.fillMaxWidth(bufferedFraction).height(thickness).clip(CircleShape).background(Color.White.copy(alpha = .38f)))
                Box(Modifier.fillMaxWidth(fraction).height(thickness).clip(CircleShape).background(Accent))
                Box(Modifier.offset(x = (maxWidth * fraction) - 6.dp).size(12.dp).background(Color.White, CircleShape))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label(formatMobileTime(shown), 11, Color.White.copy(alpha = .85f), true)
                Label("-${if (duration > 0) formatMobileTime((duration - shown).coerceAtLeast(0L)) else "—"}", 11, Color.White.copy(alpha = .85f), true)
            }
        }
    }
}

/** One selectable row in an option panel. */
@Composable
fun MobileOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Label(text, 15, Color.White, selected, lines = 1, modifier = Modifier.weight(1f))
        if (selected) Label("✓", 16, Accent, true)
    }
}

/** Panel that grows out of the button that opened it, sized to its content; a tap outside closes it. */
@Composable
fun MobileOptionsPopup(title: String, anchor: IntRect, width: Dp, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var entered by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 12.dp.roundToPx() }
    val windowWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val panelStart = (anchor.center.x - widthPx / 2).coerceIn(margin, (windowWidth - widthPx - margin).coerceAtLeast(margin))
    val originX = if (anchor == IntRect.Zero) .5f else ((anchor.center.x - panelStart).toFloat() / widthPx).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(20.dp)
    LaunchedEffect(Unit) { entered = true }
    Popup(popupPositionProvider = remember(anchor, widthPx, gap, margin) { MobileAboveAnchor(anchor, widthPx, gap, margin) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(entered,
            enter = fadeIn(tween(140)) + scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = .3f,
                transformOrigin = TransformOrigin(originX, 1f)) +
                expandVertically(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(100))) {
            Column(Modifier.width(width).heightIn(max = 230.dp)
                .background(PanelGlass, shape).border(1.dp, Color.White.copy(alpha = .10f), shape)
                .padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(title, 13, Muted, true, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
                content()
            }
        }
    }
}

/** Places a panel directly above its button, centred on it and kept inside the window. */
private class MobileAboveAnchor(private val anchor: IntRect, private val widthPx: Int, private val gap: Int, private val margin: Int) :
    PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val a = if (anchor == IntRect.Zero) anchorBounds else anchor
        val x = (a.center.x - widthPx / 2).coerceIn(margin, (windowSize.width - widthPx - margin).coerceAtLeast(margin))
        val y = (a.top - popupContentSize.height - gap).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}
