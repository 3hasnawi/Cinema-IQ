package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.relatedFilms
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** TV details use RTL placement with the information and actions on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailsScreen(vm: CinemaViewModel, ageRating: String? = null, displayQuality: String? = null) {
    val sourceFilm = vm.selected ?: return
    val film = vm.withArtwork(sourceFilm)
    val artwork = listOf(film.backdrop, sourceFilm.backdrop).filter { it.isNotBlank() }.distinct()
        .ifEmpty { listOf(film.poster) }
    var artworkIndex by remember(film.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var initialFocusAssigned by remember(film.id) { mutableStateOf(false) }
    val playFocus = remember(film.id) { FocusRequester() }
    val seasonFocus = remember(film.id) { FocusRequester() }
    val episodeFocus = remember(film.id) { FocusRequester() }
    val trailerFocus = remember(film.id) { FocusRequester() }
    val castFocus = remember(film.id) { FocusRequester() }
    val credits = vm.detailsCredits
    val hasCast = !credits?.cast.isNullOrEmpty()
    LaunchedEffect(film.id) { vm.loadDetailsCredits() }
    val infoFocus = remember(film.id) { FocusRequester() }
    val seasonRowState = rememberLazyListState()
    val episodeRowState = rememberLazyListState()
    val castRowState = rememberLazyListState()
    val relatedRowState = rememberLazyListState()
    var focusedSection by remember(film.id) { mutableStateOf("hero") }
    val navigationJob = remember(film.id) { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val relatedFocus = remember(film.id) { FocusRequester() }
    val season = vm.seasons.getOrNull(vm.seasonIndex)
    val episodes = season?.episodes.orEmpty()
    // A series shows the selected season's year; it falls back to the series year when TMDB gave no season date.
    val seasonYear = if(film.series) season?.airDate?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } } else null
    LaunchedEffect(film.id, season?.number) { if(season != null) vm.enrichSelectedSeason() }
    val candidates = vm.shelves.map { it.copy(films = it.films.map(vm::withArtwork)) }
    val related = relatedFilms(film, candidates)
    LaunchedEffect(film.id, vm.shelves) {
        vm.enrich(vm.shelves.flatMap { it.films }.filter { it.series == film.series && it.episode == 0 && it.id != film.id }
            .distinctBy { it.id }.take(36))
    }
    var showTrailer by remember(film.id) { mutableStateOf(false) }
    LaunchedEffect(film.id, artwork, focusedSection) {
        artworkIndex = artworkIndex.coerceIn(0, artwork.lastIndex)
        if(artwork.size > 1 && focusedSection == "hero") while(true) {
            delay(8000)
            artworkIndex = (artworkIndex + 1) % artwork.size
        }
    }
    val revision = vm.libraryRevision
    val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
    // The episode Play starts: the one in progress, else the first not yet watched.
    val resume = remember(film.id, vm.seasons, revision) { if (film.series) vm.resumeEpisode(film) else null }
    val playLabel = when {
        !film.series -> if (vm.library.progress(film.id) > 0) "استئناف" else "تشغيل"
        resume == null -> "تشغيل"
        vm.library.progress(resume.id) > 0 -> "استئناف الحلقة ${resume.episode}"
        resume.episode > 1 || resume.season > 1 -> "تشغيل الحلقة ${resume.episode}"
        else -> "تشغيل"
    }
    // One block of the page is visible at a time, like the home stage: the hero information, the seasons and
    // episodes, the cast and the related titles replace each other on Down/Up while the artwork stays put.
    val stageKeys = buildList {
        add("hero")
        if(film.series && vm.seasons.isNotEmpty()) add("episodes")
        if(credits != null && (hasCast || credits.directors.isNotEmpty() || credits.creators.isNotEmpty())) add("cast")
        if(related.isNotEmpty()) add("related")
    }
    var stageKey by remember(film.id) { mutableStateOf("hero") }
    val activeKey = stageKey.takeIf { it in stageKeys } ?: "hero"
    fun stageOf(section: String) = when {
        section == "hero" -> "hero"
        section == "season" || section.startsWith("episodes-") -> "episodes"
        section == "cast" || section == "credits-info" -> "cast"
        else -> "related"
    }
    // Back leaves a row stage for the hero first; the next Back closes the page.
    BackHandler(enabled = !showTrailer && activeKey == "hero") { vm.backDetail() }
    BackHandler(enabled = !showTrailer && activeKey != "hero") {
        stageKey = "hero"
        scope.launch { awaitTvFocus { if(!film.series || episodes.isNotEmpty()) playFocus else trailerFocus } }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            Crossfade(artwork[artworkIndex.coerceAtMost(artwork.lastIndex)],
                animationSpec = tween(900), label = "Details artwork", modifier = Modifier.fillMaxSize()) { image ->
                AsyncImage(ImageRequest.Builder(LocalContext.current).data(image).crossfade(true).build(),
                    null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .60f), Color.Black.copy(alpha = .92f)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .20f), Color.Black))))
            val infoWidth = maxWidth * .52f
            val sections = buildList<Pair<String, FocusRequester>> {
                add("hero" to if(!film.series || episodes.isNotEmpty()) playFocus else trailerFocus)
                if(film.series && vm.seasons.isNotEmpty()) {
                    add("season" to seasonFocus)
                    if(episodes.isNotEmpty()) add("episodes-${season?.number}" to episodeFocus)
                }
                if(hasCast) add("cast" to castFocus)
                else if(credits != null && (credits.directors.isNotEmpty() || credits.creators.isNotEmpty())) add("credits-info" to infoFocus)
                if(related.isNotEmpty()) add("related" to relatedFocus)
            }
            // Extra scrim once the information is replaced by a row, keeping the artwork readable behind the cards.
            val rowsScrim by animateFloatAsState(if(activeKey == "hero") 0f else .5f, tween(300), label = "Details scrim")
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = rowsScrim)))
            Label(mediaTitle(film), 54, Color.White, true, lines = 2,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(.76f).padding(start = 32.dp, top = 68.dp))
            WithoutFocusScroll {
                Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    val down = event.key == Key.DirectionDown
                    if(!down && event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                    val current = sections.indexOfFirst { it.first == focusedSection }
                    val next = adjacentTvSection(current, sections.size, down) ?: return@onPreviewKeyEvent true
                    if(event.type == KeyEventType.KeyDown && navigationJob[0]?.isActive != true) {
                        navigationJob[0] = scope.launch {
                            val (key, target) = sections[next]
                            val horizontal = when(key) {
                                "season" -> seasonRowState
                                "cast" -> castRowState
                                "related" -> relatedRowState
                                else -> if(key.startsWith("episodes-")) episodeRowState else null
                            }
                            horizontal?.requestScrollToItem(0)
                            stageKey = stageOf(key)
                            awaitTvFocus { target }
                        }
                    }
                    true
                }) {
                    AnimatedContent(targetState = activeKey, modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val forward = stageKeys.indexOf(targetState) > stageKeys.indexOf(initialState)
                            (slideInVertically(tween(280)) { if(forward) it / 2 else -it / 4 } + fadeIn(tween(240))) togetherWith
                                (slideOutVertically(tween(220)) { if(forward) -it / 4 else it / 2 } + fadeOut(tween(160)))
                        }, label = "Details stage") { key ->
                        when(key) {
                            "hero" -> Box(Modifier.fillMaxSize().onFocusChanged { if(it.hasFocus) focusedSection = "hero" }.focusGroup()
                                .padding(horizontal = 32.dp, vertical = 28.dp)) {
                        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DetailsControl(playLabel,
                                        Modifier.width(210.dp).height(52.dp).focusRequester(playFocus),
                                        true, enabled = !film.series || resume != null) {
                                        if(film.series) resume?.let { vm.play(it) } else vm.play(film)
                                    }
                                    DetailsControl("عرض التريلر", Modifier.size(52.dp).focusRequester(trailerFocus),
                                        false, icon = TrailerIcon) { showTrailer = true }
                                    DetailsControl(if(saved) "إزالة من قائمتي" else "إضافة إلى قائمتي",
                                        Modifier.size(52.dp), false,
                                        icon = if(saved) Icons.Rounded.Check else Icons.Rounded.Add) { vm.toggle(film) }
                                }
                                if(vm.detailBusy) Label("جارٍ تحميل التفاصيل والحلقات…", 12, Muted)
                                vm.detailError?.let {
                                    Label(it, 12, Muted, lines = 2)
                                    TvActionButton("إعادة المحاولة") { vm.open(film) }
                                }
                            }
                            Column(Modifier.width(infoWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(seasonYear ?: film.year, if(film.series && vm.seasons.isNotEmpty()) "${vm.seasons.size} مواسم" else "",
                                        formatContentDuration(film.duration), ageRating.orEmpty(), film.contentQuality,
                                        film.edition, displayQuality.orEmpty()).filter { it.isNotBlank() }.distinct().forEach {
                                        Label(it, 14, Color(0xFFAAAAAA), true)
                                    }
                                    if(film.imdbRating.isNotBlank()) {
                                        Label("IMDb ${film.imdbRating}", 14, Color(0xFFF5C518), true)
                                    } else RatingChip(film.rating, size = 14)
                                }
                                Label(film.description.ifBlank {
                                    if(vm.detailBusy) "جارٍ تحميل التفاصيل…" else "لا يتوفر وصف لهذا العمل."
                                }, 15, Color.White.copy(alpha = .92f), lines = 3)
                            }
                        }
                        if(sections.size > 1) Label("↓ المواسم والحلقات وطاقم العمل", 12, Muted,
                            modifier = Modifier.align(Alignment.BottomCenter))
                            }
                            "episodes" -> Column(Modifier.fillMaxSize().padding(bottom = 28.dp), verticalArrangement = Arrangement.Bottom) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailsHorizontalRow(state = seasonRowState, modifier = Modifier.onFocusChanged { if(it.hasFocus) focusedSection = "season" }.focusGroup(),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(vm.seasons, key = { _, value -> value.number }) { index, value ->
                                DetailsControl("الموسم ${value.number}",
                                    Modifier.then(if(index == 0) Modifier.focusRequester(seasonFocus) else Modifier)
                                        .focusProperties { up = playFocus; if(episodes.isNotEmpty()) down = episodeFocus },
                                    primary = false, selected = index == vm.seasonIndex) {
                                    vm.seasonIndex = index
                                    scope.launch {
                                        withFrameNanos { }
                                        if(value.episodes.isNotEmpty()) { episodeRowState.requestScrollToItem(0); awaitTvFocus { episodeFocus } }
                                    }
                                }
                            }
                        }
                                    if(!season?.description.isNullOrBlank()) Label(season!!.description, 14, Muted, modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 650.dp), lines = 2)
                        DetailsRail(episodes.map { it.copy(seriesId = film.id, season = season?.number ?: it.season) },
                            episodeFocus, seasonFocus, revision, vm, episodes = true, rowState = episodeRowState,
                            seriesTitles = setOf(film.title, film.enTitle, film.originalTitle),
                            modifier = Modifier.onFocusChanged { if(it.hasFocus) focusedSection = "episodes-${season?.number}" }) {
                            vm.play(it.copy(seriesId = film.id, season = season?.number ?: it.season))
                        }
                        if(episodes.isEmpty() && !vm.detailBusy) Label("لا توجد حلقات متاحة لهذا الموسم.", 14, Muted,
                            modifier = Modifier.padding(horizontal = 24.dp))
                                }
                            }
                            "cast" -> Column(Modifier.fillMaxSize().padding(bottom = 28.dp), verticalArrangement = Arrangement.Bottom) {
                              credits?.let { credits ->
                        Column(Modifier.padding(horizontal = 24.dp).then(if(!hasCast) Modifier.focusRequester(infoFocus)
                            .onFocusChanged { if(it.isFocused) focusedSection = "credits-info" }.focusable() else Modifier), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Label("طاقم العمل", 22, Color.White, true)
                            val directorText = if(credits.directors.isNotEmpty()) "المخرج: " + credits.directors.joinToString("، ")
                                else if(credits.creators.isNotEmpty()) "صنّاع العمل: " + credits.creators.joinToString("، ") else ""
                            val year = seasonYear ?: film.year.ifBlank { credits.year }
                            Label(listOf(directorText, if(year.isNotBlank()) "سنة الإنتاج: $year" else "")
                                .filter { it.isNotBlank() }.joinToString("   •   "), 14, Muted, lines = 2)
                        }
                        DetailsHorizontalRow(state = castRowState, modifier = Modifier.onFocusChanged { if(it.hasFocus) focusedSection = "cast" }.focusGroup(),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            itemsIndexed(credits.cast, key = { _, actor -> actor.id }) { index, actor ->
                                var focused by remember { mutableStateOf(false) }
                                Column(Modifier.width(112.dp).then(if(index == 0) Modifier.focusRequester(castFocus) else Modifier)
                                    .focusProperties { up = if(episodes.isNotEmpty()) episodeFocus else playFocus
                                        if(related.isNotEmpty()) down = relatedFocus }
                                    .onFocusChanged { focused = it.isFocused }.focusable(),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.size(84.dp).clip(CircleShape).background(Panel)
                                        .border(if(focused) 2.dp else 1.dp, if(focused) Color.White else Color.White.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Person, null, Modifier.size(32.dp), Muted)
                                        if(actor.image.isNotBlank()) AsyncImage(actor.image, actor.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                    Label(actor.name, 13, if(focused) Color.White else Muted, bold = focused, lines = 2)
                                }
                            }
                        }
                              }
                            }
                            else -> Column(Modifier.fillMaxSize().padding(bottom = 28.dp), verticalArrangement = Arrangement.Bottom) {
                                Label("أعمال مشابهة", 22, Color.White, true, Modifier.padding(horizontal = 24.dp))
                        DetailsRail(related, relatedFocus, if(hasCast) castFocus else if(episodes.isNotEmpty()) episodeFocus else playFocus, revision, vm,
                            episodes = false, rowState = relatedRowState, modifier = Modifier.onFocusChanged { if(it.hasFocus) focusedSection = "related" }, onClick = vm::open)
                            }
                        }
                    }
                }
            }
        }
        if(showTrailer) TrailerOverlay(film) {
            showTrailer = false; scope.launch { delay(32); trailerFocus.requestFocus() }
        }
    }
    // Background updates must not move focus away from a selected episode or season.
    LaunchedEffect(film.id, film.series && episodes.isNotEmpty()) {
        if(!initialFocusAssigned) {
            delay(32)
            if(!film.series || episodes.isNotEmpty()) {
                initialFocusAssigned = runCatching { playFocus.requestFocus() }.getOrDefault(false)
            } else {
                initialFocusAssigned = runCatching { trailerFocus.requestFocus() }.getOrDefault(false)
            }
        }
    }
}

