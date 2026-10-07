package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.tv.material3.MaterialTheme as TvTheme
import com.cinemaplus.rebuilt.data.Profile
import com.cinemaplus.rebuilt.data.ProfileList
import com.cinemaplus.rebuilt.data.ProfileStore
import kotlinx.coroutines.delay

/** Lets any settings page ask the app shell to show the profile picker again. */
val LocalProfileSwitch = compositionLocalOf<() -> Unit> { {} }

/**
 * "Who is watching?" screen. Each profile owns its own list, history and playback settings; the account
 * used for the sources stays shared. Works with the remote (focus ring) and with touch.
 */
@Composable
fun ProfilePicker(store: ProfileStore, onChosen: (Profile) -> Unit) {
    var profiles by remember { mutableStateOf(store.profiles()) }
    var adding by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { firstFocus.requestFocus() } }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
            TvTheme {
                Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Tiles wrap into rows that fit the width, so six profiles never run off a narrow phone screen.
                    val perRow = ProfileGridLayout.columns(maxWidth.value)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ProfileGridLayout.EdgePadding.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically)) {
                        Label("من يشاهد؟", 34, bold = true)
                        Label("لكل ملف قائمته وسجل مشاهدته الخاص", 15, Muted)
                        // A null entry stands for the "add profile" tile.
                        val entries: List<Profile?> = profiles + if (profiles.size < ProfileList.MAX && !managing) listOf(null) else emptyList()
                        entries.chunked(perRow).forEachIndexed { rowIndex, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(ProfileGridLayout.Gap.dp), verticalAlignment = Alignment.Top) {
                                row.forEachIndexed { column, profile ->
                                    val first = if (rowIndex == 0 && column == 0) Modifier.focusRequester(firstFocus) else Modifier
                                    if (profile == null) AddTile { name = ""; adding = true }
                                    else ProfileTile(profile, removable = managing && profile.id != ProfileList.MAIN, modifier = first) {
                                        if (!managing) onChosen(profile)
                                        else if (profile.id != ProfileList.MAIN) { store.remove(profile.id); profiles = store.profiles() }
                                    }
                                }
                            }
                        }
                        Action(if (managing) "تم" else "إدارة الملفات") { managing = !managing }
                    }
                    }
                    if (adding) AlertDialog(
                        onDismissRequest = { adding = false },
                        containerColor = GlassStrong,
                        title = { Label("ملف جديد", 20, bold = true) },
                        text = {
                            OutlinedTextField(name, { name = it.take(16) }, singleLine = true,
                                label = { Label("الاسم") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                        },
                        confirmButton = {
                            TextButton(enabled = name.isNotBlank(), onClick = {
                                store.add(name)?.let { profiles = store.profiles() }
                                adding = false
                            }) { Label("إضافة", 15, bold = true) }
                        },
                        dismissButton = { TextButton(onClick = { adding = false }) { Label("إلغاء", 15) } })
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile, removable: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(stiffness = 400f), label = "Profile focus")
    Column(modifier.width(ProfileGridLayout.TileWidth.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(Color(profile.color))
            // A zero-width border would still draw a hairline, so the ring is only added while focused.
            .then(if (focused) Modifier.border(4.dp, Color.White, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            Label(profile.name.first().toString(), 44, OnAccent, true)
        }
        Label(profile.name, 17, if (focused) Color.White else Muted, true, lines = 1, modifier = Modifier.widthIn(max = (ProfileGridLayout.TileWidth - 16).dp))
        if (removable) Label("حذف", 14, Signal, true)
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(stiffness = 400f), label = "Add focus")
    Column(Modifier.width(ProfileGridLayout.TileWidth.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(Panel)
            .border(if (focused) 4.dp else 2.dp, if (focused) Color.White else InputBorder, CircleShape), contentAlignment = Alignment.Center) {
            Label("+", 44, if (focused) Color.White else Muted, true)
        }
        Label("إضافة ملف", 17, if (focused) Color.White else Muted, true, lines = 1)
    }
}
