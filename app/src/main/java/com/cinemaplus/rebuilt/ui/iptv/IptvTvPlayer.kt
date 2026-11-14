package com.cinemaplus.rebuilt.ui.iptv

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun IptvTvPlayer(initial: IptvChannel, refresh: suspend (IptvChannel) -> IptvChannel, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var channel by remember(initial.id) { mutableStateOf(initial) }
    var attempt by remember { mutableIntStateOf(0) }
    val player = remember(channel.id, channel.url, attempt) {
        val factory = DefaultHttpDataSource.Factory().setUserAgent("Cinema-iQ-IPTV/1.0")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(false)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
    }
    var error by remember(player) { mutableStateOf<String?>(null) }
    var loading by remember(player) { mutableStateOf(true) }
    var controls by remember(player) { mutableStateOf(true) }
    var tracks by remember(player) { mutableStateOf(Tracks.EMPTY) }
    var panel by remember { mutableStateOf<Int?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { view.keepScreenOn = isPlaying }
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onPlaybackStateChanged(value: Int) {
                loading = value == Player.STATE_BUFFERING || value == Player.STATE_IDLE
                if (value == Player.STATE_ENDED) { loading = false; error = "انتهى بث القناة. يمكنك تحديث الرابط وإعادة المحاولة." }
            }
            override fun onPlayerError(exception: PlaybackException) {
                loading = false; view.keepScreenOn = false
                error = "تعذّر تشغيل القناة. تحقق من الاشتراك والشبكة وتوافق صيغة البث، ثم حدّث الرابط."
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            player.pause(); view.keepScreenOn = false
        } }
        player.addListener(listener); lifecycle.addObserver(observer)
        player.setMediaItem(MediaItem.fromUri(channel.url)); player.prepare(); player.playWhenReady = true
        onDispose {
            lifecycle.removeObserver(observer); player.removeListener(listener); view.keepScreenOn = false; player.release()
        }
    }
    BackHandler(enabled = panel == null, onBack = onExit)
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            useController = true; controllerShowTimeoutMs = 4000
            setShowSubtitleButton(false); setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controls = it == View.VISIBLE })
            isFocusable = true; isFocusableInTouchMode = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            post { requestFocus(); showController() }
        } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
        if (controls || error != null) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Ink.copy(alpha = .75f)).padding(22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) { Label(channel.name, 21, bold = true, lines = 1); Label("بث مباشر", 12, Brand.TextTertiary) }
                IptvButton("الجودة") { panel = C.TRACK_TYPE_VIDEO }
                IptvButton("الصوت") { panel = C.TRACK_TYPE_AUDIO }
                IptvButton("الترجمة") { panel = C.TRACK_TYPE_TEXT }
                IptvButton("إغلاق", onClick = onExit)
            }
        }
        if (loading && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        if (error != null) Column(Modifier.align(Alignment.Center).widthIn(max = 700.dp).background(Panel).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Label(error!!, 17)
            IptvButton(if (refreshing) "جارٍ تحديث الرابط…" else "تحديث الرابط وإعادة المحاولة", enabled = !refreshing) {
                scope.launch {
                    refreshing = true
                    try { channel = refresh(channel); attempt++ }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = iptvMessage(e) }
                    finally { refreshing = false }
                }
            }
            IptvButton("العودة للقنوات", onClick = onExit)
        }
        panel?.let { type ->
            val groups = tracks.groups.filter { it.type == type }
            val first = remember(type) { androidx.compose.ui.focus.FocusRequester() }
            IptvDialog(when (type) { C.TRACK_TYPE_VIDEO -> "جودة البث"; C.TRACK_TYPE_AUDIO -> "مسار الصوت"; else -> "الترجمة" }, { panel = null }) {
                IptvButton(if (type == C.TRACK_TYPE_TEXT) "إيقاف الترجمة" else "تلقائي", Modifier.focusRequester(first)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                        .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT).build()
                    panel = null
                }
                groups.forEach { group ->
                    for (i in 0 until group.length) if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val label = when (type) {
                            C.TRACK_TYPE_VIDEO -> if (format.height > 0) "${format.height}p" else format.label ?: "جودة ${i + 1}"
                            else -> format.label ?: format.language ?: "مسار ${i + 1}"
                        }
                        IptvButton((if (group.isTrackSelected(i)) "✓ " else "") + label) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                                .setTrackTypeDisabled(type, false).addOverride(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                            panel = null
                        }
                    }
                }
                if (groups.isEmpty()) Label("لم يوفّر هذا البث خيارات إضافية من هذا النوع.", 14, Brand.TextTertiary)
                IptvButton("إغلاق") { panel = null }
            }
            LaunchedEffect(type) { withFrameNanos { }; runCatching { first.requestFocus() } }
        }
    }
}
