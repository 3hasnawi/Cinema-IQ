package com.cinemaplus.rebuilt.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colour tokens of the 2026 Cinema iQ redesign (design-tokens.json). */
object Brand {
    val Background = Color(0xFF181D25)
    val Surface = Color(0xFF1F252E)
    val Raised = Color(0xFF272E3A)
    val BorderStrong = Color(0xFF3E495B)
    val Divider = Color(0x1AFFFFFF)
    val GlassFill = Color(0x33FFFFFF)
    val GlassStroke = Color(0x26FFFFFF)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFD1D7E0)
    val TextTertiary = Color(0xFFA3AFC2)
    val TextMuted = Color(0xFF8595AD)
    val OnPrimary = Color(0xFF272E3A)
    val Accent = Color(0xFFF2B233)
    val OnAccent = Color(0xFF1A1206)
    val Highlight = Color(0xFFFFBA7F)
    val Danger = Color(0xFFE5484D)
}

/**
 * The TV mockups are drawn at 1920×1080 px (960×540 dp). TV pages are laid out under [TvScaled], which shrinks
 * layout by [TvMetrics.UiScale] and text by [TvMetrics.TextScale]; these convert mockup pixels so the result on
 * screen matches the mockup.
 */
val Int.mx: Dp get() = (this / (2f * TvMetrics.UiScale)).dp
val Int.ms: TextUnit get() = (this / (2f * TvMetrics.TextScale)).sp
