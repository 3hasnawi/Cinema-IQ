package com.cinemaplus.rebuilt.ui.iptv

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*

@Composable fun IptvMobileEntry() {
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { IptvTvActivity.open(context) },
        color = Panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Brand.BorderStrong)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Label("IPTV", 21, bold = true); Label("قنواتك المباشرة · M3U وXtream", 13, Brand.TextTertiary)
        }
    }
}
private enum class MobileIptvPanel { Add, Manage, Groups, Delete }
@Composable internal fun IptvMobileScreen(onExit: () -> Unit, vm: IptvTvViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as? Activity
    var playing by remember { mutableStateOf<IptvChannel?>(null) }
    var editing by remember { mutableStateOf<IptvSource?>(null) }
    var panel by remember { mutableStateOf<MobileIptvPanel?>(null) }
    var returned by remember { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState()
    DisposableEffect(playing != null) {
        activity?.requestedOrientation = if (playing != null) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.let {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            if (playing != null) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    BackHandler(enabled = playing == null && panel == null, onBack = onExit)
    if (playing != null) {
        IptvTvPlayer(playing!!, state.channels, state.group, false,
            state.sources.firstOrNull { it.id == playing!!.sourceId }?.allowHttp == true, vm::refreshPlayback) { last, group ->
            playing = null; vm.returnFromPlayer(group); returned = last.id
        }
    } else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onExit) { Text("رجوع") }; Label("IPTV", 26, bold = true)
            TextButton({ editing = null; panel = MobileIptvPanel.Add }, enabled = state.storageReady && !state.busy) { Text("إضافة") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ panel = MobileIptvPanel.Manage }, enabled = state.sources.isNotEmpty() && !state.busy, modifier = Modifier.weight(1f)) {
                Text(state.selected?.name ?: "المصادر", maxLines = 1)
            }
            OutlinedButton({ panel = MobileIptvPanel.Groups }, enabled = state.channels.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(state.group ?: "كل القنوات", maxLines = 1) }
        }
        OutlinedTextField(state.query, vm::query, modifier = Modifier.fillMaxWidth(), label = { Text("ابحث عن قناة") }, singleLine = true)
        if (state.storageLoading || state.busy || state.filtering) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Label(it, 14, Brand.Danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("${state.visible.size} قناة", 13, Brand.TextTertiary)
            TextButton({ vm.reload(true) }, enabled = !state.busy && state.selected != null) { Text("تحديث") }
        }
        LazyColumn(state = scroll, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.storageLoading && state.sources.isEmpty()) item { Label("أضف قائمة M3U أو حساب Xtream من مزوّدك.", 16, Brand.TextTertiary) }
            items(state.visible, key = { it.id }) { channel ->
                Surface(Modifier.fillMaxWidth().clickable { playing = channel }, color = Panel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Label(channel.name, 17, bold = true, lines = 2); Label(channel.group, 12, Brand.TextTertiary, lines = 1)
                    }
                }
            }
        }
    }
    LaunchedEffect(state.visible, playing == null) {
        if (playing == null && returned != null) {
            val index = state.visible.indexOfFirst { it.id == returned }
            if (index >= 0) { scroll.scrollToItem(index); returned = null }
        }
    }
    when (panel) {
        MobileIptvPanel.Add -> IptvSourceForm(editing, state, { vm.cancelConnection(); panel = null }) { source -> vm.connect(source) { panel = null } }
        MobileIptvPanel.Manage -> IptvDialog("المصادر", { panel = null }) {
            state.sources.forEach { source ->
                IptvButton((if (state.selectedId == source.id) "✓ " else "") + source.name) { vm.select(source); panel = null }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IptvButton("تعديل") { editing = source; panel = MobileIptvPanel.Add }
                    IptvButton("حذف") { editing = source; panel = MobileIptvPanel.Delete }
                }
            }
        }
        MobileIptvPanel.Groups -> IptvDialog("التصنيفات", { panel = null }) {
            IptvButton("كل القنوات") { vm.group(null); panel = null }
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.channels.map { it.group }.distinct().sorted(), key = { it }) { value -> IptvButton(value) { vm.group(value); panel = null } }
            }
        }
        MobileIptvPanel.Delete -> IptvDialog("حذف المصدر", { panel = null }) {
            Text("حذف ${editing?.name.orEmpty()} من هذا الجهاز؟")
            IptvButton("حذف", enabled = !state.busy) { editing?.let { vm.remove(it) { panel = null } } }
            IptvButton("إلغاء", enabled = !state.busy) { panel = null }
        }
        null -> Unit
    }
}
