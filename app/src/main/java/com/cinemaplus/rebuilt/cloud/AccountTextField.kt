package com.cinemaplus.rebuilt.cloud

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.*
import com.cinemaplus.rebuilt.ui.mobile.usesTelevisionInterface

/** Remote focus does not open the TV IME until Select; Next/Done never trap the editor. */
@Composable
fun AccountTextField(value: String, onValueChange: (String) -> Unit, label: String,
    focus: FocusRequester, up: FocusRequester, down: FocusRequester, modifier: Modifier = Modifier,
    password: Boolean = false, email: Boolean = false, done: Boolean = false, enabled: Boolean = true) {
    val keyboard = LocalSoftwareKeyboardController.current
    val tv = usesTelevisionInterface(LocalContext.current)
    fun move(target: FocusRequester, editing: Boolean = false) {
        keyboard?.hide()
        if(runCatching { target.requestFocus() }.getOrDefault(false) && editing) keyboard?.show()
    }
    OutlinedTextField(value, onValueChange, modifier.focusRequester(focus)
        .focusProperties { this.up = up; this.down = down }
        .onPreviewKeyEvent { event ->
            if(!tv || event.type != KeyEventType.KeyDown) false else when(event.key) {
                Key.DirectionUp -> { move(up); true }
                Key.DirectionDown -> { move(down); true }
                // Let the editor also handle Select so it can start an input session.
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { keyboard?.show(); false }
                else -> false
            }
        }, singleLine = true, enabled = enabled, label = { Text(label) },
        visualTransformation = if(password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; email -> KeyboardType.Email; else -> KeyboardType.Text },
            imeAction = if(done) ImeAction.Done else ImeAction.Next, showKeyboardOnFocus = !tv),
        keyboardActions = KeyboardActions(onNext = { move(down, editing = true) }, onDone = { move(down) }))
}
