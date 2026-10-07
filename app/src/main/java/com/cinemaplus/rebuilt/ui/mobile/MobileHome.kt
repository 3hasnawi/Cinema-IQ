package com.cinemaplus.rebuilt.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
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

/** Swipeable featured titles that also advance on their own; art is framed like on TV, never stretched. */
@Composable
fun MobileHero(vm: CinemaViewModel, featured: List<Film>) {
    if (featured.isEmpty()) return
    val pager = rememberPagerState(pageCount = { featured.size })
    LaunchedEffect(featured.size) {
        if (featured.size < 2) return@LaunchedEffect
        while (true) {
            delay(HeroRotationMillis)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % featured.size)
        }
    }
    Box(Modifier.fillMaxWidth().height(380.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { featured[it].id }) { index ->
            val film = vm.withArtwork(featured[index])
            Box(Modifier.fillMaxSize()) {
                HeroBackdrop(film, Modifier.fillMaxSize(), sideScrim = false, fadeFrom = .35f)
                Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mediaTitle(film), fontFamily = Cairo, fontWeight = FontWeight.Bold, fontSize = 27.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        KindBadge(film.series)
                        InfoChip(film.year)
                        RatingChip(film.rating)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (film.series) vm.open(film) else vm.play(film) }) {
                            Icon(Icons.Rounded.PlayArrow, null); Text(if (film.series) "الحلقات" else "شاهد الآن")
                        }
                        OutlinedButton(onClick = { vm.open(film) }) { Text("التفاصيل") }
                    }
                }
            }
        }
        if (featured.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(featured.size) { dot ->
                Box(Modifier.size(if (dot == pager.currentPage) 16.dp else 6.dp, 6.dp)
                    .background(if (dot == pager.currentPage) Accent else Color.White.copy(alpha = .35f), CircleShape))
            }
        }
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
