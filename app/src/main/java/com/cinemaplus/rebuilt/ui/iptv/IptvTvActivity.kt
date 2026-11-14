package com.cinemaplus.rebuilt.ui.iptv

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cinemaplus.rebuilt.ui.*

/** Device-local live TV is isolated from movie progress, cloud accounts and the mobile navigation. */
class IptvTvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val type = Typography().let { t -> t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = Cairo),
                bodyMedium = t.bodyMedium.copy(fontFamily = Cairo), labelLarge = t.labelLarge.copy(fontFamily = Cairo),
                bodySmall = t.bodySmall.copy(fontFamily = Cairo), titleMedium = t.titleMedium.copy(fontFamily = Cairo)) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel), typography = type) {
                    androidx.tv.material3.MaterialTheme { TvScaled { IptvTvScreen(onExit = { finish() }) } }
                }
            }
        }
    }
    companion object { fun open(context: Context) { context.startActivity(Intent(context, IptvTvActivity::class.java)) } }
}

/** A single persistent focus node, matching the existing expanded/collapsed sidebar rows. */
@Composable fun IptvRailEntry(expanded: Boolean) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.then(if (expanded) Modifier.fillMaxWidth().height(72.mx) else Modifier.size(64.mx))
        .clip(RoundedCornerShape(16.mx)).background(if (focused) Color.White else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }.clickable { IptvTvActivity.open(context) }
        .semantics { contentDescription = "IPTV" }.padding(horizontal = if (expanded) 20.mx else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.spacedBy(20.mx) else Arrangement.Center) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(30.mx), tint = if (focused) Ink else Brand.TextTertiary)
        if (expanded) Label("IPTV", 15, if (focused) Ink else Brand.TextTertiary, focused, lines = 1)
    }
}
