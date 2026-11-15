package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun IptvBrowserPanels(channels: List<IptvChannel>, current: IptvChannel, collection: IptvChannelCollection,
    section: IptvChannelSection, query: String, selectSection: (IptvChannelSection) -> Unit, choose: (IptvChannel) -> Unit) {
    val groups = remember(channels, current.sourceId) { IptvNavigation.categories(channels, current.sourceId) }
    val numbering = remember(channels, current.sourceId) { IptvNavigation.numbers(channels, current.sourceId) }
    val logos = rememberIptvLogos()
    var visible by remember { mutableStateOf(emptyList<IptvChannel>()) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width((maxWidth * .39f).coerceIn(132.dp, 210.dp)).fillMaxHeight().background(Ink)) {
                    item { IptvBrowserCategory("◷  الأخيرة", section == IptvChannelSection.Recent) { selectSection(IptvChannelSection.Recent) } }
                    item { IptvBrowserCategory("★  المفضلة", section == IptvChannelSection.Favorites) { selectSection(IptvChannelSection.Favorites) } }
                    item { IptvBrowserCategory("▣  كل القنوات", section == IptvChannelSection.All) { selectSection(IptvChannelSection.All) } }
                    items(groups.entries.toList(), key = { it.key }) { entry ->
                        IptvBrowserCategory(entry.key + " · " + entry.value, section == IptvChannelSection.Group(entry.key)) { selectSection(IptvChannelSection.Group(entry.key)) }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight().background(Panel), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(4.dp)) {
                    items(visible, key = { it.id }) { channel -> IptvChannelRow(channel, numbering[channel.id] ?: 0, channel.id == current.id, logos) { choose(channel) } }
                    if (visible.isEmpty()) item { Text("لا توجد قنوات في هذا القسم.", Modifier.padding(18.dp), color = Brand.TextTertiary) }
                }
            }
        }
    }
    LaunchedEffect(channels, collection, section, query) {
        visible = withContext(Dispatchers.Default) { collection.channels(channels, current.sourceId, section, query) }
    }
}
@Composable private fun IptvBrowserCategory(title: String, selected: Boolean, choose: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).background(if (selected) Brand.Raised else Ink).clickable(onClick = choose).padding(horizontal = 12.dp, vertical = 18.dp)) {
        Label(title, 14, bold = selected, lines = 2)
    }
}

@Composable internal fun IptvFullscreenButton(click: () -> Unit) {
    androidx.compose.material3.IconButton(click) {
        androidx.compose.foundation.Canvas(Modifier.size(23.dp)) {
            val color = androidx.compose.ui.graphics.Color.White; val edge = size.width * .1f; val arm = size.width * .25f; val stroke = 2.dp.toPx()
            for (x in listOf(edge, size.width - edge)) for (y in listOf(edge, size.height - edge)) {
                val dx = if (x < size.width / 2) arm else -arm; val dy = if (y < size.height / 2) arm else -arm
                drawLine(color, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + dx, y), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, y + dy), stroke)
            }
        }
    }
}
