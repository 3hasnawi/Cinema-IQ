package com.cinemaplus.rebuilt.ui

import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.text.CueGroup
import com.cinemaplus.rebuilt.data.SubtitleDiagnostic
import java.io.IOException
import com.cinemaplus.rebuilt.R
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.SceneFilter
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults

private enum class PlayerPanel { Quality, Tracks, SubtitleStyle }
private val PlayerRed = Color.White.copy(alpha = .92f)
private val PlayerNeonRed = Color.White
private const val OverlayTimeoutMillis = 4_000L
/** Scrub speed steps (multiples of the 10 second base step). */
private val ScrubSpeeds = intArrayOf(1, 2, 4, 6)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: CinemaViewModel) {
    val film = vm.playing ?: return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val keyRouter = remember { TvRemoteKeyRouter() }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitleStyleFocus = remember { FocusRequester() }
    val nextEpisodeFocus = remember { FocusRequester() }
    var nextEpisodeFocused by remember { mutableStateOf(false) }
    var ended by remember(film.id) { mutableStateOf(false) }
    val qualityFocus = remember { FocusRequester() }
    var streamIndex by remember(film.id, vm.streams) { mutableIntStateOf(
        vm.streams.indexOfFirst { it.resolution.removeSuffix("p") == vm.quality.removeSuffix("p") }.coerceAtLeast(0)) }
    var controlsVisible by remember(film.id) { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var panel by remember(film.id) { mutableStateOf<PlayerPanel?>(null) }
    var panelReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    // Where each panel button sits in the window. A plain map: it is written on layout and read when a panel opens.
    val anchors = remember { HashMap<PlayerPanel, IntRect>() }
    var panelAnchor by remember { mutableStateOf(IntRect.Zero) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var resumeAfterLifecyclePause by remember { mutableStateOf(false) }
    var previewPosition by remember(film.id) { mutableStateOf<Long?>(null) }
    var playerError by remember(film.id, streamIndex) { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    val isSeries = film.episode > 0 || film.series || vm.selected?.series == true
    val next = vm.nextEpisode()
    val offerNext = film.episode > 0 && PlaybackEndPolicy.offerNextEpisode(position, duration, next != null)
    // Episodes of the season being played, shown as a thumbnail strip when the user presses Down.
    val episodes = remember(vm.seasons, film.id) {
        if (!isSeries) emptyList()
        else (vm.seasons.firstOrNull { s -> s.episodes.any { it.id == film.id } }
            ?: vm.seasons.firstOrNull { it.number == film.season })?.episodes.orEmpty()
    }
    var episodesShown by remember(film.id) { mutableStateOf(false) }
    var seekFocused by remember { mutableStateOf(false) }
    var scrubTick by remember { mutableIntStateOf(0) }
    var lastScrubAt by remember { mutableLongStateOf(0L) }
    var scrubHeldSince by remember { mutableLongStateOf(0L) }
    var scrubLevel by remember { mutableIntStateOf(0) }
    var scrubDir by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun saveProgress() {
        if(player.currentPosition > 0 && player.currentMediaItem?.mediaId == film.id)
            vm.recordProgress(film, player.currentPosition, player.duration.coerceAtLeast(0))
    }
    fun interact() { lastInteraction = SystemClock.uptimeMillis() }
    fun reveal() { interact(); controlsVisible = true }
    fun togglePlayback() { PlaybackEndPolicy.toggle(player) }
    fun seekBy(delta: Long) {
        if(player.isCurrentMediaItemSeekable && player.duration > 0)
            player.seekTo((player.currentPosition + delta).coerceIn(0L, player.duration))
    }
    /**
     * Apple-style scrubbing. A tap jumps 10s; holding accelerates. Playback pauses while scrubbing and,
     * once the remote is idle, resumes from the point reached.
     */
    fun scrub(direction: Int, repeat: Int) {
        if(!player.isCurrentMediaItemSeekable || player.duration <= 0) return
        reveal()
        val now = SystemClock.uptimeMillis()
        val starting = previewPosition == null
        if(starting) { player.pause(); scrubLevel = 0; scrubDir = direction }
        if(direction != scrubDir) { scrubDir = direction; scrubLevel = 0 }
        val base = previewPosition ?: player.currentPosition
        val delta: Long
        if(repeat == 0) {
            // Pressing again within the same scrubbing session steps the speed up: x1, x2, x4, x6.
            if(!starting) scrubLevel = (scrubLevel + 1).coerceAtMost(ScrubSpeeds.lastIndex)
            scrubHeldSince = now
            delta = 10_000L * ScrubSpeeds[scrubLevel]
        } else {
            // Holding the key also climbs the steps the longer it is held.
            val held = now - scrubHeldSince
            val floor = when { held < 800 -> 0; held < 1_800 -> 1; held < 3_200 -> 2; else -> 3 }
            scrubLevel = maxOf(scrubLevel, floor)
            delta = (now - lastScrubAt).coerceIn(0L, 120L) * 10L * ScrubSpeeds[scrubLevel]
        }
        lastScrubAt = now
        previewPosition = (base + direction * delta).coerceIn(0L, player.duration)
        scrubTick++
    }
    fun commitScrub() {
        previewPosition?.let { player.seekTo(it) }
        previewPosition = null
        player.play(); interact()
    }
    fun cancelScrub() { previewPosition = null; player.play() }
    fun openPanel(value: PlayerPanel, returnFocus: FocusRequester) {
        reveal(); panelReturnFocus = returnFocus; panelAnchor = anchors[value] ?: IntRect.Zero; panel = value
    }
    fun closePanel() { panel = null; reveal() }
    fun playEpisode(episode: Film) { saveProgress(); panel = null; vm.play(episode) }

    BackHandler {
        when {
            panel != null -> closePanel()
            episodesShown -> { episodesShown = false; reveal(); runCatching { playFocus.requestFocus() } }
            previewPosition != null -> { cancelScrub(); reveal(); runCatching { seekFocus.requestFocus() } }
            controlsVisible -> controlsVisible = false
            else -> { saveProgress(); vm.stop() }
        }
    }
    DisposableEffect(player, owner, film.id) {
        val diagnose = !film.id.startsWith("vodu:") && !film.id.startsWith("demo-")
        fun trace(message: String) {
            if (diagnose) Log.i("CinemaSubtitle", "media=${film.id} $message")
        }
        fun externalRef(info: LoadEventInfo): Int? = player.currentMediaItem?.localConfiguration
            ?.subtitleConfigurations?.indexOfFirst {
                it.uri == info.dataSpec.uri || it.uri == info.uri
            }?.takeIf { it >= 0 }
        val analytics = object : AnalyticsListener {
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                val ref = externalRef(info)
                if (data.trackType == C.TRACK_TYPE_TEXT || ref != null) {
                    trace("load=completed external=${ref ?: -1} bytes=${info.bytesLoaded} durationMs=${info.loadDurationMs} mime=${data.trackFormat?.sampleMimeType.orEmpty()} contentType=${info.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.joinToString().orEmpty()}")
                }
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo,
                data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                val ref = externalRef(info)
                if (data.trackType == C.TRACK_TYPE_TEXT || ref != null) {
                    // Exception messages and URIs can contain signed links; only the exception type is logged.
                    trace("load=error external=${ref ?: -1} type=${error.javaClass.simpleName} canceled=$wasCanceled")
                }
            }
        }
        if (diagnose) {
            trace("externalCount=${film.subtitles.size}")
            film.subtitles.forEachIndexed { index, subtitle ->
                trace("external=$index lang=${subtitle.language} extension=${SubtitleDiagnostic.extension(subtitle.url)} ref=${SubtitleDiagnostic.reference(subtitle.url)} declaredMime=${subtitle.mimeType}")
            }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                playing = value
                if(!value) controlsVisible = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if(state == Player.STATE_ENDED) {
                    ended = true; controlsVisible = true; previewPosition = null; player.pause()
                    if (player.currentMediaItem?.mediaId == film.id) vm.completePlayback(film)
                }
            }
            override fun onTracksChanged(value: Tracks) {
                tracks = value
                value.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEachIndexed { groupIndex, group ->
                    repeat(group.length) { index ->
                        val format = group.getTrackFormat(index)
                        trace("group=$groupIndex track=$index ref=${SubtitleDiagnostic.reference(format.id.orEmpty())} lang=${format.language.orEmpty()} mime=${format.sampleMimeType.orEmpty()} selected=${group.isTrackSelected(index)} supported=${group.isTrackSupported(index)} external=${format.id.orEmpty().contains("cinema-external-")}")
                    }
                }
            }
            override fun onCues(cueGroup: CueGroup) {
                trace("cues=${cueGroup.cues.size} atMs=${player.currentPosition}")
            }
            override fun onPlayerError(error: PlaybackException) {
                trace("playerError=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName.orEmpty()}")
                playerError = "تعذّر تشغيل الفيديو. تحقق من الشبكة المحلية أو جرّب جودة أخرى."
                controlsVisible = true
            }
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_PAUSE -> { resumeAfterLifecyclePause = player.playWhenReady; saveProgress(); player.pause() }
                Lifecycle.Event.ON_RESUME -> if(resumeAfterLifecyclePause) { resumeAfterLifecyclePause = false; player.play() }
                Lifecycle.Event.ON_STOP -> saveProgress()
                else -> Unit
            }
        }
        player.addListener(listener); if (diagnose) player.addAnalyticsListener(analytics); owner.lifecycle.addObserver(lifecycle)
        onDispose { saveProgress(); player.removeListener(listener); if (diagnose) player.removeAnalyticsListener(analytics); owner.lifecycle.removeObserver(lifecycle) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(vm.subtitleLanguage) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, vm.subtitleLanguage == "off")
            .setPreferredTextLanguage(vm.subtitleLanguage.takeUnless { it == "off" }).build()
    }
    LaunchedEffect(film.id, streamIndex, vm.streams) {
        val stream = vm.streams.getOrNull(streamIndex)
        if(stream == null) {
            if(player.currentMediaItem?.mediaId != film.id) { player.stop(); player.clearMediaItems() }
            return@LaunchedEffect
        }
        val sameFilm = player.currentMediaItem?.mediaId == film.id
        val resume = if(sameFilm) player.currentPosition else vm.library.progress(film.id)
        val resumePlaying = !sameFilm || player.playWhenReady || resumeAfterLifecyclePause
        val preferred = film.subtitles.indexOfFirst { it.language.substringBefore('-') == vm.subtitleLanguage }.takeIf { it >= 0 } ?: 0
        val subtitles = film.subtitles.mapIndexed { index, subtitle ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                .setId("cinema-external-$index").setMimeType(subtitle.mimeType).setLanguage(subtitle.language).setLabel(subtitle.title)
                .setSelectionFlags(if(index == preferred) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        val item = MediaItem.Builder().setMediaId(film.id).setUri(stream.url).setSubtitleConfigurations(subtitles).build()
        player.setMediaItem(item, resume); player.prepare()
        val foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        player.playWhenReady = resumePlaying && foreground
        if(!foreground) resumeAfterLifecyclePause = resumePlaying
    }
    // Skip ranges for the scissors filter: null while analysing, empty when off or nothing was found.
    val skipRanges by produceState<List<LongRange>?>(null, film.id, film.subtitles.size, vm.sceneFilter) {
        value = null
        value = if(vm.sceneFilter) SceneFilter.ranges(context, film) else emptyList()
    }
    LaunchedEffect(skipRanges) {
        val ranges = skipRanges
        if(vm.sceneFilter && ranges != null)
            notice = if(ranges.isEmpty()) "لا مشاهد للتخطي (التحليل يعتمد على الترجمة)" else "فلترة المشاهد: ${ranges.size} مقطع سيُتخطّى"
    }
    LaunchedEffect(notice) { if(notice != null) { delay(2_400); notice = null } }
    LaunchedEffect(player, film.id) {
        var lastSave = SystemClock.uptimeMillis()
        while(true) {
            position = player.currentPosition.coerceAtLeast(0L)
            if(previewPosition == null && player.isPlaying)
                skipRanges?.firstOrNull { position in it }?.let { range ->
                    player.seekTo(range.last + 1); position = range.last + 1; notice = "تم تخطي مشهد"
                }
            duration = player.duration.coerceAtLeast(0L)
            bufferedPosition = player.bufferedPosition.coerceAtLeast(0L)
            if(SystemClock.uptimeMillis() - lastSave >= 5_000) { saveProgress(); lastSave = SystemClock.uptimeMillis() }
            delay(250)
        }
    }
    LaunchedEffect(film.id) { nextEpisodeFocused = false; interact() }
    LaunchedEffect(offerNext, panel, episodesShown) {
        if ((!offerNext || panel != null || episodesShown) && nextEpisodeFocused) {
            nextEpisodeFocused = false
            if (panel == null && !episodesShown) runCatching { if(controlsVisible) seekFocus.requestFocus() else rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(controlsVisible, lastInteraction, playing, panel, previewPosition, buffering, playerError, episodesShown, nextEpisodeFocused) {
        if(controlsVisible && playing && panel == null && previewPosition == null && !episodesShown && !nextEpisodeFocused && !buffering && playerError == null) {
            // Playback state changes must not restart the remote's inactivity deadline.
            val remaining = OverlayTimeoutMillis - (SystemClock.uptimeMillis() - lastInteraction)
            if (remaining > 0) delay(remaining)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, film.id) {
        // The hidden controller leaves a focusable host, so Select never disappears into PlayerView.
        delay(32)
        // The scrubber takes focus first so Left/Right scrub immediately, as on Apple TV.
        if(controlsVisible) seekFocus.requestFocus() else { episodesShown = false; rootFocus.requestFocus() }
    }
    LaunchedEffect(scrubTick) {
        // Remote idle for a moment: continue playing from where scrubbing stopped.
        if(previewPosition != null) { delay(900); commitScrub() }
    }
    LaunchedEffect(panel) {
        if(panel == null && controlsVisible) { delay(32); panelReturnFocus?.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)
        .focusRequester(rootFocus).focusProperties { canFocus = !controlsVisible }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (offerNext && panel == null && !episodesShown && !nextEpisodeFocused &&
                (seekFocused || !controlsVisible) && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    interact(); runCatching { nextEpisodeFocus.requestFocus() }
                }
                return@onPreviewKeyEvent true
            }
            // Let the focused prompt consume Select even when the main overlay is hidden.
            if (nextEpisodeFocused && native.keyCode in listOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A))
                return@onPreviewKeyEvent false
            val horizontal = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
            if(horizontal && !nextEpisodeFocused && !episodesShown && (!controlsVisible || previewPosition != null || seekFocused)) {
                if(native.action == AndroidKeyEvent.ACTION_DOWN)
                    scrub(if(native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, native.repeatCount)
                return@onPreviewKeyEvent true
            }
            val key = when(native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> TvRemoteKeyRouter.Key.Select
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> TvRemoteKeyRouter.Key.PlayPause
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> TvRemoteKeyRouter.Key.Play
                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> TvRemoteKeyRouter.Key.Pause
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> TvRemoteKeyRouter.Key.Left
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> TvRemoteKeyRouter.Key.Right
                AndroidKeyEvent.KEYCODE_DPAD_UP -> TvRemoteKeyRouter.Key.Up
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> TvRemoteKeyRouter.Key.Down
                else -> TvRemoteKeyRouter.Key.Other
            }
            if(key != TvRemoteKeyRouter.Key.Other && native.action == AndroidKeyEvent.ACTION_DOWN) interact()
            val routed = keyRouter.route(key, native.action == AndroidKeyEvent.ACTION_DOWN, native.repeatCount, controlsVisible)
            routed.commands.forEach { command -> when(command) {
                TvRemoteKeyRouter.Command.TogglePlayback -> togglePlayback()
                TvRemoteKeyRouter.Command.Play -> if(!ended) player.play()
                TvRemoteKeyRouter.Command.Pause -> player.pause()
                TvRemoteKeyRouter.Command.SeekBack -> seekBy(-10_000)
                TvRemoteKeyRouter.Command.SeekForward -> seekBy(10_000)
                TvRemoteKeyRouter.Command.Reveal -> reveal()
            } }
            routed.consumed
        }.focusable()) {
        AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx)
            .inflate(R.layout.cinema_player_surface, null, false) as PlayerView).apply {
            this.player = player
            // TextureView keeps transformed Compose controls in the same composition on older TVs.
            // Compose owns all input and visibility; Media3 still renders video and subtitles.
            useController = false; isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        } }, update = { view ->
            view.subtitleView?.apply {
                setApplyEmbeddedStyles(false)
                setApplyEmbeddedFontSizes(false)
                setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, vm.subtitleSize)
                setBottomPaddingFraction(vm.subtitleHeight)
                setStyle(androidx.media3.ui.CaptionStyleCompat(android.graphics.Color.WHITE,
                    android.graphics.Color.argb((vm.subtitleBackground * 255).toInt(), 0, 0, 0),
                    android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK, null))
            }
        }, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(controlsVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .70f), .26f to Color.Transparent,
                .52f to Color.Transparent, 1f to Color.Black.copy(alpha = .90f)))) {
                // Title block, top leading edge like the Apple TV app.
                Column(Modifier.align(Alignment.TopStart).padding(horizontal = TvMetrics.EdgeInset, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(if(isSeries && vm.selected?.series == true) mediaTitle(vm.selected!!) else mediaTitle(film),
                        22, Color.White, true, lines = 1)
                    if(isSeries) Label("الموسم ${film.season} • الحلقة ${film.episode.takeIf { it > 0 } ?: 1}", 13, Color.White.copy(alpha = .75f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        vm.streams.getOrNull(streamIndex)?.resolution?.takeIf(String::isNotBlank)?.let { PlayerBadge(it) }
                        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { group ->
                            (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { index ->
                                group.getTrackFormat(index).channelCount.takeIf { it > 0 }?.let { PlayerBadge("$it قنوات") }
                            }
                        }
                    }
                }
                PlayerControl("إغلاق", "×", modifier = Modifier.align(Alignment.TopEnd)
                    .padding(horizontal = TvMetrics.EdgeInset, vertical = 22.dp)
                    .focusRequester(backFocus).focusProperties { down = seekFocus }) { saveProgress(); vm.stop() }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = TvMetrics.EdgeInset, vertical = 20.dp)) {
                    PlayerSeekBar(position = previewPosition ?: position, duration = duration,
                        previewing = previewPosition != null, bufferedPosition = bufferedPosition,
                        speedLabel = if(previewPosition != null && scrubLevel > 0)
                            "${if(scrubDir < 0) "◀◀" else "▶▶"} ×${ScrubSpeeds[scrubLevel]}" else "",
                        modifier = Modifier.focusRequester(seekFocus).focusProperties { down = playFocus; up = if(offerNext) nextEpisodeFocus else backFocus },
                        onFocusChange = { seekFocused = it },
                        onSelect = { if(previewPosition != null) commitScrub() else { interact(); togglePlayback() } })
                    Spacer(Modifier.height(8.dp))
                    // The transport group stays physically centered independently of RTL text.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                            // Down from the transport row reveals the episode strip (series only).
                            if(episodes.isNotEmpty() && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                                if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) { interact(); episodesShown = true }
                                true
                            } else false
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            PlayerControl(if(playing) "إيقاف مؤقت" else "تشغيل", if(playing) "Ⅱ" else "▶", main = true, enabled = !ended,
                                modifier = Modifier.focusRequester(playFocus).focusProperties { up = seekFocus }) { interact(); togglePlayback() }
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End)) {
                                PlayerControl("الصوت والترجمة", "CC",
                                    modifier = Modifier.focusRequester(audioFocus).focusProperties { up = seekFocus },
                                    onPlaced = { anchors[PlayerPanel.Tracks] = it }) {
                                    openPanel(PlayerPanel.Tracks, audioFocus)
                                }
                                PlayerControl("حجم الترجمة وخلفيتها وارتفاعها", "Aa",
                                    modifier = Modifier.focusRequester(subtitleStyleFocus).focusProperties { up = seekFocus },
                                    onPlaced = { anchors[PlayerPanel.SubtitleStyle] = it }) {
                                    openPanel(PlayerPanel.SubtitleStyle, subtitleStyleFocus)
                                }
                                PlayerControl(if(vm.sceneFilter) "فلترة المشاهد: مفعّلة" else "فلترة المشاهد غير اللائقة", "✂", active = vm.sceneFilter,
                                    modifier = Modifier.focusProperties { up = seekFocus }) {
                                    interact(); vm.chooseSceneFilter(!vm.sceneFilter)
                                    notice = if(vm.sceneFilter) "جارٍ تحليل المشاهد…" else "أُوقفت فلترة المشاهد"
                                }
                                PlayerControl("الجودة", "HD", modifier = Modifier.focusRequester(qualityFocus).focusProperties { up = seekFocus },
                                    onPlaced = { anchors[PlayerPanel.Quality] = it },
                                    enabled = vm.streams.isNotEmpty()) { openPanel(PlayerPanel.Quality, qualityFocus) }
                            }
                        }
                    }
                    AnimatedVisibility(episodesShown && episodes.isNotEmpty(),
                        enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(140)) + shrinkVertically(tween(180))) {
                        EpisodeStrip(episodes, film.id, onPlay = ::playEpisode,
                            onUp = { episodesShown = false; runCatching { playFocus.requestFocus() } })
                    }
                }
            }
        }
        if(offerNext && next != null && panel == null && !episodesShown) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                TvActionButton("الحلقة التالية", icon = Icons.Rounded.PlayArrow, modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = TvMetrics.EdgeInset, bottom = 142.dp)
                    .focusRequester(nextEpisodeFocus)
                    .onFocusChanged { nextEpisodeFocused = it.isFocused }
                    .focusProperties { down = if(controlsVisible) seekFocus else rootFocus; up = if(controlsVisible) backFocus else rootFocus }) {
                    playEpisode(next)
                }
            }
        }
        notice?.let { text ->
            Label(text, 13, Color.White, true, lines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                    .background(GlassStrong, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if(buffering || vm.playbackBusy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        val error = vm.playbackError ?: playerError
        if(error != null && controlsVisible) Column(Modifier.align(Alignment.Center).widthIn(max = 570.dp)
            .background(GlassStrong, RoundedCornerShape(24.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label(error, 15, Color.White, lines = 3)
            if(playerError != null) Action("أعد المحاولة") { playerError = null; reveal(); player.prepare(); player.play() }
        }
    }
    panel?.let { shown ->
        PlayerOptionsDialog(title = when(shown) {
            PlayerPanel.Quality -> "جودة الفيديو"; PlayerPanel.Tracks -> "الصوت والترجمة"; PlayerPanel.SubtitleStyle -> "مظهر الترجمة"
        },
            anchor = panelAnchor,
            width = when(shown) { PlayerPanel.Quality -> 240.dp; PlayerPanel.Tracks -> 340.dp; PlayerPanel.SubtitleStyle -> 320.dp },
            onDismiss = ::closePanel,
            onMediaToggle = { interact(); togglePlayback() }) { firstFocus ->
            when(shown) {
                PlayerPanel.Quality -> vm.streams.forEachIndexed { index, stream ->
                    PlayerOption(stream.resolution, selected = index == streamIndex,
                        modifier = if(index == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                        saveProgress(); vm.chooseQuality(stream.resolution); streamIndex = index; closePanel()
                    }
                }
                PlayerPanel.SubtitleStyle -> {
                    // Each row cycles to the next value and stays open, so the sample below shows the change at once.
                    val sizes = listOf(16f, 18f, 20f, 22f, 26f, 30f, 34f, 40f)
                    val backgrounds = listOf(0f, .25f, .5f, .75f, 1f)
                    fun nearest(values: List<Float>, current: Float) =
                        values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
                    PlayerOption("حجم الخط: ${vm.subtitleSize.toInt()}", false, Modifier.focusRequester(firstFocus)) {
                        interact(); vm.chooseSubtitleSize(sizes[(nearest(sizes, vm.subtitleSize) + 1) % sizes.size])
                    }
                    PlayerOption("الخلفية: " + if(vm.subtitleBackground == 0f) "بدون" else "${(vm.subtitleBackground * 100).toInt()}%", false) {
                        interact(); vm.chooseSubtitleBackground(backgrounds[(nearest(backgrounds, vm.subtitleBackground) + 1) % backgrounds.size])
                    }
                    PlayerOption("الارتفاع: " + subtitleHeightLabel(vm.subtitleHeight), false) {
                        interact(); vm.chooseSubtitleHeight(nextSubtitleHeight(vm.subtitleHeight))
                    }
                    PlayerOption("إعادة الضبط الافتراضي", false) {
                        interact(); vm.chooseSubtitleSize(22f); vm.chooseSubtitleBackground(.5f); vm.chooseSubtitleHeight(.08f)
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).background(Color.White.copy(alpha = .10f), RoundedCornerShape(14.dp))
                        .padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                        Label("هذا نص تجريبي للترجمة", vm.subtitleSize.toInt(), Color.White, false, lines = 1,
                            modifier = Modifier.background(Color.Black.copy(alpha = vm.subtitleBackground)).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                PlayerPanel.Tracks -> {
                    Label("الترجمة", 14, Muted, true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                    PlayerOption("إيقاف الترجمة", selected = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
                        modifier = Modifier.focusRequester(firstFocus)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); closePanel()
                    }
                    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    var textIndex = 0
                    textGroups.forEach { group -> repeat(group.length) { index ->
                        if(group.isTrackSupported(index)) {
                            textIndex++
                            PlayerOption(trackLabel(group.getTrackFormat(index), "ترجمة $textIndex"), group.isTrackSelected(index)) {
                                if (!film.id.startsWith("vodu:")) Log.i("CinemaSubtitle", "media=${film.id} action=select group=${textGroups.indexOf(group)} track=$index lang=${group.getTrackFormat(index).language.orEmpty()} mime=${group.getTrackFormat(index).sampleMimeType.orEmpty()}")
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setPreferredTextLanguage(group.getTrackFormat(index).language)
                                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)).build(); closePanel()
                            }
                        }
                    } }
                    if(textGroups.isEmpty()) Label("لا توجد مسارات ترجمة متاحة في هذا الفيديو.", 12, Muted)
                    Label("الصوت", 14, Muted, true, modifier = Modifier.padding(start = 6.dp, top = 16.dp))
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    var audioIndex = 0
                    audioGroups.forEach { group -> repeat(group.length) { index ->
                        if(group.isTrackSupported(index)) {
                            audioIndex++
                            PlayerOption(trackLabel(group.getTrackFormat(index), "صوت $audioIndex"), group.isTrackSelected(index)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    .setPreferredAudioLanguage(group.getTrackFormat(index).language)
                                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)).build(); closePanel()
                            }
                        }
                    } }
                    if(audioGroups.isEmpty()) Label("جارٍ قراءة مسارات الصوت…", 12, Muted)
                }
            }
        }
    }
}

private fun trackLabel(format: Format, fallback: String): String {
    if(!format.label.isNullOrBlank()) return format.label!!
    val language = format.language?.takeUnless { it == "und" || it.isBlank() } ?: return fallback
    return Locale.forLanguageTag(language).getDisplayLanguage(Locale.forLanguageTag("ar")).ifBlank { fallback }
}

@Composable
private fun PlayerBadge(text: String) {
    Label(text, 10, Color.White.copy(alpha = .85f), true,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = .4f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 0.dp))
}

/**
 * Round glass button. Every button uses the same 60dp circle (72dp for play) so the rows align on one
 * center line; the label is an overlay under the focused button and never changes the layout.
 */
@Composable
private fun PlayerControl(
    label: String, symbol: String, main: Boolean = false, modifier: Modifier = Modifier,
    enabled: Boolean = true, active: Boolean = false, onPlaced: (IntRect) -> Unit = {}, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.12f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "Player control focus")
    val size = if(main) 52.dp else 42.dp
    Box(Modifier.size(size).onGloballyPositioned { box ->
        val r = box.boundsInWindow()
        onPlaced(IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
    }, contentAlignment = Alignment.Center) {
        TvButton(onClick = onClick, enabled = enabled,
            modifier = modifier.size(size)
                .zIndex(if(focused) 10f else 0f)
                .onFocusChanged { focused = it.isFocused }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale; clip = false
                    shadowElevation = if(focused) 22.dp.toPx() else 0f
                    shape = CircleShape
                }
                .semantics { contentDescription = label },
            colors = TvButtonDefaults.colors(containerColor = if(active) Accent else Color.White.copy(alpha = .20f),
                contentColor = if(active) OnAccent else Color.White,
                focusedContainerColor = Accent, focusedContentColor = OnAccent,
                disabledContainerColor = Color.White.copy(alpha = .08f), disabledContentColor = Color.White.copy(alpha = .35f)),
            scale = TvButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
            shape = TvButtonDefaults.shape(shape = CircleShape),
            contentPadding = PaddingValues(0.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlayerTransportIcon(symbol, if(focused || active) OnAccent else Color.White, if(main) 24.dp else 19.dp)
            }
        }
        if(focused) Label(label, 11, Color.White, true, lines = 1,
            modifier = Modifier.wrapContentSize(unbounded = true).offset(y = size / 2 + 16.dp))
    }
}

/**
 * tvOS scrubber: a thin bar that thickens on focus. Left/Right scrubbing is handled by the player root
 * (so it also works while the controls are hidden); this view shows the position and a time bubble.
 */
@Composable
private fun PlayerSeekBar(
    position: Long, duration: Long, previewing: Boolean, bufferedPosition: Long, speedLabel: String, modifier: Modifier,
    onFocusChange: (Boolean) -> Unit, onSelect: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var selectPressed by remember { mutableStateOf(false) }
    val active = focused || previewing
    val thickness by animateFloatAsState(if(active) 7f else 4f, tween(160), label = "Seek bar thickness")
    Column(modifier.onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
        .onPreviewKeyEvent { event ->
            val code = event.nativeKeyEvent.keyCode
            if(code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
                code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER || code == AndroidKeyEvent.KEYCODE_BUTTON_A) {
                // Act on key-up so a single press cannot both commit and toggle playback.
                if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                    if(event.nativeKeyEvent.repeatCount == 0) selectPressed = true
                } else if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_UP && selectPressed) {
                    selectPressed = false; onSelect()
                }
                true
            } else false
        }.semantics { contentDescription = "موضع الفيديو: ${formatPlayerTime(position)} من ${formatPlayerTime(duration)}" }
        .focusable()) {
        // Fixed-height slot for the time bubble keeps the layout still while scrubbing.
        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            if(previewing) Box(Modifier.background(GlassStrong, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 2.dp)) {
                Label(if(speedLabel.isBlank()) formatPlayerTime(position) else "${formatPlayerTime(position)}   $speedLabel", 12, Color.White, true)
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val fraction = if(duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val buffered = if(duration > 0) (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(thickness.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f)))
                Box(Modifier.fillMaxWidth(buffered).height(thickness.dp).clip(CircleShape).background(Color.White.copy(alpha = .38f)))
                Box(Modifier.fillMaxWidth(fraction).height(thickness.dp).clip(CircleShape).background(Accent))
                if(active) Box(Modifier.offset(x = (maxWidth * fraction) - 6.dp).size(12.dp).background(Color.White, CircleShape))
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label(formatPlayerTime(position), 11, Color.White.copy(alpha = .85f), true)
                Label("-${if(duration > 0) formatPlayerTime((duration - position).coerceAtLeast(0L)) else "—"}", 11, Color.White.copy(alpha = .85f), true)
            }
        }
    }
}

