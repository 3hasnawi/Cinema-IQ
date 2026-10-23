package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.relatedFilms
import kotlinx.coroutines.delay

/** Rows keep the focused card in the middle of the screen. */
private object CenteredDetailsRow : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize - size) / 2f
}

/**
 * TV details of the redesign (03-details.png): the artwork (and, when enabled, the muted trailer) across the
 * top, the title column on the right with the resume button and round actions, then one tab per season plus
 * "similar titles" above a row of 16:9 cards. Up from the row goes to the tabs, then to the actions.
 * Back returns to the previous screen.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MovieDetailsScreen(vm: CinemaViewModel, ageRating: String? = null, displayQuality: String? = null) {
    val sourceFilm = vm.selected ?: return
    val film = vm.withArtwork(sourceFilm)
    val playFocus = remember(film.id) { FocusRequester() }
    val tabFocus = remember(film.id) { FocusRequester() }
    val rowFocus = remember(film.id) { FocusRequester() }
    val trailerFocus = remember(film.id) { FocusRequester() }
    val credits = vm.detailsCredits
    LaunchedEffect(film.id) { vm.loadDetailsCredits() }
    val season = vm.seasons.getOrNull(vm.seasonIndex)
    val episodes = season?.episodes.orEmpty()
    // A series shows the selected season's year; it falls back to the series year when TMDB gave no season date.
    val seasonYear = if (film.series) season?.airDate?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } } else null
    LaunchedEffect(film.id, season?.number) { if (season != null) vm.enrichSelectedSeason() }
    val candidates = vm.shelves.map { it.copy(films = it.films.map(vm::withArtwork)) }
    val related = relatedFilms(film, candidates)
    LaunchedEffect(film.id, vm.shelves) {
        vm.enrich(vm.shelves.flatMap { it.films }.filter { it.series == film.series && it.episode == 0 && it.id != film.id }
            .distinctBy { it.id }.take(36))
    }
    var showTrailer by remember(film.id) { mutableStateOf(false) }
    val revision = vm.libraryRevision
    val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
    // The episode Play starts: the one in progress, else the first not yet watched.
    val resume = remember(film.id, vm.seasons, revision) { if (film.series) vm.resumeEpisode(film) else null }
    val target = if (film.series) resume else film
    val started = target != null && vm.library.progress(target.id) > 0
    val playLabel = when {
        target == null -> "شاهد الآن"
        target.episode > 0 && started -> "تابع المشاهدة · م${target.season} ح${target.episode}"
        target.episode > 0 && (target.episode > 1 || target.season > 1) -> "شاهد الحلقة ${target.episode}"
        started -> "تابع المشاهدة"
        else -> "شاهد الآن"
    }
    val playProgress = remember(revision, target?.id) { target?.let { vm.library.fraction(it.id) } ?: 0f }

    // Tabs: one per season, then similar titles. The selected tab follows focus.
    val tabs = buildList {
        if (film.series) vm.seasons.forEachIndexed { index, value -> add(DetailsTab("season-$index", "الموسم ${value.number}", index)) }
        if (related.isNotEmpty()) add(DetailsTab("related", "أعمال مشابهة", -1))
    }
    var tabKey by remember(film.id) { mutableStateOf<String?>(null) }
    val activeTab = tabs.firstOrNull { it.key == tabKey } ?: tabs.firstOrNull { it.season == vm.seasonIndex } ?: tabs.firstOrNull()

    BackHandler(enabled = !showTrailer) { vm.backDetail() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(Brand.Background)) {
            DetailsArtwork(film, vm.trailerPreview && !showTrailer, Modifier.fillMaxWidth().height(720.mx))
            KeyHint("→", "رجوع", Modifier.align(Alignment.TopStart).padding(start = 96.mx, top = 48.mx))

            Column(Modifier.align(Alignment.TopStart).padding(start = 96.mx, top = 140.mx).width(860.mx),
                verticalArrangement = Arrangement.spacedBy(18.mx)) {
                Text(mediaTitle(film), color = Color.White, fontFamily = Cairo, fontWeight = FontWeight.Bold,
                    fontSize = 64.ms, lineHeight = (64 * 1.15).toInt().ms, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.mx), verticalArrangement = Arrangement.spacedBy(6.mx),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    val seasons = if (film.series && vm.seasons.isNotEmpty())
                        (if (vm.seasons.size == 1) "موسم واحد" else if (vm.seasons.size == 2) "موسمان" else "${vm.seasons.size} مواسم") else ""
                    val rating = if (film.imdbRating.isNotBlank()) "IMDb ${film.imdbRating}"
                        else film.rating.takeIf { (it.toFloatOrNull() ?: 0f) > 0f }?.let { "★ $it" }.orEmpty()
                    val facts = listOf(rating, seasonYear ?: film.year, seasons, formatContentDuration(film.duration)).filter(String::isNotBlank)
                    facts.forEachIndexed { i, fact ->
                        if (i > 0) Label("•", 13, Brand.BorderStrong)
                        Label(fact, 13, if (i == 0 && rating.isNotBlank()) Color(0xFFF5C518) else Brand.TextSecondary, i == 0 && rating.isNotBlank(), lines = 1)
                    }
                    MetaChip(ageRating.orEmpty())
                    listOf(film.contentQuality, displayQuality.orEmpty(), film.edition).filter(String::isNotBlank).distinct()
                        .forEach { MetaChip(it, glass = true) }
                }
                Text(film.description.ifBlank { if (vm.detailBusy) "جارٍ تحميل التفاصيل…" else "لا يتوفر وصف لهذا العمل." },
                    color = Brand.TextSecondary, fontFamily = Cairo, fontSize = 24.ms, lineHeight = (24 * 1.6).toInt().ms,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 780.mx))
                val castLine = when {
                    !credits?.cast.isNullOrEmpty() -> "بطولة: " + credits!!.cast.take(4).joinToString("، ") { it.name }
                    !credits?.directors.isNullOrEmpty() -> "إخراج: " + credits!!.directors.joinToString("، ")
                    !credits?.creators.isNullOrEmpty() -> "صنّاع العمل: " + credits!!.creators.joinToString("، ")
                    else -> ""
                }
                if (castLine.isNotBlank()) Label(castLine, 12, Brand.TextTertiary, lines = 1)
                vm.detailError?.let { Label(it, 12, Brand.Danger, lines = 2) }
                Row(Modifier.padding(top = 14.mx).focusProperties { if (tabs.isNotEmpty()) down = tabFocus },
                    horizontalArrangement = Arrangement.spacedBy(20.mx), verticalAlignment = Alignment.CenterVertically) {
                    ResumeButton(playLabel, playProgress, enabled = target != null, modifier = Modifier.focusRequester(playFocus)) {
                        when {
                            vm.detailError != null && target == null -> vm.open(film)
                            target != null -> vm.play(target)
                        }
                    }
                    CircleButton(if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                        if (saved) "إزالة من قائمتي" else "أضف إلى قائمتي") { vm.toggle(film) }
                    CircleButton(TrailerIcon, "البرومو", Modifier.focusRequester(trailerFocus)) { showTrailer = true }
                }
            }

            if (tabs.isNotEmpty()) Column(Modifier.align(Alignment.TopStart).padding(top = 760.mx).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(22.mx)) {
                Row(Modifier.padding(start = 96.mx).focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.mx),
                    verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { index, tab ->
                        if (tab.season < 0 && index > 0) Box(Modifier.padding(horizontal = 8.mx).size(1.dp, 28.mx).background(Color.White.copy(alpha = .15f)))
                        DetailsTabPill(tab.label, tab == activeTab,
                            Modifier.then(if (tab == activeTab) Modifier.focusRequester(tabFocus) else Modifier)
                                .focusProperties { up = playFocus; down = rowFocus }) {
                            tabKey = tab.key
                            if (tab.season >= 0 && vm.seasonIndex != tab.season) vm.seasonIndex = tab.season
                        }
                    }
                }
                val rowState = rememberLazyListState()
                val showRelated = activeTab?.season == -1
                val items = if (showRelated) related else episodes.map { it.copy(seriesId = film.id, season = season?.number ?: it.season) }
                val startIndex = if (!showRelated) items.indexOfFirst { it.id == resume?.id }.coerceAtLeast(0) else 0
                LaunchedEffect(activeTab?.key, items.size) { if (items.isNotEmpty()) rowState.scrollToItem(startIndex) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides CenteredDetailsRow) {
                    LazyRow(state = rowState, contentPadding = PaddingValues(start = 96.mx, end = 58.mx, top = 10.mx, bottom = 10.mx),
                        horizontalArrangement = Arrangement.spacedBy(28.mx), modifier = Modifier.fillMaxWidth().focusGroup()) {
                        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                            val cardModifier = Modifier.then(if (index == startIndex) Modifier.focusRequester(rowFocus) else Modifier)
                                .focusProperties { up = tabFocus }
                            if (showRelated) {
                                FilmCard(item, vm.library.fraction(item.id), wide = true, width = 360.mx, modifier = cardModifier,
                                    titleOnly = true) { vm.open(item) }
                            } else {
                                EpisodeCard(item, vm, revision, setOf(film.title, film.enTitle, film.originalTitle), cardModifier) { vm.play(item) }
                            }
                        }
                    }
                }
                if (!showRelated && episodes.isEmpty() && !vm.detailBusy)
                    Label("لا توجد حلقات متاحة لهذا الموسم.", 13, Brand.TextTertiary, modifier = Modifier.padding(start = 96.mx))
            }
        }
        if (showTrailer) TrailerOverlay(film) {
            showTrailer = false
        }
    }
    // Initial focus: the resume button (the trailer button while no episode is playable); after the trailer, back to its button.
    var trailerSeen by remember(film.id) { mutableStateOf(false) }
    LaunchedEffect(film.id, showTrailer, target != null) {
        if (showTrailer) { trailerSeen = true; return@LaunchedEffect }
        awaitTvFocus { if (trailerSeen || target == null) trailerFocus else playFocus }
    }
}

private data class DetailsTab(val key: String, val label: String, val season: Int)

/** Arrow keycap with its meaning, as in the mockups. */
@Composable
internal fun KeyHint(key: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mx)) {
        Box(Modifier.border(1.dp, Brand.BorderStrong, RoundedCornerShape(8.mx)).padding(horizontal = 12.mx, vertical = 2.mx)) {
            Label(key, 12, Color.White, lines = 1)
        }
        Label(text, 12, Brand.TextTertiary, lines = 1)
    }
}

