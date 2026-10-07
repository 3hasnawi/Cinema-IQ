package com.cinemaplus.rebuilt.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.ui.*

/** Shared touch episode cards for details and the player bottom sheet. */
@Composable
fun MobileEpisodeRail(episodes: List<Film>, fallbackArtwork: String, onPlay: (Film) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(episodes, key = { it.id }) { episode ->
            Column(Modifier.width(220.dp).clickable { onPlay(episode) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AsyncImage(episode.backdrop.ifBlank { episode.poster.ifBlank { fallbackArtwork } }, "الحلقة ${episode.episode}",
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Panel),
                    contentScale = ContentScale.Crop)
                Text("الموسم ${episode.season} • الحلقة ${episode.episode}", fontWeight = FontWeight.Bold)
                Text(mediaTitle(episode), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatContentDuration(episode.duration), color = Muted)
            }
        }
    }
}
