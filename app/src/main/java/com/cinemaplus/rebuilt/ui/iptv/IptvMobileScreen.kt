package com.cinemaplus.rebuilt.ui.iptv

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var fullscreen by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        onDispose {
            if (original != null) activity.requestedOrientation = original
            activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    DisposableEffect(fullscreen) {
        activity?.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.let {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            if (fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    LaunchedEffect(state.selectedId, state.channels) {
        if (playing?.sourceId != state.selectedId) {
            fullscreen = false
            playing = state.collection.channels(state.channels, state.selectedId.orEmpty(), IptvChannelSection.Recent).firstOrNull()
                ?: state.channels.firstOrNull { it.sourceId == state.selectedId }
        }
    }
    BackHandler(enabled = playing == null && panel == null, onBack = onExit)
    Column(Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onExit) { Text("رجوع") }; Label("IPTV", 22, bold = true)
                TextButton({ editing = null; panel = MobileIptvPanel.Add }, enabled = state.storageReady && !state.busy) { Text("إضافة") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton({ panel = MobileIptvPanel.Manage }, enabled = state.sources.isNotEmpty() && !state.busy, modifier = Modifier.weight(1f)) {
                    Text(state.selected?.name ?: "المصادر", maxLines = 1)
                }
                TextButton({ panel = MobileIptvPanel.Groups }, enabled = state.channels.isNotEmpty()) { Text("التصنيفات") }
                TextButton({ vm.reload(true) }, enabled = !state.busy && state.selected != null) { Text("تحديث") }
            }
            if (state.storageLoading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Label(it, 13, Brand.Danger) }
        }
        Box(Modifier.weight(1f)) {
            val initial = playing
            if (initial != null && initial.sourceId == state.selectedId) {
                key(initial.sourceId) { IptvTvPlayer(initial, state.channels, state.group, false,
                    state.sources.firstOrNull { it.id == initial.sourceId }?.allowHttp == true, vm::refreshPlayback,
                    startInBrowser = true, collection = state.collection, favorite = vm::toggleFavorite, watched = vm::noteWatched,
                    onFullscreen = { fullscreen = it }) { _, _ -> onExit() } }
            } else if (!state.storageLoading && !state.busy) {
                Label("أضف قائمة M3U أو حساب Xtream من مزوّدك.", 16, Brand.TextTertiary)
            }
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