/** Artwork with the brief's scrims; when trailer previews are on, the muted trailer replaces it after 3 s. */
@Composable
private fun DetailsArtwork(film: Film, trailerAllowed: Boolean, modifier: Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var ids by remember(film.id) { mutableStateOf<List<String>>(emptyList()) }
    var index by remember(film.id) { mutableIntStateOf(0) }
    var playing by remember(film.id) { mutableStateOf(false) }
    val artAlpha by animateFloatAsState(if (playing) 0f else 1f, tween(700), label = "Details trailer fade")
    LaunchedEffect(film.id, trailerAllowed) {
        playing = false
        if (trailerAllowed) { delay(3_000); ids = TrailerResolver.candidates(film) } else ids = emptyList()
    }
    Box(modifier) {
        val id = ids.getOrNull(index)
        if (trailerAllowed && id != null) key(id) {
            TrailerPreview(id, Modifier.fillMaxSize(), onPlaying = { playing = true; TrailerResolver.markWorking(film.id, id) },
                onFailed = { if (index + 1 < ids.size) index++ else ids = emptyList() })
        }
        val image = film.backdrop.ifBlank { film.poster }
        if (image.isNotBlank()) AsyncImage(ImageRequest.Builder(LocalContext.current).data(image).crossfade(true).build(), null,
            Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha }, contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Brand.Raised))
        Box(Modifier.fillMaxSize().drawWithCache {
            val bg = Brand.Background
            val stops = arrayOf(0f to bg, .30f to bg.copy(alpha = .92f), .62f to bg.copy(alpha = .30f), .82f to Color.Transparent, 1f to Color.Transparent)
            val side = Brush.horizontalGradient(*(if (rtl) stops.map { (1f - it.first) to it.second }.reversed().toTypedArray() else stops))
            val bottom = Brush.verticalGradient(0f to Color.Transparent, (420f / 720f) to Color.Transparent, 1f to bg)
            onDrawBehind { drawRect(side); drawRect(bottom) }
        })
        if (playing && LocalTrailerMuted.current) Row(Modifier.align(Alignment.TopEnd).padding(top = 48.mx, end = 58.mx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mx)) {
            Label("الصوت مكتوم", 11, Brand.TextTertiary, lines = 1)
        }
    }
}