/** Horizontal focus scrolling is independent of the explicitly controlled vertical page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsHorizontalRow(
    state: LazyListState,
    modifier: Modifier,
    contentPadding: PaddingValues,
    horizontalArrangement: Arrangement.Horizontal,
    content: LazyListScope.() -> Unit
) {
    val centered = remember { object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            offset - (containerSize - size) / 2f
    } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides centered) {
        LazyRow(state = state, modifier = modifier, contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement, content = content)
    }
}

@Composable
private fun DetailsControl(text: String, modifier: Modifier, primary: Boolean, enabled: Boolean = true, icon: ImageVector? = null, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.05f else 1f,
        spring(stiffness = Spring.StiffnessLow), label = "Details action focus")
    val shape = RoundedCornerShape(50)
    Row(modifier.zIndex(if(focused) 10f else 0f).onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if(enabled) 1f else .5f }
        .then(if(focused) Modifier.focusGlow(shape) else Modifier)
        .clip(shape).background(if(primary || focused) Color.White else Color.White.copy(alpha = if(selected) .22f else .08f))
        .border(if(focused) 3.dp else 1.dp, if(focused) Accent else Color.White.copy(alpha = .12f), shape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = if(icon == null) 16.dp else 0.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if(primary) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(24.dp), Color.Black); Spacer(Modifier.width(8.dp)) }
        if(icon != null) Icon(icon, text, Modifier.size(24.dp), if(focused) Color.Black else Color.White)
        else Label(text, if(primary) 16 else 18, if(primary || focused) Color.Black else Color.White, true, lines = 1)
    }
}

@Composable
private fun DetailsRail(items: List<Film>, first: FocusRequester, upper: FocusRequester, revision: Int,
    vm: CinemaViewModel, episodes: Boolean, rowState: LazyListState, seriesTitles: Set<String> = emptySet(), modifier: Modifier = Modifier, onClick: (Film) -> Unit) {
    DetailsHorizontalRow(state = rowState, modifier = modifier.focusGroup(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            var episodeDetails by remember(item.id) { mutableStateOf(item) }
            LaunchedEffect(item.id, episodes) {
                if(episodes) episodeDetails = vm.episodeCardDetails(item)
            }
            val card = if(episodes) episodeDetails.copy(
                episodeTitle = item.episodeTitle.ifBlank { episodeDetails.episodeTitle },
                description = item.description.ifBlank { episodeDetails.description },
                backdrop = item.backdrop, duration = item.duration.ifBlank { episodeDetails.duration },
                tmdbRating = item.tmdbRating, airDate = item.airDate) else item
            var focused by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if(focused) 1.08f else 1f,
                spring(stiffness = Spring.StiffnessLow), label = "Details card focus")
            val shape = RoundedCornerShape(12.dp)
            val complete = remember(revision, item.id) { vm.library.isCompleted(item.id) }
            Box(Modifier.width(240.dp).aspectRatio(16f / 9f).zIndex(if(focused) 10f else 0f)
                .then(if(index == 0) Modifier.focusRequester(first) else Modifier)
                .focusProperties { up = upper }
                .onFocusChanged { focused = it.isFocused }.graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if(focused) Modifier.focusGlow(shape) else Modifier)
                .clip(shape).background(Panel).border(if(focused) 3.dp else 1.dp,
                    if(focused) Accent else Color.White.copy(alpha = .2f), shape).clickable { onClick(item) }) {
                AsyncImage(vm.withArtwork(card).let { it.backdrop.ifBlank { it.poster } }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f), Color.Black))))
                if(complete) Icon(Icons.Rounded.Check, "تمت المشاهدة", Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp), Color.White)
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if(episodes) Label("S${item.season}E${item.episode}", 9, Color.White, true)
                    // Season/episode numbering stays visible even when a genuine title is available.
                    if(episodes) {
                        val title = com.cinemaplus.rebuilt.data.EpisodeLabels.title(card.episodeTitle.ifBlank { card.title }, seriesTitles)
                        if(title.isNotBlank()) Label(title, 15, Color.White, true, lines = 1)
                    } else Label(mediaTitle(item), 15, Color.White, true, lines = 1)
                    if(episodes || card.description.isNotBlank()) Label(card.description.ifBlank { "لا يتوفر وصف لهذه الحلقة." }, 10, Color.White.copy(alpha = .8f), lines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(formatContentDuration(card.duration), 11, Color(0xFFAAAAAA))
                        if(episodes && card.imdbRating.isNotBlank()) {
                            Label("IMDb", 10, Color(0xFFF5C518), true)
                            Label(card.imdbRating, 11, Color.White, true)
                        } else if(episodes && card.tmdbRating.isNotBlank()) {
                            Label("TMDB", 10, Color(0xFF90CEA1), true)
                            Label(card.tmdbRating, 11, Color.White, true)
                        } else if(!episodes) RatingChip(card.rating, size = 11)
                    }
                }
            }
        }
    }
}

/**
 * Full-screen trailer for the details page. Opened by an explicit action, so it plays with sound
 * regardless of the muted-previews setting. Walks the candidate list when a video refuses to embed.
 * Back closes it; other remote keys are swallowed so the page underneath is not navigated.
 */
