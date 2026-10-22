package com.cinemaplus.rebuilt.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Shelf
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.delay

private const val HeroRotationMillis = 8_000L

/** Same pick as the TV home: the best rated titles interleaved with the newest, preferring real backdrops. */
fun phoneFeaturedFilms(shelves: List<Shelf>): List<Film> {
    val all = shelves.flatMap { it.films }.distinctBy { it.id }
    val withBackdrop = all.filter { it.backdrop.isNotBlank() }
    val pool = if (withBackdrop.size >= 3) withBackdrop else all.filter { it.backdrop.isNotBlank() || it.poster.isNotBlank() }
    val popular = pool.sortedByDescending { it.rating.toFloatOrNull() ?: 0f }.take(6)
    val newest = pool.filter { it !in popular }.sortedByDescending { it.year.toIntOrNull() ?: 0 }.take(4)
    val mixed = popular.flatMapIndexed { i, film -> listOfNotNull(film, newest.getOrNull(i)) }
    return mixed + newest.drop(popular.size)
}

/**
 * Phone hero (phone-png/01): a full-bleed 2:3 poster with top and bottom fades, then the kind chip, title, meta
 * line, actions (my list, play, details) and rotation dots centred over its bottom. Swipeable; advances on its own.
 */
@Composable
fun MobileHero(vm: CinemaViewModel, featured: List<Film>, topBar: @Composable () -> Unit = {}) {
    if (featured.isEmpty()) return
    val pager = rememberPagerState(pageCount = { featured.size })
    LaunchedEffect(featured.size) {
        if (featured.size < 2) return@LaunchedEffect
        while (true) {
            delay(HeroRotationMillis)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % featured.size)
        }
    }
    val revision = vm.libraryRevision
    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), key = { featured[it].id }) { index ->
            val film = vm.withArtwork(featured[index])
            val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
            val recent = remember(revision, film.id) { if (film.series) vm.library.recent().firstOrNull { it.seriesId == film.id && it.episode > 0 } else null }
            val started = remember(revision, film.id) { !film.series && vm.library.progress(film.id) > 0 }
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(390f / 540f).background(Brand.Raised)) {
                    AsyncImage(film.poster.ifBlank { film.backdrop }, mediaTitle(film), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().drawBehind {
                        val bg = Brand.Background
                        val top = 120.dp.toPx()
                        drawRect(Brush.verticalGradient(0f to bg.copy(alpha = .85f), 1f to Color.Transparent, endY = top), size = Size(size.width, top))
                        val from = size.height * (260f / 540f)
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, .88f to bg, 1f to bg, startY = from, endY = size.height),
                            topLeft = Offset(0f, from), size = Size(size.width, size.height - from))
                    })
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneChip(if (film.series) "مسلسل" else "فيلم", ChipKind.Accent)
                    PText(mediaTitle(film), 34.sp, weight = FontWeight.Bold, lines = 2, align = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhoneDotted(listOf(film.year, formatContentDuration(film.duration)))
                        PhoneChip(film.contentQuality, ChipKind.Glass)
                        PhoneRating(film.rating)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PhoneGlassCircle(if (saved) Icons.Rounded.Check else Icons.Rounded.Add, if (saved) "إزالة من قائمتي" else "أضف إلى قائمتي") { vm.toggle(film) }
                        val label = when {
                            recent != null -> "شاهد الحلقة ${recent.episode}"
                            film.series -> "عرض الحلقات"
                            started -> "استئناف"
                            else -> "شاهد الآن"
                        }
                        PhonePrimaryButton(label, Modifier.weight(1f)) {
                            when { recent != null -> vm.play(recent); film.series -> vm.open(film); else -> vm.play(film) }
                        }
                        PhoneGlassCircle(Icons.Rounded.Info, "التفاصيل") { vm.open(film) }
                    }
                }
            }
        }
        if (featured.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(featured.size) { dot ->
                Box(Modifier.size(if (dot == pager.currentPage) 16.dp else 4.dp, 4.dp)
                    .background(if (dot == pager.currentPage) Color.White else Brand.BorderStrong, CircleShape))
            }
        }
        Box(Modifier.align(Alignment.TopCenter)) { topBar() }
    }
}

/** Full-width trailer window for the phone; tries the resolved candidates in turn like the TV overlay. */
@Composable
fun MobileTrailer(film: Film, onClose: () -> Unit) {
    var ids by remember(film.id) { mutableStateOf<List<String>?>(null) }
    var index by remember(film.id) { mutableIntStateOf(0) }
    var playing by remember(film.id) { mutableStateOf(false) }
    LaunchedEffect(film.id) { ids = runCatching { TrailerResolver.candidates(film) }.getOrDefault(emptyList()) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            val current = ids?.getOrNull(index)
            if (current != null) key(current) {
                TrailerPreview(current, Modifier.fillMaxSize(), muted = false,
                    onPlaying = { playing = true; TrailerResolver.markWorking(film.id, current) },
                    onFailed = { if (index + 1 < (ids?.size ?: 0)) index++ else ids = emptyList() })
            }
            if (!playing) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ids?.isEmpty() == true) Text("تعذّر تشغيل التريلر لهذا العمل.", color = Color.White)
                else { CircularProgressIndicator(color = Color.White); Text("جارٍ تحميل التريلر…", color = Muted) }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) { Icon(Icons.Rounded.Close, "إغلاق", tint = Color.White) }
        }
    }
}
