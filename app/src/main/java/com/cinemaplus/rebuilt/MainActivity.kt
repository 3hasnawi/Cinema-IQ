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
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        com.cinemaplus.rebuilt.ui.TrailerResolver.init(applicationContext)
        // One image loader tuned for weak TV hardware: 16-bit bitmaps halve memory and upload cost, and a
        // capped memory cache keeps the app from competing with the system for RAM.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(applicationContext)
            .allowRgb565(true).crossfade(false)
            .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.20).build() }
            .build())
        setContent { CinemaApp() }
    }
}
