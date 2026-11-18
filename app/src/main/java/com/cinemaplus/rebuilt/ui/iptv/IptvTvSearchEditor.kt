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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cinemaplus.rebuilt.ui.Brand

@Stable internal class IptvTvSearchEditorState {
    var editor: EditText? by mutableStateOf(null)
    var focused by mutableStateOf(false)
    var editing by mutableStateOf(false)
    fun hideKeyboard() {
        // Input ownership ends synchronously; an inset left by another window cannot keep it alive.
        editing = false
        focused = false
        editor?.let { view ->
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }
}

private class SearchEditText(context: Context) : EditText(context) {
    var back: (() -> Unit)? = null
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}

/** This editor is mounted only by an explicit search action; filtering never changes its native focus. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun IptvTvSearchEditor(
    value: String, state: IptvTvSearchEditorState, done: FocusRequester,
    change: (String) -> Unit, submit: () -> Unit, back: () -> Unit, modifier: Modifier = Modifier
) {
    val latestChange by rememberUpdatedState(change)
    val latestSubmit by rememberUpdatedState(submit)
    val latestBack by rememberUpdatedState(back)
    val imeVisible = WindowInsets.isImeVisible
    val liveImeVisible by rememberUpdatedState(imeVisible)
    val window = LocalWindowInfo.current
    val fontPixels = with(LocalDensity.current) { 16.sp.toPx() }
    var requested by remember { mutableStateOf(false) }
    var hadIme by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; state.hideKeyboard(); latestSubmit() } }
    val shape = RoundedCornerShape(10.dp)
    AndroidView(factory = { context ->
        SearchEditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(200))
            background = null
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, com.cinemaplus.rebuilt.R.font.cairo_regular)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontPixels)
            setTextColor(Brand.TextPrimary.toArgb())
            setHintTextColor(Brand.TextSecondary.toArgb())
            hint = "اسم القناة أو التصنيف"
            contentDescription = "ابحث عن قناة"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(0, 0, 0, 0)
            setText(value); setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { latestChange(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnFocusChangeListener { _, focused -> state.focused = focused }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) { finish(); true } else false
            }
            setOnKeyListener { _, code, event ->
                when {
                    code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_UP) finish()
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_DOWN && !liveImeVisible -> {
                        if (event.action == KeyEvent.ACTION_UP) { clearFocus(); runCatching { done.requestFocus() } }
                        true
                    }
                    else -> false
                }
            }
            this.back = { latestBack() }
            state.editor = this
        }
    }, update = { editor ->
        editor.back = { latestBack() }
        if (editor.text.toString() != value) { editor.setText(value); editor.setSelection(editor.text.length) }
    }, onRelease = { editor ->
        val imm = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
        if (state.editor === editor) { state.editor = null; state.focused = false }
    }, modifier = modifier.fillMaxWidth().height(48.dp).background(IptvTvGlass.QuietRowBrush, shape)
        .border(1.dp, IptvTvGlass.Hairline, shape).padding(horizontal = 12.dp))
    LaunchedEffect(state.editor, window.isWindowFocused) {
        val editor = state.editor
        if (window.isWindowFocused && editor != null && !requested && !finished) repeat(24) {
            withFrameNanos { }
            if (editor.requestFocus()) {
                requested = true
                withFrameNanos { }
                val imm = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) hadIme = true
        else if (hadIme && !finished) {
            withFrameNanos { }
            state.editor?.clearFocus()
            runCatching { done.requestFocus() }
        }
    }
}
