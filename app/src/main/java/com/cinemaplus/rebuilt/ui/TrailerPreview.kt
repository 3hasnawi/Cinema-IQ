package com.cinemaplus.rebuilt.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cinemaplus.rebuilt.BuildConfig
import com.cinemaplus.rebuilt.data.Film
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Seconds a card must keep focus before it expands and starts its trailer. */
const val TrailerDwellMillis = 3_000L
/** Trailers start muted so browsing never blasts audio; set to false to play with sound. */
const val TrailerMuted = true

/** Current user preference for trailer sound (Settings); previews read it, the details page overrides it. */
val LocalTrailerMuted = androidx.compose.runtime.compositionLocalOf { TrailerMuted }

/**
 * Finds a YouTube video id for a film: a trailer link from the source data when present,
 * otherwise the first video result of a YouTube search for the title. Results are cached per film.
 * The search path scrapes a public results page, so it is best effort and can break if YouTube changes it.
 */
object TrailerResolver {
    private const val MaxCandidates = 5
    private val cache = ConcurrentHashMap<String, List<String>>() // film id -> candidate video ids
    private var prefs: android.content.SharedPreferences? = null
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val idPattern = Regex("""(?:v=|youtu\.be/|embed/|shorts/)([\w-]{11})""")
    private val resultPattern = Regex(""""videoId":"([\w-]{11})"""")

    /** Loads the persistent store of video ids that are known to play. */
    fun init(context: android.content.Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("trailer_cache", android.content.Context.MODE_PRIVATE)
    }

    /** Remembers an id that really played, so the next session starts with it and skips the search. */
    fun markWorking(filmId: String, videoId: String) {
        prefs?.edit()?.putString("ok_$filmId", videoId)?.apply()
    }

    internal fun parseId(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (Regex("""[\w-]{11}""").matches(value)) return value
        return idPattern.find(value)?.groupValues?.get(1)
    }

    /**
     * Candidate video ids, best first: the known-working id, a link from the source data, then search results.
     * The player walks the list when a video refuses to embed (error 152/153) or is unavailable.
     */
    suspend fun candidates(film: Film): List<String> {
        cache[film.id]?.let { return it }
        val known = prefs?.getString("ok_${film.id}", null)?.takeIf { it.length == 11 }
        val fromSource = parseId(film.trailer)
        val found = if (known != null) emptyList() else runCatching { search(film) }.getOrDefault(emptyList())
        val list = listOfNotNull(known, fromSource).plus(found).distinct().take(MaxCandidates)
        // Only cache real hits; an empty answer or a network failure is retried next time.
        if (list.isNotEmpty()) cache[film.id] = list
        return list
    }

    private fun query(film: Film) = listOf(film.enTitle.ifBlank { film.title }, film.year, "official trailer")
        .filter { it.isNotBlank() }.joinToString(" ")

    private val trailerWords = listOf("trailer", "teaser", "official", "تريلر", "إعلان", "اعلان", "برومو")

    /**
     * Gathers candidates, then keeps only videos whose owner allows embedding. The public oEmbed endpoint
     * answers 401 for blocked videos, which is what the player would otherwise report as error 152.
     * Trailer-titled videos come first.
     */
    private suspend fun search(film: Film): List<String> = withContext(Dispatchers.IO) {
        val key = BuildConfig.YOUTUBE_API_KEY
        // Official API first: embeddable videos only, so previews do not fail on blocked embeds.
        val viaApi = if (key.isNotBlank()) runCatching { searchApi(query(film), key) }.getOrDefault(emptyList()) else emptyList()
        val raw = viaApi.ifEmpty { scrape(query(film)) }
        val embeddable = raw.mapNotNull { id -> embeddableTitle(id)?.let { id to it } }
        // If the check itself failed for every id (offline oEmbed), fall back to the unfiltered list.
        if (embeddable.isEmpty()) raw.take(MaxCandidates)
        else (embeddable.filter { (_, title) -> trailerWords.any { title.contains(it, ignoreCase = true) } } +
            embeddable.filterNot { (_, title) -> trailerWords.any { title.contains(it, ignoreCase = true) } }).map { it.first }.take(MaxCandidates)
    }

    /** Title of the video when it can be embedded, null when blocked or unreachable. */
    private fun embeddableTitle(id: String): String? = runCatching {
        val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
        client.newCall(Request.Builder().url("https://www.youtube.com/oembed?url=$watch&format=json").build()).execute().use { response ->
            if (!response.isSuccessful) null else org.json.JSONObject(response.body?.string().orEmpty()).optString("title", "")
        }
    }.getOrNull()

    private fun searchApi(query: String, key: String): List<String> {
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=$MaxCandidates" +
            "&videoEmbeddable=true&videoSyndicated=true&safeSearch=moderate" +
            "&q=${URLEncoder.encode(query, "UTF-8")}&key=${URLEncoder.encode(key, "UTF-8")}"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            // 403 (quota or key restriction) falls through to the scraping fallback.
            if (!response.isSuccessful) return emptyList()
            val items = org.json.JSONObject(response.body?.string().orEmpty()).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull {
                items.optJSONObject(it)?.optJSONObject("id")?.optString("videoId")?.takeIf { id -> id.length == 11 }
            }
        }
    }

    private fun scrape(query: String): List<String> {
        // sp=EgIQAQ%3D%3D restricts results to videos (double-encoded as YouTube writes it).
        val url = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&sp=EgIQAQ%253D%253D"
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9").header("Cookie", "CONSENT=YES+1").build()
        return client.newCall(Request.Builder().url(url).headers(request.headers).build()).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            resultPattern.findAll(response.body?.string().orEmpty()).map { it.groupValues[1] }.distinct().take(10).toList()
        }
    }
}

