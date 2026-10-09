package com.cinemaplus.rebuilt.ui.mobile

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import com.cinemaplus.rebuilt.ui.PlayerTransportIcon
import com.cinemaplus.rebuilt.ui.Label
import com.cinemaplus.rebuilt.ui.Muted
import com.cinemaplus.rebuilt.ui.nextSubtitleHeight
import com.cinemaplus.rebuilt.ui.subtitleHeightLabel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.PlaybackSpeed
import com.cinemaplus.rebuilt.data.SceneFilter
import com.cinemaplus.rebuilt.ui.mediaTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Native Media3 touch controls own scrubbing, playback, audio and subtitle track selection. */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MobilePlayerScreen(vm: CinemaViewModel) {
    val film = vm.playing ?: return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember { ExoPlayer.Builder(context).build().apply { setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
    } }
    var qualityIndex by remember(film.id) { mutableIntStateOf(0) }
    var qualityChosen by remember(film.id) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var ended by remember(film.id) { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var countdown by remember(film.id) { mutableStateOf<Int?>(null) }
    var offerNext by remember(film.id) { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var lastInteraction by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    val anchors = remember { HashMap<MobilePanel, IntRect>() }
    var seekable by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var playerError by remember(film.id) { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<MobilePanel?>(null) }
    var episodesOpen by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var sheetSeason by remember(film.id) { mutableIntStateOf(vm.seasons.indexOfFirst { it.number == film.season }.coerceAtLeast(0)) }
    val next = vm.nextEpisode()
    fun saveProgress() {
        if (player.currentMediaItem?.mediaId == film.id && player.currentPosition > 0)
            vm.recordProgress(film, player.currentPosition, player.duration.coerceAtLeast(0))
    }
    fun close() { saveProgress(); vm.stop() }
    fun interact() { lastInteraction = android.os.SystemClock.uptimeMillis() }
    fun togglePlayback() { MobilePlayerActions.toggle(player) }
    fun seekBy(delta: Long) {
        if (!MobilePlayerActions.canSeek(player)) return
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + delta).coerceIn(0L, end))
    }
    BackHandler { when { panel != null -> panel = null; episodesOpen -> episodesOpen = false; else -> close() } }
    DisposableEffect(player, owner, film.id) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                seekable = MobilePlayerActions.canSeek(player)
            }
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (isPlaying) interact() }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && player.currentMediaItem?.mediaId == film.id) {
                    ended = true; controlsVisible = true; player.pause(); vm.completePlayback(film)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                playerError = "تعذّر تشغيل الفيديو. تحقق من الشبكة أو جرّب جودة أخرى."
            }
        }
        var resume = false
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_PAUSE -> { resume = player.playWhenReady; saveProgress(); player.pause() }
            Lifecycle.Event.ON_RESUME -> if (resume) { resume = false; player.play() }
            Lifecycle.Event.ON_STOP -> saveProgress()
            else -> Unit
        } }
        player.addListener(listener); owner.lifecycle.addObserver(observer)
        onDispose { saveProgress(); player.removeListener(listener); owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) { onDispose { playerView?.keepScreenOn = false; playerView?.player = null; player.release() } }
    LaunchedEffect(vm.subtitleLanguage) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, vm.subtitleLanguage == "off")
            .setPreferredTextLanguage(vm.subtitleLanguage.takeUnless { it == "off" }).build()
    }
    LaunchedEffect(film.id, vm.streams, qualityIndex) {
        val streams = vm.streams
        if (streams.isEmpty()) return@LaunchedEffect
        if (!qualityChosen) {
            val preferred = MobilePlaybackPolicy.preferredIndex(streams, vm.quality)
            qualityChosen = true
            if (qualityIndex != preferred) { qualityIndex = preferred; return@LaunchedEffect }
        }
        val stream = streams.getOrNull(qualityIndex) ?: streams.first()
        val sameFilm = player.currentMediaItem?.mediaId == film.id
        val resume = MobilePlaybackPolicy.resumePosition(sameFilm, player.currentPosition, vm.library.progress(film.id))
        val shouldPlay = !sameFilm || player.playWhenReady
        val subtitles = film.subtitles.mapIndexed { index, subtitle ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                .setId("cinema-external-$index").setMimeType(subtitle.mimeType).setLanguage(subtitle.language).setLabel(subtitle.title)
                .setSelectionFlags(if (subtitle.language.substringBefore('-') == vm.subtitleLanguage) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        playerError = null
        player.setMediaItem(MediaItem.Builder().setMediaId(film.id).setUri(stream.url).setSubtitleConfigurations(subtitles).build(), resume)
        player.prepare()
        player.playWhenReady = shouldPlay && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    val ranges by produceState<List<LongRange>>(emptyList(), film.id, film.subtitles, vm.sceneFilter) {
        value = if (!vm.sceneFilter) emptyList() else try { SceneFilter.ranges(context, film) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    LaunchedEffect(player, film.id, ranges) {
        var lastSave = 0L
        while (true) {
            if (player.isPlaying) ranges.firstOrNull { player.currentPosition in it }?.let { player.seekTo(it.last + 1) }
            if (android.os.SystemClock.elapsedRealtime() - lastSave > 5000) { saveProgress(); lastSave = android.os.SystemClock.elapsedRealtime() }
            delay(400)
        }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    // After an episode ends, count down and start the next one unless the viewer turns it off or leaves.
    LaunchedEffect(ended, next?.id, vm.autoNext) {
        if (ended && next != null && film.episode > 0 && vm.autoNext) {
            for (left in PlaybackSpeed.AUTO_NEXT_SECONDS downTo 1) { countdown = left; delay(1000) }
            countdown = null
            saveProgress(); vm.play(next)
        } else countdown = null
    }
    LaunchedEffect(player, film.id, next?.id) {
        while (true) {
            offerNext = player.currentMediaItem?.mediaId == film.id && film.episode > 0 &&
                MobilePlayerActions.offerNextEpisode(player.currentPosition, player.duration, next != null)
            delay(500)
        }
    }
    // A fresh title gets its own full control visibility interval.
    LaunchedEffect(film.id) { controlsVisible = true; scrubbing = false; panel = null; interact() }
    LaunchedEffect(notice) { if (notice != null) { delay(2_400); notice = null } }
    LaunchedEffect(controlsVisible, lastInteraction, playing, panel, scrubbing, buffering, playerError, episodesOpen) {
        if (controlsVisible && playing && panel == null && !scrubbing && !episodesOpen && !buffering && playerError == null) {
            val remaining = 3_500L - (android.os.SystemClock.uptimeMillis() - lastInteraction)
            if (remaining > 0) delay(remaining)
            controlsVisible = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            // Compose draws every control; Media3 only renders the video and subtitles.
            this.player = player; keepScreenOn = true; useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            playerView = this
        } }, modifier = Modifier.fillMaxSize(), update = { view ->
            view.subtitleView?.apply {
                setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false)
                setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, vm.subtitleSize)
                setBottomPaddingFraction(vm.subtitleHeight)
                setStyle(CaptionStyleCompat(AndroidColor.WHITE, AndroidColor.argb((vm.subtitleBackground * 255).toInt(), 0, 0, 0),
                    AndroidColor.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, AndroidColor.BLACK, null))
            }
        })
        // Tap anywhere on the picture to show or hide the controls.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { if (controlsVisible) controlsVisible = false else { controlsVisible = true; interact() } },
                // Double tap: left half goes back 10 s, right half forward 10 s (physical sides).
                onDoubleTap = { offset ->
                    if (MobilePlayerActions.canSeek(player)) {
                        val forward = offset.x > size.width / 2
                        seekBy(if (forward) 10_000 else -10_000)
                        notice = if (forward) "تقديم 10 ثوانٍ" else "رجوع 10 ثوانٍ"
                    }
                })
        })
        // Swipe up from the bottom edge to open the episodes (series only).
        if (vm.seasons.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
            .pointerInput(film.id) {
                var drag = 0f
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onVerticalDrag = { change, amount ->
                    change.consume(); drag += amount
                    if (drag < -40.dp.toPx()) { episodesOpen = true; drag = 0f }
                })
            })
        AnimatedVisibility(controlsVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .70f), .26f to Color.Transparent,
                .52f to Color.Transparent, 1f to Color.Black.copy(alpha = .90f)))
                .displayCutoutPadding().navigationBarsPadding()) {
                // Title block on the leading edge, close on the other, like the TV player.
                Column(Modifier.align(Alignment.TopStart).padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth(.7f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val seriesTitle = vm.selected?.takeIf { it.series }
                    Label(if (film.episode > 0 && seriesTitle != null) mediaTitle(seriesTitle) else mediaTitle(film), 20, Color.White, true, lines = 1)
                    if (film.episode > 0) Label("الموسم ${film.season} • الحلقة ${film.episode}", 12, Color.White.copy(alpha = .75f), lines = 1)
                    vm.streams.getOrNull(qualityIndex)?.resolution?.takeIf(String::isNotBlank)?.let { MobileBadge(it) }
                }
                MobileRoundControl("إغلاق", "×", Modifier.align(Alignment.TopEnd).padding(horizontal = 20.dp, vertical = 10.dp)) { close() }
                // Transport stays physically centred whatever the text direction.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MobileRoundControl("رجوع 10 ثوانٍ", "↶10", enabled = seekable) { interact(); seekBy(-10_000) }
                        MobileRoundControl(if (playing) "إيقاف مؤقت" else "تشغيل", if (playing) "Ⅱ" else "▶", main = true, enabled = !ended) { interact(); togglePlayback() }
                        MobileRoundControl("تقديم 10 ثوانٍ", "10↷", enabled = seekable) { interact(); seekBy(10_000) }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 22.dp)) {
                    MobileSeekBar(player, onScrubbing = { scrubbing = it }, onInteract = ::interact)
                    Spacer(Modifier.height(6.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (vm.seasons.isNotEmpty()) MobileRoundControl("الحلقات", "☷") { interact(); episodesOpen = true }
                            // Picture-in-picture needs Android 8+; playback keeps running in the small window.
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                MobileRoundControl("نافذة عائمة", "⧉") {
                                    val activity = context as? android.app.Activity
                                    controlsVisible = false
                                    runCatching {
                                        activity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
                                            .setAspectRatio(android.util.Rational(16, 9)).build())
                                    }
                                }
                            }
                            MobileRoundControl(if (vm.sceneFilter) "فلترة المشاهد: مفعّلة" else "فلترة المشاهد غير اللائقة", "✂", active = vm.sceneFilter) {
                                interact(); vm.chooseSceneFilter(!vm.sceneFilter)
                                notice = if (vm.sceneFilter) "جارٍ تحليل المشاهد…" else "أُوقفت فلترة المشاهد"
                            }
                            MobileRoundControl("حجم الترجمة وخلفيتها وارتفاعها", "Aa", onPlaced = { anchors[MobilePanel.SubtitleStyle] = it }) { interact(); panel = MobilePanel.SubtitleStyle }
                            MobileRoundControl("الصوت والترجمة", "CC", onPlaced = { anchors[MobilePanel.Tracks] = it }) { interact(); panel = MobilePanel.Tracks }
                            MobileRoundControl("الجودة", "HD", enabled = vm.streams.isNotEmpty(), onPlaced = { anchors[MobilePanel.Quality] = it }) { interact(); panel = MobilePanel.Quality }
                        }
                    }
                }
            }
        }
        // Keep this action visible above the physical right side of the scrubber, even when controls fade.
        if (offerNext && next != null && panel == null && !episodesOpen) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Button(onClick = { saveProgress(); vm.play(next) }, modifier = Modifier.align(Alignment.BottomEnd)
                    .displayCutoutPadding().navigationBarsPadding().padding(end = 20.dp, bottom = 138.dp)) {
                    PlayerTransportIcon("▶|", Color.Black, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(countdown?.let { "الحلقة التالية ($it)" } ?: "الحلقة التالية")
                }
            }
        }
        notice?.let { text ->
            Label(text, 13, Color.White, true, lines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                    .background(Color(0xF21E1722), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if (buffering || vm.playbackBusy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        (vm.playbackError ?: playerError)?.let { error ->
            Column(Modifier.align(Alignment.Center).padding(24.dp).background(Color(0xF21E1722), RoundedCornerShape(20.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Label(error, 15, Color.White, lines = 3)
                TextButton(onClick = { vm.play(film) }) { Text("إعادة المحاولة") }
                TextButton(onClick = { close() }) { Text("رجوع") }
            }
        }
    }
    panel?.let { shown ->
        MobileOptionsPopup(title = when (shown) {
            MobilePanel.Quality -> "الجودة والسرعة"; MobilePanel.Tracks -> "الصوت والترجمة"; MobilePanel.SubtitleStyle -> "مظهر الترجمة"
        }, anchor = anchors[shown] ?: IntRect.Zero,
            width = when (shown) { MobilePanel.Quality -> 200.dp; MobilePanel.Tracks -> 300.dp; MobilePanel.SubtitleStyle -> 280.dp },
            onDismiss = { panel = null; interact() }) {
            when (shown) {
                MobilePanel.Quality -> {
                    vm.streams.forEachIndexed { index, stream ->
                        MobileOption(stream.resolution, index == qualityIndex) {
                            saveProgress(); qualityChosen = true; vm.chooseQuality(stream.resolution); qualityIndex = index; panel = null
                        }
                    }
                    MobileOption("السرعة: ${PlaybackSpeed.label(speed)}", false) { interact(); speed = PlaybackSpeed.next(speed) }
                }
                MobilePanel.SubtitleStyle -> {
                    // Each row cycles to the next value and stays open, so the sample below shows the change at once.
                    val sizes = listOf(16f, 18f, 20f, 22f, 26f, 30f, 34f, 40f)
                    val backgrounds = listOf(0f, .25f, .5f, .75f, 1f)
                    fun nearest(values: List<Float>, current: Float) =
                        values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
                    MobileOption("حجم الخط: ${vm.subtitleSize.toInt()}", false) {
                        interact(); vm.chooseSubtitleSize(sizes[(nearest(sizes, vm.subtitleSize) + 1) % sizes.size])
                    }
                    MobileOption("الخلفية: " + if (vm.subtitleBackground == 0f) "بدون" else "${(vm.subtitleBackground * 100).toInt()}%", false) {
                        interact(); vm.chooseSubtitleBackground(backgrounds[(nearest(backgrounds, vm.subtitleBackground) + 1) % backgrounds.size])
                    }
                    MobileOption("الارتفاع: " + subtitleHeightLabel(vm.subtitleHeight), false) {
                        interact(); vm.chooseSubtitleHeight(nextSubtitleHeight(vm.subtitleHeight))
                    }
                    MobileOption("إعادة الضبط الافتراضي", false) {
                        interact(); vm.chooseSubtitleSize(22f); vm.chooseSubtitleBackground(.5f); vm.chooseSubtitleHeight(.08f)
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp).background(Color.White.copy(alpha = .10f), RoundedCornerShape(14.dp))
                        .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Label("هذا نص تجريبي للترجمة", vm.subtitleSize.toInt(), Color.White, false, lines = 1,
                            modifier = Modifier.background(Color.Black.copy(alpha = vm.subtitleBackground)).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                MobilePanel.Tracks -> {
                    Label("الترجمة", 14, Muted, true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                    MobileOption("إيقاف الترجمة", player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                        vm.chooseSubtitleLanguage("off")
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        panel = null
                    }
                    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    if (textGroups.isEmpty()) Label("لا توجد مسارات ترجمة متاحة في هذا الفيديو.", 12, Muted, modifier = Modifier.padding(start = 6.dp))
                    var textNumber = 0
                    textGroups.forEach { group -> repeat(group.length) { index ->
                        if (group.isTrackSupported(index)) {
                            textNumber++
                            val format = group.getTrackFormat(index)
                            MobileOption(mobileTrackLabel(format, "ترجمة $textNumber"), group.isTrackSelected(index)) {
                                vm.chooseSubtitleLanguage(format.language.orEmpty().ifBlank { "und" })
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                                panel = null
                            }
                        }
                    } }
                    Label("الصوت", 14, Muted, true, modifier = Modifier.padding(start = 6.dp, top = 12.dp))
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.isEmpty()) Label("جارٍ قراءة مسارات الصوت…", 12, Muted, modifier = Modifier.padding(start = 6.dp))
                    var audioNumber = 0
                    audioGroups.forEach { group -> repeat(group.length) { index ->
                        if (group.isTrackSupported(index)) {
                            audioNumber++
                            MobileOption(mobileTrackLabel(group.getTrackFormat(index), "صوت $audioNumber"), group.isTrackSelected(index)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                                panel = null
                            }
                        }
                    } }
                }
            }
        }
    }
    if (episodesOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { episodesOpen = false; interact() }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الحلقات", modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.seasons.forEachIndexed { index, season ->
                        FilterChip(selected = sheetSeason == index, onClick = { sheetSeason = index }, label = { Text("الموسم ${season.number}") })
                    }
                }
                vm.seasons.getOrNull(sheetSeason)?.let { season ->
                    MobileEpisodeRail(season.episodes.map { it.copy(season = season.number, seriesId = film.seriesId) },
                        film.backdrop) { episode -> saveProgress(); vm.play(episode); episodesOpen = false }
                }
            }
        }
    }
}

/** Readable name of an audio or subtitle track: its label, else the language in Arabic, else [fallback]. */
private fun mobileTrackLabel(format: Format, fallback: String): String {
    if (!format.label.isNullOrBlank()) return format.label!!
    val language = format.language?.takeUnless { it == "und" || it.isBlank() } ?: return fallback
    return java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.forLanguageTag("ar")).ifBlank { fallback }
}
