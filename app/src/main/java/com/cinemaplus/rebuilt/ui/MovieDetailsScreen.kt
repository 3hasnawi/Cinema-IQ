package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** TV details use RTL placement with the information and actions on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailsScreen(vm: CinemaViewModel, ageRating: String? = null, displayQuality: String? = null) {
    val film = vm.selected?.let(vm::withArtwork) ?: return
    val scope = rememberCoroutineScope()
    var initialFocusAssigned by remember(film.id) { mutableStateOf(false) }
    val backFocus = remember(film.id) { FocusRequester() }
    val playFocus = remember(film.id) { FocusRequester() }
    val seasonFocus = remember(film.id) { FocusRequester() }
    val episodeFocus = remember(film.id) { FocusRequester() }
    val trailerFocus = remember(film.id) { FocusRequester() }
    val relatedFocus = remember(film.id) { FocusRequester() }
    val season = vm.seasons.getOrNull(vm.seasonIndex)
    val episodes = season?.episodes.orEmpty()
    LaunchedEffect(film.id, season?.number) { if(season != null) vm.enrichSelectedSeason() }
    val related = vm.shelves.flatMap { it.films }.filter { it.id != film.id }.distinctBy { it.id }
    var showTrailer by remember(film.id) { mutableStateOf(false) }
    val revision = vm.libraryRevision
    val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
    BackHandler(enabled = !showTrailer) { vm.backDetail() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(ImageRequest.Builder(LocalContext.current).data(film.backdrop.ifBlank { film.poster })
                .crossfade(true).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .60f), Color.Black.copy(alpha = .92f)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .20f), Color.Black))))
            val heroHeight = (maxHeight * .60f).coerceAtLeast(350.dp)
            val infoWidth = (maxWidth * .43f).coerceIn(380.dp, 600.dp)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "hero") {
                    Column(Modifier.fillMaxWidth().heightIn(min = heroHeight).padding(start = 24.dp, top = 20.dp, end = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailsControl("›", Modifier.size(44.dp).focusRequester(backFocus), false, onClick = vm::backDetail)
                        Column(Modifier.width(infoWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Label(mediaTitle(film), 64, Color.White, true, lines = 2)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(film.year, if(film.series && vm.seasons.isNotEmpty()) "${vm.seasons.size} مواسم" else "",
                                    formatContentDuration(film.duration), ageRating.orEmpty(), film.contentQuality,
                                    film.edition, displayQuality.orEmpty()).filter { it.isNotBlank() }.distinct().forEach {
                                    Label(it, 14, Color(0xFFAAAAAA), true)
                                }
                            }
                            if(film.imdbRating.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Label("IMDb", 14, Color(0xFFF5C518), true)
                                Label(film.imdbRating, 14, Color.White, true)
                            } else RatingChip(film.rating, size = 14)
                            Label(if(film.series) "مسلسل" else "فيلم", 13, Color(0xFFAAAAAA))
                            Label(film.description.ifBlank { if(vm.detailBusy) "جارٍ تحميل التفاصيل…" else "لا يتوفر وصف لهذا العمل." },
                                15, Color.White.copy(alpha = .86f), lines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                DetailsControl(if(vm.library.progress(film.id) > 0) "استئناف" else "تشغيل",
                                    Modifier.weight(1f).height(52.dp).focusRequester(playFocus).focusProperties {
                                        if(episodes.isNotEmpty()) down = seasonFocus
                                        else if(related.isNotEmpty()) down = relatedFocus
                                    }, true, enabled = !film.series || episodes.isNotEmpty()) {
                                    if(film.series) episodes.firstOrNull()?.let { vm.play(it.copy(seriesId = film.id, season = season?.number ?: it.season)) }
                                    else vm.play(film)
                                }
                                DetailsControl("عرض التريلر", Modifier.size(52.dp).focusRequester(trailerFocus).focusProperties {
                                    if(episodes.isNotEmpty()) down = seasonFocus
                                    else if(related.isNotEmpty()) down = relatedFocus
                                }, false, icon = TrailerIcon) { showTrailer = true }
                                DetailsControl(if(saved) "إزالة من قائمتي" else "إضافة إلى قائمتي",
                                    Modifier.size(52.dp).focusProperties {
                                        if(episodes.isNotEmpty()) down = seasonFocus
                                        else if(related.isNotEmpty()) down = relatedFocus
                                    }, false, icon = if(saved) Icons.Rounded.Check else Icons.Rounded.Add) { vm.toggle(film) }
                            }
                            if(vm.detailBusy) Label("جارٍ تحميل التفاصيل والحلقات…", 12, Muted)
                            vm.detailError?.let {
                                Label(it, 12, Muted, lines = 2)
                                TvActionButton("إعادة المحاولة") { vm.open(film) }
                            }
                        }
                    }
                }
                if(film.series && vm.seasons.isNotEmpty()) {
                    item(key = "season") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(vm.seasons, key = { _, value -> value.number }) { index, value ->
                                DetailsControl("الموسم ${value.number}",
                                    Modifier.then(if(index == vm.seasonIndex) Modifier.focusRequester(seasonFocus) else Modifier)
                                        .focusProperties { up = playFocus; if(episodes.isNotEmpty()) down = episodeFocus },
                                    primary = false, selected = index == vm.seasonIndex) {
                                    vm.seasonIndex = index
                                    scope.launch { delay(80); if(value.episodes.isNotEmpty()) episodeFocus.requestFocus() }
                                }
                            }
                        }
                    }
                    if(!season?.description.isNullOrBlank()) item(key = "season-description") {
                        Label(season!!.description, 14, Muted, modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 650.dp), lines = 2)
                    }
                    item(key = "episodes-${season?.number}") {
                        DetailsRail(episodes.map { it.copy(seriesId = film.id, season = season?.number ?: it.season) },
                            episodeFocus, seasonFocus, revision, vm, episodes = true) {
                            vm.play(it.copy(seriesId = film.id, season = season?.number ?: it.season))
                        }
                        if(episodes.isEmpty() && !vm.detailBusy) Label("لا توجد حلقات متاحة لهذا الموسم.", 14, Muted,
                            modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
                if(related.isNotEmpty()) {
                    item(key = "related-title") { Label("أعمال مشابهة", 22, Color.White, true, Modifier.padding(horizontal = 24.dp)) }
                    item(key = "related") {
                        DetailsRail(related, relatedFocus, if(episodes.isNotEmpty()) episodeFocus else playFocus, revision, vm,
                            episodes = false, onClick = vm::open)
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
            } else runCatching { backFocus.requestFocus() }
        }
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
        .clip(shape).background(if(primary || focused) Color.White else Color.White.copy(alpha = if(selected) .22f else .08f))
        .border(if(focused) 2.dp else 1.dp, Color.White.copy(alpha = if(focused) 1f else .12f), shape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = if(icon == null) 16.dp else 0.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if(primary) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(24.dp), Color.Black); Spacer(Modifier.width(8.dp)) }
        if(icon != null) Icon(icon, text, Modifier.size(24.dp), if(focused) Color.Black else Color.White)
        else Label(text, if(primary) 16 else 18, if(primary || focused) Color.Black else Color.White, true, lines = 1)
    }
}

