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
import com.cinemaplus.rebuilt.data.PlaybackSpeed
import com.cinemaplus.rebuilt.data.SceneFilter
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

private enum class PlayerPanel { Quality, Tracks, SubtitleStyle }
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
    PlaybackScreenAwake(player)
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
    // Playback speed is a viewing habit, not a per-film setting, so it survives episode changes.
    var speed by remember { mutableFloatStateOf(1f) }
    var countdown by remember(film.id) { mutableStateOf<Int?>(null) }
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
    val offerNext = film.episode > 0 && PlaybackEndPolicy.offerNextEpisode(position, duration, next != null, film.markers.creditsStartMs)
    val introTarget = PlaybackEndPolicy.skipIntroTarget(position, duration, film.markers)
    // One prompt slot: skip-intro early in the episode, next-episode near its end.
    val promptShown = introTarget != null || offerNext
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
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    // After an episode ends, count down and start the next one unless the viewer turns it off or leaves.
    LaunchedEffect(ended, next?.id, vm.autoNext) {
        if (ended && next != null && film.episode > 0 && vm.autoNext) {
            for (left in PlaybackSpeed.AUTO_NEXT_SECONDS downTo 1) { countdown = left; delay(1000) }
            countdown = null
            playEpisode(next)
        } else countdown = null
    }

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
        val diagnose = !film.id.startsWith("vodu:") && !com.cinemaplus.rebuilt.data.DawwirliMapper.owns(film.id) && !film.id.startsWith("demo-")
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
    val promptKind = if(introTarget != null) "intro" else if(offerNext) "next" else null
    var autoFocusedPrompt by remember(film.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(film.id, promptKind, panel, episodesShown, previewPosition != null, buffering, playerError) {
        if(promptKind == null) autoFocusedPrompt = null
        else if(autoFocusedPrompt != promptKind && panel == null && !episodesShown &&
            previewPosition == null && !buffering && playerError == null) {
            // Let controller/panel restoration finish and the prompt attach before claiming focus once.
            delay(64)
            if(awaitTvFocus { nextEpisodeFocus }) {
                autoFocusedPrompt = promptKind
                interact()
            }
        }
    }
    LaunchedEffect(promptShown, panel, episodesShown) {
        if ((!promptShown || panel != null || episodesShown) && nextEpisodeFocused) {
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
        if(!nextEpisodeFocused) {
            // While skip-intro or next-episode shows, it keeps focus when the controls come up.
            if(controlsVisible) { if(promptShown) runCatching { nextEpisodeFocus.requestFocus() } else seekFocus.requestFocus() }
            else { episodesShown = false; rootFocus.requestFocus() }
        }
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
            if (promptShown && panel == null && !episodesShown && !nextEpisodeFocused &&
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
            Box(Modifier.fillMaxSize().drawBehind {
                // Top and bottom scrims of the brief: 260 px from the top, 420 px from the bottom (of 1080).
                val top = size.height * 260f / 1080f
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .75f), 1f to Color.Transparent, endY = top),
                    size = Size(size.width, top))
                val bottom = size.height * (1f - 420f / 1080f)
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f), startY = bottom, endY = size.height),
                    topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
            }) {
                // Title block at the top start (right in RTL): series name small, episode label large.
                Column(Modifier.align(Alignment.TopStart).padding(start = 96.mx, top = 54.mx),
                    verticalArrangement = Arrangement.spacedBy(6.mx)) {
                    if(isSeries) {
                        Label(if(vm.selected?.series == true) mediaTitle(vm.selected!!) else mediaTitle(film), 12, Brand.TextTertiary, lines = 1)
                        Label("الموسم ${film.season} · الحلقة ${film.episode.takeIf { it > 0 } ?: 1}", 20, Color.White, true, lines = 1)
                    } else Label(mediaTitle(film), 20, Color.White, true, lines = 1)
                    tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { group ->
                        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { index ->
                            group.getTrackFormat(index).channelCount.takeIf { it > 0 }?.let { Label("$it قنوات صوت", 10, Brand.TextMuted, lines = 1) }
                        }
                    }
                }
                PlayerControl("إغلاق", "×", modifier = Modifier.focusRequester(backFocus).focusProperties { down = seekFocus },
                    outer = Modifier.align(Alignment.TopEnd).padding(end = 96.mx, top = 48.mx)) { saveProgress(); vm.stop() }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 96.mx, end = 96.mx, bottom = 72.mx)) {
                    PlayerSeekBar(position = previewPosition ?: position, duration = duration,
                        previewing = previewPosition != null, bufferedPosition = bufferedPosition,
                        speedLabel = if(previewPosition != null && scrubLevel > 0)
                            "${if(scrubDir < 0) "◀◀" else "▶▶"} ×${ScrubSpeeds[scrubLevel]}" else "",
                        modifier = Modifier.focusRequester(seekFocus).focusProperties { down = playFocus; up = if(promptShown) nextEpisodeFocus else backFocus },
                        onFocusChange = { seekFocused = it },
                        onSelect = { if(previewPosition != null) commitScrub() else { interact(); togglePlayback() } })
                    Spacer(Modifier.height(28.mx))
                    val up = Modifier.focusProperties { up = seekFocus }
                    Box(Modifier.fillMaxWidth().height(96.mx).onPreviewKeyEvent { event ->
                        // Down from the controls row reveals the episode strip (series only).
                        if(episodes.isNotEmpty() && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                            if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) { interact(); episodesShown = true }
                            true
                        } else false
                    }) {
                        // Start side (right in RTL): subtitles/audio and episodes pills, then subtitle style and the scene filter.
                        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(16.mx),
                            verticalAlignment = Alignment.CenterVertically) {
                            PlayerPill("الترجمة والصوت", "CC", modifier = up.focusRequester(audioFocus),
                                onPlaced = { anchors[PlayerPanel.Tracks] = it }) { openPanel(PlayerPanel.Tracks, audioFocus) }
                            if(episodes.isNotEmpty()) PlayerPill("الحلقات", "☷", modifier = up) {
                                interact(); episodesShown = !episodesShown
                            }
                            PlayerControl("مظهر الترجمة", "Aa", modifier = up.focusRequester(subtitleStyleFocus),
                                onPlaced = { anchors[PlayerPanel.SubtitleStyle] = it }) {
                                openPanel(PlayerPanel.SubtitleStyle, subtitleStyleFocus)
                            }
                            PlayerControl(if(vm.sceneFilter) "فلترة المشاهد: مفعّلة" else "فلترة المشاهد غير اللائقة", "✂", active = vm.sceneFilter,
                                modifier = up) {
                                interact(); vm.chooseSceneFilter(!vm.sceneFilter)
                                notice = if(vm.sceneFilter) "جارٍ تحليل المشاهد…" else "أُوقفت فلترة المشاهد"
                            }
                        }
                        // Transport in the centre, always left to right.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.mx),
                                verticalAlignment = Alignment.CenterVertically) {
                                TransportButton("↶10", "رجوع ١٠ ثوانٍ", up) { interact(); seekBy(-10_000) }
                                PlayPauseButton(playing, enabled = !ended, modifier = up.focusRequester(playFocus)) { interact(); togglePlayback() }
                                TransportButton("10↷", "تقديم ١٠ ثوانٍ", up) { interact(); seekBy(10_000) }
                            }
                        }
                        // End side (left in RTL): the quality badge. Next episode appears above the timeline near the end instead.
                        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(16.mx),
                            verticalAlignment = Alignment.CenterVertically) {
                            QualityBadge(vm.streams.getOrNull(streamIndex)?.resolution?.takeIf(String::isNotBlank) ?: "الجودة",
                                enabled = vm.streams.isNotEmpty(), modifier = up.focusRequester(qualityFocus),
                                onPlaced = { anchors[PlayerPanel.Quality] = it }) { openPanel(PlayerPanel.Quality, qualityFocus) }
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
        if(promptShown && panel == null && !episodesShown) {
            // Skip-intro / next-episode prompt above the controls on the end side (left in RTL).
            // Right above the timeline on the end side; it takes focus when it appears (see the prompt effect).
            val promptModifier = Modifier.align(Alignment.BottomEnd).padding(end = 96.mx, bottom = 252.mx)
                .focusRequester(nextEpisodeFocus)
                .onFocusChanged { nextEpisodeFocused = it.isFocused }
                .focusProperties { down = if(controlsVisible) seekFocus else rootFocus; up = if(controlsVisible) backFocus else rootFocus }
            if(introTarget != null) PlayerPill("تخطي المقدمة", "▶|", primary = true, modifier = promptModifier) { player.seekTo(introTarget); interact() }
            else next?.let { upcoming -> NextEpisodeCard(upcoming, countdown, promptModifier) { playEpisode(upcoming) } }
        }
        notice?.let { text ->
            Label(text, 13, Color.White, true, lines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.mx)
                    .background(GlassStrong, RoundedCornerShape(24.mx)).padding(horizontal = 32.mx, vertical = 10.mx))
        }
        if(buffering || vm.playbackBusy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        val error = vm.playbackError ?: playerError
        if(error != null && controlsVisible) Column(Modifier.align(Alignment.Center).widthIn(max = 1140.mx)
            .background(GlassStrong, RoundedCornerShape(24.mx)).padding(horizontal = 48.mx, vertical = 40.mx), verticalArrangement = Arrangement.spacedBy(24.mx)) {
            Label(error, 15, Color.White, lines = 3)
            if(playerError != null) Action("أعد المحاولة") { playerError = null; reveal(); player.prepare(); player.play() }
            else if(vm.playbackError != null) Action("أعد المحاولة") { vm.play(film) }
        }
    }
    panel?.let { shown ->
        PlayerOptionsDialog(title = when(shown) {
            PlayerPanel.Quality -> "الجودة والسرعة"; PlayerPanel.Tracks -> "الصوت والترجمة"; PlayerPanel.SubtitleStyle -> "مظهر الترجمة"
        },
            anchor = panelAnchor,
            width = when(shown) { PlayerPanel.Quality -> 300.mx; PlayerPanel.Tracks -> 400.mx; PlayerPanel.SubtitleStyle -> 420.mx },
            onDismiss = ::closePanel,
            onMediaToggle = { interact(); togglePlayback() }) { firstFocus ->
            when(shown) {
                PlayerPanel.Quality -> {
                    vm.streams.forEachIndexed { index, stream ->
                        PlayerOption(stream.resolution, selected = index == streamIndex,
                            modifier = if(index == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                            saveProgress(); vm.chooseQuality(stream.resolution); streamIndex = index; closePanel()
                        }
                    }
                    // Cycles and stays open so the change is felt immediately.
                    PlayerOption("السرعة: ${PlaybackSpeed.label(speed)}", false,
                        if(vm.streams.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier) {
                        interact(); speed = PlaybackSpeed.next(speed)
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
                    Box(Modifier.fillMaxWidth().padding(top = 10.mx).background(Brand.Raised, RoundedCornerShape(18.mx))
                        .padding(vertical = 20.mx), contentAlignment = Alignment.Center) {
                        // The sample keeps the real subtitle size: this screen runs under the TV text scale.
                        androidx.compose.material3.Text("هذا نص تجريبي للترجمة", color = Color.White, fontFamily = Cairo,
                            fontSize = (vm.subtitleSize / TvMetrics.TextScale).sp, maxLines = 1,
                            modifier = Modifier.background(Color.Black.copy(alpha = vm.subtitleBackground)).padding(horizontal = 16.mx, vertical = 4.mx))
                    }
                }
                PlayerPanel.Tracks -> {
                    Label("الترجمة", 10, Brand.TextMuted, true, modifier = Modifier.padding(start = 20.mx, top = 4.mx))
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
                                if (!film.id.startsWith("vodu:") && !com.cinemaplus.rebuilt.data.DawwirliMapper.owns(film.id)) Log.i("CinemaSubtitle", "media=${film.id} action=select group=${textGroups.indexOf(group)} track=$index lang=${group.getTrackFormat(index).language.orEmpty()} mime=${group.getTrackFormat(index).sampleMimeType.orEmpty()}")
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setPreferredTextLanguage(group.getTrackFormat(index).language)
                                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)).build(); closePanel()
                            }
                        }
                    } }
                    if(textGroups.isEmpty()) Label("لا توجد مسارات ترجمة متاحة في هذا الفيديو.", 11, Brand.TextMuted, modifier = Modifier.padding(horizontal = 12.mx))
                    Label("الصوت", 10, Brand.TextMuted, true, modifier = Modifier.padding(start = 20.mx, top = 14.mx))
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
                    if(audioGroups.isEmpty()) Label("جارٍ قراءة مسارات الصوت…", 11, Brand.TextMuted, modifier = Modifier.padding(horizontal = 12.mx))
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

/** Glass circle (subtitle style, scene filter, close); white with a dark glyph when focused, accent when active. */
@Composable
private fun PlayerControl(
    label: String, symbol: String, modifier: Modifier = Modifier, outer: Modifier = Modifier,
    enabled: Boolean = true, active: Boolean = false, onPlaced: (IntRect) -> Unit = {}, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.06f else 1f, tween(140), label = "Player control focus")
    val size = 64.mx
    Box(outer.size(size).onGloballyPositioned { box -> onPlaced(box.windowRect()) }, contentAlignment = Alignment.Center) {
        Box(modifier.size(size).zIndex(if(focused) 10f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if(enabled) 1f else .4f }
            .background(when { focused -> Color.White; active -> Brand.Accent; else -> Brand.GlassFill }, CircleShape)
            .then(if(focused || active) Modifier else Modifier.border(1.dp, Brand.GlassStroke, CircleShape))
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            PlayerTransportIcon(symbol, when { focused -> Brand.OnPrimary; active -> Brand.OnAccent; else -> Color.White }, 26.mx)
        }
        if(focused) Label(label, 10, Color.White, true, lines = 1,
            modifier = Modifier.wrapContentSize(unbounded = true).offset(y = size / 2 + 22.mx))
    }
}

