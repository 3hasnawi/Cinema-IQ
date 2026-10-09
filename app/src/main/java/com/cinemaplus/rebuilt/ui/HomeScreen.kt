package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults

/*
 * Home. Performance rules that keep D-pad navigation smooth on weak devices:
 *  - the hero rotation index is a State object read only inside the hero composables, so a swap never
 *    recomposes the shelves;
 *  - the next backdrop is prefetched at the exact size the screen requests, so a swap never waits on I/O;
 *  - rotation pauses while the list scrolls or the user is below the hero;
 *  - the remembered card of the first shelf is a plain holder (no state), so focus moves recompose nothing.
 */

/** Backdrops are requested at one fixed size so the prefetched bitmap is the one the screen shows. */
private const val HeroImageWidth = 1280
private const val HeroImageHeight = 720
private const val HeroRotationMillis = 8_000L

private fun heroRequest(context: android.content.Context, url: String): ImageRequest =
    ImageRequest.Builder(context).data(url).size(HeroImageWidth, HeroImageHeight).scale(coil.size.Scale.FIT).crossfade(false).build()

private fun Film.heroArt(): String = backdrop.ifBlank { poster }

/** Not observable on purpose: remembering a card never triggers composition. Saved so it survives opening a title. */
private class StageMemory(var row: Int = 0, val cards: MutableMap<String, Int> = mutableMapOf())
private val StageMemorySaver = listSaver<StageMemory, String>(
    save = { memory -> listOf(memory.row.toString()) + memory.cards.map { "${it.key}|${it.value}" } },
    restore = { saved -> StageMemory(saved[0].toInt(), saved.drop(1).mapNotNull { entry ->
        entry.substringAfterLast('|').toIntOrNull()?.let { entry.substringBeforeLast('|') to it } }.toMap().toMutableMap()) })

/** What the fixed stage shows. Written only by the debounce below, read only by the stage layers. */
@Stable
private class StageState { var film by mutableStateOf<Film?>(null) }

/** Small cards keep the stage clean; the artwork of the title in focus carries the page. */
private val StripPosterWidth = 112.dp
private val StripWideWidth = 208.dp
private const val FeaturedId = "featured"
private const val AutoAdvanceMillis = 7_000L

/**
 * Home is one fixed stage: the artwork and details of the title in focus fill the screen, and the shelves come
 * up from the bottom one at a time. The first shelf holds the featured titles and its cursor moves along by
 * itself until the viewer takes over. Down brings the next shelf, Up the previous one.
 */