@Composable
private fun DetailsRail(items: List<Film>, first: FocusRequester, upper: FocusRequester, revision: Int,
    vm: CinemaViewModel, episodes: Boolean, onClick: (Film) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            var episodeDetails by remember(item.id) { mutableStateOf(item) }
            LaunchedEffect(item.id, episodes) {
                if(episodes) episodeDetails = vm.episodeCardDetails(item)
            }
            val card = if(episodes) episodeDetails.copy(
                description = item.description.ifBlank { episodeDetails.description },
                backdrop = item.backdrop, duration = item.duration.ifBlank { episodeDetails.duration },
                tmdbRating = item.tmdbRating, airDate = item.airDate) else item
            var focused by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if(focused) 1.08f else 1f,
                spring(stiffness = Spring.StiffnessLow), label = "Details card focus")
            val shape = RoundedCornerShape(12.dp)
            val complete = remember(revision, item.id) { vm.library.isCompleted(item.id) }
            Box(Modifier.width(330.dp).height(200.dp).zIndex(if(focused) 10f else 0f)
                .then(if(index == 0) Modifier.focusRequester(first) else Modifier).focusProperties { up = upper }
                .onFocusChanged { focused = it.isFocused }.graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape).background(Panel).border(if(focused) 2.dp else 1.dp,
                    Color.White.copy(alpha = if(focused) 1f else .2f), shape).clickable { onClick(item) }) {
                AsyncImage(vm.withArtwork(card).let { it.backdrop.ifBlank { it.poster } }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f), Color.Black))))
                if(complete) Icon(Icons.Rounded.Check, "تمت المشاهدة", Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp), Color.White)
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if(episodes) Label("S${item.season}E${item.episode}", 10, Color.White, true)
                    Label(mediaTitle(item), 17, Color.White, true, lines = 1)
                    if(episodes || card.description.isNotBlank()) Label(card.description.ifBlank { "لا يتوفر وصف لهذه الحلقة." }, 12, Color.White.copy(alpha = .8f), lines = 2)
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
