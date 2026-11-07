package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.view.KeyEvent as AndroidKeyEvent
import kotlinx.coroutines.Job
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The featured carousel occupies one complete viewport. Shelves start below it and have one explicit vertical
 * scroll owner, so D-pad focus cannot reveal a row early or fight a second bring-into-view animation.
 */

/** Backdrops are requested at one fixed size so the prefetched bitmap is the one the screen shows. */
private const val HeroImageWidth = 1280
private const val HeroImageHeight = 720
/** The hero changes after this long without a key press (the brief's "~8 s", paused while the viewer acts). */
private const val HeroRotationMillis = 8_000L

private fun heroRequest(context: android.content.Context, url: String): ImageRequest =
    ImageRequest.Builder(context).data(url).size(HeroImageWidth, HeroImageHeight).scale(coil.size.Scale.FIT).crossfade(false).build()

private fun Film.heroArt(): String = backdrop.ifBlank { poster }

@Composable
fun HomeScreen(vm: CinemaViewModel) {
    val revision = vm.libraryRevision
    val recent = remember(revision, vm.demo) { vm.library.recent().filter { it.id.startsWith("demo-") == vm.demo } }
    val featured = remember(vm.shelves) { featuredFilms(vm.shelves) }
    val shelves = remember(vm.shelves) { vm.shelves.filter { it.films.isNotEmpty() } }
    val rows = remember(recent, shelves) {
        (if (recent.isNotEmpty()) listOf(Shelf("recent", "متابعة المشاهدة", recent)) else emptyList()) + shelves
    }
    val rowIDs = rows.map { it.id }
    val rowFocus = remember(rowIDs) { rowIDs.associateWith { FocusRequester() } }
    val rowStates = rows.map { shelf -> key(shelf.id) { rememberLazyListState() } }
    val rowOffsets = remember { mutableMapOf<String, Int>() }
    val heroIndex = rememberSaveable { mutableIntStateOf(0) }
    val lastInput = remember { longArrayOf(android.os.SystemClock.uptimeMillis()) }
    val selectPress = remember { TvHomeSelectPress() }
    val heroFocus = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    var returnKey by rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember { FocusRequester() }
    var restoring by remember { mutableStateOf(returnKey != null) }
    val scope = rememberCoroutineScope()
    var navigation by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val enterRail = LocalTvHomeRailEntry.current
    val heroReturn = returnKey == "hero"
    val returningRow = rows.firstOrNull { row -> row.films.any { "${row.id}/${it.id}" == returnKey } }
    LaunchedEffect(featured) {
        heroIndex.intValue = heroIndex.intValue.coerceIn(0, (featured.size - 1).coerceAtLeast(0))
        vm.enrich(featured)
    }

    fun showRow(index: Int, first: Boolean) {
        val row = rows.getOrNull(index) ?: return
        navigation?.cancel()
        navigation = scope.launch {
            if (first) {
                // A LazyRow may have scrolled its first card out of composition. Materialize it before focusing.
                rowStates[index].scrollToItem(0)
                if (!awaitTvFocus { rowFocus[row.id] }) return@launch
            }
            rowOffsets[row.id]?.let { scroll.animateScrollTo(it, tween(280)) }
        }
    }
    fun showHero() {
        navigation?.cancel()
        navigation = scope.launch {
            if (awaitTvFocus { heroFocus }) scroll.animateScrollTo(0, tween(280))
        }
    }

    RegisterPageEntry(when {
        returningRow != null -> returnFocus
        featured.isNotEmpty() -> heroFocus
        else -> rowIDs.firstOrNull()?.let { rowFocus[it] }
    })
    LaunchedEffect(Unit) {
        when {
            heroReturn && featured.isNotEmpty() -> {
                scroll.scrollTo(0)
                awaitTvFocus { heroFocus }
            }
            returningRow != null -> {
                if (awaitTvFocus { returnFocus }) rowOffsets[returningRow.id]?.let { scroll.scrollTo(it) }
                else returnKey = null
            }
            else -> returnKey = null
        }
        restoring = false
    }
    LaunchedEffect(featured) {
        while (true) {
            delay(1_000)
            if (!heroFocused || scroll.value != 0 || selectPress.armed || featured.size < 2 ||
                android.os.SystemClock.uptimeMillis() - lastInput[0] < HeroRotationMillis) continue
            val next = (heroIndex.intValue + 1) % featured.size
            featured.getOrNull((next + 1) % featured.size)?.let { upcoming ->
                val art = vm.withArtwork(upcoming).heroArt()
                if (art.isNotBlank()) context.imageLoader.enqueue(heroRequest(context, art))
            }
            heroIndex.intValue = next
            lastInput[0] = android.os.SystemClock.uptimeMillis()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Background).onPreviewKeyEvent { event ->
        lastInput[0] = android.os.SystemClock.uptimeMillis()
        val native = event.nativeKeyEvent
        val down = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
        val up = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP
        if ((!down && !up) || (!heroFocused && focusedRow == null)) return@onPreviewKeyEvent false
        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
            val current = if (heroFocused) null else rowIDs.indexOf(focusedRow).takeIf { it >= 0 }
            when (val target = TvHomeNavigation.verticalTarget(current, down, rows.size, featured.isNotEmpty())) {
                TvHomeNavigation.Destination.Hero -> showHero()
                TvHomeNavigation.Destination.Rail -> enterRail?.invoke()
                is TvHomeNavigation.Destination.Shelf -> showRow(target.index, first = true)
                TvHomeNavigation.Destination.None -> Unit
            }
        }
        // Keep key-up/repeats from reaching the newly focused row and causing a second jump.
        true
    }) {
        val screen = maxHeight
        WithoutFocusScroll {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (featured.isNotEmpty()) HomeFeaturedSurface(vm, featured, heroIndex, screen,
                    heroFocus, selectPress, rtl,
                    onFocused = { focused ->
                        heroFocused = focused
                        if (!focused) selectPress.cancel()
                        else if (!restoring) {
                            returnKey = null
                            if (navigation?.isActive != true) {
                                navigation?.cancel()
                                navigation = scope.launch { scroll.animateScrollTo(0, tween(280)) }
                            }
                        }
                    }, onOpen = { film -> returnKey = "hero"; vm.open(film) })
                else if (vm.busy && rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(screen)) {
                        HeroSkeleton(Modifier.align(Alignment.CenterStart).padding(start = TvMetrics.HomeStart).width(820.mx))
                    }
                }
                rows.forEachIndexed { index, shelf -> key(shelf.id) {
                    val isRecent = shelf.id == "recent"
                    val returnIndex = shelf.films.indexOfFirst { "${shelf.id}/${it.id}" == returnKey }
                    Box(Modifier.fillMaxWidth().padding(bottom = 22.mx)
                        .onGloballyPositioned { rowOffsets[shelf.id] = it.positionInParent().y.toInt() }
                        .onFocusChanged { if (!it.hasFocus && focusedRow == shelf.id) focusedRow = null }) {
                        TvShelfRow(shelf, vm, wide = isRecent,
                            width = if (isRecent) TvMetrics.ContinueWidth else TvMetrics.PosterWidth,
                            preview = false, titleOnly = !isRecent, inset = TvMetrics.HomeStart,
                            firstRequester = rowFocus[shelf.id], suppliedRowState = rowStates[index],
                            cardModifier = { card -> if (card == returnIndex) Modifier.focusRequester(returnFocus) else Modifier },
                            onFilmFocused = {
                                if (focusedRow != shelf.id) {
                                    focusedRow = shelf.id
                                    if (!restoring && navigation?.isActive != true) showRow(index, first = false)
                                }
                            },
                            onCardLongClick = if (isRecent) ({ film -> vm.showRecentOptions(film) }) else null,
                            onCardClick = { film ->
                                returnKey = "${shelf.id}/${film.id}"
                                if (isRecent) vm.play(film) else vm.open(film)
                            })
                    }
                } }
                if (vm.busy && shelves.isEmpty()) ShelfSkeleton()
                vm.error?.let { error ->
                    Column(Modifier.padding(horizontal = TvMetrics.HomeStart, vertical = 24.mx), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Label(error, 14, Brand.TextTertiary, lines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action("إعادة المحاولة", true) { vm.reload() }
                            Action("تجربة التصميم") { vm.changeDemo(true) }
                        }
                    }
                }
                if (!vm.busy && vm.error == null && featured.isEmpty() && rows.isEmpty())
                    Label("لا يوجد محتوى متاح حاليًا", 17, Brand.TextTertiary, modifier = Modifier.padding(TvMetrics.HomeStart))
                Spacer(Modifier.height(48.mx))
            }
        }
    }
}

