package com.cinemaplus.rebuilt.ui.iptv

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.cinemaplus.rebuilt.ui.*

/** Browsing a TV form must not activate the IME; OK explicitly opens the field editor. */
@Composable internal fun IptvTvInput(label: String, value: String, enabled: Boolean, secret: Boolean,
    modifier: Modifier, edit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    Button({
        if (com.cinemaplus.rebuilt.BuildConfig.DEBUG) android.util.Log.d("IptvInputFocus", "activate control; mode=${inputMode.inputMode}")
        edit()
    }, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 58.dp)
        .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (com.cinemaplus.rebuilt.BuildConfig.DEBUG && key.keyCode in listOf(19, 20, 21, 22, 23, 66)) {
                android.util.Log.d("IptvInputFocus", "browse key=${key.keyCode} action=${key.action}; focused=$focused mode=${inputMode.inputMode}")
            }
            false
        }
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

/** A native editor releases its actual Android input connection when the field leaves composition. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun IptvTvFieldEditor(label: String, initial: String, keyboardType: KeyboardType,
    secret: Boolean, limit: Int, imeAction: ImeAction, commit: (String) -> Unit, cancel: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf<EditText?>(null) }
    val done = remember { FocusRequester() }
    val context = LocalContext.current
    val imm = remember(context) { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    val manager = LocalFocusManager.current
    val window = LocalWindowInfo.current
    val imeVisible = WindowInsets.isImeVisible
    val fontPixels = with(LocalDensity.current) { 15.sp.toPx() }
    var requested by remember { mutableStateOf(false) }
    var hadIme by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun hide() { input?.let { imm.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }; manager.clearFocus(force = true) }
    fun finish() { if (!finished) { finished = true; hide(); commit(draft) } }
    fun dismiss() { if (!finished) { finished = true; hide(); cancel() } }
    BackHandler { if (imeVisible) input?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) } else dismiss() }
    Column(Modifier.fillMaxWidth().border(2.dp, Accent, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label, 12, Brand.TextTertiary)
        AndroidView(factory = { ctx ->
            EditText(ctx).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or when {
                    secret -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                    keyboardType == KeyboardType.Uri -> InputType.TYPE_TEXT_VARIATION_URI
                    else -> InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                imeOptions = if (imeAction == ImeAction.Next) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
                filters = arrayOf(InputFilter.LengthFilter(limit))
                background = null
                typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, com.cinemaplus.rebuilt.R.font.cairo_regular)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontPixels)
                setTextColor(Brand.TextPrimary.toArgb())
                highlightColor = Accent.copy(alpha = .35f).toArgb()
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setPadding(0, 0, 0, 0)
                setText(initial); setSelection(text.length)
                contentDescription = label
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s?.toString().orEmpty() }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE) { finish(); true } else false
                }
                setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        if (event.action == KeyEvent.ACTION_UP) finish()
                        true
                    } else if (code == KeyEvent.KEYCODE_DPAD_DOWN &&
                        androidx.core.view.ViewCompat.getRootWindowInsets(this)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true) {
                        if (event.action == KeyEvent.ACTION_UP) { clearFocus(); done.requestFocus() }
                        true
                    } else false
                }
                input = this
            }
        }, onRelease = { editor ->
            imm.hideSoftInputFromWindow(editor.windowToken, 0)
            editor.clearFocus()
        }, modifier = Modifier.fillMaxWidth().height(40.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IptvButton("تم", Modifier.focusRequester(done)) { finish() }
        IptvButton("إلغاء") { dismiss() }
    }
    LaunchedEffect(input, window.isWindowFocused) {
        if (window.isWindowFocused && input != null && !requested) repeat(24) {
            withFrameNanos { }
            if (input?.requestFocus() == true) {
                requested = true; withFrameNanos { }; imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) hadIme = true
        else if (hadIme) { withFrameNanos { }; input?.clearFocus(); runCatching { done.requestFocus() } }
    }
}