@Composable
fun HomeScreen(vm: CinemaViewModel) {
    val revision = vm.libraryRevision
    // Parsing the saved list on every recomposition made focus moves stutter; recompute only when it changes.
    val recent = remember(revision, vm.demo) { vm.library.recent().filter { it.id.startsWith("demo-") == vm.demo } }
    val featured = remember(vm.shelves) { featuredFilms(vm.shelves) }
    val rows = remember(vm.shelves, recent, featured) {
        (if (featured.isNotEmpty()) listOf(Shelf(FeaturedId, "الأعمال المميزة", featured)) else emptyList()) +
            (if (recent.isNotEmpty()) listOf(Shelf("recent", "تابع المشاهدة", recent)) else emptyList()) +
            vm.shelves.filter { it.films.isNotEmpty() }
    }
    val memory = rememberSaveable(saver = StageMemorySaver) { StageMemory() }
    // The index of the shelf on screen.
    val rowIndex = remember { mutableIntStateOf(memory.row.coerceIn(0, (rows.size - 1).coerceAtLeast(0))) }
    val focused = remember { mutableStateOf<Film?>(null) }
    val stage = remember { StageState() }
    val entryRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val entryIndexes = remember { mutableMapOf<String, MutableIntState>() }
    val rowStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val navigationJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val lastInput = remember { longArrayOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val tabBar = LocalTabBarState.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val context = LocalContext.current
    LaunchedEffect(featured) { vm.enrich(featured) }
    // Before any card has focus the stage already shows the first title.
    LaunchedEffect(rows) { if (focused.value == null) focused.value = rows.getOrNull(rowIndex.intValue)?.films?.firstOrNull() }
    // Choose what the stage shows. Moving fast along a shelf waits for the viewer to settle, so artwork is not decoded per key press.
    LaunchedEffect(Unit) {
        snapshotFlow { focused.value }.collectLatest { target ->
            if (stage.film != null) delay(140)
            stage.film = target ?: stage.film
        }
    }
    fun entryIndex(shelf: Shelf) = entryIndexes.getOrPut(shelf.id) {
        mutableIntStateOf((memory.cards[shelf.id] ?: 0).coerceIn(0, shelf.films.lastIndex))
    }
    // Back from a title, or a return to this page, puts focus where the viewer was.
    LaunchedEffect(rows.firstOrNull()?.id) {
        if (!tabBar.hasFocus) awaitTvFocus { rows.getOrNull(rowIndex.intValue)?.let { entryRequesters[it.id] } }
    }
    // The list can change under the viewer (a shelf appears or goes). Stay on the same shelf, or the nearest one.
    val shownShelf = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(rows) {
        val current = rowIndex.intValue
        val id = shownShelf[0] ?: rows.getOrNull(current)?.id
        val moved = rows.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: current.coerceAtMost((rows.size - 1).coerceAtLeast(0))
        shownShelf[0] = rows.getOrNull(moved)?.id
        if (moved != current) {
            rowIndex.intValue = moved; memory.row = moved
            rows.getOrNull(moved)?.let { entryIndex(it) }
            awaitTvFocus { rows.getOrNull(moved)?.let { entryRequesters[it.id] } }
        }
    }
    // The featured cursor walks along by itself while the viewer is idle on that shelf, and starts over at the end.
    LaunchedEffect(rows.firstOrNull()?.id) {
        while (true) {
            delay(1_000)
            val first = rows.firstOrNull()
            if (first?.id != FeaturedId || rowIndex.intValue != 0 || tabBar.hasFocus) continue
            if (System.currentTimeMillis() - lastInput[0] < AutoAdvanceMillis) continue
            val at = memory.cards[FeaturedId] ?: 0
            first.films.getOrNull(at + 1)?.let { upcoming ->
                val art = vm.withArtwork(upcoming).heroArt()
                if (art.isNotBlank()) context.imageLoader.enqueue(heroRequest(context, art))
            }
            if (at >= first.films.lastIndex) {
                entryIndex(first).intValue = 0
                rowStates[FeaturedId]?.scrollToItem(0)
                awaitTvFocus { entryRequesters[FeaturedId] }
            } else focusManager.moveFocus(FocusDirection.Left)
        }
    }
    fun go(destination: Int) {
        if (navigationJob[0]?.isActive == true) return
        navigationJob[0] = scope.launch {
            rowIndex.intValue = destination; memory.row = destination
            shownShelf[0] = rows.getOrNull(destination)?.id
            rows.getOrNull(destination)?.let { entryIndex(it).intValue = (memory.cards[it.id] ?: 0).coerceIn(0, it.films.lastIndex) }
            awaitTvFocus { rows.getOrNull(destination)?.let { entryRequesters[it.id] } }
        }
    }
    Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { event ->
        lastInput[0] = System.currentTimeMillis()
        val down = event.key == Key.DirectionDown
        if (!down && event.key != Key.DirectionUp) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) {
            val target = rowIndex.intValue + if (down) 1 else -1
            if (target in rows.indices) go(target)
        }
        // Vertical keys never fall through to the focus search: the stage decides what Up and Down mean.
        rows.isNotEmpty()
    }) {
        StageBackdrop(vm, stage, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            StageInfo(vm, stage, Modifier.fillMaxWidth()
                .padding(start = TvMetrics.EdgeInset, end = TvMetrics.EdgeInset, bottom = 4.dp))
            ShelfStage(rows, rowIndex, vm, entryRequesters, ::entryIndex, rowStates, memory, focused)
        }
        if (vm.busy && featured.isEmpty() && rows.isEmpty()) Box(Modifier.align(Alignment.BottomStart)) { ShelfSkeleton() }
        vm.error?.let { error ->
            Column(Modifier.align(Alignment.Center).padding(horizontal = TvMetrics.EdgeInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(error, 14, Muted, lines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action("إعادة المحاولة", true) { vm.reload() }
                    Action("تجربة التصميم") { vm.changeDemo(true) }
                }
            }
        }
        if (!vm.busy && vm.error == null && featured.isEmpty() && rows.isEmpty())
            Label("لا يوجد محتوى متاح حاليًا", 17, Muted, modifier = Modifier.align(Alignment.Center))
    }
}