/** 64 px pill: glass with white content, white with dark content when focused; primary is white with an accent ring. */
@Composable
private fun PlayerPill(text: String, symbol: String, primary: Boolean = false, modifier: Modifier = Modifier,
    onPlaced: (IntRect) -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.04f else 1f, tween(140), label = "Player pill focus")
    val radius = 32.mx
    val shape = RoundedCornerShape(radius)
    val light = primary || focused
    val content = if(light) Brand.OnPrimary else Color.White
    Row(modifier.zIndex(if(focused) 1f else 0f).onGloballyPositioned { onPlaced(it.windowRect()) }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if(primary) Modifier.focusRingBehind(focused, radius) else Modifier)
        .height(64.mx)
        .background(if(light) Color.White else Color.White.copy(alpha = .14f), shape)
        .then(if(light) Modifier else Modifier.border(1.dp, Brand.GlassStroke, shape))
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .padding(horizontal = 26.mx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mx)) {
        PlayerTransportIcon(symbol, content, 26.mx)
        Label(text, 13, content, primary, lines = 1)
    }
}

/** Seek ±10 s: a bare glyph that turns into a white circle when focused. */
@Composable
private fun TransportButton(symbol: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(72.mx).background(if(focused) Color.White else Color.Transparent, CircleShape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        PlayerTransportIcon(symbol, if(focused) Brand.OnPrimary else Color.White, 40.mx)
    }
}

