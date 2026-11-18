package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.data.iptv.IptvChannel
import com.cinemaplus.rebuilt.data.iptv.IptvChannelDisplayName
import com.cinemaplus.rebuilt.ui.*

@Composable internal fun rememberIptvLogos(): ImageLoader {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loader = remember(context) {
        ImageLoader.Builder(context).allowRgb565(true).crossfade(false)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .memoryCache { coil.memory.MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build() }
            .okHttpClient { okhttp3.OkHttpClient.Builder().followSslRedirects(false).build() }.build()
    }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    return loader
}

@Composable internal fun IptvChannelRow(channel: IptvChannel, number: Int, playing: Boolean, logos: ImageLoader,
    modifier: Modifier = Modifier, translucent: Boolean = false, click: () -> Unit) {
    if (translucent) {
        IptvGlassChannelRow(channel, number, playing, logos, modifier, click)
        return
    }
    var focused by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(14.dp))
        .clickable(onClick = click).semantics { contentDescription = channel.name },
        color = if (focused) Brand.Raised.copy(alpha = if (translucent) .82f else 1f)
            else Panel.copy(alpha = if (translucent) .38f else 1f),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused || playing) Accent else Brand.BorderStrong)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Label(number.toString(), 12, Brand.TextTertiary, lines = 1)
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Glass), contentAlignment = Alignment.Center) {
                if (channel.logo != null) AsyncImage(model = channel.logo, contentDescription = null, imageLoader = logos, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
                else Label("TV", 11, Brand.TextTertiary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Label(channel.name, 14, bold = true, lines = 2); Label(channel.group, 11, Brand.TextTertiary, lines = 1)
            }
            if (playing) Label("▶", 13, Accent)
        }
    }
}


/** The TV overlay uses quiet glass rows; phone/library rows retain their existing appearance. */
@Composable private fun IptvGlassChannelRow(channel: IptvChannel, number: Int, playing: Boolean,
    logos: ImageLoader, modifier: Modifier, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val displayName = remember(channel.name, channel.group) { IptvChannelDisplayName.of(channel) }
    val shape = RoundedCornerShape(12.dp)
    androidx.tv.material3.Button(onClick = click,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused }
        .clip(shape).background(if (focused) IptvTvGlass.FocusedBrush else IptvTvGlass.QuietRowBrush)
        .border(.75.dp, if (focused) Color.White.copy(alpha = .3f) else Color.Transparent, shape)
        .semantics { contentDescription = displayName },
        shape = androidx.tv.material3.ButtonDefaults.shape(shape),
        scale = androidx.tv.material3.ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent, contentColor = Brand.TextPrimary, focusedContentColor = Brand.TextPrimary),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
    Row(Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (channel.logo != null) AsyncImage(model = channel.logo, contentDescription = null, imageLoader = logos,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else IptvTelevisionIcon(Modifier.size(30.dp), Brand.TextSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label(displayName, 14, bold = true, lines = 1)
            Label(channel.group, 11, Brand.TextSecondary, lines = 1)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label(number.toString(), 11, Brand.TextSecondary, lines = 1)
            if (playing) Label("▶", 10, Color(0xFFFFC36A), lines = 1)
        }
    }
    }
}
