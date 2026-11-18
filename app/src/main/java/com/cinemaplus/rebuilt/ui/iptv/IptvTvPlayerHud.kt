package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil.ImageLoader
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.data.iptv.IptvChannel
import com.cinemaplus.rebuilt.data.iptv.IptvChannelDisplayName
import com.cinemaplus.rebuilt.data.iptv.IptvNowNext
import com.cinemaplus.rebuilt.ui.*
import java.util.Locale

private val LocalHudInteractive = staticCompositionLocalOf { true }

/** Compact live-TV controls; the uninterrupted picture remains visible above the bottom strip. */
@Composable internal fun IptvTvPlayerHud(
    channel: IptvChannel, number: Int, status: String, programmes: IptvNowNext,
    videoHeight: Int, audioLanguage: String?, isFavorite: Boolean, logos: ImageLoader,
    entryFocus: FocusRequester, focusChanged: (Boolean) -> Unit, channels: () -> Unit,
    favorite: () -> Unit,
    channelStep: (Int) -> Unit, tracks: (Int, FocusRequester, Rect) -> Unit, interactive: Boolean = true
) {
    val displayName = remember(channel.name, channel.group) { IptvChannelDisplayName.of(channel) }
    var qualityBounds by remember { mutableStateOf(Rect.Zero) }
    var audioBounds by remember { mutableStateOf(Rect.Zero) }
    var subtitleBounds by remember { mutableStateOf(Rect.Zero) }
    val qualityFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val audio = remember(audioLanguage) {
        audioLanguage?.takeIf { it.isNotBlank() && it.length <= 32 && !it.equals("und", true) }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("ar")) }
            ?.takeIf { it.isNotBlank() } ?: "الصوت"
    }
    CompositionLocalProvider(LocalHudInteractive provides interactive) {
        Box(Modifier.fillMaxWidth().testTag("iptv.tv.hud").onPreviewKeyEvent { event ->
            if (!interactive) return@onPreviewKeyEvent false
            val key = event.nativeKeyEvent
            if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP || key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (key.action == KeyEvent.ACTION_DOWN) channelStep(if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                true
            } else false
        }) {
        Column(Modifier.fillMaxWidth().background(IptvTvGlass.HudBrush, RoundedCornerShape(20.dp))
            .padding(start = 30.dp, end = 30.dp, top = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Reserve width without stretching the strip; the logo is a separate unclipped sibling.
                Spacer(Modifier.width(104.dp))
                Column(Modifier.weight(.9f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Label(displayName, 20, bold = true, modifier = Modifier.testTag("iptv.tv.hud.channel"), lines = 1)
                    Label((if (number > 0) "$number  ·  " else "") + status, 12, Brand.TextSecondary, lines = 1)
                }
                Canvas(Modifier.size(16.dp, 36.dp).semantics { contentDescription = "أعلى وأسفل: تغيير القناة" }) {
                    val w = size.width; val h = size.height; val stroke = 1.5.dp.toPx()
                    drawLine(Color.White, Offset(w * .2f, h * .2f), Offset(w * .5f, h * .1f), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(w * .5f, h * .1f), Offset(w * .8f, h * .2f), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(w * .2f, h * .8f), Offset(w * .5f, h * .9f), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(w * .5f, h * .9f), Offset(w * .8f, h * .8f), stroke, StrokeCap.Round)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (programmes.current == null && programmes.next == null) Label("لا تتوفر معلومات", 13, Brand.TextSecondary)
                    else {
                        ProgrammeLine("الآن", programmes.current)
                        ProgrammeLine("التالي", programmes.next)
                    }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .12f))
            Row(Modifier.fillMaxWidth().onFocusChanged { focusChanged(it.hasFocus) }
                .horizontalScroll(rememberScrollState()).padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                HudButton("القنوات", "channels", Modifier.focusRequester(entryFocus), click = channels) { HudGlyph("tv", Color.White) }
                HudButton("المفضلة", "favorite", Modifier.semantics { stateDescription = if (isFavorite) "مضافة إلى المفضلة" else "غير مضافة" }, click = favorite) {
                    Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null,
                        Modifier.size(23.dp), tint = Color.White)
                }
                HudButton(audio, "audio", Modifier.widthIn(max = 175.dp).focusRequester(audioFocus),
                    click = { tracks(C.TRACK_TYPE_AUDIO, audioFocus, audioBounds) }) {
                    Box(Modifier.onGloballyPositioned { audioBounds = it.boundsInWindow() }) { HudGlyph("audio", Color.White) }
                }
                HudButton(if (videoHeight > 0) "${videoHeight}p" else "الجودة", "quality", Modifier.focusRequester(qualityFocus),
                    click = { tracks(C.TRACK_TYPE_VIDEO, qualityFocus, qualityBounds) }) {
                    Box(Modifier.onGloballyPositioned { qualityBounds = it.boundsInWindow() }) { HudGlyph("quality", Color.White) }
                }
                HudButton("الترجمة", "subtitles", Modifier.focusRequester(subtitlesFocus),
                    click = { tracks(C.TRACK_TYPE_TEXT, subtitlesFocus, subtitleBounds) }) {
                    Box(Modifier.onGloballyPositioned { subtitleBounds = it.boundsInWindow() }) { PlayerTransportIcon("CC", Color.White, 23.dp) }
                }
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(start = 30.dp).offset(y = (-32).dp).size(104.dp),
        contentAlignment = Alignment.TopCenter) {
            if (channel.logo != null) AsyncImage(model = channel.logo, contentDescription = displayName,
            imageLoader = logos, contentScale = ContentScale.Fit, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            else IptvTelevisionIcon(Modifier.size(80.dp), Color.White)
        }
        }
    }
}

@Composable private fun ProgrammeLine(prefix: String, programme: com.cinemaplus.rebuilt.data.iptv.IptvProgramme?) {
    val text = remember(programme, prefix) {
        if (programme == null) "$prefix: لا تتوفر معلومات"
        else {
            val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
            "$prefix  ${time.format(java.util.Date(programme.startsAt))}–${time.format(java.util.Date(programme.endsAt))}  ${programme.title}"
        }
    }
    Label(text, 13, if (prefix == "الآن") Brand.TextPrimary else Brand.TextSecondary, lines = 1)
}

@Composable private fun HudButton(label: String, id: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    click: () -> Unit, icon: @Composable () -> Unit) {
    val interactive = LocalHudInteractive.current
    Button(onClick = { if (interactive) click() }, enabled = enabled, modifier = modifier.heightIn(min = 44.dp)
        .focusProperties { canFocus = interactive && enabled }.testTag("iptv.tv.hud.$id")
        .alpha(if (enabled) 1f else .35f).semantics {
            contentDescription = when (id) { "audio" -> "الصوت: $label"; "quality" -> "الجودة: $label"; else -> label }
        },
        shape = ButtonDefaults.shape(RoundedCornerShape(7.dp)),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White,
            focusedContainerColor = IptvTvGlass.Focus, focusedContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)) {
        icon(); Spacer(Modifier.width(8.dp)); Label(label, 13, bold = true, lines = 1)
    }
}