/** One shelf at a time, anchored to the bottom. A new shelf rises from below; the old one drifts away. */
@Composable
private fun ShelfStage(rows: List<Shelf>, rowIndex: IntState, vm: CinemaViewModel,
    entryRequesters: MutableMap<String, FocusRequester>, entryIndex: (Shelf) -> MutableIntState,
    rowStates: MutableMap<String, androidx.compose.foundation.lazy.LazyListState>,
    memory: StageMemory, focused: MutableState<Film?>) {
    androidx.compose.animation.AnimatedContent(targetState = rowIndex.intValue,
        transitionSpec = {
            val forward = targetState > initialState
            (androidx.compose.animation.slideInVertically(tween(260)) { if (forward) it / 2 else -it / 4 } +
                androidx.compose.animation.fadeIn(tween(220))) togetherWith
                (androidx.compose.animation.slideOutVertically(tween(200)) { if (forward) -it / 4 else it / 2 } +
                    androidx.compose.animation.fadeOut(tween(150)))
        }, label = "Shelf stage") { index ->
        val shelf = rows.getOrNull(index)
        if (shelf != null) {
            val entry = entryRequesters.getOrPut(shelf.id) { FocusRequester() }
            // The card the viewer last stood on in this shelf (the first one on a first visit) takes focus on entry.
            val entryCard = entryIndex(shelf).intValue
            val state = rowStates.getOrPut(shelf.id) { androidx.compose.foundation.lazy.LazyListState(entryCard) }
            val wide = shelf.id == "recent"
            TvShelfRow(shelf, vm, wide = wide, width = if (wide) StripWideWidth else StripPosterWidth,
                suppliedRowState = state, preview = false,
                firstRequester = entry, firstRequesterIndex = entryCard,
                onFilmFocused = { film ->
                    memory.cards[shelf.id] = shelf.films.indexOfFirst { it.id == film.id }.coerceAtLeast(0)
                    focused.value = film
                },
                onCardLongClick = if (shelf.id == "recent") { film -> vm.showRecentOptions(film) } else null,
                onCardClick = { if (shelf.id == "recent") vm.play(it) else vm.open(it) })
        } else Spacer(Modifier.height(0.dp))
    }
}

/** The artwork of the title the stage is on; it fills the screen and fades between titles. */
@Composable
private fun StageBackdrop(vm: CinemaViewModel, stage: StageState, modifier: Modifier) {
    HeroBackdrop(stage.film?.let(vm::withArtwork), modifier, fadeFrom = .30f)
}

/** Details of the title the stage is on. Only this block recomposes when the title changes. */
@Composable
private fun StageInfo(vm: CinemaViewModel, stage: StageState, modifier: Modifier) {
    val hero = stage.film?.let(vm::withArtwork)
    var about by remember(hero?.id) { mutableStateOf(hero?.description.orEmpty()) }
    var quality by remember(hero?.id) { mutableStateOf(hero?.contentQuality.orEmpty()) }
    LaunchedEffect(hero?.id) {
        if (hero != null && about.isBlank()) about = runCatching { vm.describe(hero) }.getOrDefault("")
    }
    LaunchedEffect(hero?.id) {
        if (hero != null && quality.isBlank()) quality = runCatching { vm.bestQuality(hero) }.getOrDefault("")
    }
    if (hero == null) {
        if (vm.busy) HeroSkeleton(modifier)
        return
    }
    Crossfade(hero, modifier, animationSpec = tween(300), label = "Stage info") { film ->
        HeroPosterInfo(film, if (film.id == hero.id) about else film.description, if (film.id == hero.id) quality else film.contentQuality)
    }
}

/**
 * Hero titles: the most popular (top rated) interleaved with the newest, all with a real backdrop.
 * Posters are only used when the source offers too few backdrops.
 */
private fun featuredFilms(shelves: List<Shelf>): List<Film> {
    val all = shelves.flatMap { it.films }.distinctBy { it.id }
    val withBackdrop = all.filter { it.backdrop.isNotBlank() }
    val pool = if (withBackdrop.size >= 3) withBackdrop else all.filter { it.heroArt().isNotBlank() }
    val popular = pool.sortedByDescending { it.rating.toFloatOrNull() ?: 0f }.take(6)
    val newest = pool.filter { it !in popular }.sortedByDescending { it.year.toIntOrNull() ?: 0 }.take(4)
    val mixed = popular.flatMapIndexed { i, film -> listOfNotNull(film, newest.getOrNull(i)) }
    return mixed + newest.drop(popular.size)
}

/**
 * Wide artwork fills the screen. Artwork that is not wide (a poster) is shown whole at the full height of the
 * screen, centred, with a dimmed enlarged copy filling the sides, so nothing is cropped away or stretched.
 */