private const val TAG = "Trailer"
private const val PollIntervalMillis = 500L
private const val PollAttempts = 30 // ~15 seconds before giving up

/** Hides YouTube's own chrome (title bar, gradients, pause overlay) and forces muted autoplay. */
private fun prepareScript(muted: Boolean) = """
(function(){
  var css=document.createElement('style');
  css.textContent='.ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom,.ytp-pause-overlay,.ytp-watermark,.ytp-large-play-button,.ytp-ce-element,.ytp-cards-teaser,.ytp-show-cards-title{display:none!important}html,body{background:#000!important;overflow:hidden!important}';
  document.head.appendChild(css);
  var v=document.querySelector('video');
  if(v){v.muted=$muted;v.loop=true;var p=v.play();if(p&&p.catch){p.catch(function(){});}}
})();
"""

/** Shows a small diagnostics strip on the expanded card. Turn off once trailers work reliably. */
const val TrailerDebug = false

/** Returns a one-line state summary: video element state plus page title and the first words of its text. */
private const val ProbeScript = """(function(){
  var v=document.querySelector('video');
  var t=(document.title||'')+' | '+((document.body&&document.body.innerText)||'').replace(/\s+/g,' ').slice(0,70);
  return v?('video p='+v.paused+' t='+v.currentTime.toFixed(1)+' rs='+v.readyState+' err='+(v.error?v.error.code:0)+' | '+t):('no-video | '+t);
})()"""

private val ErrorPagePattern = Regex("(?i)error|unavailable|not available|غير متوفر|رمز الخطأ")

private suspend fun WebView.probe(): String = suspendCancellableCoroutine { cont ->
    evaluateJavascript(ProbeScript) { result -> if (cont.isActive) cont.resume(result.orEmpty().trim('"')) }
}

private fun String.isAdvancing(): Boolean {
    if (!startsWith("video p=false")) return false
    val time = Regex("""t=([\d.]+)""").find(this)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    val ready = Regex("""rs=(\d)""").find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return time > 0.2f && ready >= 3
}

private fun isYoutubeHost(host: String?) = host != null &&
    (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") || host.endsWith("google.com") || host.endsWith("gstatic.com"))

/**
 * Muted, controller-less YouTube player for card previews. Never takes D-pad focus.
 * It opens the official /embed/ page directly with a YouTube Referer (embeds reject requests without one,
 * which breaks loadData based players), then polls the page's <video> element to learn when playback truly starts.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPreview(videoId: String, modifier: Modifier = Modifier, muted: Boolean = LocalTrailerMuted.current,
    onPlaying: () -> Unit, onFailed: () -> Unit, onStatus: (String) -> Unit = {}) {
    val status = rememberUpdatedState(onStatus)
    val playing = rememberUpdatedState(onPlaying)
    val failed = rememberUpdatedState(onFailed)
    var web by remember(videoId) { mutableStateOf<WebView?>(null) }

    LaunchedEffect(web) {
        val view = web ?: return@LaunchedEffect
        val engine = if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName else null
        repeat(PollAttempts) { attempt ->
            delay(PollIntervalMillis)
            // Re-apply every few polls: the embed player builds its DOM asynchronously.
            if (attempt % 4 == 0) view.evaluateJavascript(prepareScript(muted), null)
            val state = view.probe()
            status.value("wv=${engine ?: "?"} #${attempt + 1} $state")
            // YouTube's own error page (embedding blocked, unavailable): fail fast so the next candidate can be tried.
            if (attempt >= 2 && state.substringAfter('|', "").contains(ErrorPagePattern)) {
                Log.w(TAG, "error page for $videoId: $state"); failed.value(); return@LaunchedEffect
            }
            if (state.isAdvancing()) { Log.d(TAG, "playing $videoId after ${(attempt + 1) * PollIntervalMillis}ms"); playing.value(); return@LaunchedEffect }
        }
        Log.w(TAG, "no playback for $videoId, giving up")
        failed.value()
    }

    AndroidView(factory = { context ->
        try {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(AndroidColor.BLACK)
                isFocusable = false; isFocusableInTouchMode = false
                isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = object : WebChromeClient() {
                    // Keep the player inline; never let it go fullscreen.
                    override fun onShowCustomView(view: android.view.View?, callback: WebChromeClient.CustomViewCallback?) { callback?.onCustomViewHidden() }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) =
                        !isYoutubeHost(request?.url?.host)
                    override fun onPageFinished(view: WebView?, url: String?) { view?.evaluateJavascript(prepareScript(muted), null) }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "load error ${error?.errorCode} ${error?.description}")
                            status.value("load error ${error?.errorCode} ${error?.description}")
                        }
                    }
                }
                // YouTube requires embedded players to identify the host app through the Referer header;
                // a youtube.com referer is rejected (error 152-4), so use an https URL derived from the package name.
                val appReferer = "https://${context.packageName}"
                val embed = "https://www.youtube.com/embed/$videoId?autoplay=1&mute=${if (muted) 1 else 0}&controls=0&rel=0" +
                    "&modestbranding=1&playsinline=1&iv_load_policy=3&disablekb=1&fs=0&loop=1&playlist=$videoId&origin=$appReferer"
                loadUrl(embed, mapOf("Referer" to appReferer))
                web = this
            }
        } catch (_: Exception) {
            // No WebView provider on this device: report failure so the card just stays as artwork.
            failed.value()
            android.view.View(context)
        }
    }, modifier = modifier, onRelease = { view ->
        (view as? WebView)?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
    })
}
