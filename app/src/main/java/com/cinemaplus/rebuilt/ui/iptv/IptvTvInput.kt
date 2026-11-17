package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.cinemaplus.rebuilt.ui.*

/** Browsing a TV form must not activate the IME; OK explicitly opens the field editor. */
@Composable internal fun IptvTvInput(label: String, value: String, enabled: Boolean, secret: Boolean,
    modifier: Modifier, edit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Button(edit, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 58.dp)
        .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }
        .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Brand.BorderStrong, shape),
        shape = ButtonDefaults.shape(shape), scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Brand.Raised,
            contentColor = Brand.TextPrimary, focusedContentColor = Brand.TextPrimary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label(label, 12, Brand.TextTertiary)
            Label(if (value.isEmpty()) "اضغط OK للإدخال" else if (secret) "•".repeat(value.length.coerceAtMost(24)) else value,
                15, lines = 1)
        }
    }
}

/** Edit inside the existing source dialog; never replace windows while dismissing the IME. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun IptvTvFieldEditor(label: String, initial: String, keyboardType: KeyboardType,
    secret: Boolean, limit: Int, imeAction: ImeAction, commit: (String) -> Unit, cancel: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val input = remember { FocusRequester() }; val done = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val manager = LocalFocusManager.current
    val window = LocalWindowInfo.current
    val imeVisible = WindowInsets.isImeVisible
    var requested by remember { mutableStateOf(false) }
    var hadIme by remember { mutableStateOf(false) }
    fun finish() { keyboard?.hide(); manager.clearFocus(force = true); commit(draft) }
    fun dismiss() { keyboard?.hide(); manager.clearFocus(force = true); cancel() }
    BackHandler { if (imeVisible) keyboard?.hide() else dismiss() }
    OutlinedTextField(draft, { draft = it.take(limit) }, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onNext = { finish() }, onDone = { finish() }),
        modifier = Modifier.fillMaxWidth().focusRequester(input).focusProperties { down = done; next = done }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode == KeyEvent.KEYCODE_ENTER || key.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (key.action == KeyEvent.ACTION_UP) finish()
                    true
                } else false
            })
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IptvButton("تم", Modifier.focusRequester(done).focusProperties { up = input }) { finish() }
        IptvButton("إلغاء") { dismiss() }
    }
    LaunchedEffect(window.isWindowFocused) {
        if (window.isWindowFocused && !requested) repeat(24) {
            withFrameNanos { }
            if (runCatching { input.requestFocus() }.getOrDefault(false)) {
                requested = true; withFrameNanos { }; keyboard?.show(); return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) hadIme = true
        else if (hadIme) { withFrameNanos { }; runCatching { done.requestFocus() } }
    }
}
