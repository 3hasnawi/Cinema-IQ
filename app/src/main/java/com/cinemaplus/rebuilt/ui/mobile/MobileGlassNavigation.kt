package com.cinemaplus.rebuilt.ui.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.Page
import com.cinemaplus.rebuilt.ui.Brand
import com.cinemaplus.rebuilt.ui.RailGlyph

/**
 * Floating bottom bar of the phone layout (phone-png/01): 64 dp tall, 16 dp from the edges, radius 32, surface at
 * 94 % with a hairline and a shadow. The current item is an accent pill with icon and label; the others are an icon
 * over a 10 sp label. It slides away while scrolling down and returns on scroll up.
 */
@Composable
internal fun MobileGlassNavigation(vm: CinemaViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val hidden by animateFloatAsState(if (compact) 1f else 0f, tween(220), label = "Bottom bar hide")
    val shape = RoundedCornerShape(32.dp)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        .graphicsLayer { translationY = hidden * (size.height + 48.dp.toPx()); alpha = 1f - hidden * .6f },
        contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(64.dp)
            .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = .5f))
            .clip(shape).background(Brand.Surface.copy(alpha = .94f)).border(1.dp, Brand.Divider, shape)
            .padding(horizontal = 8.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround) {
            val destinations = listOf(Page.Home to "الرئيسية", Page.Movies to "أفلام", Page.Series to "مسلسلات",
                Page.Search to "البحث", Page.Library to "قائمتي")
            destinations.forEach { (page, label) ->
                val selected = vm.page == page
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (selected) Brand.Accent else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { vm.navigate(page) })
                    .padding(horizontal = if (selected) 14.dp else 6.dp), contentAlignment = Alignment.Center) {
                    if (selected) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RailGlyph(page, Brand.OnAccent, 20.dp)
                        PText(label, 13.sp, Brand.OnAccent, FontWeight.Bold)
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RailGlyph(page, Brand.TextTertiary, 20.dp)
                        PText(label, 10.sp, Brand.TextTertiary)
                    }
                }
            }
        }
    }
}
