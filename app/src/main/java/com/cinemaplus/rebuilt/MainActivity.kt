package com.cinemaplus.rebuilt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cinemaplus.rebuilt.ui.CinemaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.cinemaplus.rebuilt.cloud.CloudAccount.init(applicationContext)
        val television = com.cinemaplus.rebuilt.ui.mobile.usesTelevisionInterface(this)
        requestedOrientation = if (television) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (television) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        com.cinemaplus.rebuilt.ui.TrailerResolver.init(applicationContext)
        // One image loader tuned for weak TV hardware: 16-bit bitmaps halve memory and upload cost, and a
        // capped memory cache keeps the app from competing with the system for RAM.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(applicationContext)
            // Artwork URLs are stable; ignore the servers' no-cache headers so images are reused from disk.
            .respectCacheHeaders(false)
            .allowRgb565(true).crossfade(false)
            .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.20).build() }
            .build())
        readLaunchRequest(intent)
        setContent { CinemaApp() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLaunchRequest(intent)
    }

    private fun readLaunchRequest(intent: android.content.Intent?) {
        intent?.getStringExtra(com.cinemaplus.rebuilt.data.WatchNext.EXTRA_FILM_ID)?.takeIf { it.isNotBlank() }?.let {
            com.cinemaplus.rebuilt.ui.LaunchRequest.pending = com.cinemaplus.rebuilt.ui.WatchNextLaunch(
                it, intent.getStringExtra(com.cinemaplus.rebuilt.data.WatchNext.EXTRA_PROFILE_ID))
            intent.removeExtra(com.cinemaplus.rebuilt.data.WatchNext.EXTRA_FILM_ID)
            intent.removeExtra(com.cinemaplus.rebuilt.data.WatchNext.EXTRA_PROFILE_ID)
        }
    }
}
