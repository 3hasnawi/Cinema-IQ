package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared live-TV surfaces stay translucent without blurring the decoder or foreground text. */
internal object IptvTvGlass {
    val Focus = Color(0xFFCC4165)
    val Hairline = Color.White.copy(alpha = .12f)
    val PanelBrush = Brush.linearGradient(listOf(
        Color(0xFF2B3442).copy(alpha = .24f),
        Color(0xFF1F252E).copy(alpha = .60f),
        Color(0xFF181D25).copy(alpha = .78f)
    ))
    val HudBrush = Brush.verticalGradient(listOf(
        Color(0xFF272F3B).copy(alpha = .28f),
        Color(0xFF181D25).copy(alpha = .72f)
    ))
    val FocusedBrush = Brush.horizontalGradient(listOf(Focus.copy(alpha = .14f), Focus.copy(alpha = .42f)))
    val QuietRowBrush = Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF181D25).copy(alpha = .14f)))

    // Guide gradients use physical screen coordinates: their left edge dissolves into the picture in RTL too.
    val GuideBrush = Brush.horizontalGradient(
        0f to Color(0xFF0B141E).copy(alpha = 0f),
        .15f to Color(0xFF0B141E).copy(alpha = .24f),
        .38f to Color(0xFF0B141E).copy(alpha = .75f),
        .62f to Color(0xFF0B141E).copy(alpha = .91f),
        1f to Color(0xFF0B141E).copy(alpha = .97f)
    )
    val GuideRowFocusedBrush = Brush.horizontalGradient(
        0f to Color(0xFFDC5273).copy(alpha = 0f),
        .48f to Color(0xFFDC5273).copy(alpha = .18f),
        1f to Color(0xFFDC5273).copy(alpha = .29f)
    )
    val GuideCategoryFocusedBrush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .125f)))
    val GuideRowQuietBrush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    val GuidePlaying = Color(0xFFF1A6B8)
}

@Composable internal fun IptvTvGlassPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.clip(shape).background(IptvTvGlass.PanelBrush).border(.75.dp, IptvTvGlass.Hairline, shape), content = content)
}
