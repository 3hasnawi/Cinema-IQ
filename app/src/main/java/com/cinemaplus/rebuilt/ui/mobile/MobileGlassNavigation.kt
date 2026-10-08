package com.cinemaplus.rebuilt.ui.mobile

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.Page

/** Translucent glass treatment overlays the page without blurring icons or requiring a new dependency. */
@Composable
internal fun MobileGlassNavigation(vm: CinemaViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if(compact) .94f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow), label = "Glass navigation size")
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 440.dp).fillMaxWidth().height(72.dp).scale(scale)
            .shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .5f))
            .clip(shape).background(Brush.verticalGradient(listOf(
                Color(0xFF545454).copy(alpha = .78f), Color(0xFF252525).copy(alpha = .88f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .04f))), shape)
            .padding(6.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val destinations = listOf(Page.Home to Icons.Rounded.Home, Page.Search to Icons.Rounded.Search,
                Page.Explore to Icons.Rounded.Star, Page.Library to Icons.Rounded.Favorite, Page.Settings to Icons.Rounded.Settings)
            destinations.forEach { (page, icon) ->
                val selected = vm.page == page || (page == Page.Home && vm.page in listOf(Page.Movies, Page.Series))
                val highlight by animateFloatAsState(if(selected) .22f else 0f, label = "Glass selected tab")
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = highlight), Color.White.copy(alpha = highlight * .5f))))
                    .selectable(selected = selected, role = Role.Tab, onClick = { vm.navigate(page) }),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, page.label, Modifier.size(27.dp), if(selected) Color.White else Color.White.copy(alpha = .55f))
                }
            }
        }
    }
}
