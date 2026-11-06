package com.cinemaplus.rebuilt.cast

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.*
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Stream
import kotlinx.coroutines.delay

/** A phone-only sender. The receiver downloads the original media; it never receives account credentials. */
@Composable
fun MobileCastButton(film: Film, streams: List<Stream>, quality: Int, position: () -> Long,
                     pauseLocal: () -> Unit, resumeLocal: (Long) -> Unit,
                     save: (Long, Long) -> Unit, complete: () -> Unit, onCasting: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Devices without Google Play services keep their local player intact.
    val cast = remember(context) { runCatching { CastContext.getSharedInstance(context) }.getOrNull() } ?: return
    var session by remember { mutableStateOf(cast.sessionManager.currentCastSession) }
    var expanded by remember { mutableStateOf(false) }
    var remotePosition by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var selected by remember(film.id) { mutableIntStateOf(quality) }
    var subtitle by remember(film.id) { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedID by remember { mutableStateOf<String?>(null) }
    var completed by remember(film.id) { mutableStateOf(false) }
    val alive = remember(film.id) { java.util.concurrent.atomic.AtomicBoolean(true) }
    var lastSave by remember(film.id) { mutableLongStateOf(0L) }
    var pending by remember { mutableStateOf(false) }
    val tracks = remember(film.subtitles) { film.subtitles.filter { CastPolicy.subtitle(it.url, it.mimeType) } }
    val latestPause by rememberUpdatedState(pauseLocal)
    val latestCasting by rememberUpdatedState(onCasting)
    val latestResume by rememberUpdatedState(resumeLocal)
    val latestSave by rememberUpdatedState(save)
    val latestComplete by rememberUpdatedState(complete)
    val client = session?.remoteMediaClient
    fun load(index: Int, at: Long, autoplay: Boolean = true) {
        val remote = session?.remoteMediaClient ?: return
        val stream = streams.getOrNull(index) ?: return
        val mime = CastPolicy.mime(stream.url)
        if (mime == null) { error = "هذه الصيغة غير مدعومة بالكاست. اختر جودة أخرى."; return }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, film.title) }
        val mediaTracks = tracks.mapIndexed { i, track -> MediaTrack.Builder(i + 1L, MediaTrack.TYPE_TEXT)
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES).setContentId(track.url).setContentType("text/vtt")
            .setName(track.title).setLanguage(track.language).build() }
        val info = MediaInfo.Builder(stream.url).setContentType(mime).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata).setMediaTracks(mediaTracks)
            .setCustomData(org.json.JSONObject().put("cinemaID", film.id)).build()
        pending = true; error = null
        remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setCurrentTime(at.coerceAtLeast(0))
            .setAutoplay(autoplay).setActiveTrackIds(if (subtitle > 0) longArrayOf(subtitle) else longArrayOf()).build())
            .setResultCallback { result ->
                if (!alive.get()) return@setResultCallback
                pending = false
                if (result.status.isSuccess) { selected = index; loadedID = film.id; completed = false; latestCasting(true); latestPause() }
                else error = "تعذّر التشغيل على جهاز الكاست. تحقق من شبكة المصدر أو جرّب جودة أخرى."
            }
    }
    DisposableEffect(cast, film.id) {
        alive.set(true)
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(s: CastSession, id: String) { session = s; expanded = true }
            override fun onSessionResumed(s: CastSession, suspended: Boolean) { session = s; expanded = true }
            override fun onSessionEnded(s: CastSession, code: Int) {
                if (loadedID == film.id && !completed) latestResume(remotePosition)
                session = null; loadedID = null; expanded = false; latestCasting(false)
            }
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionSuspended(s: CastSession, reason: Int) { error = "انقطع اتصال الكاست. جارٍ انتظار إعادة الاتصال." }
            override fun onSessionStartFailed(s: CastSession, code: Int) { error = "تعذّر الاتصال بجهاز الكاست."; expanded = true }
            override fun onSessionResumeFailed(s: CastSession, code: Int) { error = "تعذّر استعادة اتصال الكاست." }
        }
        cast.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        onDispose {
            alive.set(false)
            cast.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
            if (loadedID == film.id) {
                if (!completed && duration > 0) latestSave(remotePosition, duration)
                session?.remoteMediaClient?.stop()
                latestCasting(false)
            }
        }
    }
    LaunchedEffect(session, film.id, streams) {
        if (session?.isConnected == true && streams.isNotEmpty()) {
            val status = client?.mediaStatus
            if (status?.mediaInfo?.customData?.optString("cinemaID") == film.id) {
                loadedID = film.id; latestCasting(true); latestPause()
            } else load(quality, position())
        }
    }
    LaunchedEffect(client, film.id) {
        while (client != null) {
            val status = client.mediaStatus
            if (status?.mediaInfo?.customData?.optString("cinemaID") == film.id) {
                latestPause()
                remotePosition = client.approximateStreamPosition; duration = client.streamDuration
                isPlaying = client.isPlaying
                if (status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    if (!completed) { completed = true; latestComplete() }
                } else if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    error = "رفض جهاز الكاست رابط الفيديو. تأكد أن الجهاز على الشبكة الداعمة للمصدر."
                } else if (!pending && duration > 0 && android.os.SystemClock.elapsedRealtime() - lastSave >= 5000) {
                    latestSave(remotePosition, duration); lastSave = android.os.SystemClock.elapsedRealtime()
                }
            }
            delay(1000)
        }
    }
    Column {
        AndroidView(factory = { MediaRouteButton(it).apply { CastButtonFactory.setUpMediaRouteButton(it, this) } },
            modifier = Modifier.size(44.dp))
        if (session?.isConnected == true) TextButton(onClick = { expanded = true }) { Text("تحكم بالكاست") }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("Google Cast") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(film.title)
            Text("يجب أن يكون جهاز الكاست على الشبكة الداعمة للمصدر.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (pending) LinearProgressIndicator()
            if (duration > 0) Slider(value = remotePosition.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { remotePosition = it.toLong() }, onValueChangeFinished = {
                    client?.seek(MediaSeekOptions.Builder().setPosition(remotePosition).build())
                }, valueRange = 0f..duration.toFloat())
            Row {
                TextButton(onClick = { client?.seek(MediaSeekOptions.Builder().setPosition((remotePosition - 10000).coerceAtLeast(0)).build()) }) { Text("−10") }
                TextButton(onClick = { if (isPlaying) client?.pause() else client?.play() }) { Text(if (isPlaying) "إيقاف مؤقت" else "تشغيل") }
                TextButton(onClick = { client?.seek(MediaSeekOptions.Builder().setPosition((remotePosition + 10000).coerceAtMost(duration.coerceAtLeast(0))).build()) }) { Text("+10") }
            }
            Text("الجودة")
            streams.forEachIndexed { index, stream -> TextButton(enabled = !pending, onClick = { load(index, if (loadedID == film.id) remotePosition else position(), isPlaying || loadedID != film.id) }) {
                Text((if (index == selected) "✓ " else "") + stream.resolution)
            } }
            Text("الترجمة (WebVTT)")
            TextButton(onClick = { client?.setActiveMediaTracks(longArrayOf())?.setResultCallback {
                if (it.status.isSuccess) subtitle = 0 else error = "تعذّر تغيير الترجمة."
            } }) { Text("بدون ترجمة") }
            tracks.forEachIndexed { index, track -> TextButton(onClick = {
                client?.setActiveMediaTracks(longArrayOf(index + 1L))?.setResultCallback {
                    if (it.status.isSuccess) subtitle = index + 1L else error = "تعذّر إرسال الترجمة إلى الكاست."
                }
            }) { Text((if (subtitle == index + 1L) "✓ " else "") + track.title) } }
            if (tracks.size < film.subtitles.size) Text("بعض ملفات الترجمة تحتاج تحويلًا إلى WebVTT على خادم مصرح به قبل إرسالها.")
        } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("إغلاق") } },
        dismissButton = { TextButton(onClick = { cast.sessionManager.endCurrentSession(true) }) { Text("إيقاف الكاست") } })
}