/** Reads the rotating index here, so changing artwork does not recompose the shelf list. */
@Composable
private fun HomeFeaturedSurface(vm: CinemaViewModel, featured: List<Film>, index: MutableIntState,
    height: Dp, focus: FocusRequester, selectPress: TvHomeSelectPress, rtl: Boolean,
    onFocused: (Boolean) -> Unit, onOpen: (Film) -> Unit) {
    val hero = featured.getOrNull(index.intValue.coerceIn(0, (featured.size - 1).coerceAtLeast(0)))?.let(vm::withArtwork) ?: return
    Box(Modifier.fillMaxWidth().height(height)) {
        HomeHeroArtwork(hero, Modifier.matchParentSize())
        HomeHero(vm, hero, featured.size, index.intValue, height,
            Modifier.focusRequester(focus).onFocusChanged { onFocused(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (native.action == AndroidKeyEvent.ACTION_DOWN) index.intValue = TvHomeNavigation.carouselIndex(
                                index.intValue, featured.size, native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT, rtl)
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (selectPress.update(native.action == AndroidKeyEvent.ACTION_DOWN, native.repeatCount)) onOpen(hero)
                            true
                        }
                        else -> false
                    }
                }, onOpen = { onOpen(hero) })
        Wordmark(Modifier.align(Alignment.TopEnd).padding(top = 48.mx, end = 58.mx))
    }
}

