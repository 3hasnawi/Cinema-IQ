package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.Page

/*
 * Cinema IQ design kit: "midnight marquee". A near-black violet canvas, gold for films and primary actions,
 * teal for series, and ticket-style dashed dividers. Everything here only displays data the source provides.
 */

/** Series are teal; films use the gold accent. */
val SeriesTint = Color(0xFF3DD6C0)
fun kindTint(series: Boolean): Color = if (series) SeriesTint else Accent

/** Short caption under each page title. */
fun pageSubtitle(page: Page): String = when (page) {
    Page.Movies -> "أفلام مرتبة حسب التصنيف"
    Page.Series -> "مسلسلات مرتبة حسب التصنيف"
    Page.Library -> "أعمال أضفتها لتشاهدها لاحقًا"
    Page.Search -> "ابحث بالاسم"
    Page.Explore -> "تصفّح حسب المزاج"
    Page.Settings -> "الحساب والترجمة والتشغيل"
    else -> ""
}

/** Gold bar + large title + optional caption. */
@Composable
fun PageHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(5.dp).height(34.dp).background(Accent, CircleShape))
            Label(title, 34, Color.White, true, lines = 1)
        }
        if (subtitle.isNotBlank()) Label(subtitle, 14, Muted, lines = 1, modifier = Modifier.padding(start = 17.dp))
    }
}

/** Shelf title with the gold marker and the number of titles in it. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(4.dp).height(20.dp).background(Accent, CircleShape))
        Label(title, 17, Color.White.copy(alpha = .95f), true, lines = 1)
        if (count != null && count > 0) Label("$count", 13, Muted, lines = 1)
    }
}

/** Star + rating on a dark chip, readable over any artwork. */
@Composable
fun RatingChip(rating: String, modifier: Modifier = Modifier, size: Int = 12) {
    if (rating.isBlank()) return
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .66f))
        .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(Icons.Rounded.Star, null, Modifier.size((size + 3).dp), tint = Accent)
        Label(rating, size, Color.White, true, lines = 1)
    }
}

/** Film / series tag in the colour of its kind. */
@Composable
fun KindBadge(series: Boolean, modifier: Modifier = Modifier, size: Int = 12) {
    val tint = kindTint(series)
    Row(modifier.clip(CircleShape).background(tint.copy(alpha = .20f)).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Label(if (series) "مسلسل" else "فيلم", size, tint, true, lines = 1)
    }
}

/** Outlined text chip (year, runtime, quality). Draws nothing for blank text. */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, size: Int = 12) {
    if (text.isBlank()) return
    Box(modifier.border(1.dp, Color.White.copy(alpha = .24f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Label(text, size, Color.White.copy(alpha = .88f), false, lines = 1)
    }
}

/** Dashed line like the perforation of a ticket. */
@Composable
fun TicketDivider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    Canvas(modifier.then(if (vertical) Modifier.width(1.dp).height(34.dp) else Modifier.fillMaxWidth().height(1.dp))) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
        val end = if (vertical) Offset(0f, size.height) else Offset(size.width, 0f)
        drawLine(Color.White.copy(alpha = .26f), Offset.Zero, end, 1.dp.toPx(), pathEffect = dash)
    }
}

/** Ticket-style strip of facts (year, runtime, rating, seasons...). Blank values are left out. */
@Composable
fun FactsTicket(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val shown = facts.filter { it.second.isNotBlank() }
    if (shown.isEmpty()) return
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.clip(shape).background(Color.White.copy(alpha = .07f))
        .border(1.dp, Color.White.copy(alpha = .10f), shape).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, (label, value) ->
            if (index > 0) TicketDivider(vertical = true)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(value, 18, Color.White, true, lines = 1)
                Label(label, 12, Muted, lines = 1)
            }
        }
    }
}

/** Trailer glyph: a small screen with a play triangle cut out of it (the core icon set has no film icon). */
val TrailerIcon: ImageVector by lazy {
    materialIcon(name = "Trailer") {
        materialPath(pathFillType = PathFillType.EvenOdd) {
            moveTo(4f, 5f)
            horizontalLineTo(20f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(10f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(7f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            close()
            moveTo(10f, 8.5f)
            verticalLineToRelative(7f)
            lineToRelative(6f, -3.5f)
            close()
        }
    }
}

/** Resting heights of the subtitles above the bottom edge (fraction of the video height). */
val SubtitleHeights = listOf(.02f, .05f, .08f, .14f, .20f, .28f)
private val SubtitleHeightNames = listOf("الأدنى", "منخفض", "افتراضي", "مرتفع", "عالٍ", "الأعلى")
fun subtitleHeightLabel(value: Float): String =
    SubtitleHeightNames[SubtitleHeights.indices.minByOrNull { kotlin.math.abs(SubtitleHeights[it] - value) } ?: 2]
fun nextSubtitleHeight(value: Float): Float {
    val at = SubtitleHeights.indices.minByOrNull { kotlin.math.abs(SubtitleHeights[it] - value) } ?: 2
    return SubtitleHeights[(at + 1) % SubtitleHeights.size]
}