/** 96 px white play/pause circle; focused it gets the accent ring behind a dark gap. */
@Composable
private fun PlayPauseButton(playing: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.04f else 1f, tween(140), label = "Play focus")
    Box(modifier.zIndex(if(focused) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if(enabled) 1f else .5f }
        .focusRingBehind(focused, 48.mx)
        .size(96.mx).background(Color.White, CircleShape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
        .semantics { contentDescription = if(playing) "إيقاف مؤقت" else "تشغيل" }, contentAlignment = Alignment.Center) {
        PlayerTransportIcon(if(playing) "Ⅱ" else "▶", Brand.Background, 40.mx)
    }
}

/** Outlined quality badge; it opens the quality and speed panel, white when focused. */
@Composable
private fun QualityBadge(text: String, enabled: Boolean, modifier: Modifier, onPlaced: (IntRect) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.mx)
    Box(modifier.onGloballyPositioned { onPlaced(it.windowRect()) }.height(40.mx)
        .graphicsLayer { alpha = if(enabled) 1f else .5f }
        .background(if(focused) Color.White else Color.Transparent, shape)
        .border(1.dp, if(focused) Color.White else Color.White.copy(alpha = .2f), shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
        .semantics { contentDescription = "الجودة والسرعة" }
        .padding(horizontal = 14.mx), contentAlignment = Alignment.Center) {
        Label(text, 10, if(focused) Brand.OnPrimary else Color.White, true, lines = 1)
    }
}

