package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cinemaplus.rebuilt.ui.*

/** Remote-native PIN entry never creates an IME connection or persists the entered digits. */
@Composable internal fun IptvPinPrompt(title: String, setup: Boolean, busy: Boolean, error: String?, resetEpoch: Int,
    cancel: () -> Unit, submit: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    val keys = remember { List(12) { FocusRequester() } }
    val currentBusy by rememberUpdatedState(busy)
    val currentSubmit by rememberUpdatedState(submit)
    val currentCancel by rememberUpdatedState(cancel)
    fun enter(value: String) {
        if (value == "cancel") { currentCancel(); return }
        if (currentBusy || submitted) return
        if (value == "delete") { draft = draft.dropLast(1); return }
        if (draft.length >= 4) return
        val entered = draft + value
        draft = entered
        localError = null
        if (entered.length != 4) return
        when {
            setup && firstPin == null -> { firstPin = entered; draft = "" }
            setup && firstPin != entered -> {
                firstPin = null; draft = ""; localError = "الرمزان غير متطابقين. أدخل رمزًا جديدًا ثم أكّده."
            }
            else -> { submitted = true; draft = ""; currentSubmit(entered) }
        }
    }
    LaunchedEffect(resetEpoch, error, busy) {
        if (!busy) { draft = ""; submitted = false }
    }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = LocalWindowInfo.current
        val keyboard = LocalSoftwareKeyboardController.current
        val inputMode = LocalInputModeManager.current
        val focus = LocalFocusManager.current
        LaunchedEffect(Unit) { keyboard?.hide() }
        DisposableEffect(Unit) { onDispose { runCatching { keyboard?.hide(); focus.clearFocus(force = true) } } }
        LaunchedEffect(resetEpoch, busy, window.isWindowFocused) {
            if (window.isWindowFocused) repeat(24) {
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                withFrameNanos { }
                if (runCatching { keys[if (busy) 11 else 0].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(20.dp)
            Surface(Modifier.widthIn(max = 360.dp).fillMaxWidth(.9f).heightIn(max = maxHeight * .94f)
                .testTag("iptv.tv.pin").onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    val number = when (key.keyCode) {
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> key.keyCode - KeyEvent.KEYCODE_0
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> key.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                        else -> null
                    }
                    if (number != null) {
                        if (key.action == KeyEvent.ACTION_UP) enter(number.toString())
                        true
                    } else if (key.keyCode == KeyEvent.KEYCODE_DEL) {
                        if (key.action == KeyEvent.ACTION_UP) enter("delete")
                        true
                    } else false
                }, color = Color.Transparent, shape = shape, border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))) {
                Column(Modifier.background(Brush.verticalGradient(listOf(Panel.copy(alpha = .94f), Ink.copy(alpha = .88f))))
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(title, 21, bold = true, lines = 2)
                    Label(when { busy -> "جارٍ التحقق…"; setup && firstPin != null -> "أعد إدخال الرمز للتأكيد"; setup -> "اختر رمزًا من أربعة أرقام"; else -> "أدخل رمز الحماية" }, 14, Brand.TextSecondary)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, LocalIptvCompactControls provides true) {
                        Row(Modifier.semantics { contentDescription = "تم إدخال ${draft.length} من أربعة أرقام" },
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(4) { index ->
                                Box(Modifier.size(36.dp, 42.dp).background(Color.White.copy(alpha = .07f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    Label(if (index < draft.length) "●" else "—", 22, if (index < draft.length) Brand.TextPrimary else Brand.TextTertiary)
                                }
                            }
                        }
                        val values = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "delete", "0", "cancel")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(4) { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    repeat(3) { column ->
                                        val index = row * 3 + column
                                        val value = values[index]
                                        IptvButton(when (value) { "delete" -> "مسح"; "cancel" -> "إلغاء"; else -> value },
                                            Modifier.weight(1f).testTag("iptv.tv.pin.$value").focusRequester(keys[index]).focusProperties {
                                                left = if (column == 0) FocusRequester.Cancel else keys[index - 1]
                                                right = if (column == 2) FocusRequester.Cancel else keys[index + 1]
                                                up = if (row == 0) FocusRequester.Cancel else keys[index - 3]
                                                down = if (row == 3) FocusRequester.Cancel else keys[index + 3]
                                            }, enabled = value == "cancel" || (!busy && !submitted)) { enter(value) }
                                    }
                                }
                            }
                        }
                    }
                    (localError ?: error)?.let { Label(it, 13, Brand.Danger, lines = 3) }
                }
            }
        }
    }
}