@Composable private fun HudGlyph(symbol: String, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val w = size.width; val stroke = Stroke(w * .07f, cap = StrokeCap.Round)
        when (symbol) {
            "tv" -> {
                drawRoundRect(color, Offset(w * .1f, w * .24f), Size(w * .8f, w * .58f), CornerRadius(w * .1f), style = stroke)
                drawLine(color, Offset(w * .35f, w * .06f), Offset(w * .5f, w * .22f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .65f, w * .06f), Offset(w * .5f, w * .22f), stroke.width, StrokeCap.Round)
            }
            "audio" -> {
                drawArc(color, 180f, 180f, false, Offset(w * .16f, w * .12f), Size(w * .68f, w * .72f), style = stroke)
                drawRoundRect(color, Offset(w * .12f, w * .48f), Size(w * .2f, w * .38f), CornerRadius(w * .06f), style = stroke)
                drawRoundRect(color, Offset(w * .68f, w * .48f), Size(w * .2f, w * .38f), CornerRadius(w * .06f), style = stroke)
            }
            "quality" -> {
                drawRoundRect(color, Offset(w * .08f, w * .17f), Size(w * .84f, w * .66f), CornerRadius(w * .09f), style = stroke)
                for (i in 0..2) drawLine(color, Offset(w * (.3f + i * .2f), w * .65f),
                    Offset(w * (.3f + i * .2f), w * (.5f - i * .12f)), stroke.width, StrokeCap.Round)
            }
        }
    }
}