/** "Next episode in N s" card: still of the next episode, caption and title; white ring when focused. */
@Composable
private fun NextEpisodeCard(next: Film, countdown: Int?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.mx)
    Row(modifier.width(440.mx)
        .drawWithContent {
            drawContent()
            if(focused) {
                val stroke = 5.mx.toPx(); val outset = 3.mx.toPx() + stroke / 2
                drawRoundRect(Color.White, Offset(-outset, -outset), Size(size.width + outset * 2, size.height + outset * 2),
                    CornerRadius(16.mx.toPx() + outset), style = Stroke(stroke))
            }
        }
        .background(Brand.Surface.copy(alpha = .92f), shape).border(1.dp, Brand.Divider, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .padding(16.mx), horizontalArrangement = Arrangement.spacedBy(16.mx), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(160.mx, 90.mx).clip(RoundedCornerShape(10.mx)).background(Brand.Raised)) {
            val image = next.backdrop.ifBlank { next.poster }
            if(image.isNotBlank()) coil.compose.AsyncImage(image, null, Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.mx)) {
            Label(countdown?.let { "الحلقة التالية خلال ${arabicSeconds(it)}" } ?: "الحلقة التالية", 10, Brand.TextTertiary, lines = 1)
            Label("الحلقة ${next.episode}", 13, Color.White, true, lines = 1)
        }
    }
}

