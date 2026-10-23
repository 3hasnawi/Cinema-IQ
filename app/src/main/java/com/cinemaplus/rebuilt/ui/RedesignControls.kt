package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Buttons of the redesign, with the focus rules of the design brief:
 *  - primary: white pill with dark text; focused it grows 4% and gets an accent ring behind a dark gap;
 *  - secondary: glass fill and stroke with white content; focused it turns solid white with dark content.
 */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    primary: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 72.mx,
    textSize: Int = if (primary) 15 else 14,
    cornerRadius: Dp? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && primary) 1.04f else 1f, tween(140), label = "Pill focus")
    val radius = cornerRadius ?: (height / 2)
    val shape = RoundedCornerShape(radius)
    val light = primary || focused
    val content = if (light) Brand.OnPrimary else Color.White
    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (primary) Modifier.focusRingBehind(focused, radius) else Modifier)
            .height(height)
            .background(if (light) Color.White else Brand.GlassFill, shape)
            .then(if (light) Modifier else Modifier.border(1.dp, Brand.GlassStroke, shape))
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = if (primary) 44.mx else 32.mx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.mx)
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(26.mx), tint = content)
        Label(text, textSize, content, true, lines = 1)
    }
}

/** Round glass button (my list, trailer…); white with a dark glyph when focused. */
@Composable
fun CircleButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 76.mx,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "Circle focus")
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .background(if (focused) Color.White else Brand.GlassFill, CircleShape)
            .then(if (focused) Modifier else Modifier.border(1.dp, Brand.GlassStroke, CircleShape))
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(size * .42f), tint = if (focused) Brand.OnPrimary else Color.White)
    }
}

/** Accent ring with a dark gap around a focused primary control, drawn behind it (no layout change). */
fun Modifier.focusRingBehind(focused: Boolean, radius: Dp, gap: Dp = 4.mx, ring: Dp = 4.mx): Modifier =
    if (!focused) this else drawBehind {
        val g = gap.toPx(); val r = ring.toPx()
        val outer = g + r / 2
        drawRoundRect(Brand.Background, Offset(-g / 2, -g / 2), Size(size.width + g, size.height + g),
            CornerRadius(radius.toPx() + g / 2), style = Stroke(g))
        drawRoundRect(Brand.Accent, Offset(-outer, -outer), Size(size.width + outer * 2, size.height + outer * 2),
            CornerRadius(radius.toPx() + outer), style = Stroke(r))
    }

/** Small chip of the meta line: outlined (age) or glass (quality). */
@Composable
fun MetaChip(text: String, glass: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    val shape = RoundedCornerShape(6.mx)
    Box(modifier
        .then(if (glass) Modifier.background(Brand.Background.copy(alpha = .8f), shape) else Modifier)
        .border(1.dp, if (glass) Brand.GlassStroke else Color.White.copy(alpha = .3f), shape)
        .padding(horizontal = 8.mx)) {
        Label(text, if (glass) 9 else 10, Color.White, glass, lines = 1)
    }
}

/** Accent chip such as the kind of a work. */
@Composable
fun AccentChip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Brand.Accent, RoundedCornerShape(6.mx)).padding(horizontal = 12.mx, vertical = 2.mx)) {
        Label(text, 10, Brand.OnAccent, true, lines = 1)
    }
}