@Composable
private fun HeroArt(request: ImageRequest) {
    var ratio by remember(request) { mutableFloatStateOf(0f) }
    val framed = ratio in 0.1f..1.55f
    Box(Modifier.fillMaxSize().then(if (framed) Modifier.background(Ink) else Modifier)) {
        // The dimmed side fill only needs the small, already cached decode.
        if (framed) AsyncImage(request, null, Modifier.fillMaxSize().graphicsLayer { alpha = .30f }, contentScale = ContentScale.Crop)
        // A poster is shown at the full screen height, so it is decoded at a larger size than a wide backdrop.
        val sharp = remember(request, framed) {
            if (framed) request.newBuilder().size(1080, 1620).scale(coil.size.Scale.FIT).build() else request
        }
        AsyncImage(sharp, null,
            if (framed) Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(ratio)
            else Modifier.fillMaxSize(),
            contentScale = if (framed) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { state ->
                val art = state.result.drawable
                ratio = art.intrinsicWidth.toFloat() / art.intrinsicHeight.coerceAtLeast(1)
            })
    }
}

@Composable
fun HeroBackdrop(film: Film?, modifier: Modifier = Modifier, sideScrim: Boolean = true, fadeFrom: Float = .45f) {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier) {
        Crossfade(targetState = film?.heroArt().orEmpty(), animationSpec = tween(600), label = "Hero artwork") { url ->
            if (url.isNotBlank()) HeroArt(heroRequest(context, url))
        }
        // One cached draw pass for both scrims: a shade on the text side and a fade into the page below.
        Box(Modifier.fillMaxSize().drawWithCache {
            val edge = Color.Black.copy(alpha = .78f)
            val side = Brush.horizontalGradient(
                if (rtl) listOf(Color.Transparent, Color.Black.copy(alpha = .15f), edge)
                else listOf(edge, Color.Black.copy(alpha = .15f), Color.Transparent))
            val shade = if (sideScrim) 1f else 0f
            val tint = AccentDeep.copy(alpha = .55f).compositeOver(Ink.copy(alpha = .85f))
            val bottom = Brush.verticalGradient(0f to Color.Transparent, fadeFrom to Color.Transparent,
                (fadeFrom + .35f).coerceAtMost(.95f) to tint, 1f to Ink)
            onDrawBehind { if (shade > 0f) drawRect(side); drawRect(bottom) }
        })
    }
}

/** Title, badges and "open" cue on one side; rating, facts and story beside them. No frame: it floats over the artwork. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroPosterInfo(film: Film, about: String, quality: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KindBadge(film.series, size = 12)
            Label(mediaTitle(film), 38, Color.White, true, lines = 2)
            if (film.enTitle.isNotBlank() && film.enTitle != mediaTitle(film)) Label(film.enTitle, 15, Muted, lines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Only facts present in the model are shown; nothing is invented. Quality is the best the source offers.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RatingChip(film.rating, size = 13)
                InfoChip(film.year, size = 13); InfoChip(formatContentDuration(film.duration), size = 13)
                InfoChip(quality, size = 13)
            }
            if (about.isNotBlank()) Text(about, color = Color.White.copy(alpha = .88f), fontFamily = Cairo,
                fontSize = 15.sp, lineHeight = 23.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            val languages = film.subtitles.map { it.title }.distinct().take(3)
            if (languages.isNotEmpty()) Label("الترجمة: " + languages.joinToString(" · "), 12, Muted, lines = 1)
        }
    }
}

/** Grey placeholder blocks shown instead of a loading message. */
@Composable
private fun skeletonColor(): Color {
    val pulse by rememberInfiniteTransition(label = "Skeleton").animateFloat(.07f, .17f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "Skeleton alpha")
    return Color.White.copy(alpha = pulse)
}

