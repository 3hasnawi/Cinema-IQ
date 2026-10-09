package com.cinemaplus.rebuilt.cloud

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cinemaplus.rebuilt.CinemaViewModel

@Composable
fun CloudProfilesEffect(uid: String?) {
    val context = LocalContext.current
    DisposableEffect(uid) {
        val sync = uid?.let { CloudProfileSync(context).also { it.start() } }
        onDispose { sync?.close() }
    }
}

@Composable
fun CloudLibraryEffect(uid: String?, vm: CinemaViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(uid, vm, lifecycle) {
        val sync = uid?.let { CloudLibrarySync(context, vm.library.profileId) {
            vm.library.cloudChanged()
            vm.libraryRevision++
            vm.refreshProfile()
            vm.syncWatchNext()
        }.also { it.start() } }
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) sync?.flush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sync?.close() }
    }
}
