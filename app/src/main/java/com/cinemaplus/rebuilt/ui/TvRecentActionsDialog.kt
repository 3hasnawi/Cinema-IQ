package com.cinemaplus.rebuilt.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Compact TV option panel, using the same glass border and white focused rows as the player menus. */
@Composable
internal fun TvRecentActionsDialog(title: String, onWatched: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var ready by remember { mutableStateOf(false) }
    val watchedFocus = remember { FocusRequester() }
    val removeFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitTvFocus { watchedFocus }
        // The key that opened the menu is still held. Require a new select press after the opening delay.
        delay(900)
        ready = true
    }
    Dialog(onDismissRequest = { if (ready) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // This dialog is above the page's TvScaled scope, so it owns its TV density/font scaling.
        TvScaled {
            BoxWithConstraints(Modifier.padding(24.mx)) {
                val shape = RoundedCornerShape(28.mx)
                Column(Modifier.widthIn(min = 360.mx, max = 640.mx).width(IntrinsicSize.Max)
                    .heightIn(max = (maxHeight - 48.mx).coerceAtLeast(0.dp))
                    .clip(shape).background(Brand.Surface.copy(alpha = .82f))
                    .border(1.dp, Brand.GlassStroke.copy(alpha = .28f), shape)
                    .padding(16.mx).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.mx)) {
                    Label(title, 16, Brand.TextPrimary, true, lines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.mx, vertical = 12.mx))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.GlassStroke))
                    RecentActionRow("تعليم كمشاهَد", Icons.Rounded.Check, watchedFocus, null, removeFocus, ready, onWatched)
                    RecentActionRow("إزالة من متابعة المشاهدة", Icons.Rounded.Delete, removeFocus, watchedFocus, cancelFocus, ready, onRemove)
                    Box(Modifier.padding(vertical = 4.mx).fillMaxWidth().height(1.dp).background(Brand.GlassStroke))
                    RecentActionRow("إلغاء", Icons.Rounded.Close, cancelFocus, removeFocus, null, ready, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun RecentActionRow(text: String, icon: ImageVector, focus: FocusRequester,
    previous: FocusRequester?, next: FocusRequester?, ready: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val press = remember { TvHomeSelectPress() }
    val shape = RoundedCornerShape(18.mx)
    val foreground = if (focused) Brand.OnPrimary else Brand.TextPrimary
    Row(Modifier.fillMaxWidth().heightIn(min = 64.mx).clip(shape)
        .background(if (focused) Color.White else Color.Transparent)
        .focusRequester(focus).focusProperties {
            up = previous ?: FocusRequester.Cancel
            down = next ?: FocusRequester.Cancel
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
        }.onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) press.cancel()
        }.onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (key.keyCode !in listOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)) return@onPreviewKeyEvent false
            if (!ready) press.cancel()
            else if (press.update(key.action == AndroidKeyEvent.ACTION_DOWN, key.repeatCount)) onClick()
            true
        }.clickable(remember { MutableInteractionSource() }, null) { if (ready) onClick() }
        .padding(horizontal = 20.mx, vertical = 12.mx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.mx)) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(28.mx))
        Label(text, 14, foreground, lines = 1, modifier = Modifier.weight(1f))
    }
}
