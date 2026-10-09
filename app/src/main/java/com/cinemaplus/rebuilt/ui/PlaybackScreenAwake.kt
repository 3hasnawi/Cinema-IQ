package com.cinemaplus.rebuilt.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player

internal fun playbackNeedsAwakeScreen(foreground: Boolean, wantsPlayback: Boolean, state: Int, failed: Boolean): Boolean =
    foreground && wantsPlayback && !failed && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)

private fun Context.activity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> if(baseContext === this) null else baseContext.activity()
    else -> null
}

/** Window-level protection survives hidden controls and Media3 surface keepScreenOn changes. */
@Composable
internal fun PlaybackScreenAwake(player: Player) {
    val window = LocalContext.current.activity()?.window ?: return
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner, window) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val originallyKeptAwake = window.attributes.flags and flag != 0
        fun update() {
            val awake = originallyKeptAwake || playbackNeedsAwakeScreen(
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                player.playWhenReady, player.playbackState, player.playerError != null)
            if(awake) window.addFlags(flag) else window.clearFlags(flag)
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { update() }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        update()
        onDispose {
            player.removeListener(listener)
            owner.lifecycle.removeObserver(observer)
            if(originallyKeptAwake) window.addFlags(flag) else window.clearFlags(flag)
        }
    }
}
