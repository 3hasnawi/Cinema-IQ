package com.cinemaplus.rebuilt.ui.mobile

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.compose.BackHandler
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
import com.cinemaplus.rebuilt.data.SceneFilter
import com.cinemaplus.rebuilt.ui.mediaTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Native Media3 touch controls own scrubbing, playback, audio and subtitle track selection. */
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
    var buffering by remember { mutableStateOf(false) }
    var playerError by remember(film.id) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val next = vm.nextEpisode()
    fun saveProgress() {
        if (player.currentMediaItem?.mediaId == film.id && player.currentPosition > 0)
            vm.recordProgress(film, player.currentPosition, player.duration.coerceAtLeast(0))
    }
    fun close() { saveProgress(); vm.stop() }
    BackHandler { if (menu != null) menu = null else close() }
    DisposableEffect(player, owner, film.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && player.currentMediaItem?.mediaId == film.id) vm.completePlayback(film)
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
    DisposableEffect(player) { onDispose { playerView?.player = null; player.release() } }
    LaunchedEffect(vm.subtitleLanguage) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, vm.subtitleLanguage == "off")
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            this.player = player; useController = true; controllerShowTimeoutMs = 3000
            setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
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
        if (controlsVisible || vm.playbackError != null || playerError != null) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .65f)).statusBarsPadding().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { close() }) { Icon(Icons.Rounded.Close, "إغلاق المشغل", tint = Color.White) }
                Text(mediaTitle(film), color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { menu = "quality" }) { Text("الجودة") }
                    if (vm.seasons.isNotEmpty()) TextButton(onClick = { menu = "episodes" }) { Text("الحلقات") }
                    if (next != null) TextButton(onClick = { saveProgress(); vm.play(next) }) { Text("التالية") }
                }
            }
        }
        if (buffering || vm.playbackBusy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        (vm.playbackError ?: playerError)?.let { error ->
            Column(Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha = .8f)), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = Color.White)
                TextButton(onClick = { vm.play(film) }) { Text("إعادة المحاولة") }
                TextButton(onClick = { close() }) { Text("رجوع") }
            }
        }
    }
    menu?.let { shown ->
        AlertDialog(onDismissRequest = { menu = null; playerView?.showController() }, title = { Text(if (shown == "quality") "جودة الفيديو" else "الحلقات") },
            text = {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    if (shown == "quality") items(vm.streams.indices.toList()) { index ->
                        TextButton(onClick = { saveProgress(); qualityChosen = true; vm.chooseQuality(vm.streams[index].resolution); qualityIndex = index; menu = null }) {
                            Text(vm.streams[index].resolution + if (index == qualityIndex) " ✓" else "")
                        }
                    } else items(vm.seasons.flatMap { season -> season.episodes.map { it.copy(season = season.number, seriesId = film.seriesId) } }, key = { it.id }) { episode ->
                        TextButton(onClick = { saveProgress(); vm.play(episode); menu = null }) { Text("الموسم ${episode.season} • الحلقة ${episode.episode}") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { menu = null }) { Text("إغلاق") } })
    }
}