/** Small thumbnails of the season's episodes with the episode number underneath in a small font. */
@Composable
private fun EpisodeStrip(episodes: List<Film>, currentId: String, onPlay: (Film) -> Unit, onUp: () -> Unit) {
    val currentIndex = episodes.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
    LazyRow(state = state,
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().focusGroup().onPreviewKeyEvent { event ->
            if(event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) onUp()
                true
            } else false
        },
        contentPadding = PaddingValues(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
            EpisodeThumb(episode, episode.id == currentId, if(index == currentIndex) Modifier.focusRequester(first) else Modifier) { onPlay(episode) }
        }
    }
}

@Composable
private fun EpisodeThumb(film: Film, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.06f else 1f, tween(140), label = "Episode focus")
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(128.dp).graphicsLayer { scaleX = scale; scaleY = scale }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier.fillMaxWidth().aspectRatio(TvMetrics.LandscapeRatio).clip(shape).background(Panel)
            .then(if(focused || current) Modifier.border(if(focused) 2.dp else 1.dp, if(focused) Accent else Accent.copy(alpha = .6f), shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)) {
            val image = film.backdrop.ifBlank { film.poster }
            if(image.isNotBlank()) coil.compose.AsyncImage(image, null, Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Spacer(Modifier.height(4.dp))
        Label("الحلقة ${film.episode}", 10, Color.White.copy(alpha = if(focused || current) 1f else .7f), focused || current, lines = 1)
    }
}