@Composable
private fun HeroSkeleton(modifier: Modifier) {
    val block = skeletonColor()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(220.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(block))
        Box(Modifier.fillMaxWidth(.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
        Box(Modifier.fillMaxWidth(.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
        Box(Modifier.width(130.dp).height(40.dp).clip(CircleShape).background(block))
    }
}

@Composable
private fun ShelfSkeleton() {
    val block = skeletonColor()
    Row(Modifier.padding(horizontal = TvMetrics.EdgeInset, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap)) {
        repeat(4) { Box(Modifier.width(TvMetrics.ContinueWidth).aspectRatio(TvMetrics.LandscapeRatio)
            .clip(RoundedCornerShape(TvMetrics.CardRadius)).background(block)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroContentInfo(film: Film, about: String = film.description) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Only facts present in the model are shown; nothing is invented.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KindBadge(film.series, size = 12)
            RatingChip(film.rating, size = 12)
            InfoChip(film.year); InfoChip(formatContentDuration(film.duration))
        }
        Text(mediaTitle(film), color = Color.White, fontFamily = Cairo, fontWeight = FontWeight.Bold,
            fontSize = 40.sp, lineHeight = 48.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (film.enTitle.isNotBlank() && film.enTitle != mediaTitle(film))
            Label(film.enTitle, 15, Muted, lines = 1)
        if (about.isNotBlank()) Text(about, color = Color.White.copy(alpha = .86f),
            fontFamily = Cairo, fontSize = 15.sp, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val languages = film.subtitles.map { it.title }.distinct().take(3)
        if (languages.isNotEmpty()) Label("الترجمة: " + languages.joinToString(" · "), 13, Muted, lines = 1)
    }
}

@Composable
fun HeroActionButtons(film: Film, saved: Boolean, playFocus: FocusRequester, belowTarget: FocusRequester?,
    onPlay: () -> Unit, onWatchlist: () -> Unit, onDetails: () -> Unit) {
    // Down always lands on the remembered card of the first shelf instead of the nearest one.
    val down = Modifier.focusProperties { if (belowTarget != null) this.down = belowTarget }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        HeroButton(if (film.series) "الحلقات" else "شاهد الآن", Icons.Rounded.PlayArrow, true,
            Modifier.focusRequester(playFocus).then(down), onPlay)
        HeroButton(if (saved) "إزالة من قائمتي" else "إضافة إلى قائمتي", if (saved) Icons.Rounded.Check else Icons.Rounded.Add, false,
            down, onWatchlist)
        HeroButton("التفاصيل", Icons.Rounded.Info, false, down, onDetails)
    }
}

/** Primary = gold pill with label. Secondary = round glass button. Focus lifts and turns solid white or light gold. */
@Composable
internal fun HeroButton(label: String, icon: ImageVector, primary: Boolean,
    modifier: Modifier, onClick: () -> Unit, focusScale: Float = 1.08f, labelled: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium), label = "Hero button scale")
    val pill = primary || labelled
    val shape = if (pill) RoundedCornerShape(50) else CircleShape
    // No drop shadow: it is expensive to draw on weak GPUs; the colour change and scale carry the focus.
    TvButton(onClick, modifier.height(48.dp).then(if (pill) Modifier else Modifier.width(48.dp))
        .onFocusChanged { focused = it.isFocused }.zIndex(if (focused) 10f else 0f)
        .semantics { contentDescription = label }
        .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = TvButtonDefaults.shape(shape),
        scale = TvButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        colors = TvButtonDefaults.colors(
            containerColor = if (primary) Accent else Color.White.copy(alpha = .20f),
            contentColor = if (primary) OnAccent else Color.White,
            focusedContainerColor = if (primary) AccentLight else Color.White, focusedContentColor = if (primary) OnAccent else Color.Black),
        contentPadding = PaddingValues(horizontal = if (primary) 28.dp else if (labelled) 22.dp else 0.dp, vertical = 8.dp)) {
        Icon(icon, contentDescription = label, Modifier.size(if (primary) 24.dp else 22.dp))
        if (pill) { Spacer(Modifier.width(10.dp)); Label(label, 16, if (primary || focused) OnAccent else Color.White, true) }
    }
}

/** Round icon button with its label beside it (used on the details page). */
@Composable
internal fun RoundLabelButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroButton(label, icon, false, modifier.onFocusChanged { focused = it.isFocused }, onClick)
        Label(label, 14, if (focused) Color.White else Color.White.copy(alpha = .75f), focused, lines = 1)
    }
}

@Composable
fun ContentRailRow(shelf: Shelf, vm: CinemaViewModel, firstRequester: FocusRequester?,
    heroFocus: FocusRequester?, returnId: String? = null, wide: Boolean = true,
    rowState: androidx.compose.foundation.lazy.LazyListState? = null, onFocused: (Film) -> Unit) {
    TvShelfRow(shelf, vm, wide = wide || shelf.id == "recent", firstRequester = firstRequester, suppliedRowState = rowState,
        cardModifier = { Modifier.focusProperties { if (heroFocus != null) up = heroFocus } },
        onFilmFocused = onFocused,
        onCardLongClick = if (shelf.id == "recent") { film -> vm.showRecentOptions(film) } else null,
        onCardClick = { if (shelf.id == "recent") vm.play(it) else vm.open(it) })
}

/** Shared card implementation preserves progress, centered scrolling and restored focus. */
@Composable
fun LandscapeMediaCard(film: Film, progress: Float = 0f, modifier: Modifier = Modifier,
    onFocused: (Film) -> Unit = {}, onClick: () -> Unit) {
    FilmCard(film, progress, wide = true, modifier = modifier, onFocused = onFocused, click = onClick)
}