private fun arabicSeconds(value: Int): String = when {
    value == 1 -> "ثانية"
    value == 2 -> "ثانيتين"
    value in 3..10 -> "$value ثوانٍ"
    else -> "$value ثانية"
}

private fun androidx.compose.ui.layout.LayoutCoordinates.windowRect(): IntRect {
    val r = boundsInWindow()
    return IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
}

/**
 * Timeline of the brief, always left to right: elapsed, bar (track 25 %, buffered 40 %, played in accent, white
 * thumb), remaining. Left/Right scrubbing is handled by the player root (so it also works while the controls are
 * hidden); this view shows the position, and a time bubble above the thumb while scrubbing.
 */
@Composable
private fun PlayerSeekBar(
    position: Long, duration: Long, previewing: Boolean, bufferedPosition: Long, speedLabel: String, modifier: Modifier,
    onFocusChange: (Boolean) -> Unit, onSelect: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var selectPressed by remember { mutableStateOf(false) }
    val active = focused || previewing
    val halo by animateFloatAsState(if(active) 1f else 0f, tween(160), label = "Seek focus")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
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
            .focusable(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.mx)) {
            Label(formatPlayerTime(position), 13, Brand.TextSecondary, lines = 1)
            val fraction = if(duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val buffered = if(duration > 0) (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            BoxWithConstraints(Modifier.weight(1f).height(24.mx), contentAlignment = Alignment.CenterStart) {
                val track = 8.mx
                Box(Modifier.fillMaxWidth().height(track).clip(CircleShape).background(Color.White.copy(alpha = .25f)))
                Box(Modifier.fillMaxWidth(buffered).height(track).clip(CircleShape).background(Color.White.copy(alpha = .40f)))
                Box(Modifier.fillMaxWidth(fraction).height(track).clip(CircleShape).background(Brand.Accent))
                val thumb = 24.mx
                Box(Modifier.offset(x = maxWidth * fraction - thumb / 2).size(thumb).drawBehind {
                    if(halo > 0f) drawCircle(Color.White.copy(alpha = .25f * halo), radius = size.minDimension / 2 + 6.mx.toPx() * halo)
                }.background(Color.White, CircleShape))
                if(previewing) Box(Modifier.offset(x = maxWidth * fraction - 110.mx, y = (-52).mx).width(220.mx), contentAlignment = Alignment.Center) {
                    Box(Modifier.background(Brand.Raised, RoundedCornerShape(12.mx)).border(3.mx, Color.White, RoundedCornerShape(12.mx))
                        .padding(horizontal = 18.mx, vertical = 4.mx)) {
                        Label(if(speedLabel.isBlank()) formatPlayerTime(position) else "${formatPlayerTime(position)}   $speedLabel", 12, Color.White, true, lines = 1)
                    }
                }
            }
            Label("-${if(duration > 0) formatPlayerTime((duration - position).coerceAtLeast(0L)) else "—"}", 13, Brand.TextSecondary, lines = 1)
        }
    }
}

/** Thumbnails of the season's episodes with the episode number underneath. */
@Composable
private fun EpisodeStrip(episodes: List<Film>, currentId: String, onPlay: (Film) -> Unit, onUp: () -> Unit) {
    val currentIndex = episodes.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
    LazyRow(state = state,
        modifier = Modifier.padding(top = 20.mx).fillMaxWidth().focusGroup().onPreviewKeyEvent { event ->
            if(event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) onUp()
                true
            } else false
        },
        contentPadding = PaddingValues(vertical = 14.mx), horizontalArrangement = Arrangement.spacedBy(24.mx)) {
        itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
            EpisodeThumb(episode, episode.id == currentId, if(index == currentIndex) Modifier.focusRequester(first) else Modifier) { onPlay(episode) }
        }
    }
}