private fun formatPlayerTime(value: Long): String {
    val seconds = value.coerceAtLeast(0L) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

@Composable
private fun PlayerOption(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if(focused) Accent else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Label(text, 15, if(focused) OnAccent else Color.White, selected, lines = 1, modifier = Modifier.weight(1f))
        if(selected) Label("✓", 16, if(focused) OnAccent else Accent, true)
    }
}

@Composable
private fun PlayerOptionsDialog(
    title: String, anchor: IntRect, width: Dp, onDismiss: () -> Unit, onMediaToggle: () -> Unit,
    content: @Composable ColumnScope.(FocusRequester) -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val margin = with(density) { 16.dp.roundToPx() }
    val windowWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    // Horizontal start of the panel and the point of the icon it grows from (as a fraction of the panel width).
    val panelStart = (anchor.center.x - widthPx / 2).coerceIn(margin, (windowWidth - widthPx - margin).coerceAtLeast(margin))
    val originX = if(anchor == IntRect.Zero) .5f else ((anchor.center.x - panelStart).toFloat() / widthPx).coerceIn(0f, 1f)
    val gap = with(density) { 14.dp.roundToPx() }
    val shape = RoundedCornerShape(22.dp)
    LaunchedEffect(Unit) { entered = true }
    Popup(popupPositionProvider = remember(anchor, widthPx, gap, margin) { AboveAnchor(anchor, widthPx, gap, margin) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        // Grows out of the button like a drop: from its bottom edge upwards, sized to its content.
        AnimatedVisibility(entered,
            enter = fadeIn(tween(140)) + scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = .3f,
                transformOrigin = TransformOrigin(originX, 1f)) +
                expandVertically(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(100))) {
            Column(Modifier.width(width).heightIn(max = 360.dp)
                .background(Color(0xD9170F1C), shape).border(1.dp, Color.White.copy(alpha = .10f), shape)
                .padding(8.dp).onPreviewKeyEvent { event ->
                    if(event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount == 0) onMediaToggle()
                        true
                    } else false
                }.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(title, 13, Muted, true, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
                content(firstFocus)
            }
        }
        LaunchedEffect(Unit) { delay(260); runCatching { firstFocus.requestFocus() } }
    }
}

/** Places a panel directly above the button that opened it, centred on it and kept inside the window. */
private class AboveAnchor(private val anchor: IntRect, private val widthPx: Int, private val gap: Int, private val margin: Int) :
    PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val a = if(anchor == IntRect.Zero) anchorBounds else anchor
        val x = (a.center.x - widthPx / 2).coerceIn(margin, (windowSize.width - widthPx - margin).coerceAtLeast(margin))
        val y = (a.top - popupContentSize.height - gap).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}
