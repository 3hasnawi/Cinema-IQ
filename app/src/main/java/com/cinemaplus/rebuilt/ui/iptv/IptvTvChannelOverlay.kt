package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.data.iptv.IptvChannel
import com.cinemaplus.rebuilt.data.iptv.IptvChannelCollection
import com.cinemaplus.rebuilt.data.iptv.IptvChannelSection
import com.cinemaplus.rebuilt.data.iptv.IptvNavigation
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Same-window translucent panels keep the single, fullscreen decoder surface running underneath. */
@Composable internal fun IptvTvChannelOverlay(
    channels: List<IptvChannel>, current: IptvChannel, section: IptvChannelSection,
    collection: IptvChannelCollection, tune: (IptvChannel, IptvChannelSection) -> Unit, close: () -> Unit
) {
    val groups = remember(channels, current.sourceId) { IptvNavigation.categories(channels, current.sourceId) }
    val numbering = remember(channels, current.sourceId) { IptvNavigation.numbers(channels, current.sourceId) }
    val logos = rememberIptvLogos()
    var browsingSection by remember { mutableStateOf(section) }
    val categories = remember(groups, numbering.size) {
        listOf(Triple("all", IptvChannelSection.All, "كل القنوات · ${numbering.size}"),
            Triple("favorites", IptvChannelSection.Favorites, "المفضلة")) +
            groups.map { Triple("group:${it.key}", IptvChannelSection.Group(it.key), "${it.key} · ${it.value}") }
    }
    var query by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(emptyList<IptvChannel>()) }
    var filtering by remember { mutableStateOf(true) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var focusedChannelId by remember { mutableStateOf(current.id) }
    var focusEpoch by remember { mutableIntStateOf(0) }
    val channelFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val channelScroll = rememberLazyListState()
    val categoryScroll = rememberLazyListState()
    val target = IptvNavigation.entry(visible, focusedChannelId)
    fun returnToChannels() { categoriesOpen = false; searching = false; focusEpoch++ }
    fun chooseSection(value: IptvChannelSection) {
        if (value != browsingSection || query.isNotEmpty()) filtering = true
        browsingSection = value; query = ""; focusedChannelId = current.id
        returnToChannels()
    }
    BackHandler { if (categoriesOpen) returnToChannels() else close() }

    // Physical Right must reveal categories on the right, independently of the Arabic text direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp).testTag("iptv.tv.overlay"), contentAlignment = Alignment.CenterEnd) {
            val channelWidth = (maxWidth * .36f).coerceIn(300.dp, 420.dp)
            val categoryWidth = (maxWidth * .22f).coerceIn(190.dp, 270.dp)
            val panelHeight = maxHeight * .88f
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.width(channelWidth).height(panelHeight).focusGroup().onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !searching) {
                            if (key.action == KeyEvent.ACTION_DOWN && !categoriesOpen) categoriesOpen = true
                            true
                        } else false
                    },
                        color = Panel.copy(alpha = .86f), shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Brand.BorderStrong.copy(alpha = .85f))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Label("القنوات", 22, bold = true)
                                Spacer(Modifier.weight(1f)); IptvButton("إغلاق", onClick = close)
                            }
                            OutlinedTextField(query, { value ->
                                val next = value.take(200)
                                if (next != query) { query = next; filtering = true }
                            },
                                label = { Text("ابحث عن قناة") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("iptv.tv.search").focusRequester(searchFocus)
                                    .onFocusChanged { searching = it.isFocused })
                            LazyColumn(state = channelScroll, modifier = Modifier.fillMaxWidth().weight(1f).focusGroup(),
                                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(5.dp)) {
                                items(visible, key = { it.id }) { channel ->
                                    IptvChannelRow(channel, numbering[channel.id] ?: 0, channel.id == current.id, logos,
                                        Modifier.testTag("iptv.tv.channel.${channel.id}")
                                            .then(if (channel.id == target) Modifier.focusRequester(channelFocus) else Modifier)
                                            .onFocusChanged { if (it.isFocused) focusedChannelId = channel.id },
                                        translucent = true) { tune(channel, browsingSection) }
                                }
                                if (visible.isEmpty() && !filtering) item { Label("لا توجد قنوات تطابق البحث أو التصنيف.", 14, Brand.TextTertiary) }
                            }
                            IptvButton("التصنيفات  ›", Modifier.focusRequester(emptyFocus)) { searching = false; categoriesOpen = true }
                        }
                    }
                    AnimatedVisibility(categoriesOpen, enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
                        Surface(Modifier.width(categoryWidth).height(panelHeight).testTag("iptv.tv.categories").focusGroup()
                            .onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                when (key.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (key.action == KeyEvent.ACTION_DOWN) returnToChannels()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                                    else -> false
                                }
                            }, color = Panel.copy(alpha = .88f), shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Brand.BorderStrong.copy(alpha = .85f))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Label("التصنيفات", 21, bold = true)
                                LazyColumn(state = categoryScroll, modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(5.dp)) {
                                    items(categories, key = { it.first }) { entry ->
                                        IptvButton((if (browsingSection == entry.second) "✓ " else "") + entry.third,
                                            Modifier.fillMaxWidth().then(if (browsingSection == entry.second) Modifier.focusRequester(categoryFocus) else Modifier)) { chooseSection(entry.second) }
                                    }
                                }
                                IptvButton("رجوع", onClick = ::returnToChannels)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(channels, current.sourceId, collection, browsingSection, query) {
        filtering = true
        val selectedGroup = (browsingSection as? IptvChannelSection.Group)?.name
        if (selectedGroup != null && selectedGroup !in groups) {
            browsingSection = IptvChannelSection.All
            return@LaunchedEffect
        }
        visible = withContext(Dispatchers.Default) { collection.channels(channels, current.sourceId, browsingSection, query) }
        filtering = false
    }
    LaunchedEffect(visible, categories, categoriesOpen, focusEpoch, filtering, searching) {
        if (categoriesOpen) {
            val index = categories.indexOfFirst { it.second == browsingSection }
            categoryScroll.scrollToItem(index.coerceAtLeast(0))
        } else {
            if (filtering || searching) return@LaunchedEffect
            val index = visible.indexOfFirst { it.id == target }
            if (index >= 0) channelScroll.scrollToItem(index)
        }
        repeat(12) {
            withFrameNanos { }
            val requester = if (categoriesOpen) categoryFocus else if (target == null) emptyFocus else channelFocus
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}
