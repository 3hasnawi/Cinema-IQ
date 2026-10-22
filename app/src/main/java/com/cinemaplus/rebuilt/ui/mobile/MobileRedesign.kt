package com.cinemaplus.rebuilt.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.Page
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.ui.*

/*
 * Phone pieces of the redesign (phone-png/). Mockups are 390×844 css px, which equals dp; the brand tokens and
 * Cairo are shared with the TV layout.
 */

/** Plain Cairo text with phone sizes. */
@Composable
internal fun PText(
    text: String, size: TextUnit, color: Color = Color.White, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, lines: Int = 1, align: TextAlign? = null
) = Text(text, modifier, color = color, fontSize = size, fontFamily = Cairo, fontWeight = weight, maxLines = lines,
    overflow = TextOverflow.Ellipsis, textAlign = align, lineHeight = size * 1.35f)

/** Top bar: logo on the start side, explore / refresh and the avatar (profile and settings) on the end side. */
@Composable
internal fun PhoneTopBar(vm: CinemaViewModel, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(buildAnnotatedString {
            append("Cinema ")
            withStyle(SpanStyle(color = Brand.Accent)) { append("iQ") }
        }, fontFamily = Cairo, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White, modifier = Modifier.weight(1f))
        // Explore stays reachable although the bottom bar of the mockup has no slot for it.
        IconButton(onClick = { vm.navigate(Page.Explore) }) {
            Icon(Icons.Rounded.Star, "استكشاف", tint = if (vm.page == Page.Explore) Brand.Accent else Color.White)
        }
        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "تحديث المحتوى", tint = Color.White) }
        Box(Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).background(Brand.BorderStrong)
            .clickable { vm.navigate(Page.Settings) }.semantics { contentDescription = "الملف الشخصي والإعدادات" },
            contentAlignment = Alignment.Center) {
            PText(vm.profile.name.trim().take(1).ifBlank { "؟" }, 15.sp, weight = FontWeight.SemiBold)
        }
    }
}

/** 44 dp glass circle (my list, details). */
@Composable
internal fun PhoneGlassCircle(icon: ImageVector, label: String, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(Brand.GlassFill).border(1.dp, Brand.GlassStroke, CircleShape)
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .5f), tint = Color.White)
    }
}

/** White primary pill with dark text. */
@Composable
internal fun PhonePrimaryButton(text: String, modifier: Modifier = Modifier, height: Dp = 44.dp, radius: Dp = height / 2,
    progress: Float = 0f, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.height(height).clip(shape).background(if (enabled) Color.White else Color.White.copy(alpha = .5f))
        .clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp), tint = Brand.OnPrimary)
            PText(text, 16.sp, Brand.OnPrimary, FontWeight.Bold)
        }
        if (progress > 0f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Brand.TextSecondary)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Brand.Accent))
        }
    }
}

/** Small chips of the meta line. */
@Composable
internal fun PhoneChip(text: String, kind: ChipKind = ChipKind.Outline) {
    if (text.isBlank()) return
    val shape = RoundedCornerShape(if (kind == ChipKind.Accent) 5.dp else 4.dp)
    val modifier = when (kind) {
        ChipKind.Accent -> Modifier.background(Brand.Accent, shape)
        ChipKind.Glass -> Modifier.background(Brand.Background.copy(alpha = .8f), shape).border(1.dp, Brand.GlassStroke, shape)
        ChipKind.Outline -> Modifier.border(1.dp, Color.White.copy(alpha = .3f), shape)
    }
    Box(modifier.padding(horizontal = if (kind == ChipKind.Accent) 8.dp else 5.dp)) {
        PText(text, if (kind == ChipKind.Accent) 12.sp else 11.sp, if (kind == ChipKind.Accent) Brand.OnAccent else Color.White,
            if (kind == ChipKind.Outline) FontWeight.Normal else FontWeight.Bold)
    }
}

internal enum class ChipKind { Accent, Glass, Outline }

/** "★ 8.2" rating chip in glass. */
@Composable
internal fun PhoneRating(rating: String, glass: Boolean = true) {
    val value = rating.takeIf { (it.toFloatOrNull() ?: 0f) > 0f } ?: return
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.then(if (glass) Modifier.background(Brand.Background.copy(alpha = .8f), shape).border(1.dp, Brand.GlassStroke, shape) else Modifier)
        .padding(horizontal = if (glass) 5.dp else 0.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PText("★", 11.sp, Brand.Accent)
        PText(value, if (glass) 11.sp else 13.sp, Color.White, FontWeight.SemiBold)
    }
}

/** Facts separated by slate dots. */
@Composable
internal fun PhoneDotted(facts: List<String>, size: TextUnit = 13.sp) {
    facts.filter(String::isNotBlank).forEachIndexed { i, fact ->
        if (i > 0) PText("•", size, Brand.BorderStrong)
        PText(fact, size, Brand.TextSecondary)
    }
}

/** Shelf title (16 sp bold). */
@Composable
internal fun PhoneShelfTitle(text: String) = PText(text, 16.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))

/** Continue-watching card: 160×90, radius 8, resume badge and a flush 3 dp accent strip; caption under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneContinueCard(film: Film, progress: Float, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp)).background(Brand.Raised)) {
            AsyncImage(film.backdrop.ifBlank { film.poster }, mediaTitle(film), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(26.dp).background(Color.Black.copy(alpha = .55f), CircleShape),
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "استئناف", Modifier.size(16.dp), tint = Color.White) }
            if (progress > 0f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Brand.Accent))
            }
        }
        PText(continueCaption(mediaTitle(film), film, progress), 12.sp, Brand.TextSecondary)
    }
}

private fun continueCaption(title: String, film: Film, progress: Float): String {
    if (film.episode > 0) return "$title · ح ${film.episode}"
    val total = if (':' in film.duration) film.duration.split(':').mapNotNull { it.trim().toLongOrNull() }.fold(0L) { t, p -> t * 60 + p }
        else film.duration.trim().toLongOrNull() ?: 0L
    val left = if (total > 0 && progress > 0f) formatContentDuration((total * (1f - progress)).toLong().toString()) else ""
    return listOf(title, if (left.isNotBlank()) "متبقٍ $left" else "").filter(String::isNotBlank).joinToString(" · ")
}

/** Poster card: 2:3 artwork, radius 8, title underneath. */
@Composable
internal fun PhonePosterCard(film: Film, modifier: Modifier = Modifier, width: Dp? = 116.dp, onClick: () -> Unit) {
    Column((if (width != null) modifier.width(width) else modifier).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(film.poster.ifBlank { film.backdrop }, mediaTitle(film),
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Brand.Raised), contentScale = ContentScale.Crop)
        PText(mediaTitle(film), 12.sp, Brand.TextSecondary)
    }
}

/** Filter chip: white with dark text when selected, outlined otherwise. */
@Composable
internal fun PhoneFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.height(36.dp).clip(shape).background(if (selected) Color.White else Color.Transparent)
        .then(if (selected) Modifier else Modifier.border(1.dp, Brand.GlassStroke, shape))
        .clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        PText(text, 13.sp, if (selected) Brand.Background else Brand.TextSecondary, if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
