package com.cinemaplus.rebuilt.ui.iptv

import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalInputModeManager
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
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class OverlayDestination { Channels, Categories, Search, SearchDone, Empty }
private data class OverlayCategory(val key: String, val section: IptvChannelSection, val label: String, val locked: Boolean = false)
private data class OverlayObservedFocus(val destination: OverlayDestination, val key: String)
private const val OverlayFocusTag = "IptvOverlayFocus"

/** Explicit QA opt-in: adb shell setprop log.tag.IptvOverlayFocus DEBUG. Never log catalogue values. */
private inline fun overlayFocusLog(message: () -> String) {
    if (Log.isLoggable(OverlayFocusTag, Log.DEBUG)) Log.d(OverlayFocusTag, message())
}

/** A single decoder stays visible under both the HUD and these same-window remote panels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun IptvTvChannelOverlay(
    channels: List<IptvChannel>, current: IptvChannel, section: IptvChannelSection,
    tune: (IptvChannel, IptvChannelSection) -> Unit, close: () -> Unit,
    categoryCounts: Map<String, Int>? = null, lockedGroups: Set<String> = emptySet(), hiddenGroups: Set<String> = emptySet(),
    requestGroupAccess: ((String?, () -> Unit, () -> Unit) -> Unit)? = null, manageCategory: ((String?) -> Unit)? = null,
    manageSources: (() -> Unit)? = null, canonicalNumbers: Map<String, Int>? = null,
    sectionChanged: (IptvChannelSection) -> Unit = {},
    preparedGuide: IptvTvGuideSnapshot?, logos: ImageLoader
) {
    val groups = categoryCounts ?: remember(channels, current.sourceId) { IptvNavigation.categories(channels, current.sourceId) }
    val numbering = canonicalNumbers ?: remember(channels, current.sourceId) { IptvNavigation.numbers(channels, current.sourceId) }
    val keyboard = LocalSoftwareKeyboardController.current
    val manager = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    val window = LocalWindowInfo.current
    val editor = remember { IptvTvSearchEditorState() }
    var browsingSection by remember { mutableStateOf(section) }
    var query by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(false) }
    var searchSession by remember { mutableStateOf(false) }
    val initialGroup = (section as? IptvChannelSection.Group)?.name
    var admissionPending by remember { mutableStateOf(initialGroup != null && initialGroup in lockedGroups) }
    var beforeSearchSection by remember { mutableStateOf(section) }
    var beforeSearchChannelId by remember { mutableStateOf(current.id) }
    var categoriesOpen by remember { mutableStateOf(false) }
    // Reuse precomputed blank pages. Search results are never reused across different base pages.
    val basePage = preparedGuide?.page(browsingSection)
    val searchInput = remember(basePage, query, admissionPending) { Any() }
    var preparedSearch by remember { mutableStateOf<Pair<Any, IptvTvGuidePage>?>(null) }
    val page = when {
        admissionPending || preparedGuide == null -> null
        query.isBlank() -> basePage
        else -> preparedSearch?.takeIf { it.first === searchInput }?.second
    }
    val visible = page?.rows.orEmpty()
    val filtering = !admissionPending && page == null
    var focusedChannelId by remember { mutableStateOf(current.id) }
    var focusedCategoryKey by remember { mutableStateOf("") }
    var actualFocus by remember { mutableStateOf<OverlayObservedFocus?>(null) }
    var channelPaneHasFocus by remember { mutableStateOf(false) }
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
    val target = focusedChannelId.takeIf { page?.indexById?.containsKey(it) == true } ?: visible.firstOrNull()?.id
    val categories = remember(groups, hiddenGroups, lockedGroups, numbering.size) {
        listOf(OverlayCategory("all", IptvChannelSection.All, "كل القنوات · ${numbering.size}"),
            OverlayCategory("favorites", IptvChannelSection.Favorites, "المفضلة")) +
            groups.filterKeys { it !in hiddenGroups }.map { (name, count) ->
                OverlayCategory("group:$name", IptvChannelSection.Group(name), "$name · $count", name in lockedGroups)
            }
    }
    val categoryTarget = categories.firstOrNull { it.key == focusedCategoryKey }
        ?: categories.firstOrNull { it.section == browsingSection } ?: categories.first()
    fun observeFocus(destination: OverlayDestination, key: String, index: Int, focused: Boolean) {
        val observed = OverlayObservedFocus(destination, key)
        if (focused) actualFocus = observed else if (actualFocus == observed) actualFocus = null
        overlayFocusLog { "observed destination=$destination index=$index focused=$focused" }
    }
    fun focusStatus(): String {
        val observed = actualFocus
        val observedIndex = when (observed?.destination) {
            OverlayDestination.Channels -> page?.indexById?.get(observed.key) ?: -1
            OverlayDestination.Categories -> categories.indexOfFirst { it.key == observed.key }
            else -> -1
        }
        return "items=${visible.size} visible=${channelScroll.layoutInfo.visibleItemsInfo.joinToString(",") { it.index.toString() }} " +
            "window=${window.isWindowFocused} ownedEditing=${editor.editing} nativeFocus=${editor.focused} input=${inputMode.inputMode} " +
            "actual=${observed?.destination ?: "none"} actualIndex=$observedIndex"
    }
    fun hideKeyboard() { editor.hideKeyboard(); keyboard?.hide(); manager.clearFocus(force = true) }
    fun returnToChannels() {
        hideKeyboard(); searchMode = false; categoriesOpen = false
        lastDestination = OverlayDestination.Channels; pending = OverlayDestination.Channels
    }
    fun cancelSearch() {
        if (!searchSession) { returnToChannels(); return }
        val origin = beforeSearchSection
        val originGroup = (origin as? IptvChannelSection.Group)?.name
        val originChannel = beforeSearchChannelId
        hideKeyboard(); query = ""; searchSession = false; searchMode = false
        val restore = {
            browsingSection = origin; sectionChanged(origin); focusedChannelId = originChannel
            returnToChannels()
        }
        // A global search ended the old visit. Returning to it is a fresh admission.
        if (originGroup != null && originGroup in lockedGroups) {
            categoriesOpen = true; focusedCategoryKey = "group:$originGroup"
            lastDestination = OverlayDestination.Categories; pending = OverlayDestination.Categories
        }
        requestGroupAccess?.invoke(originGroup, restore, {})
            ?: if (originGroup == null || originGroup !in lockedGroups) restore() else Unit
    }
    fun beginSearch() {
        if (!searchSession) {
            beforeSearchSection = browsingSection; beforeSearchChannelId = focusedChannelId
            searchSession = true
        }
        val start = {
            browsingSection = IptvChannelSection.All; sectionChanged(IptvChannelSection.All)
            categoriesOpen = false; searchMode = true; pending = null
        }
        requestGroupAccess?.invoke(null, start, {}) ?: start()
    }
    fun finishSearch() {
        hideKeyboard(); searchMode = false; focusedChannelId = ""
        lastDestination = OverlayDestination.Channels; pending = OverlayDestination.Channels
    }
    fun back() {
        when {
            searchMode && (editor.editing || editor.focused) -> {
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
        browsingSection = value; sectionChanged(value); query = ""; searchSession = false; focusedChannelId = ""
        returnToChannels()
    }
    fun selectCategory(entry: OverlayCategory) {
        val group = (entry.section as? IptvChannelSection.Group)?.name
        requestGroupAccess?.invoke(group, { chooseSection(entry.section) }, {})
            ?: if (!entry.locked) chooseSection(entry.section) else Unit
    }
    BackHandler(onBack = ::back)
    DisposableEffect(Unit) { onDispose { editor.hideKeyboard(); keyboard?.hide() } }
    LaunchedEffect(Unit) {
        if (admissionPending) {
            val accepted = {
                admissionPending = false; chooseSection(section); focusedChannelId = current.id
            }
            val denied = {
                admissionPending = false; chooseSection(IptvChannelSection.All)
                // Preserve a useful, non-secret return target when initial admission is cancelled.
                requestGroupAccess?.invoke(null, {}, {})
                categoriesOpen = true; focusedCategoryKey = "group:$initialGroup"
                lastDestination = OverlayDestination.Categories; pending = OverlayDestination.Categories
            }
            requestGroupAccess?.invoke(initialGroup, accepted, denied) ?: denied()
        }
    }

    // Only explicit navigation requests restore focus. Filter results and row focus updates never take it away.
    LaunchedEffect(window.isWindowFocused) {
        if (!window.isWindowFocused) windowLost = true
        else if (windowLost) {
            windowLost = false
            pending = if (searchMode) { if (editor.editing || editor.focused) null else OverlayDestination.SearchDone } else lastDestination
        }
    }
    LaunchedEffect(browsingSection, hiddenGroups, groups, admissionPending) {
        if (admissionPending) return@LaunchedEffect
        val group = (browsingSection as? IptvChannelSection.Group)?.name
        if (group != null && (group !in groups || group in hiddenGroups)) {
            browsingSection = IptvChannelSection.All; sectionChanged(IptvChannelSection.All)
            requestGroupAccess?.invoke(null, {}, {})
            focusedChannelId = ""; pending = OverlayDestination.Channels
            return@LaunchedEffect
        }
    }
    LaunchedEffect(searchInput) {
        val snapshot = preparedGuide ?: return@LaunchedEffect
        if (admissionPending || query.isBlank()) return@LaunchedEffect
        val requestedSection = browsingSection
        val requestedQuery = query
        val result = withContext(Dispatchers.Default) { snapshot.search(requestedSection, requestedQuery) }
        preparedSearch = searchInput to result
    }
    LaunchedEffect(page, filtering) {
        if (page == null || filtering) return@LaunchedEffect
        // A refreshed authorized catalogue may remove the focused row while pages are prepared.
        // Reuse the observed-ACK path only when no pane control/editor or other owner has focus.
        withFrameNanos { }
        if (pending == null && actualFocus == null && !channelPaneHasFocus &&
            lastDestination == OverlayDestination.Channels && window.isWindowFocused &&
            !admissionPending && !categoriesOpen && !searchMode && !editor.editing && !editor.focused) {
            pending = OverlayDestination.Channels
        }
    }
    LaunchedEffect(pending, filtering, visible, categoriesOpen, searchMode, editor.editing, editor.focused, window.isWindowFocused, admissionPending) {
        val destination = pending ?: run {
            overlayFocusLog { "gate=no-pending ${focusStatus()}" }
            return@LaunchedEffect
        }
        if (!window.isWindowFocused || admissionPending || (searchMode && (editor.editing || editor.focused)) || filtering) {
            overlayFocusLog { "gate=window-owned-editor-filter destination=$destination filtering=$filtering ${focusStatus()}" }
            return@LaunchedEffect
        }
        if (searchMode && destination != OverlayDestination.SearchDone) {
            overlayFocusLog { "gate=editing destination=$destination ${focusStatus()}" }
            return@LaunchedEffect
        }
        val (requester, expected, requestedIndex) = when (destination) {
            OverlayDestination.Channels -> {
                val index = page?.indexById?.get(target) ?: -1
                if (index >= 0) {
                    channelScroll.scrollToItem(index)
                    Triple(channelFocus, OverlayObservedFocus(OverlayDestination.Channels, visible[index].id), index)
                } else Triple(emptyFocus, OverlayObservedFocus(OverlayDestination.Empty, "empty"), -1)
            }
            OverlayDestination.Categories -> {
                val index = categories.indexOfFirst { it.key == categoryTarget.key }.coerceAtLeast(0)
                categoryScroll.scrollToItem(index)
                Triple(categoryFocus, OverlayObservedFocus(OverlayDestination.Categories, categoryTarget.key), index)
            }
            OverlayDestination.Search -> Triple(searchFocus, OverlayObservedFocus(OverlayDestination.Search, "search"), -1)
            OverlayDestination.SearchDone -> Triple(doneFocus, OverlayObservedFocus(OverlayDestination.SearchDone, "done"), -1)
            OverlayDestination.Empty -> Triple(emptyFocus, OverlayObservedFocus(OverlayDestination.Empty, "empty"), -1)
        }
        overlayFocusLog { "restore-start destination=$destination index=$requestedIndex ${focusStatus()}" }
        repeat(24) { attempt ->
            val modeRequested = inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            withFrameNanos { }
            val result = runCatching { requester.requestFocus() }
            // A successful request can be followed by another node losing focus in the same frame.
            withFrameNanos { }
            overlayFocusLog {
                "restore-attempt destination=$destination index=$requestedIndex attempt=$attempt modeRequested=$modeRequested " +
                    "requested=${result.getOrDefault(false)} exception=${result.exceptionOrNull()?.javaClass?.simpleName ?: "none"} ${focusStatus()}"
            }
            if (actualFocus == expected) {
                overlayFocusLog { "restore-ack destination=$destination index=$requestedIndex ${focusStatus()}" }
                pending = null
                return@LaunchedEffect
            }
        }
        overlayFocusLog { "restore-exhausted destination=$destination index=$requestedIndex ${focusStatus()}" }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("iptv.tv.overlay"), contentAlignment = Alignment.TopEnd) {
            val channelWidth = (maxWidth * .32f).coerceIn(288.dp, 360.dp)
            val categoryWidth = (maxWidth * .19f).coerceIn(168.dp, 196.dp)
            val edgeInset = (maxWidth * .032f).coerceIn(20.dp, 36.dp)
            val fadeWidth by animateFloatAsState(if (categoriesOpen) .82f else .61f, tween(180), label = "guide-fade-width")
            // Physical LTR coordinates keep the unbounded, noninteractive fade on the LEFT in Arabic too.
            Box(Modifier.fillMaxHeight().fillMaxWidth(fadeWidth).background(IptvTvGlass.GuideBrush))
            Row(Modifier.fillMaxHeight().padding(end = edgeInset, top = 24.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(channelWidth).fillMaxHeight()
                        .onFocusChanged { channelPaneHasFocus = it.hasFocus }.focusGroup().onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !editor.editing && !editor.focused) {
                            if (key.action == KeyEvent.ACTION_DOWN) openCategories()
                            true
                        } else false
                    }) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Label("القنوات", 18, bold = true)
                                    Label(if (query.isBlank()) "${visible.size} قناة" else "نتائج البحث · ${visible.size}", 11, Brand.TextSecondary)
                                }
                                OverlayButton("البحث", Modifier.testTag("iptv.tv.search").focusRequester(searchFocus)
                                    .onFocusChanged {
                                        observeFocus(OverlayDestination.Search, "search", -1, it.isFocused)
                                        if (it.isFocused) lastDestination = OverlayDestination.Search
                                    }, Icons.Rounded.Search, iconOnly = true) {
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
                                    change = { if (it != query) query = it },
                                    submit = ::finishSearch, back = ::back, modifier = Modifier.testTag("iptv.tv.search.editor"))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OverlayButton("بحث / تم", Modifier.focusRequester(doneFocus).testTag("iptv.tv.search.done")
                                        .onFocusChanged { observeFocus(OverlayDestination.SearchDone, "done", -1, it.isFocused) }) { finishSearch() }
                                    OverlayButton("إلغاء", Modifier.testTag("iptv.tv.search.cancel")) { cancelSearch() }
                                }
                            }
                            if (filtering) CircularProgressIndicator(Modifier.size(18.dp).align(Alignment.CenterHorizontally),
                                color = Brand.TextSecondary, strokeWidth = 2.dp)
                            LazyColumn(state = channelScroll, modifier = Modifier.fillMaxWidth().weight(1f).focusGroup(),
                                verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(3.dp)) {
                                itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                                    IptvChannelRow(item, numbering[item.id] ?: 0, item.id == current.id, logos,
                                        Modifier.testTag("iptv.tv.channel.${item.id}")
                                            .then(if (item.id == target) Modifier.focusRequester(channelFocus) else Modifier)
                                            .focusProperties { if (index == 0) up = searchFocus }
                                            .onFocusChanged {
                                                observeFocus(OverlayDestination.Channels, item.id, index, it.isFocused)
                                                if (it.isFocused) { focusedChannelId = item.id; lastDestination = OverlayDestination.Channels }
                                            },
                                        translucent = true) {
                                        hideKeyboard(); tune(item, browsingSection)
                                    }
                                }
                                if (visible.isEmpty() && !filtering) item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Label("لا توجد قنوات مطابقة.", 13, Brand.TextSecondary)
                                        OverlayButton(if (query.isBlank()) "التصنيفات" else "مسح البحث", Modifier.focusRequester(emptyFocus)
                                            .onFocusChanged { observeFocus(OverlayDestination.Empty, "empty", -1, it.isFocused) }) {
                                            if (query.isBlank()) openCategories() else { query = ""; focusedChannelId = ""; pending = OverlayDestination.Channels }
                                        }
                                    }
                                }
                            }
                            if (!searchMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Label("OK تشغيل", 11, Brand.TextSecondary)
                                OverlayButton("التصنيفات", Modifier.testTag("iptv.tv.categories.open"), icon = Icons.Rounded.ArrowForward) { openCategories() }
                            }
                        }
                    }
                    AnimatedVisibility(categoriesOpen,
                        enter = fadeIn(tween(160)) + expandHorizontally(tween(160), expandFrom = Alignment.End),
                        exit = fadeOut(tween(120)) + shrinkHorizontally(tween(120), shrinkTowards = Alignment.End)) {
                        Box(Modifier.width(categoryWidth).fillMaxHeight().testTag("iptv.tv.categories").focusGroup()
                            .onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                when (key.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (key.action == KeyEvent.ACTION_DOWN) returnToChannels(); true }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                                    else -> false
                                }
                            }) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Label("التصنيفات", 17, bold = true, modifier = Modifier.weight(1f))
                                    if (manageCategory != null) OverlayButton("إدارة التصنيفات", icon = Icons.Rounded.MoreVert, iconOnly = true) {
                                        lastDestination = OverlayDestination.Categories; manageCategory(null)
                                    }
                                }
                                LazyColumn(state = categoryScroll, modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(3.dp)) {
                                    itemsIndexed(categories, key = { _, item -> item.key }) { index, entry ->
                                        OverlayButton(entry.label, Modifier.fillMaxWidth()
                                            .then(if (entry.key == categoryTarget.key) Modifier.focusRequester(categoryFocus) else Modifier)
                                            .onFocusChanged {
                                                observeFocus(OverlayDestination.Categories, entry.key, index, it.isFocused)
                                                if (it.isFocused) { focusedCategoryKey = entry.key; lastDestination = OverlayDestination.Categories }
                                            }
                                            .onPreviewKeyEvent { event ->
                                                val key = event.nativeKeyEvent
                                                if (key.keyCode == KeyEvent.KEYCODE_MENU && manageCategory != null) {
                                                    if (key.action == KeyEvent.ACTION_UP) manageCategory((entry.section as? IptvChannelSection.Group)?.name)
                                                    true
                                                } else false
                                            }, icon = if (entry.locked) Icons.Rounded.Lock else null,
                                            selected = entry.section == browsingSection, category = true) { selectCategory(entry) }
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
    iconOnly: Boolean = false, selected: Boolean = false, category: Boolean = false, action: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (iconOnly) CircleShape else RoundedCornerShape(10.dp)
    Button(action, modifier.heightIn(min = 44.dp).then(if (iconOnly) Modifier.width(44.dp) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .background(when {
            focused && !category -> IptvTvGlass.GuideRowFocusedBrush
            focused || selected -> IptvTvGlass.GuideCategoryFocusedBrush
            else -> IptvTvGlass.GuideRowQuietBrush
        }, shape)
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