/** White rounded rectangle with the resume label and a 6 px progress strip along its bottom. */
@Composable
private fun ResumeButton(text: String, progress: Float, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(140), label = "Resume focus")
    val radius = 16.mx
    Box(modifier.zIndex(if (focused) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .5f }
        .focusRingBehind(focused, radius)
        .height(76.mx).widthIn(min = 340.mx).clip(RoundedCornerShape(radius)).background(Color.White)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 36.mx), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.mx)) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(26.mx), tint = Brand.OnPrimary)
            Label(text, 15, Brand.OnPrimary, true, lines = 1)
        }
        if (progress > 0f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.mx).drawBehind {
            drawRect(Brand.TextSecondary)
            val fill = size.width * progress.coerceIn(0f, 1f)
            drawRect(Brand.Accent, Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - fill else 0f, 0f), Size(fill, size.height))
        })
    }
}

/** Season / "similar" tab: 14% white when selected, white with dark text when focused. Selection follows focus. */
@Composable
private fun DetailsTabPill(text: String, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.mx)
    Box(modifier.height(48.mx).clip(shape)
        .background(when { focused -> Color.White; selected -> Color.White.copy(alpha = .14f); else -> Color.Transparent })
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clickable(remember { MutableInteractionSource() }, null) { onFocus() }
        .padding(horizontal = 24.mx), contentAlignment = Alignment.Center) {
        Label(text, 13, when { focused -> Brand.Background; selected -> Color.White; else -> Brand.TextTertiary }, focused || selected, lines = 1)
    }
}

