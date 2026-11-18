package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class OverlayDestination { Channels, Categories, Search, SearchDone, Empty }
private data class OverlayCategory(val key: String, val section: IptvChannelSection, val label: String, val locked: Boolean = false)

/** A single decoder stays visible under both the HUD and these same-window remote panels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun IptvTvChannelOverlay(
    channels: List<IptvChannel>, current: IptvChannel, section: IptvChannelSection,
    collection: IptvChannelCollection, tune: (IptvChannel, IptvChannelSection) -> Unit, close: () -> Unit,
    categoryCounts: Map<String, Int>? = null, lockedGroups: Set<String> = emptySet(), hiddenGroups: Set<String> = emptySet(),
    requestGroupAccess: ((String, () -> Unit) -> Unit)? = null, manageCategory: ((String?) -> Unit)? = null,
    manageSources: (() -> Unit)? = null
) {
    val groups = categoryCounts ?: remember(channels, current.sourceId) { IptvNavigation.categories(channels, current.sourceId) }
    val numbering = remember(channels, current.sourceId) { IptvNavigation.numbers(channels, current.sourceId) }
    val logos = rememberIptvLogos()
    val keyboard = LocalSoftwareKeyboardController.current
    val manager = LocalFocusManager.current
    val window = LocalWindowInfo.current
    val imeVisible = WindowInsets.isImeVisible
    val editor = remember { IptvTvSearchEditorState() }
    var browsingSection by remember { mutableStateOf(section) }
    var query by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(false) }
    var searchSession by remember { mutableStateOf(false) }
    var beforeSearchSection by remember { mutableStateOf(section) }
    var beforeSearchChannelId by remember { mutableStateOf(current.id) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(emptyList<IptvChannel>()) }
    var filtering by remember { mutableStateOf(true) }
    var focusedChannelId by remember { mutableStateOf(current.id) }
    var focusedCategoryKey by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<OverlayDestination?>(OverlayDestination.Channels) }
    var lastDestination by remember { mutableStateOf(OverlayDestination.Channels) }
    var windowLost by remember { mutableStateOf(false) }
    val channelFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val channelScroll = rememberLazyListState()
    val categoryScroll = rememberLazyListState()
    val target = IptvNavigation.entry(visible, focusedChannelId)
    val categories = remember(groups, hiddenGroups, lockedGroups, numbering.size) {
        listOf(OverlayCategory("all", IptvChannelSection.All, "كل القنوات · ${numbering.size}"),
            OverlayCategory("favorites", IptvChannelSection.Favorites, "المفضلة")) +
            groups.filterKeys { it !in hiddenGroups }.map { (name, count) ->
                OverlayCategory("group:$name", IptvChannelSection.Group(name), "$name · $count", name in lockedGroups)
            }
    }
    val categoryTarget = categories.firstOrNull { it.key == focusedCategoryKey }
        ?: categories.firstOrNull { it.section == browsingSection } ?: categories.first()
    fun hideKeyboard() { editor.hideKeyboard(); keyboard?.hide(); manager.clearFocus(force = true) }
    fun returnToChannels() {
        hideKeyboard(); searchMode = false; categoriesOpen = false
        lastDestination = OverlayDestination.Channels; pending = OverlayDestination.Channels
    }
    fun cancelSearch() {
        if (searchSession) {
            browsingSection = beforeSearchSection; focusedChannelId = beforeSearchChannelId
            query = ""; searchSession = false; filtering = true
        }
        returnToChannels()
    }
    fun beginSearch() {
        if (!searchSession) {
            beforeSearchSection = browsingSection; beforeSearchChannelId = focusedChannelId
            searchSession = true; browsingSection = IptvChannelSection.All; filtering = true
        }
        categoriesOpen = false; searchMode = true; pending = null
    }
    fun finishSearch() {
        hideKeyboard(); searchMode = false; focusedChannelId = ""
        lastDestination = OverlayDestination.Channels; pending = OverlayDestination.Channels
    }
    fun back() {
        when {
            searchMode && (imeVisible || editor.focused) -> {
                editor.hideKeyboard(); keyboard?.hide()
                lastDestination = OverlayDestination.SearchDone; pending = OverlayDestination.SearchDone
            }
            searchMode -> cancelSearch()
            categoriesOpen -> returnToChannels()
            searchSession -> cancelSearch()
            else -> { hideKeyboard(); close() }
        }
    }
    fun openCategories() {
        hideKeyboard(); searchMode = false; categoriesOpen = true
        lastDestination = OverlayDestination.Categories; pending = OverlayDestination.Categories
    }
    fun chooseSection(value: IptvChannelSection) {
        if (value != browsingSection || query.isNotEmpty()) filtering = true
        browsingSection = value; query = ""; searchSession = false; focusedChannelId = ""
        returnToChannels()
    }
    fun selectCategory(entry: OverlayCategory) {
        val group = (entry.section as? IptvChannelSection.Group)?.name
        if (entry.locked && group != null) requestGroupAccess?.invoke(group) { chooseSection(entry.section) }
        else chooseSection(entry.section)
    }
    BackHandler(onBack = ::back)
    DisposableEffect(Unit) { onDispose { editor.hideKeyboard(); keyboard?.hide() } }

    // Only explicit navigation requests restore focus. Filter results and row focus updates never take it away.
    LaunchedEffect(window.isWindowFocused) {
        if (!window.isWindowFocused) windowLost = true
        else if (windowLost) {
            windowLost = false
            pending = if (searchMode) { if (editor.focused) null else OverlayDestination.SearchDone } else lastDestination
        }
    }
    LaunchedEffect(channels, current.sourceId, collection, browsingSection, query, hiddenGroups, groups, searchSession) {
        filtering = true
        val group = (browsingSection as? IptvChannelSection.Group)?.name
        if (group != null && (group !in groups || group in hiddenGroups)) {
            browsingSection = IptvChannelSection.All; focusedChannelId = ""; pending = OverlayDestination.Channels
            return@LaunchedEffect
        }
        visible = withContext(Dispatchers.Default) {
            collection.channels(channels, current.sourceId, browsingSection, query = "").filter {
                it.group !in hiddenGroups && IptvChannelSearch.matches(it, query, numbering[it.id] ?: 0)
            }
        }
        filtering = false
    }
    LaunchedEffect(pending, filtering, visible, categoriesOpen, searchMode, imeVisible, window.isWindowFocused) {
        val destination = pending ?: return@LaunchedEffect
        if (!window.isWindowFocused || imeVisible || filtering) return@LaunchedEffect
        if (searchMode && destination != OverlayDestination.SearchDone) return@LaunchedEffect
        val requester = when (destination) {
            OverlayDestination.Channels -> {
                val index = visible.indexOfFirst { it.id == target }
                if (index >= 0) { channelScroll.scrollToItem(index); channelFocus } else emptyFocus
            }
            OverlayDestination.Categories -> {
                categoryScroll.scrollToItem(categories.indexOfFirst { it.key == categoryTarget.key }.coerceAtLeast(0)); categoryFocus
            }
            OverlayDestination.Search -> searchFocus
            OverlayDestination.SearchDone -> doneFocus
            OverlayDestination.Empty -> emptyFocus
        }
        repeat(16) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) { pending = null; return@LaunchedEffect }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp).testTag("iptv.tv.overlay"), contentAlignment = Alignment.TopEnd) {
            val channelWidth = (maxWidth * .33f).coerceIn(288.dp, 340.dp)
            val categoryWidth = (maxWidth * .19f).coerceIn(168.dp, 196.dp)
            // The HUD remains painted below; its borderless station logo protrudes 32dp above the strip.
            val panelHeight = (maxHeight - 210.dp).coerceAtLeast(150.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    IptvTvGlassPanel(Modifier.width(channelWidth).height(panelHeight).focusGroup().onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !imeVisible && !editor.focused) {
                            if (key.action == KeyEvent.ACTION_DOWN) openCategories()
                            true
                        } else false
                    }) {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Label("القنوات", 18, bold = true)
                                    Label(if (query.isBlank()) "${visible.size} قناة" else "نتائج البحث · ${visible.size}", 11, Brand.TextSecondary)
                                }
                                OverlayButton("البحث", Modifier.testTag("iptv.tv.search").focusRequester(searchFocus)
                                    .onFocusChanged { if (it.isFocused) lastDestination = OverlayDestination.Search }, Icons.Rounded.Search, iconOnly = true) {
                                    beginSearch()
                                }
                                if (manageCategory != null) OverlayButton("إدارة التصنيفات", icon = Icons.Rounded.MoreVert, iconOnly = true,
                                    modifier = Modifier.testTag("iptv.tv.manage")) {
                                    lastDestination = OverlayDestination.Channels; manageCategory(null)
                                }
                                if (manageSources != null) OverlayButton("المصادر والاشتراكات", icon = Icons.Rounded.Settings, iconOnly = true,
                                    modifier = Modifier.testTag("iptv.tv.sources")) {
                                    hideKeyboard(); close(); manageSources()
                                }
                            }
                            if (searchMode) {
                                IptvTvSearchEditor(query, editor, doneFocus,
                                    change = { if (it != query) { query = it; filtering = true } },
                                    submit = ::finishSearch, back = ::back, modifier = Modifier.testTag("iptv.tv.search.editor"))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OverlayButton("بحث / تم", Modifier.focusRequester(doneFocus).testTag("iptv.tv.search.done")) { finishSearch() }
                                    OverlayButton("إلغاء", Modifier.testTag("iptv.tv.search.cancel")) { cancelSearch() }
                                }
                            }
                            LazyColumn(state = channelScroll, modifier = Modifier.fillMaxWidth().weight(1f).focusGroup(),
                                verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(3.dp)) {
                                itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                                    IptvChannelRow(item, numbering[item.id] ?: 0, item.id == current.id, logos,
                                        Modifier.testTag("iptv.tv.channel.${item.id}")
                                            .then(if (item.id == target) Modifier.focusRequester(channelFocus) else Modifier)
                                            .focusProperties { if (index == 0) up = searchFocus }
                                            .onFocusChanged { if (it.isFocused) { focusedChannelId = item.id; lastDestination = OverlayDestination.Channels } },
                                        translucent = true) {
                                        hideKeyboard(); tune(item, browsingSection)
                                    }
                                }
                                if (visible.isEmpty() && !filtering) item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Label("لا توجد قنوات مطابقة.", 13, Brand.TextSecondary)
                                        OverlayButton(if (query.isBlank()) "التصنيفات" else "مسح البحث", Modifier.focusRequester(emptyFocus)) {
                                            if (query.isBlank()) openCategories() else { query = ""; focusedChannelId = ""; pending = OverlayDestination.Channels }
                                        }
                                    }
                                }
                            }
                            if (!searchMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Label("OK تشغيل", 11, Brand.TextSecondary)
                                OverlayButton("التصنيفات ←", Modifier.testTag("iptv.tv.categories.open")) { openCategories() }
                            }
                        }
                    }
                    AnimatedVisibility(categoriesOpen,
                        enter = fadeIn(tween(160)) + expandHorizontally(tween(160), expandFrom = Alignment.End),
                        exit = fadeOut(tween(120)) + shrinkHorizontally(tween(120), shrinkTowards = Alignment.End)) {
                        IptvTvGlassPanel(Modifier.width(categoryWidth).height(panelHeight).testTag("iptv.tv.categories").focusGroup()
                            .onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                when (key.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (key.action == KeyEvent.ACTION_DOWN) returnToChannels(); true }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                                    else -> false
                                }
                            }) {
                            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Label("التصنيفات", 17, bold = true, modifier = Modifier.weight(1f))
                                    if (manageCategory != null) OverlayButton("إدارة التصنيفات", icon = Icons.Rounded.MoreVert, iconOnly = true) {
                                        lastDestination = OverlayDestination.Categories; manageCategory(null)
                                    }
                                }
                                LazyColumn(state = categoryScroll, modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(3.dp)) {
                                    itemsIndexed(categories, key = { _, item -> item.key }) { _, entry ->
                                        OverlayButton(entry.label, Modifier.fillMaxWidth()
                                            .then(if (entry.key == categoryTarget.key) Modifier.focusRequester(categoryFocus) else Modifier)
                                            .onFocusChanged { if (it.isFocused) { focusedCategoryKey = entry.key; lastDestination = OverlayDestination.Categories } }
                                            .onPreviewKeyEvent { event ->
                                                val key = event.nativeKeyEvent
                                                if (key.keyCode == KeyEvent.KEYCODE_MENU && manageCategory != null) {
                                                    if (key.action == KeyEvent.ACTION_UP) manageCategory((entry.section as? IptvChannelSection.Group)?.name)
                                                    true
                                                } else false
                                            }, icon = if (entry.locked) Icons.Rounded.Lock else null,
                                            selected = entry.section == browsingSection) { selectCategory(entry) }
                                    }
                                }
                                OverlayButton("رجوع إلى القنوات", Modifier.fillMaxWidth(), icon = Icons.Rounded.Close) { returnToChannels() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun OverlayButton(
    label: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    iconOnly: Boolean = false, selected: Boolean = false, action: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Button(action, modifier.heightIn(min = 44.dp).then(if (iconOnly) Modifier.width(44.dp) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .background(if (focused) IptvTvGlass.FocusedBrush else IptvTvGlass.QuietRowBrush, shape)
        .border(.75.dp, if (focused) Color.White.copy(alpha = .35f) else if (selected) IptvTvGlass.Hairline else Color.Transparent, shape)
        .semantics { contentDescription = label },
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
            contentColor = Color.White, focusedContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = if (iconOnly) 10.dp else 12.dp, vertical = 8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
        if (!iconOnly) { if (icon != null) Spacer(Modifier.width(7.dp)); Label(label, 13, bold = focused || selected, lines = 1) }
    }
}
