@file:Suppress("UnsafeOptInUsageError")
package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun IptvTvPlayer(initial: IptvChannel, channels: List<IptvChannel>, initialGroup: String?, television: Boolean,
    allowHttp: Boolean, refresh: suspend (IptvChannel) -> IptvChannel, startInGuide: Boolean = false, startInBrowser: Boolean = false,
    collection: IptvChannelCollection = IptvChannelCollection(), favorite: (IptvChannel) -> Unit = {},
    watched: (IptvChannel) -> Unit = {}, onFullscreen: (Boolean) -> Unit = {},
    loadProgrammes: suspend (IptvChannel) -> IptvNowNext = { IptvNowNext() },
    categoryCounts: Map<String, Int>? = null, lockedGroups: Set<String> = emptySet(), hiddenGroups: Set<String> = emptySet(),
    requestGroupAccess: ((String?, () -> Unit, () -> Unit) -> Unit)? = null, manageCategory: ((String?) -> Unit)? = null,
    manageSources: (() -> Unit)? = null, channelChanged: (IptvChannel) -> Unit = {},
    canContinuePlayback: ((IptvChannel) -> Boolean)? = null, categoryVisitEnded: (() -> Unit)? = null,
    canonicalNumbers: Map<String, Int>? = null, onExit: (IptvChannel, String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current; val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fullscreen by remember { mutableStateOf(!startInBrowser) }
    var browserSection by remember { mutableStateOf<IptvChannelSection>(initialGroup?.let { IptvChannelSection.Group(it) } ?: IptvChannelSection.All) }
    var browserQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val currentWatched by rememberUpdatedState(watched)
    val currentChannelChanged by rememberUpdatedState(channelChanged)
    val currentContinuation by rememberUpdatedState(canContinuePlayback)
    // Keep one state holder for the decoder session: listener closures outlive recompositions.
    var channel by remember { mutableStateOf(initial) }
    val latestChannels by rememberUpdatedState(channels)
    val currentRefresh by rememberUpdatedState(refresh)
    val programmeLoader by rememberUpdatedState(loadProgrammes)
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var pausedByUser by remember { mutableStateOf(false) }
    var retryNeeded by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var retryFailure by remember { mutableStateOf(IptvFailure.Temporary) }
    var retryAfter by remember { mutableLongStateOf(0) }
    var retryNotBefore by remember { mutableLongStateOf(0) }
    var linkRenewed by remember { mutableStateOf(false) }
    var terminalFailure by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(initialGroup) }
    var televisionSection by remember { mutableStateOf<IptvChannelSection>(initialGroup?.let { IptvChannelSection.Group(it) } ?: IptvChannelSection.All) }
    var attempt by remember { mutableIntStateOf(0) }
    var guide by remember { mutableStateOf(startInGuide) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var toolbarFocus by remember { mutableStateOf(false) }
    val surface = remember { FocusRequester() }; val toolbar = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val queue = remember(channels, group, television, televisionSection, collection) {
        if (television) collection.channels(channels, initial.sourceId, televisionSection)
        else IptvNavigation.channels(channels, initial.sourceId, group)
    }
    val channelNumbers = canonicalNumbers ?: remember(channels, initial.sourceId) { IptvNavigation.numbers(channels, initial.sourceId) }
    val tvLogos = if (television) rememberIptvLogos() else null
    // Prepare authorized guide pages during playback, rather than rebuilding them on every OK.
    // Identity tokens synchronously reject an old projection when a PIN visit or source changes.
    val guideCatalogInput = remember(channels, initial.sourceId, channelNumbers, hiddenGroups) { Any() }
    var preparedCatalog by remember { mutableStateOf<Pair<Any, IptvTvGuideCatalog>?>(null) }
    LaunchedEffect(guideCatalogInput, television) {
        if (!television) return@LaunchedEffect
        val catalog = withContext(Dispatchers.Default) {
            IptvTvGuideCatalog.build(channels, initial.sourceId, channelNumbers, hiddenGroups)
        }
        preparedCatalog = guideCatalogInput to catalog
    }
    val guideCatalog = preparedCatalog?.takeIf { it.first === guideCatalogInput }?.second
    val guideSnapshotInput = remember(guideCatalog, collection) { Any() }
    var preparedSnapshot by remember { mutableStateOf<Pair<IptvTvGuideCatalog, IptvTvGuideSnapshot>?>(null) }
    LaunchedEffect(guideSnapshotInput, television) {
        val catalog = guideCatalog ?: return@LaunchedEffect
        if (!television) return@LaunchedEffect
        val previous = preparedSnapshot?.takeIf { it.first === catalog }?.second
        val snapshot = withContext(Dispatchers.Default) { catalog.snapshot(collection, previous) }
        preparedSnapshot = catalog to snapshot
    }
    // A recent/favorite update can keep current-catalog rows mounted while collection pages refresh.
    // A revoked authorization projection still rejects that snapshot immediately.
    val guideSnapshot = preparedSnapshot?.takeIf { it.first === guideCatalog }?.second
    val liveLoadControl = remember(television, allowHttp) { if (television) IptvTvLiveLoadControl() else null }
    var routeAvailable by remember { mutableStateOf(true) }
    val player = remember(allowHttp, television) {
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
        val mediaSources = DefaultMediaSourceFactory(restricted)
        if (television) mediaSources.setLoadErrorHandlingPolicy(IptvTvLoadErrorPolicy())
        ExoPlayer.Builder(context).setMediaSourceFactory(mediaSources)
            .apply {
                if (television) {
                    setTrackSelector(iptvLiveTrackSelector(context))
                    setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                    setLoadControl(checkNotNull(liveLoadControl))
                }
            }
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
    }
    var error by remember { mutableStateOf<String?>(null) }
    var programmeInfo by remember { mutableStateOf(IptvNowNext()) }
    var loading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasPlayed by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Int?>(null) }
    var trackReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var trackBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var pendingRestore by remember { mutableStateOf<FocusRequester?>(null) }
    val rootWindow = androidx.compose.ui.platform.LocalWindowInfo.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var refreshing by remember { mutableStateOf(false) }
    var refreshTask by remember { mutableStateOf<Job?>(null) }
    var requestGeneration by remember { mutableIntStateOf(0) }
    fun eligible(candidate: IptvChannel): Boolean = !television ||
        (candidate.id == channel.id && candidate.sourceId == initial.sourceId && currentContinuation?.invoke(candidate) == true) ||
        latestChannels.any { it.id == candidate.id && it.sourceId == candidate.sourceId && it.sourceId == initial.sourceId }
    fun retryPlan(): IptvRetry? = if (television)
        IptvReconnectPolicy.televisionRetry(retryFailure, retryCount, retryAfter, linkRenewed)
        else IptvReconnectPolicy.retry(retryFailure, retryCount, retryAfter)
    fun retryFromTracks() {
        if (player.playerError == null || retryFailure == IptvFailure.HttpConsent || !eligible(channel)) return
        if (television && android.os.SystemClock.elapsedRealtime() < retryNotBefore) return
        terminalFailure = false; retryNeeded = false; error = null; attempt++
    }
    fun restorePlayerFocus(target: FocusRequester = surface) {
        controls = true; interaction++
        pendingRestore = target
    }
    fun closeGuide() {
        guide = false
        val activeGroup = (televisionSection as? IptvChannelSection.Group)?.name
        // Watching/surfing inside the same group is still one visit; aggregate routes are not.
        if (activeGroup == null || activeGroup != channel.group || activeGroup !in lockedGroups) categoryVisitEnded?.invoke()
        restorePlayerFocus()
    }

    fun tune(next: IptvChannel) {
        if (next.sourceId != initial.sourceId || next.id == channel.id || !eligible(next)) return
        val selected = if (television) latestChannels.firstOrNull { it.id == next.id && it.sourceId == next.sourceId } ?: return else next
        refreshTask?.cancel(); requestGeneration++; refreshing = false; channel = selected
        currentChannelChanged(selected)
        pausedByUser = false; retryNeeded = false; retryCount = 0; retryAfter = 0; retryNotBefore = 0
        linkRenewed = false; terminalFailure = false; programmeInfo = IptvNowNext(); tracks = Tracks.EMPTY
        liveLoadControl?.resetStability()
        hasPlayed = false
        controls = true; interaction++; error = null; loading = true; videoHeight = 0
    }
    fun togglePlayback() {
        if (!pausedByUser && (player.isPlaying || player.playWhenReady || retryNeeded)) {
            pausedByUser = true; retryNeeded = false; refreshTask?.cancel(); requestGeneration++; refreshing = false
            player.pause(); loading = false
        } else {
            if (!eligible(channel)) return
            pausedByUser = false
            if (television && android.os.SystemClock.elapsedRealtime() < retryNotBefore) {
                retryNeeded = true; loading = true
            } else if (player.currentMediaItem?.mediaId != channel.id || player.playerError != null || player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                terminalFailure = false; linkRenewed = false; retryCount = 0; retryFailure = IptvFailure.Temporary; retryAfter = 0; error = null; retryNeeded = true; loading = true
            } else { loading = player.playbackState != Player.STATE_READY; player.play() }
        }
        controls = true; interaction++
    }
    fun surf(step: Int) { IptvNavigation.neighbor(queue, channel.id, step)?.let { tune(it) } }
    LaunchedEffect(initial.sourceId, initial.id) {
        // Same-source external selections may update the seed; tune feedback is already current.
        if (initial.sourceId == channel.sourceId && initial.id != channel.id && eligible(initial)) tune(initial)
    }
    fun closeTracks() {
        val target = if (television) trackReturnFocus ?: surface else surface
        panel = null; trackReturnFocus = null; restorePlayerFocus(target)
    }
    LaunchedEffect(pendingRestore, rootWindow.isWindowFocused, guide, panel, fullscreen) {
        val target = pendingRestore
        if (target != null && rootWindow.isWindowFocused && !guide && panel == null && fullscreen) repeat(24) {
            if (television) inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) { pendingRestore = null; return@LaunchedEffect }
        }
    }
    LaunchedEffect(channel.id, foreground) {
        if (!television || !foreground || !eligible(channel)) return@LaunchedEffect
        programmeInfo = IptvNowNext()
        delay(750)
        while (isActive) {
            programmeInfo = try { programmeLoader(channel) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { IptvNowNext() }
            delay(60_000)
        }
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value; view.keepScreenOn = value
                if (value && player.currentMediaItem?.mediaId == channel.id && eligible(channel)) {
                    hasPlayed = true
                    retryNeeded = false; loading = false; terminalFailure = false; error = null; currentWatched(channel)
                }
            }
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onVideoSizeChanged(videoSize: VideoSize) { videoHeight = videoSize.height }
            override fun onPlaybackStateChanged(value: Int) {
                loading = value == Player.STATE_BUFFERING || value == Player.STATE_IDLE
                if (value == Player.STATE_BUFFERING && hasPlayed && !pausedByUser) liveLoadControl?.noteDisruption()
                if (value == Player.STATE_ENDED && !terminalFailure && player.currentMediaItem?.mediaId == channel.id) {
                    retryFailure = IptvFailure.Temporary; retryAfter = 0; retryNeeded = true; loading = true
                }
            }
            override fun onPlayerError(exception: PlaybackException) {
                if (terminalFailure || player.currentMediaItem?.mediaId != channel.id || !eligible(channel)) return
                view.keepScreenOn = false
                val response = generateSequence(exception as Throwable) { it.cause }.take(8)
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                retryFailure = when {
                    exception.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> IptvFailure.HttpConsent
                    exception.errorCode in listOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) -> IptvFailure.Unsupported
                    response?.responseCode == 429 -> IptvFailure.RateLimited
                    television && response?.responseCode in listOf(401, 403) -> IptvFailure.Authentication
                    response?.responseCode in listOf(401, 403, 404, 410) ||
                        exception.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> IptvFailure.Access
                    else -> IptvFailure.Temporary
                }
                retryAfter = response?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }
                    ?.value?.firstOrNull()?.let { IptvReconnectPolicy.retryAfter(it, System.currentTimeMillis()) } ?: 0
                retryNotBefore = if (television && (retryFailure == IptvFailure.RateLimited || retryAfter > 0))
                    android.os.SystemClock.elapsedRealtime() + maxOf(retryAfter, if (retryFailure == IptvFailure.RateLimited) 30_000L else 0L)
                    else 0
                liveLoadControl?.noteDisruption()
                val plan = retryPlan()
                terminalFailure = plan == null; retryNeeded = plan != null; loading = plan != null
                if (!television || terminalFailure) controls = true
                error = when (retryFailure) {
                    IptvFailure.HttpConsent -> "هذا البث يستخدم HTTP. عدّل المصدر وفعّل السماح بالاتصال غير المشفّر إذا كان مزوّدك يتطلبه."
                    IptvFailure.Unsupported -> "صيغة الصوت أو الصورة غير مدعومة على هذا الجهاز. جرّب جودة أخرى."
                    IptvFailure.Authentication -> if (terminalFailure) "رفض المزوّد تشغيل القناة. تحقق من صلاحية الاشتراك وبيانات المصدر." else null
                    else -> null
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false; refreshTask?.cancel(); requestGeneration++; refreshing = false
                if (!television) linkRenewed = false
                player.pause(); view.keepScreenOn = false
            }
        }
        player.addListener(listener); lifecycle.addObserver(observer)
        onDispose {
            refreshTask?.cancel(); requestGeneration++
            lifecycle.removeObserver(observer); player.removeListener(listener); view.keepScreenOn = false; player.release()
        }
    }
    LaunchedEffect(television, foreground, player) {
        if (!television || !foreground) return@LaunchedEffect
        var lostRoute = false
        iptvDefaultRoute(context).collect { available ->
            routeAvailable = available
            if (available && lostRoute && !pausedByUser && !terminalFailure && !retryNeeded && !refreshing &&
                player.currentMediaItem?.mediaId == channel.id && player.playbackState == Player.STATE_BUFFERING &&
                eligible(channel) && android.os.SystemClock.elapsedRealtime() >= retryNotBefore) {
                // The old socket may still be waiting for a read timeout on the disconnected route.
                attempt++
            }
            lostRoute = !available
        }
    }
    LaunchedEffect(channels, channel.id, television, player) {
        if (television && !eligible(channel)) {
            refreshTask?.cancel(); requestGeneration++; refreshing = false
            retryNeeded = false; loading = false; player.stop(); view.keepScreenOn = false
        }
    }
    LaunchedEffect(channel.id, channel.url, attempt, player, foreground) {
        if (!foreground || pausedByUser || retryNeeded || terminalFailure || !eligible(channel)) return@LaunchedEffect
        if (!television || player.currentMediaItem != null) delay(180) // Collapse channel repeats; open TV's first channel immediately.
        if (!foreground || pausedByUser || retryNeeded || terminalFailure || !eligible(channel)) return@LaunchedEffect
        error = null; loading = true
        if (player.currentMediaItem?.mediaId != channel.id) {
            tracks = Tracks.EMPTY; videoHeight = 0
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().apply {
                if (television) setForceHighestSupportedBitrate(false).setViewportSizeToPhysicalDisplaySize(true)
            }.build()
        }
        player.stop()
        player.setMediaItem(MediaItem.Builder().setUri(channel.url).setMediaId(channel.id).build())
        player.prepare(); player.playWhenReady = true
    }
    LaunchedEffect(retryNeeded, retryCount, retryFailure, retryAfter, retryNotBefore, channel.id, foreground, pausedByUser, terminalFailure) {
        if (!retryNeeded || terminalFailure || !eligible(channel) || !IptvReconnectPolicy.allowed(foreground, pausedByUser)) return@LaunchedEffect
        val requested = channel; val pending = IptvPendingReconnect(requested, requestGeneration); val failure = retryFailure
        val plan = retryPlan()
        if (plan == null) {
            terminalFailure = true; retryNeeded = false; loading = false
            if (error == null) error = "رفض المزوّد تشغيل القناة. تحقق من صلاحية الاشتراك وبيانات المصدر."
            return@LaunchedEffect
        }
        val remainingCooldown = if (television) (retryNotBefore - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) else 0
        if (television) {
            val waitMillis = if (retryNotBefore > 0) remainingCooldown else plan.delayMillis
            IptvReconnectPolicy.awaitTvRoute(waitMillis,
                expediteOnReturn = failure == IptvFailure.Temporary && retryAfter == 0L,
                route = snapshotFlow { routeAvailable })
        } else delay(plan.delayMillis)
        if (!pending.matches(channel, requestGeneration) || !eligible(requested) ||
            retryFailure != failure || (television && android.os.SystemClock.elapsedRealtime() < retryNotBefore) ||
            !IptvReconnectPolicy.allowed(foreground, pausedByUser)) return@LaunchedEffect
        val next = latestChannels.firstOrNull { it.id == requested.id && it.sourceId == requested.sourceId }
            ?: requested.takeIf { !television || (it.id == channel.id && it.sourceId == initial.sourceId &&
                currentContinuation?.invoke(it) == true) } ?: return@LaunchedEffect
        if (television && retryFailure == IptvFailure.Authentication) {
            // Refresh through the configured provider once; never replay rejected credentials in a loop.
            refreshing = true
            try {
                val fresh = currentRefresh(requested)
                ensureActive()
                if (pending.matches(channel, requestGeneration) && IptvReconnectPolicy.allowed(foreground, pausedByUser)) {
                    if (!eligible(fresh) || fresh.id != requested.id || fresh.sourceId != requested.sourceId)
                        throw IptvException(IptvException.Reason.NoChannels)
                    IptvUrls.validate(fresh.url, allowHttp)
                    // Only a completed, validated renewal consumes the one-renewal budget.
                    linkRenewed = true
                    retryNotBefore = 0; retryCount++; channel = fresh; retryNeeded = false; error = null; loading = true; attempt++
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (pending.matches(channel, requestGeneration) && eligible(requested)) {
                    if (e is IptvException && IptvReconnectPolicy.retryableRenewal(e)) {
                        // Retry the renewal itself. Only try the rejected stream again after a successful refresh.
                        retryCount++
                        retryAfter = e.retryAfterMillis
                        val cooldown = if (e.status == 429) maxOf(30_000L, retryAfter) else retryAfter
                        retryNotBefore = if (cooldown > 0) android.os.SystemClock.elapsedRealtime() + cooldown else 0
                        loading = true
                    } else {
                        terminalFailure = true; retryNeeded = false; loading = false
                        error = (e as? IptvException)?.arabic() ?: "تعذّر تجديد تشغيل القناة. تحقق من الاشتراك وبيانات المصدر."
                    }
                }
            } finally { if (pending.matches(channel, requestGeneration)) refreshing = false }
            return@LaunchedEffect
        }
        if (plan.refreshLink && !linkRenewed) {
            linkRenewed = true
            refreshTask?.cancel()
            // Renew expired URLs independently; slow catalogue downloads must not block stream retries.
            refreshTask = scope.launch {
                try {
                    val fresh = currentRefresh(requested)
                    if (pending.matches(channel, requestGeneration) && !player.isPlaying && eligible(fresh) &&
                        fresh.id == requested.id && fresh.sourceId == requested.sourceId &&
                        IptvReconnectPolicy.allowed(foreground, pausedByUser)) {
                        if (television) IptvUrls.validate(fresh.url, allowHttp)
                        channel = fresh; retryNeeded = false; error = null; loading = true; attempt++
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: IptvException) {
                    if (television && e.status == 429 && pending.matches(channel, requestGeneration) &&
                        eligible(requested) && !player.isPlaying) {
                        retryFailure = IptvFailure.RateLimited; retryAfter = maxOf(30_000L, e.retryAfterMillis)
                        retryNotBefore = android.os.SystemClock.elapsedRealtime() + retryAfter
                        retryNeeded = true; loading = true; player.stop()
                    }
                    if (pending.matches(channel, requestGeneration) && !player.isPlaying && eligible(requested) &&
                        (e.reason in listOf(IptvException.Reason.Credentials, IptvException.Reason.Inactive, IptvException.Reason.HttpConsent) || e.status in listOf(401, 403))) {
                        terminalFailure = true; error = e.arabic(); loading = false; retryNeeded = false
                    }
                } catch (_: Exception) { /* Keep direct retries when catalogue renewal is unavailable. */ }
            }
        }
        ensureActive()
        if (pending.matches(channel, requestGeneration) && eligible(next) && IptvReconnectPolicy.allowed(foreground, pausedByUser)) {
            retryNotBefore = 0; retryCount++; channel = next; retryNeeded = false; error = null; loading = true; attempt++
        }
    }
    LaunchedEffect(isPlaying, channel.id) {
        if (isPlaying) { delay(30_000); retryCount = 0; linkRenewed = false; liveLoadControl?.resetStability() }
    }
    LaunchedEffect(loading, channel.id, attempt, foreground, pausedByUser) {
        if (loading && IptvReconnectPolicy.allowed(foreground, pausedByUser)) {
            delay(30_000)
            if (!terminalFailure && !retryNeeded && eligible(channel) && (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE)) {
                liveLoadControl?.noteDisruption()
                retryFailure = IptvFailure.Temporary; retryAfter = 0; retryNeeded = true
            }
        }
    }
    LaunchedEffect(player, television, channel.id, foreground, pausedByUser, attempt) {
        if (!television) return@LaunchedEffect
        val watchdog = IptvLiveStallWatchdog()
        while (isActive) {
            delay(2_000)
            if (watchdog.sample("${channel.sourceId}:${channel.id}:$attempt", android.os.SystemClock.elapsedRealtime(),
                    player.currentPosition, player.playbackState == Player.STATE_READY,
                    player.currentMediaItem?.mediaId == channel.id && player.playWhenReady && eligible(channel) &&
                        foreground && !pausedByUser && !terminalFailure && !retryNeeded && !refreshing,
                    player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)) {
                liveLoadControl?.noteDisruption()
                retryFailure = IptvFailure.Temporary; retryAfter = 0; retryNeeded = true; loading = true
            }
        }
    }
    LaunchedEffect(Unit) { if (television && !guide) restorePlayerFocus() }
    LaunchedEffect(controls, guide, panel, interaction, fullscreen, foreground, error, rootWindow.isWindowFocused) {
        if (controls && foreground && rootWindow.isWindowFocused && fullscreen && !guide && panel == null && error == null) {
            delay(5_000)
            if (toolbarFocus) runCatching { surface.requestFocus() }
            controls = false
        }
    }
    LaunchedEffect(fullscreen) { onFullscreen(fullscreen) }
    LaunchedEffect(initialGroup) { if (startInBrowser) browserSection = initialGroup?.let { IptvChannelSection.Group(it) } ?: IptvChannelSection.All }
    val showControls = fullscreen && !guide && (controls || (television && panel != null))
    BackHandler(enabled = panel == null && !guide) {
        when {
            television && showControls -> {
                controls = false
                // Returning focus must not call restorePlayerFocus(), which reveals the HUD again.
                pendingRestore = surface
            }
            startInBrowser && fullscreen -> fullscreen = false
            else -> onExit(channel, group?.takeIf { it == channel.group })
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.action == KeyEvent.ACTION_DOWN && (!television || key.keyCode != KeyEvent.KEYCODE_BACK)) {
            interaction++; controls = true
        }
        if (key.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) when (key.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> surf(-1)
                KeyEvent.KEYCODE_CHANNEL_DOWN -> surf(1)
                else -> togglePlayback()
            }
            true
        } else false
    }) {
        val wideBrowser = startInBrowser && !fullscreen && maxWidth > maxHeight
        val previewWidth = if (wideBrowser) maxWidth * .52f else maxWidth
        val previewHeight = previewWidth * 9f / 16f
        val videoModifier = if (startInBrowser) Modifier.width(previewWidth).aspectRatio(16f / 9f)
            .align(if (fullscreen) Alignment.Center else Alignment.TopCenter)
            .absoluteOffset(x = if (wideBrowser) -(maxWidth - previewWidth) / 2 else 0.dp) else Modifier.fillMaxSize()
        AndroidView(factory = { ctx ->
            val playerView = if (television) PlayerView(ctx) else
                LayoutInflater.from(ctx).inflate(com.cinemaplus.rebuilt.R.layout.iptv_player_surface, null, false) as PlayerView
            playerView.apply {
                if (television) setEnableComposeSurfaceSyncWorkaround(true)
                useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); setKeepContentOnPlayerReset(true)
                isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
        }, update = {
            it.player = player
            if (television) it.subtitleView?.setBottomPaddingFraction(if (showControls) .24f else .08f)
        }, modifier = videoModifier)
        Box(Modifier.fillMaxSize().focusRequester(surface).focusProperties { canFocus = fullscreen && !guide && panel == null }.onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (television && key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (key.action == KeyEvent.ACTION_DOWN) when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> surf(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> surf(1)
                    else -> restorePlayerFocus(toolbar)
                }
                true
            } else false
        }.semantics { contentDescription = if (television) "قائمة القنوات" else "إظهار خيارات المشغل" }
            .clickable(enabled = fullscreen && !guide && panel == null, role = androidx.compose.ui.semantics.Role.Button) { if (television) guide = true else { controls = !controls; interaction++ } }
            .pointerInput(channel.id, queue, television) {
                if (!television) { var delta = 0f; detectVerticalDragGestures(onDragStart = { delta = 0f }, onVerticalDrag = { _, value -> delta += value },
                    onDragEnd = { if (kotlin.math.abs(delta) > 100f) surf(if (delta < 0) 1 else -1) }) }
            })
        if (startInBrowser && !fullscreen) {
            val panes: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    if (searching) androidx.compose.material3.OutlinedTextField(browserQuery, { browserQuery = it.take(200) }, label = { androidx.compose.material3.Text("ابحث عن قناة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.weight(1f)) {
                        IptvBrowserPanels(channels, channel, collection, browserSection, browserQuery, { browserSection = it; browserQuery = "" }) {
                            group = (browserSection as? IptvChannelSection.Group)?.name
                            if (it.id == channel.id) { fullscreen = true; controls = true; interaction++ } else tune(it)
                        }
                    }
                }
            }
            if (wideBrowser) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxSize()) { Box(Modifier.width(previewWidth)); Box(Modifier.weight(1f)) { panes() } }
            }
            else Column(Modifier.fillMaxSize()) { Spacer(Modifier.height(previewHeight)); Box(Modifier.weight(1f)) { panes() } }
            Box(Modifier.width(previewWidth).height(previewHeight).align(Alignment.TopCenter)
                .absoluteOffset(x = if (wideBrowser) -(maxWidth - previewWidth) / 2 else 0.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.align(Alignment.BottomEnd).fillMaxWidth().background(Ink.copy(alpha = .6f)).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("● LIVE", 11, Accent); Spacer(Modifier.weight(1f))
                        IconButton({ favorite(channel) }) { Icon(if (channel.id in collection.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "المفضلة", tint = Accent) }
                        IptvFullscreenButton { fullscreen = true; controls = true; interaction++ }
                        IconButton({ searching = !searching }) { Icon(Icons.Rounded.Search, "البحث", tint = androidx.compose.ui.graphics.Color.White) }
                    }
                }
                if (error != null) Label(error!!, 12, Brand.Danger, lines = 3)
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            }
        }
        if (showControls) {
            if (television) {
                val audioLanguage = remember(tracks) {
                    tracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_AUDIO }.firstNotNullOfOrNull { track ->
                        (0 until track.length).firstOrNull { track.isTrackSelected(it) }?.let { track.getTrackFormat(it).language }
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    IptvTvPlayerHud(channel = channel, number = channelNumbers[channel.id] ?: 0,
                        programmes = programmeInfo, status = error ?: if (retryNeeded || (loading && retryCount > 0)) "جارٍ إعادة الاتصال…" else if (loading) "جارٍ التحميل…" else if (isPlaying) "بث مباشر" else "متوقف مؤقتًا",
                        videoHeight = videoHeight, audioLanguage = audioLanguage,
                        isFavorite = channel.id in collection.favorites,
                        logos = checkNotNull(tvLogos),
                        entryFocus = toolbar, focusChanged = { toolbarFocus = it }, channels = { guide = true },
                        interactive = !guide,
                        favorite = { favorite(channel); interaction++ },
                        channelStep = { step -> surf(step); restorePlayerFocus() },
                        tracks = { type, anchor, bounds -> trackReturnFocus = anchor; trackBounds = bounds; controls = true; interaction++; panel = type })
                }
            } else Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.background(Ink.copy(alpha = .75f)).padding(16.dp)) {
                    Label(channel.name, 21, bold = true, lines = 1); Label(if (loading) "جارٍ التحميل…" else if (isPlaying) "بث مباشر · ${channel.group}" else "متوقف مؤقتًا · ${channel.group}", 12, Brand.TextTertiary)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).background(Ink.copy(alpha = .85f)).padding(12.dp)
                    .onFocusChanged { toolbarFocus = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IptvButton("القنوات", Modifier.focusRequester(toolbar)) { guide = true }
                    IptvButton("السابقة") { surf(-1) }
                    if (!television) IptvButton("التالية") { surf(1) }
                    IptvButton(if (isPlaying) "إيقاف مؤقت" else "تشغيل") { togglePlayback() }
                    IptvButton("الجودة") { panel = C.TRACK_TYPE_VIDEO }
                    IptvButton("الصوت") { panel = C.TRACK_TYPE_AUDIO }
                    IptvButton("الترجمة") { panel = C.TRACK_TYPE_TEXT }
                    IptvButton("إغلاق") { if (startInBrowser) fullscreen = false else onExit(channel, group?.takeIf { it == channel.group }) }
                }
            }
        }
        if (fullscreen && loading && error == null && !guide) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        if (fullscreen && error != null && !guide) Column(Modifier.align(Alignment.Center).widthIn(max = 700.dp).background(Panel).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(error!!, 16)
            IptvButton(if (refreshing) "جارٍ تحديث الرابط…" else "تحديث الرابط وإعادة المحاولة", enabled = !refreshing) {
                if (!foreground || !eligible(channel)) return@IptvButton
                if (television && android.os.SystemClock.elapsedRealtime() < retryNotBefore) {
                    retryNeeded = true; loading = true; return@IptvButton
                }
                terminalFailure = false; retryCount = 0; linkRenewed = false; pausedByUser = false; retryNeeded = false
                val requested = channel; requestGeneration++; val token = requestGeneration
                refreshTask?.cancel()
                refreshTask = scope.launch {
                    refreshing = true
                    try {
                        val fresh = currentRefresh(requested)
                        if (requestGeneration == token && channel.id == requested.id && eligible(fresh) &&
                            fresh.id == requested.id && fresh.sourceId == requested.sourceId && foreground) {
                            if (television) IptvUrls.validate(fresh.url, allowHttp)
                            channel = fresh; attempt++
                        }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (requestGeneration == token && channel.id == requested.id) { terminalFailure = true; error = iptvMessage(e); loading = false } }
                    finally { if (requestGeneration == token) refreshing = false }
                }
            }
            if (television && manageSources != null) IptvButton("المصادر", onClick = manageSources)
            IptvButton("القنوات") { guide = true }
        }
        if (guide) {
            if (television) IptvTvChannelOverlay(channels, channel, televisionSection, { next, selectedSection ->
                televisionSection = selectedSection; group = (selectedSection as? IptvChannelSection.Group)?.name
                tune(next); closeGuide()
            }, ::closeGuide, categoryCounts = categoryCounts, lockedGroups = lockedGroups,
                hiddenGroups = hiddenGroups, requestGroupAccess = requestGroupAccess, manageCategory = manageCategory,
                manageSources = manageSources, canonicalNumbers = channelNumbers,
                preparedGuide = guideSnapshot, logos = checkNotNull(tvLogos),
                sectionChanged = { selected -> televisionSection = selected; group = (selected as? IptvChannelSection.Group)?.name })
            else IptvChannelGuide(channels, channel, group, false, { group = it }, {
                tune(it); guide = false; restorePlayerFocus()
            }, { guide = false; restorePlayerFocus() })
        }
        panel?.let { type ->
            val groups = tracks.groups.filter { it.type == type }; val first = remember(type) { FocusRequester() }
            val title = when (type) { C.TRACK_TYPE_VIDEO -> "جودة البث"; C.TRACK_TYPE_AUDIO -> "مسار الصوت"; else -> "الترجمة" }
            val options: @Composable ColumnScope.(close: () -> Unit) -> Unit = { close ->
                IptvTrackOption(if (type == C.TRACK_TYPE_TEXT) "إيقاف الترجمة" else "تلقائي", Modifier.focusRequester(first), compact = television) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                        .apply { if (type == C.TRACK_TYPE_VIDEO) { setForceHighestSupportedBitrate(false); setViewportSizeToPhysicalDisplaySize(true) } }
                        .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT).build()
                    retryFromTracks()
                    close()
                }
                if (type == C.TRACK_TYPE_VIDEO) {
                    IptvTrackOption((if (player.trackSelectionParameters.forceHighestSupportedBitrate) "✓ " else "") + "تفضيل أعلى جودة متاحة", compact = television) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .clearViewportSizeConstraints().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setForceHighestSupportedBitrate(true).build()
                        retryFromTracks()
                        close()
                    }
                    if (!television) Label("بما يدعمه البث والجهاز؛ 4K أو أعلى عند توفرها.", 14, Brand.TextTertiary)
                }
                groups.forEach { track -> for (i in 0 until track.length) if (track.isTrackSupported(i)) {
                    val format = track.getTrackFormat(i)
                    val label = if (type == C.TRACK_TYPE_VIDEO && format.height > 0) "${format.height}p" else format.label ?: format.language ?: "مسار ${i + 1}"
                    IptvTrackOption((if (track.isTrackSelected(i)) "✓ " else "") + label, compact = television) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                            .apply { if (type == C.TRACK_TYPE_VIDEO) { setForceHighestSupportedBitrate(false); setViewportSizeToPhysicalDisplaySize(true) } }
                            .setTrackTypeDisabled(type, false).addOverride(TrackSelectionOverride(track.mediaTrackGroup, i)).build()
                        retryFromTracks()
                        close()
                    }
                } }
                if (groups.isEmpty()) Label("لم يوفّر هذا البث خيارات إضافية من هذا النوع.", 14, Brand.TextTertiary)
                if (!television) IptvButton("إغلاق") { close() }
            }
            if (television) IptvTrackPopup(title, trackBounds, first, ::closeTracks, options)
            else IptvDialog(title, ::closeTracks, first) { options { closeTracks() } }
        }
    }
}