@Composable
private fun TrailerOverlay(film: Film, onClose: () -> Unit) {
    var ids by remember(film.id) { mutableStateOf<List<String>?>(null) }
    var index by remember(film.id) { mutableIntStateOf(0) }
    var playing by remember(film.id) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(film.id) { ids = TrailerResolver.candidates(film) }
    LaunchedEffect(Unit) { delay(32); runCatching { focus.requestFocus() } }
    BackHandler { onClose() }
    val current = ids?.getOrNull(index)
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
        .onPreviewKeyEvent { event -> event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK }
        .focusable()) {
        if(current != null) key(current) {
            TrailerPreview(current, Modifier.fillMaxSize(), muted = false,
                onPlaying = { playing = true; TrailerResolver.markWorking(film.id, current) },
                onFailed = { if(index + 1 < (ids?.size ?: 0)) index++ else ids = emptyList() })
        }
        if(!playing) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(ids?.isEmpty() == true) {
                Label("تعذّر تشغيل التريلر لهذا العمل.", 18, Color.White, true)
                Label("اضغط زر الرجوع للعودة.", 14, Muted)
            } else {
                CircularProgressIndicator(color = Color.White)
                Label("جارٍ تحميل التريلر…", 14, Muted)
            }
        }
    }
}

/** Soft accent halo behind a focused control; the white fill and the accent frame are drawn by the caller. */
private fun Modifier.focusGlow(shape: RoundedCornerShape): Modifier = this.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    for((spread, alpha) in listOf(8.dp to .05f, 5.dp to .09f, 3.dp to .14f)) {
        drawOutline(outline, Accent.copy(alpha = alpha), style = androidx.compose.ui.graphics.drawscope.Stroke(width = spread.toPx() * 2))
    }
}