/** "Cinema iQ" at the top of the page, on the side away from the rail. */
@Composable
private fun Wordmark(modifier: Modifier) {
    // The padding belongs to the page's (right-to-left) layout; only the letters inside run left to right.
    Box(modifier) { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row {
            Text("Cinema ", color = Color.White, fontFamily = Cairo, fontWeight = FontWeight.Bold, fontSize = 26.ms)
            Text("iQ", color = Brand.Accent, fontFamily = Cairo, fontWeight = FontWeight.Bold, fontSize = 26.ms)
        }
    }
    }
}

/** The featured artwork with the brief's scrims: dark on the text side, fading into the page at the bottom. */
@Composable
private fun HomeHeroArtwork(film: Film?, modifier: Modifier) {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier) {
        Crossfade(targetState = film?.heroArt().orEmpty(), animationSpec = tween(600), label = "Hero artwork") { url ->
            if (url.isNotBlank()) HeroArt(heroRequest(context, url)) else Box(Modifier.fillMaxSize().background(Brand.Raised))
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val bg = Brand.Background
            // From the text edge: solid, 92% at 28%, 35% at 58%, clear from 80%.
            val stops = arrayOf(0f to bg.copy(alpha = .90f), .34f to bg.copy(alpha = .62f), .62f to bg.copy(alpha = .16f), .84f to Color.Transparent, 1f to Color.Transparent)
            val side = Brush.horizontalGradient(*(if (rtl) stops.map { (1f - it.first) to it.second }.reversed().toTypedArray() else stops))
            val bottom = Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent, .80f to bg.copy(alpha = .75f), 1f to bg)
            onDrawBehind { drawRect(side); drawRect(bottom) }
        })
    }
}

/** A single focusable featured card: metadata and dots, with OK opening the title instead of action buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeHero(vm: CinemaViewModel, film: Film, count: Int, index: Int,
    height: Dp, modifier: Modifier, onOpen: () -> Unit) {
    var about by remember(film.id) { mutableStateOf(film.description) }
    var quality by remember(film.id) { mutableStateOf(film.contentQuality) }
    LaunchedEffect(film.id) { if (about.isBlank()) about = runCatching { vm.describe(film) }.getOrDefault("") }
    LaunchedEffect(film.id) { if (quality.isBlank()) quality = runCatching { vm.bestQuality(film) }.getOrDefault("") }
    Box(modifier.fillMaxWidth().height(height).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClickLabel = "عرض تفاصيل ${mediaTitle(film)}", onClick = onOpen)) {
        // Anchored low, so the artwork shows clearly above the text.
        Column(Modifier.align(Alignment.BottomStart).padding(start = TvMetrics.HomeStart, bottom = 96.mx).width(820.mx),
            verticalArrangement = Arrangement.spacedBy(20.mx)) {
            AccentChip(if (film.series) "مسلسل" else "فيلم")
            Text(mediaTitle(film), color = Color.White, fontFamily = Cairo, fontWeight = FontWeight.Bold,
                fontSize = 84.ms, lineHeight = (84 * 1.1).toInt().ms, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.mx), verticalArrangement = Arrangement.spacedBy(6.mx),
                itemVerticalAlignment = Alignment.CenterVertically) {
                val facts = listOf(film.year, formatContentDuration(film.duration),
                    film.rating.takeIf { (it.toFloatOrNull() ?: 0f) > 0f }?.let { "★ $it" }.orEmpty()).filter(String::isNotBlank)
                facts.forEachIndexed { i, fact ->
                    if (i > 0) Label("•", 13, Brand.BorderStrong)
                    Label(fact, 13, Brand.TextSecondary, lines = 1)
                }
                MetaChip(quality, glass = true)
                if (film.subtitles.isNotEmpty()) MetaChip("CC", glass = true)
            }
            if (about.isNotBlank()) Text(about, color = Brand.TextSecondary, fontFamily = Cairo, fontSize = 24.ms,
                lineHeight = (24 * 1.6).toInt().ms, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.mx))
        }
        if (count > 1) Row(Modifier.align(Alignment.BottomStart).padding(start = TvMetrics.HomeStart, bottom = 30.mx),
            horizontalArrangement = Arrangement.spacedBy(8.mx)) {
            repeat(count) { dot ->
                val on = dot == index
                val width by animateDpAsState(if (on) 32.mx else 6.mx, tween(200), label = "Hero dot")
                Box(Modifier.size(width, 6.mx).clip(CircleShape).background(if (on) Color.White else Brand.BorderStrong))
            }
        }
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

