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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * TV home of the 2026 redesign (01-home.png): a 760 px hero with the title block on the right, then the shelves
 * in one vertical page. Performance rules that keep D-pad navigation smooth on weak devices:
 *  - the hero index is read only by the hero, so a swap never recomposes the shelves;
 *  - the next artwork is prefetched at the size the screen requests;
 *  - card focus is drawn in the graphics layer (see [FilmCard]).
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
    val heroIndex = rememberSaveable { mutableIntStateOf(0) }
    val lastInput = remember { longArrayOf(System.currentTimeMillis()) }
    val playFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(featured) { vm.enrich(featured) }
    // Entering the page from the rail, or coming back to it, lands on the hero's main button.
    RegisterPageEntry(if (featured.isNotEmpty()) playFocus else null)
    LaunchedEffect(featured) {
        while (true) {
            delay(1_000)
            if (featured.size < 2 || System.currentTimeMillis() - lastInput[0] < HeroRotationMillis) continue
            val next = (heroIndex.intValue + 1) % featured.size
            featured.getOrNull((next + 1) % featured.size)?.let { upcoming ->
                val art = vm.withArtwork(upcoming).heroArt()
                if (art.isNotBlank()) context.imageLoader.enqueue(heroRequest(context, art))
            }
            heroIndex.intValue = next
            lastInput[0] = System.currentTimeMillis()
        }
    }
    Box(Modifier.fillMaxSize().background(Brand.Background).onPreviewKeyEvent {
        lastInput[0] = System.currentTimeMillis(); false
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth()) {
                val hero = featured.getOrNull(heroIndex.intValue.coerceIn(0, (featured.size - 1).coerceAtLeast(0)))?.let(vm::withArtwork)
                HomeHeroArtwork(hero, Modifier.fillMaxWidth().height(760.mx))
                Column(Modifier.fillMaxWidth()) {
                    if (hero != null) {
                        HomeHero(vm, hero, featured.size, heroIndex.intValue, recent, playFocus,
                            Modifier.onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } })
                    } else {
                        Box(Modifier.fillMaxWidth().height(680.mx)) {
                            if (vm.busy) HeroSkeleton(Modifier.align(Alignment.CenterStart).padding(start = TvMetrics.HomeInset).width(820.mx))
                        }
                    }
                    if (recent.isNotEmpty()) HomeShelf {
                        TvShelfRow(Shelf("recent", "متابعة المشاهدة", recent), vm, wide = true, width = TvMetrics.ContinueWidth,
                            preview = false, inset = TvMetrics.HomeInset,
                            onCardLongClick = { film -> vm.showRecentOptions(film) },
                            onCardClick = { vm.play(it) })
                    }
                    shelves.forEach { shelf ->
                        HomeShelf {
                            TvShelfRow(shelf, vm, preview = false, titleOnly = true, inset = TvMetrics.HomeInset)
                        }
                    }
                    if (vm.busy && shelves.isEmpty()) ShelfSkeleton()
                    vm.error?.let { error ->
                        Column(Modifier.padding(horizontal = TvMetrics.HomeInset, vertical = 24.mx), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Label(error, 14, Brand.TextTertiary, lines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Action("إعادة المحاولة", true) { vm.reload() }
                                Action("تجربة التصميم") { vm.changeDemo(true) }
                            }
                        }
                    }
                    if (!vm.busy && vm.error == null && featured.isEmpty() && shelves.isEmpty() && recent.isEmpty())
                        Label("لا يوجد محتوى متاح حاليًا", 17, Brand.TextTertiary, modifier = Modifier.padding(TvMetrics.HomeInset))
                    Spacer(Modifier.height(48.mx))
                }
                Wordmark(Modifier.align(Alignment.TopEnd).padding(top = 48.mx, end = 58.mx))
            }
        }
    }
}

/** Keeps a whole shelf (title and cards) on screen when focus enters it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShelf(content: @Composable () -> Unit) {
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().padding(bottom = 22.mx).bringIntoViewRequester(bring)
        .onFocusChanged { if (it.hasFocus) scope.launch { bring.bringIntoView() } }) { content() }
}

/** "Cinema iQ" at the top of the page, on the side away from the rail. */
@Composable
private fun Wordmark(modifier: Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier) {
            Text("Cinema ", color = Color.White, fontFamily = Cairo, fontWeight = FontWeight.Bold, fontSize = 26.ms)
            Text("iQ", color = Brand.Accent, fontFamily = Cairo, fontWeight = FontWeight.Bold, fontSize = 26.ms)
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
            val stops = arrayOf(0f to bg, .28f to bg.copy(alpha = .92f), .58f to bg.copy(alpha = .35f), .80f to Color.Transparent, 1f to Color.Transparent)
            val side = Brush.horizontalGradient(*(if (rtl) stops.map { (1f - it.first) to it.second }.reversed().toTypedArray() else stops))
            val bottom = Brush.verticalGradient(0f to Color.Transparent, (460f / 760f) to Color.Transparent, 1f to bg)
            onDrawBehind { drawRect(side); drawRect(bottom) }
        })
    }
}

/** Title block of the hero (kind chip, title, facts, two-line story, actions, rotation dots). 680 px tall. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeHero(vm: CinemaViewModel, film: Film, count: Int, index: Int, recent: List<Film>,
    playFocus: FocusRequester, modifier: Modifier) {
    var about by remember(film.id) { mutableStateOf(film.description) }
    var quality by remember(film.id) { mutableStateOf(film.contentQuality) }
    LaunchedEffect(film.id) { if (about.isBlank()) about = runCatching { vm.describe(film) }.getOrDefault("") }
    LaunchedEffect(film.id) { if (quality.isBlank()) quality = runCatching { vm.bestQuality(film) }.getOrDefault("") }
    val revision = vm.libraryRevision
    val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
    // A series resumes its episode in progress; without one its page opens so an episode can be chosen.
    val episode = recent.firstOrNull { film.series && it.seriesId == film.id }
    val started = remember(revision, film.id) { vm.library.progress(film.id) > 0 }
    val playLabel = when {
        episode != null -> "شاهد الحلقة ${episode.episode}"
        !film.series && started -> "استئناف"
        else -> "شاهد الآن"
    }
    Box(modifier.fillMaxWidth().height(680.mx)) {
        Column(Modifier.align(Alignment.TopStart).padding(start = TvMetrics.HomeInset, top = 150.mx).width(820.mx),
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
            Row(Modifier.padding(top = 16.mx), horizontalArrangement = Arrangement.spacedBy(20.mx), verticalAlignment = Alignment.CenterVertically) {
                PillButton(playLabel, Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus)) {
                    when {
                        episode != null -> vm.play(episode)
                        film.series -> vm.open(film)
                        else -> vm.play(film)
                    }
                }
                PillButton("قائمتي", if (saved) Icons.Rounded.Check else Icons.Rounded.Add, primary = false) { vm.toggle(film) }
                PillButton("التفاصيل", Icons.Rounded.Info, primary = false) { vm.open(film) }
            }
        }
        if (count > 1) Row(Modifier.align(Alignment.TopStart).padding(start = TvMetrics.HomeInset, top = 640.mx),
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

