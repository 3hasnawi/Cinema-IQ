@file:Suppress("UnsafeOptInUsageError")
package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun IptvTvPlayer(initial: IptvChannel, channels: List<IptvChannel>, initialGroup: String?, television: Boolean,
    allowHttp: Boolean, refresh: suspend (IptvChannel) -> IptvChannel, onExit: (IptvChannel, String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current; val lifecycle = LocalLifecycleOwner.current.lifecycle
    var channel by remember(initial.id) { mutableStateOf(initial) }
    var group by remember { mutableStateOf(initialGroup) }
    var previousId by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var guide by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var toolbarFocus by remember { mutableStateOf(false) }
    val surface = remember { FocusRequester() }; val toolbar = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val queue = remember(channels, group) { IptvNavigation.channels(channels, initial.sourceId, group) }
    val player = remember(allowHttp) {
        val factory = DefaultHttpDataSource.Factory().setUserAgent("Cinema-iQ-IPTV/1.0")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(allowHttp)
        val restricted = androidx.media3.datasource.ResolvingDataSource.Factory(factory,
            androidx.media3.datasource.ResolvingDataSource.Resolver { spec ->
                try { IptvUrls.validate(spec.uri.toString(), allowHttp) }
                catch (e: IptvException) { throw androidx.media3.datasource.DataSourceException(
                    if (e.reason == IptvException.Reason.HttpConsent) PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
                    else PlaybackException.ERROR_CODE_IO_UNSPECIFIED) }
                spec
            })
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(restricted))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
    }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var panel by remember { mutableStateOf<Int?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshTask by remember { mutableStateOf<Job?>(null) }
    fun restoreSurface() { controls = true; interaction++; scope.launch { withFrameNanos { }; runCatching { surface.requestFocus() } } }
    fun tune(next: IptvChannel) {
        if (next.sourceId != initial.sourceId || next.id == channel.id) return
        refreshTask?.cancel(); refreshing = false; previousId = channel.id; channel = next
        controls = true; interaction++; error = null; loading = true
    }
    fun surf(step: Int) { IptvNavigation.neighbor(queue, channel.id, step)?.let { tune(it) } }
    fun closeTracks() { panel = null; restoreSurface() }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value; view.keepScreenOn = value }
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onPlaybackStateChanged(value: Int) {
                loading = value == Player.STATE_BUFFERING || value == Player.STATE_IDLE
                if (value == Player.STATE_ENDED) { loading = false; error = "انتهى بث القناة. يمكنك تحديث الرابط أو اختيار قناة أخرى." }
            }
            override fun onPlayerError(exception: PlaybackException) {
                loading = false; view.keepScreenOn = false; controls = true
                error = if (exception.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED)
                    "هذا البث يستخدم HTTP. عدّل المصدر وفعّل السماح بالاتصال غير المشفّر إذا كان مزوّدك يتطلبه."
                else "تعذّر تشغيل القناة. تحقق من الاشتراك والشبكة وصيغة البث، ثم حدّث الرابط."
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { player.pause(); view.keepScreenOn = false } }
        player.addListener(listener); lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); view.keepScreenOn = false; player.release() }
    }
    LaunchedEffect(channel.id, channel.url, attempt, player) {
        delay(180) // Collapse remote repeats instead of opening a stream for every key repeat.
        error = null; loading = true; tracks = Tracks.EMPTY
        player.stop(); player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        player.setMediaItem(MediaItem.Builder().setUri(channel.url).setMediaId(channel.id).build())
        player.prepare(); player.playWhenReady = true
    }
    LaunchedEffect(Unit) { if (television) { withFrameNanos { }; runCatching { surface.requestFocus() } } }
    LaunchedEffect(controls, toolbarFocus, guide, panel, interaction, isPlaying) {
        if (controls && isPlaying && !toolbarFocus && !guide && panel == null) { delay(5000); controls = false }
    }
    BackHandler(enabled = panel == null && !guide) { onExit(channel, group?.takeIf { it == channel.group }) }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) when (key.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> surf(-1)
                KeyEvent.KEYCODE_CHANNEL_DOWN -> surf(1)
                else -> { if (player.isPlaying) player.pause() else player.play(); controls = true; interaction++ }
            }
            true
        } else false
    }) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; layoutDirection = View.LAYOUT_DIRECTION_LTR
        } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().focusRequester(surface).onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (television && key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (key.action == KeyEvent.ACTION_DOWN) when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> surf(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> surf(1)
                    else -> { controls = true; scope.launch { withFrameNanos { }; runCatching { toolbar.requestFocus() } } }
                }
                true
            } else false
        }.semantics { contentDescription = if (television) "قائمة القنوات" else "إظهار خيارات المشغل" }
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { if (television) guide = true else { controls = !controls; interaction++ } }
            .pointerInput(channel.id, queue, television) {
                if (!television) { var delta = 0f; detectVerticalDragGestures(onDragStart = { delta = 0f }, onVerticalDrag = { _, value -> delta += value },
                    onDragEnd = { if (kotlin.math.abs(delta) > 100f) surf(if (delta < 0) 1 else -1) }) }
            })
        if (controls || !isPlaying || error != null || loading) {
            Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.background(Ink.copy(alpha = .75f)).padding(16.dp)) {
                    Label(channel.name, 21, bold = true, lines = 1); Label(if (loading) "جارٍ التحميل…" else if (isPlaying) "بث مباشر · ${channel.group}" else "متوقف مؤقتًا · ${channel.group}", 12, Brand.TextTertiary)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).background(Ink.copy(alpha = .85f)).padding(12.dp)
                    .onFocusChanged { toolbarFocus = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IptvButton("القنوات", Modifier.focusRequester(toolbar)) { guide = true }
                    IptvButton("السابقة") { if (television) channels.firstOrNull { it.id == previousId }?.let { tune(it) } else surf(-1) }
                    if (!television) IptvButton("التالية") { surf(1) }
                    IptvButton(if (isPlaying) "إيقاف مؤقت" else "تشغيل") { if (player.isPlaying) player.pause() else player.play(); interaction++ }
                    IptvButton("الجودة") { panel = C.TRACK_TYPE_VIDEO }
                    IptvButton("الصوت") { panel = C.TRACK_TYPE_AUDIO }
                    IptvButton("الترجمة") { panel = C.TRACK_TYPE_TEXT }
                    IptvButton("إغلاق") { onExit(channel, group?.takeIf { it == channel.group }) }
                }
            }
        }
        if (loading && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        if (error != null) Column(Modifier.align(Alignment.Center).widthIn(max = 700.dp).background(Panel).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(error!!, 16)
            IptvButton(if (refreshing) "جارٍ تحديث الرابط…" else "تحديث الرابط وإعادة المحاولة", enabled = !refreshing) {
                val requested = channel
                refreshTask = scope.launch {
                    refreshing = true
                    try { val fresh = refresh(requested); if (channel.id == requested.id) { channel = fresh; attempt++ } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (channel.id == requested.id) error = iptvMessage(e) }
                    finally { refreshing = false }
                }
            }
            IptvButton("القنوات") { guide = true }
        }
        if (guide) IptvChannelGuide(channels, channel, group, television, { group = it }, {
            tune(it); guide = false; restoreSurface()
        }, { guide = false; restoreSurface() })
        panel?.let { type ->
            val groups = tracks.groups.filter { it.type == type }; val first = remember(type) { FocusRequester() }
            IptvDialog(when (type) { C.TRACK_TYPE_VIDEO -> "جودة البث"; C.TRACK_TYPE_AUDIO -> "مسار الصوت"; else -> "الترجمة" }, { closeTracks() }, first) {
                IptvButton(if (type == C.TRACK_TYPE_TEXT) "إيقاف الترجمة" else "تلقائي", Modifier.focusRequester(first)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT).build(); closeTracks()
                }
                groups.forEach { track -> for (i in 0 until track.length) if (track.isTrackSupported(i)) {
                    val format = track.getTrackFormat(i)
                    val label = if (type == C.TRACK_TYPE_VIDEO && format.height > 0) "${format.height}p" else format.label ?: format.language ?: "مسار ${i + 1}"
                    IptvButton((if (track.isTrackSelected(i)) "✓ " else "") + label) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, false).addOverride(TrackSelectionOverride(track.mediaTrackGroup, i)).build(); closeTracks()
                    }
                } }
                if (groups.isEmpty()) Label("لم يوفّر هذا البث خيارات إضافية من هذا النوع.", 14, Brand.TextTertiary)
                IptvButton("إغلاق") { closeTracks() }
            }
        }
    }
}