/** 360×203 episode card: duration chip in the corner, progress strip, "episode N" and its name below. */
@Composable
private fun EpisodeCard(item: Film, vm: CinemaViewModel, revision: Int, seriesTitles: Set<String>, modifier: Modifier, onClick: () -> Unit) {
    var details by remember(item.id) { mutableStateOf(item) }
    LaunchedEffect(item.id) { details = vm.episodeCardDetails(item) }
    val focusAnim = remember(item.id) { Animatable(0f) }
    var focused by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(focused) { focusAnim.animateTo(if (focused) 1f else 0f, tween(140, easing = FastOutSlowInEasing)) }
    val progress = remember(revision, item.id) { vm.library.fraction(item.id) }
    val complete = remember(revision, item.id) { vm.library.isCompleted(item.id) }
    val image = vm.withArtwork(details).let { it.backdrop.ifBlank { it.poster } }.ifBlank { item.backdrop.ifBlank { item.poster } }
    val name = com.cinemaplus.rebuilt.data.EpisodeLabels.title(item.episodeTitle.ifBlank { details.episodeTitle.ifBlank { details.title } }, seriesTitles)
    val duration = formatContentDuration(item.duration.ifBlank { details.duration })
    val radius = TvMetrics.CardRadius
    Column(Modifier.width(360.mx), verticalArrangement = Arrangement.spacedBy(10.mx)) {
        Box(modifier.fillMaxWidth().height(203.mx)
            .graphicsLayer { val s = 1f + .06f * focusAnim.value; scaleX = s; scaleY = s }
            .drawWithContent {
                drawContent()
                val p = focusAnim.value
                if (p > .01f) {
                    val stroke = 5.mx.toPx(); val outset = 3.mx.toPx() + stroke / 2
                    drawRoundRect(Color.White.copy(alpha = p), Offset(-outset, -outset), Size(size.width + outset * 2, size.height + outset * 2),
                        CornerRadius(radius.toPx() + outset), style = Stroke(stroke))
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(radius)).background(Brand.Raised)) {
                if (image.isNotBlank()) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (complete) Icon(Icons.Rounded.CheckCircle, "تمت المشاهدة", Modifier.align(Alignment.TopStart).padding(12.mx).size(28.mx), tint = Color.White)
                if (duration.isNotBlank()) Box(Modifier.align(Alignment.BottomEnd).padding(12.mx)
                    .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.mx)).padding(horizontal = 8.mx)) {
                    Label(duration, 9, Color.White, lines = 1)
                }
                if (progress > 0f && !complete) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.mx).drawBehind {
                    drawRect(Color.White.copy(alpha = .2f))
                    val fill = size.width * progress.coerceIn(0f, 1f)
                    drawRect(lerp(Brand.TextSecondary, Brand.Accent, focusAnim.value),
                        Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - fill else 0f, 0f), Size(fill, size.height))
                })
            }
        }
        Column(Modifier.graphicsLayer { alpha = .7f + .3f * focusAnim.value }) {
            Label("الحلقة ${item.episode}", 13, Color.White, true, lines = 1)
            if (name.isNotBlank()) Label(name, 11, Brand.TextTertiary, lines = 1)
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

