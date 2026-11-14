package com.cinemaplus.rebuilt.ui.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.launch

private enum class IptvPanel { Sources, Form, Groups, Delete }

@Composable internal fun IptvTvScreen(onExit: () -> Unit, vm: IptvTvViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val logos = remember {
        coil.ImageLoader.Builder(context).allowRgb565(true).crossfade(false)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .memoryCache { coil.memory.MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build() }
            .okHttpClient { okhttp3.OkHttpClient.Builder().followSslRedirects(false).build() }.build()
    }
    DisposableEffect(logos) { onDispose { logos.shutdown() } }
    var panel by remember { mutableStateOf<IptvPanel?>(null) }
    val dialogFirst = remember(panel) { FocusRequester() }
    var editing by remember { mutableStateOf<IptvSource?>(null) }
    var playing by remember { mutableStateOf<IptvChannel?>(null) }
    var returnId by remember { mutableStateOf<String?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var returnEpoch by remember { mutableIntStateOf(0) }
    var control by remember { mutableStateOf("add") }
    val grid = rememberLazyGridState()
    val cardFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val sourcesFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val targetId = returnId?.takeIf { id -> state.visible.any { it.id == id } } ?: state.visible.firstOrNull()?.id
    val groups = remember(state.channels) { state.channels.map { it.group }.distinct().sorted() }
    fun restoreControl() {
        scope.launch {
            withFrameNanos { }; withFrameNanos { }
            runCatching { when (control) { "sources" -> sourcesFocus; "group" -> groupFocus; "refresh" -> refreshFocus; "search" -> searchFocus; else -> addFocus }.requestFocus() }
        }
    }
    fun dismiss() { vm.cancelConnection(); panel = null; restoreControl() }
    BackHandler(enabled = playing == null && panel == null, onBack = onExit)
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (playing != null) {
            val channel = playing!!
            IptvTvPlayer(channel, vm::refreshPlayback) { playing = null; returnId = channel.id; returnEpoch++ }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Label("IPTV", 30, bold = true)
                Spacer(Modifier.weight(1f))
                IptvButton("إضافة مصدر", Modifier.focusRequester(addFocus), state.storageReady && !state.busy) {
                    control = "add"; editing = null; panel = IptvPanel.Form
                }
                IptvButton("العودة", onClick = onExit)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IptvButton(state.selected?.name ?: "المصادر", Modifier.focusRequester(sourcesFocus), !state.busy && state.sources.isNotEmpty()) {
                    control = "sources"; panel = IptvPanel.Sources
                }
                IptvButton(state.group ?: "كل القنوات", Modifier.focusRequester(groupFocus), state.channels.isNotEmpty() && !state.busy) {
                    control = "group"; panel = IptvPanel.Groups
                }
                OutlinedTextField(state.query, vm::query, label = { Text("ابحث عن قناة") }, singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(searchFocus).onFocusChanged { if (it.isFocused) control = "search" })
                IptvButton("تحديث", Modifier.focusRequester(refreshFocus), state.selected != null && !state.busy) {
                    control = "refresh"; returnId = focusedId; vm.reload(force = true)
                }
            }
            if (state.storageLoading || state.busy || state.filtering) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            state.message?.let { Label(it, 14, Brand.Danger) }
            if (!state.storageLoading && !state.busy && state.sources.isEmpty()) {
                Label("أضف رابط قائمة M3U أو حساب Xtream من مزوّدك لعرض القنوات هنا.", 17, Brand.TextTertiary)
            } else if (!state.busy && !state.filtering && state.visible.isEmpty()) {
                Label("لا توجد قنوات تطابق البحث أو التصنيف.", 16, Brand.TextTertiary)
            }
            Label("${state.visible.size} قناة", 13, Brand.TextTertiary)
            LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(state.visible, key = { it.id }) { channel ->
                    IptvChannelCard(channel, logos, if (channel.id == targetId) Modifier.focusRequester(cardFocus) else Modifier,
                        focused = { focusedId = channel.id }) { playing = channel }
                }
            }
        }
        when (panel) {
            IptvPanel.Form -> key(editing?.id ?: "new") { IptvSourceForm(editing, state, ::dismiss) { source ->
                vm.connect(source) { panel = null; returnId = null; returnEpoch++ }
            } }
            IptvPanel.Sources -> IptvDialog("المصادر", { panel = null; restoreControl() }, dialogFirst) {
                state.sources.forEachIndexed { index, source ->
                    IptvButton((if (source.id == state.selectedId) "✓ " else "") + source.name, if (index == 0) Modifier.focusRequester(dialogFirst) else Modifier) {
                        panel = null; returnId = null; vm.select(source)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IptvButton("تعديل") { editing = source; panel = IptvPanel.Form }
                        IptvButton("حذف") { editing = source; panel = IptvPanel.Delete }
                    }
                    HorizontalDivider(color = Brand.BorderStrong)
                }
                IptvButton("إغلاق") { panel = null; restoreControl() }
            }
            IptvPanel.Groups -> IptvDialog("تصنيفات القنوات", { panel = null; restoreControl() }, dialogFirst) {
                IptvButton("كل القنوات", Modifier.focusRequester(dialogFirst)) { panel = null; returnId = null; vm.group(null) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(groups, key = { it }) { group -> IptvButton(group) { panel = null; returnId = null; vm.group(group) } }
                }
                IptvButton("إغلاق") { panel = null; restoreControl() }
            }
            IptvPanel.Delete -> IptvDialog("حذف المصدر", { panel = null; restoreControl() }, dialogFirst) {
                Label("هل تريد حذف ${editing?.name.orEmpty()} من هذا الجهاز؟", 17)
                state.message?.let { Label(it, 13, Brand.Danger) }
                IptvButton("حذف", enabled = !state.busy) { editing?.let { vm.remove(it) { panel = null; returnId = null; returnEpoch++ } } }
                IptvButton("إلغاء", Modifier.focusRequester(dialogFirst), enabled = !state.busy) { panel = null; restoreControl() }
            }
            null -> Unit
        }
    }
    LaunchedEffect(state.focusEpoch, returnEpoch, playing == null, state.storageLoading) {
        if (playing != null || panel != null || state.busy || state.filtering || state.storageLoading) return@LaunchedEffect
        val index = state.visible.indexOfFirst { it.id == targetId }
        if (index >= 0) grid.scrollToItem(index)
        repeat(12) {
            withFrameNanos { }
            if (runCatching { if (index >= 0) cardFocus.requestFocus() else addFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

@Composable private fun IptvChannelCard(channel: IptvChannel, logos: coil.ImageLoader, modifier: Modifier, focused: () -> Unit, open: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().height(112.dp)
        .onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) focused() }.clip(RoundedCornerShape(18.dp))
        .clickable(onClick = open).semantics { contentDescription = channel.name },
        shape = RoundedCornerShape(18.dp), color = if (hasFocus) Brand.Raised else Panel,
        border = BorderStroke(if (hasFocus) 2.dp else 1.dp, if (hasFocus) Accent else Brand.BorderStrong)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Glass), contentAlignment = Alignment.Center) {
                if (channel.logo != null) AsyncImage(channel.logo, null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit, imageLoader = logos)
                else Label("TV", 16, bold = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(channel.name, 16, bold = true, lines = 2)
                Label(channel.group, 12, Brand.TextTertiary, lines = 1)
            }
        }
    }
}
