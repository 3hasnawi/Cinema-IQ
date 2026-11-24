package com.cinemaplus.rebuilt.ui.mobile

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

internal enum class InterfaceMode(val label: String) {
    Automatic("تلقائي حسب الجهاز"),
    Television("واجهة التلفزيون"),
    Mobile("واجهة الموبايل");

    fun usesTelevision(uiMode: Int): Boolean = when (this) {
        Automatic -> (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        Television -> true
        Mobile -> false
    }
}

private const val InterfaceKey = "interface_mode"
private fun preferences(context: Context) =
    context.getSharedPreferences("cinema_device_interface", Context.MODE_PRIVATE)

private fun SharedPreferences.mode(): InterfaceMode {
    val saved = getString(InterfaceKey, null)
    return InterfaceMode.entries.firstOrNull { it.name == saved } ?: InterfaceMode.Automatic
}

internal fun setInterfaceMode(context: Context, mode: InterfaceMode) {
    preferences(context).edit().putString(InterfaceKey, mode.name).apply()
}

fun usesTelevisionInterface(context: Context): Boolean =
    preferences(context).mode().usesTelevision(context.resources.configuration.uiMode)

@Composable
internal fun currentInterfaceMode(): InterfaceMode {
    val context = LocalContext.current
    val prefs = remember(context) { preferences(context) }
    var mode by remember(prefs) { mutableStateOf(prefs.mode()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == InterfaceKey || key == null) mode = prefs.mode()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        mode = prefs.mode()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return mode
}

@Composable
internal fun currentTelevisionInterface(): Boolean =
    currentInterfaceMode().usesTelevision(LocalConfiguration.current.uiMode)