@Composable
private fun EpisodeThumb(film: Film, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.06f else 1f, tween(140), label = "Episode focus")
    val radius = 12.mx
    val shape = RoundedCornerShape(radius)
    Column(Modifier.width(240.mx), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawWithContent {
                drawContent()
                if(focused) {
                    val stroke = 5.mx.toPx(); val outset = 3.mx.toPx() + stroke / 2
                    drawRoundRect(Color.White, Offset(-outset, -outset), Size(size.width + outset * 2, size.height + outset * 2),
                        CornerRadius(radius.toPx() + outset), style = Stroke(stroke))
                }
            }
            .clip(shape).background(Brand.Raised)
            .then(if(current && !focused) Modifier.border(2.dp, Brand.Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)) {
            val image = film.backdrop.ifBlank { film.poster }
            if(image.isNotBlank()) coil.compose.AsyncImage(image, null, Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Spacer(Modifier.height(10.mx))
        Label("الحلقة ${film.episode}", 11, if(focused || current) Color.White else Brand.TextTertiary, focused || current, lines = 1)
    }
}

private fun formatPlayerTime(value: Long): String {
    val seconds = value.coerceAtLeast(0L) / 1000
    return if(seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

/** Option row: transparent, white with dark text when focused; the selected one carries an accent check. */
@Composable
private fun PlayerOption(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.mx))
        .background(if(focused) Color.White else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .padding(horizontal = 20.mx, vertical = 10.mx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mx)) {
        Label(text, 12, if(focused) Brand.OnPrimary else Color.White, selected, lines = 1, modifier = Modifier.weight(1f))
        if(selected) Label("✓", 12, if(focused) Brand.OnPrimary else Brand.Accent, true)
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
    val margin = with(density) { 32.mx.roundToPx() }
    val windowWidth = androidx.compose.ui.platform.LocalView.current.rootView.width
    // The panel lines up with the edge of its button that faces the screen edge, so it reads as part of the button.
    val panelStart = panelX(anchor, widthPx, windowWidth, margin)
    val originX = if(anchor == IntRect.Zero) .5f else ((anchor.center.x - panelStart).toFloat() / widthPx).coerceIn(0f, 1f)
    val gap = with(density) { 18.mx.roundToPx() }
    val shape = RoundedCornerShape(28.mx)
    LaunchedEffect(Unit) { entered = true }
    Popup(popupPositionProvider = remember(anchor, widthPx, gap, margin) { AboveAnchor(anchor, widthPx, gap, margin) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        // Grows out of the button like a drop: from its bottom edge upwards, sized to its content.
        AnimatedVisibility(entered,
            enter = fadeIn(tween(140)) + scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = .3f,
                transformOrigin = TransformOrigin(originX, 1f)) +
                expandVertically(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(100))) {
            Column(Modifier.width(width).heightIn(max = 560.mx)
                .background(Brand.Surface.copy(alpha = .94f), shape).border(1.dp, Brand.GlassStroke, shape)
                .padding(10.mx).onPreviewKeyEvent { event ->
                    if(event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if(event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount == 0) onMediaToggle()
                        true
                    } else false
                }.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(title, 10, Brand.TextTertiary, true, lines = 1, modifier = Modifier.padding(start = 20.mx, top = 6.mx, bottom = 4.mx))
                content(firstFocus)
            }
        }
        LaunchedEffect(Unit) { delay(260); runCatching { firstFocus.requestFocus() } }
    }
}

/** Left edge of a panel: flush with its button's outer edge (right edge for buttons on the right half). */
private fun panelX(anchor: IntRect, widthPx: Int, windowWidth: Int, margin: Int): Int {
    val x = if(anchor.center.x > windowWidth / 2) anchor.right - widthPx else anchor.left
    return x.coerceIn(margin, (windowWidth - widthPx - margin).coerceAtLeast(margin))
}

/** Places a panel directly above the button that opened it, lined up with it and kept inside the window. */
private class AboveAnchor(private val anchor: IntRect, private val widthPx: Int, private val gap: Int, private val margin: Int) :
    PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val a = if(anchor == IntRect.Zero) anchorBounds else anchor
        val x = panelX(a, widthPx, windowSize.width, margin)
        val y = (a.top - popupContentSize.height - gap).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}
