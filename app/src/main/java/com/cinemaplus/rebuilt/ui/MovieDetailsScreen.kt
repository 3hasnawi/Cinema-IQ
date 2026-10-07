package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Movie and series details share the same presentation and retain the existing navigation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailsScreen(vm: CinemaViewModel, ageRating: String? = null, displayQuality: String? = null) {
    val film = vm.selected?.let(vm::withArtwork) ?: return
    val scope = rememberCoroutineScope()
    val playFocus = remember(film.id) { FocusRequester() }
    val seasonFocus = remember(film.id) { FocusRequester() }
    val episodeFocus = remember(film.id) { FocusRequester() }
    val relatedFocus = remember(film.id) { FocusRequester() }
    val revision = vm.libraryRevision
    val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
    val season = vm.seasons.getOrNull(vm.seasonIndex)
    val episodes = season?.episodes.orEmpty()
    val related = vm.shelves.flatMap { it.films }.filter { it.id != film.id }.distinctBy { it.id }
    var showTrailer by remember(film.id) { mutableStateOf(false) }
    // The poster page is fixed. The rows (seasons, episodes, similar titles) live in a panel that
    // opens with the remote's Down key and closes with Up or Back, so scrolling can never hide the poster.
    val more = remember(film.id) { mutableStateOf(false) }
    val hasSeasons = film.series && vm.seasons.isNotEmpty()
    val hasMore = hasSeasons || related.isNotEmpty()
    val enterTarget = if(hasSeasons) seasonFocus else relatedFocus
    BackHandler(enabled = !showTrailer && !more.value) { vm.backDetail() }
    BackHandler(enabled = !showTrailer && more.value) { more.value = false }
    LaunchedEffect(more.value) {
        delay(40)
        runCatching { if(more.value) enterTarget.requestFocus() else playFocus.requestFocus() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val panelHeight = maxHeight * .72f
        val dim = animateFloatAsState(if(more.value) .78f else 0f, tween(260), label = "Details dim")
        HeroBackdrop(film, Modifier.fillMaxSize(), sideScrim = false, fadeFrom = .40f)
        // Darkens the poster while the panel is open; the animated value is read in the draw phase only.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Ink))
        AnimatedVisibility(!more.value, Modifier.fillMaxSize(), enter = fadeIn(tween(220)), exit = fadeOut(tween(120))) {
            Box(Modifier.fillMaxSize()) {
                // Buttons at the start edge (right in Arabic), the story floats beside them, no frame.
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = TvMetrics.EdgeInset, end = TvMetrics.EdgeInset, bottom = 30.dp)
                    .onPreviewKeyEvent { e ->
                        if(hasMore && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { more.value = true; true } else false
                    },
                    horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KindBadge(film.series, size = 12)
                        Label(mediaTitle(film), 38, Color.White, true, lines = 2)
                        if(film.enTitle.isNotBlank() && film.enTitle != mediaTitle(film)) Label(film.enTitle, 15, Muted, lines = 1)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            HeroButton(if(film.series) "تشغيل الحلقات" else if(vm.library.progress(film.id) > 0) "استئناف" else "شاهد الآن",
                                Icons.Rounded.PlayArrow, true, Modifier.focusRequester(playFocus),
                                onClick = { if(film.series) episodes.firstOrNull()?.let(vm::play) else vm.play(film) }, focusScale = 1.08f)
                            HeroButton(if(saved) "في قائمتي" else "قائمتي", if(saved) Icons.Rounded.Check else Icons.Rounded.Add, false,
                                Modifier, onClick = { vm.toggle(film) }, labelled = true)
                            HeroButton("التريلر", TrailerIcon, false, Modifier, onClick = { showTrailer = true })
                        }
                        if(hasMore) Label(if(film.series) "اضغط ↓ للمواسم والحلقات" else "اضغط ↓ لأعمال مشابهة", 12, Muted, lines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RatingChip(film.rating, size = 13)
                            InfoChip(film.year, size = 13); InfoChip(formatContentDuration(film.duration), size = 13)
                            InfoChip(film.contentQuality, size = 13); InfoChip(film.edition, size = 13)
                            InfoChip(ageRating.orEmpty(), size = 13); InfoChip(displayQuality.orEmpty(), size = 13)
                        }
                        Label(film.description.ifBlank { if(vm.detailBusy) "جارٍ تحميل التفاصيل…" else "لا يتوفر وصف لهذا العمل." },
                            15, Color.White.copy(alpha = .88f), lines = 4)
                        val languages = film.subtitles.map { it.title }.distinct().take(3)
                        if(languages.isNotEmpty()) Label("الترجمة: " + languages.joinToString(" · "), 12, Muted, lines = 1)
                        if(vm.detailBusy) Label("جارٍ تحميل التفاصيل والحلقات…", 12, Muted)
                        vm.detailError?.let { error ->
                            Label(error, 12, Muted, lines = 2)
                            Action("إعادة المحاولة") { vm.open(film) }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(more.value, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 }, exit = fadeOut(tween(120))) {
            // Up on the topmost row closes the panel and returns to the buttons.
            val closeOnUp = Modifier.onPreviewKeyEvent { e ->
                if(e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { more.value = false; true } else false
            }
            LazyColumn(Modifier.fillMaxWidth().height(panelHeight),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "panel-title") {
                    Label(mediaTitle(film), 20, Color.White, true, lines = 1, modifier = Modifier.padding(horizontal = TvMetrics.EdgeInset))
                }
                if(hasSeasons) {
                    item(key = "seasons") {
                        LazyRow(modifier = closeOnUp, contentPadding = PaddingValues(horizontal = TvMetrics.EdgeInset, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(vm.seasons, key = { _, value -> value.number }) { index, value ->
                                TvActionButton("الموسم ${value.number}", primary = index == vm.seasonIndex,
                                    modifier = Modifier.then(if(index == vm.seasonIndex) Modifier.focusRequester(seasonFocus) else Modifier)
                                        .focusProperties { if(episodes.isNotEmpty()) down = episodeFocus }) {
                                    vm.seasonIndex = index
                                    // Move only after an explicit season selection, never after background data updates.
                                    scope.launch { delay(80); if(vm.seasons.getOrNull(index)?.episodes?.isNotEmpty() == true) episodeFocus.requestFocus() }
                                }
                            }
                        }
                    }
                    if(episodes.isNotEmpty()) item(key = "episodes-${season?.number}") {
                        TvShelfRow(Shelf("episodes-${film.id}-${season?.number}", "الحلقات", episodes.map { episode ->
                            episode.copy(title = "الحلقة ${episode.episode} • ${episode.title}") }), vm, wide = true, width = 210.dp,
                            firstRequester = episodeFocus, preview = false, cardModifier = { Modifier.focusProperties { up = seasonFocus } },
                            onCardClick = vm::play)
                    }
                    if(!vm.detailBusy && vm.detailError == null && episodes.isEmpty()) item {
                        Label("لا توجد حلقات متاحة لهذا الموسم.", 14, Muted, modifier = Modifier.padding(horizontal = TvMetrics.EdgeInset))
                    }
                }
                if(related.isNotEmpty()) item(key = "related") {
                    Box(if(hasSeasons) Modifier else closeOnUp) {
                        TvShelfRow(Shelf("related-${film.id}", "أعمال مشابهة", related), vm, wide = false, width = 116.dp,
                            firstRequester = relatedFocus, preview = false, cardModifier = {
                                if(hasSeasons) Modifier.focusProperties { up = if(episodes.isNotEmpty()) episodeFocus else seasonFocus }
                                else Modifier
                            })
                    }
                }
            }
        }
    }
    if(showTrailer) TrailerOverlay(film) { showTrailer = false }
    LaunchedEffect(film.id) { delay(32); playFocus.requestFocus() }
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
