package com.cinemaplus.rebuilt

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cinemaplus.rebuilt.data.*
import kotlinx.coroutines.*
import com.cinemaplus.rebuilt.update.*

enum class Page(val label: String) { Home("الرئيسية"), Explore("استكشاف"), Movies("أفلام"), Series("مسلسلات"), Search("بحث"), Library("قائمتي"), Settings("الإعدادات") }
class CinemaViewModel(app: Application) : AndroidViewModel(app) {
    private val updates = UpdateRepository()
    var availableUpdate by mutableStateOf<UpdateManifest?>(null)
    var updateBusy by mutableStateOf(false)
    var updateProgress by mutableIntStateOf(0)
    var updateStatus by mutableStateOf<String?>(null)
    private var lastUpdateCheck = 0L
    private var downloadedUpdate: java.io.File? = null
    fun checkUpdates(manual: Boolean = false) {
        if (updateBusy) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && lastUpdateCheck != 0L && now - lastUpdateCheck < 6 * 60 * 60 * 1000L) return
        lastUpdateCheck = now; updateBusy = true
        viewModelScope.launch {
            try {
                val latest = updates.latest()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                if (latest != null && latest.minSdk > android.os.Build.VERSION.SDK_INT) {
                    updateStatus = "التحديث الجديد غير متوافق مع إصدار أندرويد على جهازك."; return@launch
                }
                val previous = availableUpdate?.versionCode
                availableUpdate = latest
                updateStatus = if (latest == null) "لا يوجد تحديث أحدث منشور." else "يتوفر الإصدار ${latest.versionName}"
                if (latest != null && previous != latest.versionCode && !manual) {
                    android.widget.Toast.makeText(getApplication(), "يتوفر تحديث Cinema iQ؛ افتح الإعدادات للتحديث.", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (manual) updateStatus = "تعذّر فحص التحديث. تحقق من الإنترنت وأعد المحاولة." }
            finally { updateBusy = false }
        }
    }
    fun downloadUpdate() {
        val update = availableUpdate ?: return
        if (updateBusy) return
        updateBusy = true; updateProgress = 0
        viewModelScope.launch {
            try {
                val file = downloadedUpdate?.takeIf { it.name == "Cinema-iQ-${update.versionCode}.apk" && it.exists() }
                    ?: updates.download(update, java.io.File(getApplication<Application>().cacheDir, "updates")) {
                        withContext(Dispatchers.Main) { updateProgress = it }
                    }
                UpdateInstaller.verify(getApplication(), file, update)
                downloadedUpdate = file; updateStatus = "التحديث جاهز. اسمح بالتثبيت إذا طُلب، ثم اضغط تثبيت التحديث."
                UpdateInstaller.install(getApplication(), file)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { downloadedUpdate?.delete(); downloadedUpdate = null; updateStatus = e.message ?: "تعذّر تثبيت التحديث." }
            finally { updateBusy = false }
        }
    }
    val profile: Profile = ProfileStore(app).active()
    val library = Library(app, profile.id)
    private val account = SourceAccount(app)
    private val api = CinemanaApi { account.token() }
    private val subtitleResolver = CinemanaSubtitles()
    var signedIn by mutableStateOf(account.token() != null)
    var accountBusy by mutableStateOf(false)
    /** The account is shared by every profile, so a view model re-reads it when it becomes active again. */
    fun refreshAccount() { signedIn = account.token() != null; accountError = null }
    var accountError by mutableStateOf<String?>(null)
    fun login(username: String, password: String) {
        if(accountBusy) return
        accountBusy = true; accountError = null
        viewModelScope.launch {
            try { account.login(username, password); signedIn = true }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { accountError = e.message ?: "تعذّر تسجيل الدخول." }
            finally { accountBusy = false }
        }
    }
    fun logout() {
        if(accountBusy) return
        accountBusy = true; accountError = null
        viewModelScope.launch {
            try { account.logout(); signedIn = false }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { accountError = e.message ?: "تعذّر تسجيل الخروج." }
            finally { accountBusy = false }
        }
    }
    var page by mutableStateOf(Page.Home)
    var demo by mutableStateOf(false)
        private set
    var sourceChoice by mutableStateOf(library.sourceChoice)
        private set
    fun chooseSource(value: SourceChoice) {
        if (sourceChoice == value) return
        loadJob?.cancel()
        sourceChoice = value; library.sourceChoice = value
        pageNumber = 1; shelves = emptyList(); descriptions.clear()
        closeDetail(); stop(); reload()
    }
    /** Selected mood on the Explore page; null shows the mood grid. */
    var exploreMood by mutableStateOf<Mood?>(null)
        private set
    fun chooseMood(mood: Mood?) { exploreMood = mood }
    var shelves by mutableStateOf<List<Shelf>>(emptyList())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Film?>(null)
    var seasons by mutableStateOf<List<Season>>(emptyList())
    var seasonIndex by mutableIntStateOf(0)
    var detailBusy by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)
    var playing by mutableStateOf<Film?>(null)
    var streams by mutableStateOf<List<Stream>>(emptyList())
    var playbackError by mutableStateOf<String?>(null)
    var playbackBusy by mutableStateOf(false)
    var query by mutableStateOf("")
    var pageNumber by mutableIntStateOf(1)
    var libraryRevision by mutableIntStateOf(0)
    var subtitleSize by mutableFloatStateOf(library.subtitleSize)
    var subtitleLanguage by mutableStateOf(library.subtitleLanguage)
    var subtitleBackground by mutableFloatStateOf(library.subtitleBackground)
    fun chooseSubtitleSize(value: Float) { subtitleSize = value; library.subtitleSize = value }
    fun chooseSubtitleLanguage(value: String) { subtitleLanguage = value; library.subtitleLanguage = value }
    fun chooseSubtitleBackground(value: Float) { subtitleBackground = value; library.subtitleBackground = value }
    var subtitleHeight by mutableFloatStateOf(library.subtitleHeight)
    fun chooseSubtitleHeight(value: Float) { subtitleHeight = value; library.subtitleHeight = value }
    var titleLanguage by mutableStateOf(library.titleLanguage)
    fun chooseTitleLanguage(value: TitleLanguage) { titleLanguage = value; library.titleLanguage = value }
    var quality by mutableStateOf(library.quality)
    var trailerMuted by mutableStateOf(library.trailerMuted)
    fun chooseTrailerMuted(value: Boolean) { trailerMuted = value; library.trailerMuted = value }
    var sceneFilter by mutableStateOf(library.sceneFilter)
    fun chooseSceneFilter(value: Boolean) { sceneFilter = value; library.sceneFilter = value }
    var trailerPreview by mutableStateOf(library.trailerPreview)
    fun chooseTrailerPreview(value: Boolean) { trailerPreview = value; library.trailerPreview = value }
    private val descriptions = HashMap<String, String>()
    private val tmdb = TmdbMetadata(BuildConfig.TMDB_API_KEY, java.io.File(app.cacheDir, "tmdb-artwork.json"))
    /** True when a TMDB key is configured, so the attribution must be shown. */
    val artworkFromTmdb: Boolean get() = tmdb.enabled
    private val artwork = mutableStateMapOf<String, ArtworkMatch>()
    /** The film with TMDB artwork laid over the source's, when TMDB has a reliable match. Observable. */
    fun withArtwork(film: Film): Film {
        val match = artwork[film.id] ?: return film
        return film.copy(backdrop = match.backdrop.ifBlank { film.backdrop }, poster = match.poster.ifBlank { film.poster },
            description = film.description.ifBlank { match.overview }, rating = film.rating.ifBlank { match.rating },
            originalTitle = film.originalTitle.ifBlank { match.originalTitle }, genreIds = film.genreIds + match.genreIds)
    }
    /** Looks the titles up in the background; failures leave the source data untouched. */
    fun enrich(films: List<Film>) {
        if (!tmdb.enabled) return
        viewModelScope.launch {
            for (film in films) {
                if (film.episode > 0 || film.id.startsWith("demo-") || film.id in artwork) continue
                val match = try { tmdb.lookup(film) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (match != null) artwork[film.id] = match
            }
        }
    }
    /** Description for an expanded card. List endpoints often omit it, so it is fetched once and cached. */
    suspend fun describe(film: Film): String {
        if (film.description.isNotBlank()) return film.description
        if (film.id.startsWith("demo-")) return ""
        descriptions[film.id]?.let { return it }
        val text = try { api.detail(film).description }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { "" }
        descriptions[film.id] = text
        return text
    }
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var playJob: Job? = null
    private var followedSeriesJob: Job? = null
    private var lastSeriesCheck = 0L
    /** A single background check covers startup, foreground return and catalog refresh. */
    fun refreshFollowedSeries(force: Boolean = false) {
        if (demo || followedSeriesJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastSeriesCheck != 0L && now - lastSeriesCheck < 60_000L) return
        lastSeriesCheck = now
        followedSeriesJob = viewModelScope.launch {
            for (checkpoint in library.followedSeries()) {
                if (demo) break
                if (sourceChoice == SourceChoice.CINEMANA && VoduApi.owns(checkpoint.seriesId)) continue
                if (sourceChoice == SourceChoice.VODU && !VoduApi.owns(checkpoint.seriesId)) continue
                if (library.recent().any { it.seriesId == checkpoint.seriesId }) continue
                try {
                    val available = api.seasons(checkpoint.seriesId, fresh = true).map { season ->
                        season.copy(episodes = season.episodes.map {
                            it.copy(seriesId = checkpoint.seriesId, season = season.number)
                        })
                    }
                    val next = ContinueWatching.next(available, checkpoint.id) ?: continue
                    if (library.offerNewEpisode(checkpoint, next, playing)) libraryRevision++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the checkpoint and retry on the next refresh. */ }
            }
        }
    }
    init { library.demo = false; reload() }
    private val pageHistory = mutableListOf<Page>()
    private val detailHistory = mutableListOf<Film>()
    fun navigate(to: Page) {
        if (to == page) return
        pageHistory.add(page)
        page = to; pageNumber = 1; closeDetail(); reload()
    }
    fun backPage() {
        navigateBack(pageHistory.removeLastOrNull() ?: Page.Home)
    }
    private fun navigateBack(to: Page) { page = to; pageNumber = 1; reload() }
    fun search(text: String) { query = text.trim(); pageNumber = 1; reload() }
    fun changeDemo(value: Boolean) { demo = value; library.demo = value; closeDetail(); stop(); reload() }
    fun reload(append: Boolean = false) {
        if (!append) refreshFollowedSeries(force = true)
        loadJob?.cancel()
        if (page == Page.Settings) { busy = false; error = null; return }
        loadJob = viewModelScope.launch {
            busy = true; error = null
            if (!append) shelves = emptyList()
            try {
                val data = when {
                    page == Page.Library -> listOf(Shelf("saved", "المحفوظة على هذا الجهاز", library.favorites()))
                    demo -> when (page) {
                        Page.Home -> Demo.shelves
                        Page.Movies -> listOf(Shelf("movies", "أفلام", Demo.films.filterNot { it.series }))
                        Page.Series -> listOf(Shelf("series", "مسلسلات", Demo.films.filter { it.series }))
                        Page.Explore -> listOf(Shelf("genre-m-demo", "أكشن", Demo.films.filterNot { it.series }),
                            Shelf("genre-s-demo", "دراما", Demo.films.filter { it.series }))
                        else -> listOf(Shelf("search", "نتائج البحث", Demo.films.filter { it.title.contains(query) }))
                    }
                    page == Page.Explore -> exploreCatalog()
                    page == Page.Home -> combinedSources(listOf(false, true), 1) { api.home() }
                    page == Page.Search -> {
                        if (query.isBlank()) emptyList() else searchCatalog(query, pageNumber)
                    }
                    // Movies and Series are grouped by genre; fall back to the flat catalog if categories are unavailable.
                    (page == Page.Movies || page == Page.Series) && pageNumber == 1 ->
                        combinedSources(listOf(page == Page.Series), pageNumber) {
                            try { api.genreShelves(page == Page.Series).takeIf { it.isNotEmpty() }
                                ?: listOf(Shelf("catalog", page.label, api.catalog(page == Page.Series, "", pageNumber))) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { listOf(Shelf("catalog", page.label, api.catalog(page == Page.Series, "", pageNumber))) }
                        }
                    else -> combinedSources(listOf(page == Page.Series), pageNumber) {
                        listOf(Shelf("catalog", page.label, api.catalog(page == Page.Series, "", pageNumber)))
                    }
                }
                shelves = if(append) (shelves.map { old ->
                    old.copy(films = (old.films + data.firstOrNull { it.id == old.id }?.films.orEmpty()).distinctBy { it.id })
                } + data.filter { next -> shelves.none { it.id == next.id } }) else data
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "تعذّر تحميل المحتوى. تحقق من الشبكة المحلية." }
            finally { if(currentCoroutineContext().isActive) busy = false }
        }
    }
    /** Category shelves for mood browsing. Only the Cinemana source exposes categories. */
    private suspend fun exploreCatalog(): List<Shelf> {
        if (sourceChoice == SourceChoice.VODU)
            throw IllegalStateException("الاستكشاف يعتمد على تصنيفات سينمانا. اختر سينمانا من الإعدادات > المصادر.")
        return coroutineScope {
            val movies = async { api.genreShelves(false, 20) }
            val series = async { api.genreShelves(true, 20) }
            movies.await() + series.await()
        }
    }
    /** Source failures remain visible while another source can still supply content. */
    private suspend fun combinedSources(kinds: List<Boolean>, number: Int, search: String = "",
        original: suspend () -> List<Shelf>): List<Shelf> = supervisorScope {
        val requests = sourceChoice.requests(original, kinds.map { series ->
            suspend { listOf(Shelf("vodu-${if (series) "series" else "movies"}",
                if (series) "VODU · مسلسلات" else "VODU · أفلام", api.voduCatalog(series, search, number))) }
        })
        val results = progressiveSources(requests) { ready ->
            val available = ready.filter { it.films.isNotEmpty() }
            shelves = shelves.map { old ->
                old.copy(films = (old.films + available.firstOrNull { it.id == old.id }?.films.orEmpty()).distinctBy { it.id })
            } + available.filter { next -> shelves.none { it.id == next.id } }
        }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        if (results.any { it.isFailure }) error = "تعذّر تحميل أحد المصادر. تظهر النتائج المتاحة؛ يمكنك إعادة المحاولة."
        results.flatMap { it.getOrDefault(emptyList()) }.filter { it.films.isNotEmpty() }
    }
    private suspend fun searchCatalog(text: String, number: Int): List<Shelf> = supervisorScope {
        combinedSources(listOf(false, true), number, text) {
        // Keep successful movie results even if the series endpoint fails, and vice versa.
        val results = listOf(false, true).map { series -> async {
            try { Result.success(api.catalog(series, text, number)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure<List<Film>>(e) }
        } }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        if (results.any { it.isFailure }) error = "تعذّر تحميل بعض النتائج. يمكنك إعادة المحاولة."
        listOf(Shelf("search-movies", "الأفلام", results[0].getOrDefault(emptyList()).distinctBy { it.id }),
            Shelf("search-series", "المسلسلات", results[1].getOrDefault(emptyList()).distinctBy { it.id }))
        }
    }
    fun more() { if(!busy) { pageNumber++; reload(true) } }
    fun open(film: Film) {
        enrich(listOf(film))
        selected?.takeIf { it.id != film.id }?.let { detailHistory.add(it) }
        detailJob?.cancel(); selected = film; seasons = emptyList(); seasonIndex = 0; detailError = null
        detailJob = viewModelScope.launch {
            detailBusy = true
            try {
                if (film.id.startsWith("demo-")) { if(film.series) seasons = Demo.seasons(film) }
                else {
                    selected = api.detail(film)
                    if (selected?.series == false && selected?.contentQuality.isNullOrBlank()) {
                        try {
                            val quality = availableQuality(api.streams(film.id))
                            selected = selected?.copy(contentQuality = quality)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Missing stream metadata must not block details. */ }
                    }
                    if (selected?.series == true) seasons = api.seasons(film.id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { detailError = e.message ?: "تعذّر تحميل التفاصيل" }
            finally { detailBusy = false }
        }
    }
    fun closeDetail() { detailJob?.cancel(); selected = null; detailHistory.clear() }
    fun backDetail() {
        val previous = detailHistory.removeLastOrNull()
        selected = null
        if (previous == null) closeDetail() else open(previous)
    }
    fun play(film: Film) {
        val showId = film.seriesId.ifBlank {
            if (seasons.any { season -> season.episodes.any { it.id == film.id } }) selected?.id.orEmpty()
            else if (film.id.startsWith("vodu:") && film.id.count { it == ':' } == 2)
                "vodu:${VoduApi.parentId(film.id)}" else ""
        }
        val contextual = film.copy(seriesId = showId)
        library.beginWatching(contextual)
        playJob?.cancel(); playing = contextual; streams = emptyList(); playbackError = null
        if (showId.isNotBlank()) viewModelScope.launch {
            try {
                val loaded = api.seasons(showId).map { season -> season.copy(episodes = season.episodes.map {
                    it.copy(seriesId = showId, season = season.number)
                }) }
                if (playing?.seriesId == showId) seasons = loaded
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Playback remains available if episode listing fails. */ }
        } else if (seasons.none { season -> season.episodes.any { it.id == film.id } }) seasons = emptyList()
        if (film.id.startsWith("demo-")) { playbackError = "هذا عمل توضيحي للواجهة. لتشغيل المحتوى الحقيقي، عطّل العرض التجريبي واتصل بالشبكة المحلية."; return }
        playJob = viewModelScope.launch {
            playbackBusy = true
            try {
                val details = api.detail(contextual).copy(seriesId = showId, season = contextual.season, episode = contextual.episode)
                playing = if (VoduApi.owns(details.id)) details else
                    details.copy(subtitles = subtitleResolver.resolve(details.subtitles))
                streams = api.streams(film.id)
                if (streams.isEmpty()) playbackError = "لم يُرجع المصدر رابط تشغيل صالحًا لهذا العمل."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { playbackError = e.message ?: "تعذّر بدء التشغيل" }
            finally { playbackBusy = false }
        }
    }
    fun stop() { playJob?.cancel(); playing = null; streams = emptyList(); playbackBusy = false }
    fun nextEpisode(): Film? = ContinueWatching.next(seasons, playing?.id.orEmpty())
    fun recordProgress(film: Film, position: Long, duration: Long) {
        library.saveProgress(film, position, duration)
        libraryRevision++
    }
    fun completePlayback(film: Film) {
        viewModelScope.launch {
            val current = playing?.takeIf { it.id == film.id } ?: film
            val episodeSeasons = if (current.seriesId.isNotBlank()) {
                try { api.seasons(current.seriesId).map { season -> season.copy(episodes = season.episodes.map {
                    it.copy(seriesId = current.seriesId, season = season.number)
                }) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { emptyList<Season>() }
            } else seasons.takeIf { list -> list.any { it.episodes.any { e -> e.id == current.id } } }.orEmpty()
            val next = ContinueWatching.next(episodeSeasons, current.id)
            val checkpoint = episodeSeasons.flatMap { it.episodes }.firstOrNull { it.id == current.id } ?: current
            library.complete(checkpoint, next, episodeSeasons.flatMap { it.episodes }.map { it.id }.toSet())
            libraryRevision++
        }
    }
    fun toggle(f: Film) { library.toggle(f); libraryRevision++; if(page == Page.Library) reload() }
    fun chooseQuality(q: String) { quality = q; library.quality = q }
}
