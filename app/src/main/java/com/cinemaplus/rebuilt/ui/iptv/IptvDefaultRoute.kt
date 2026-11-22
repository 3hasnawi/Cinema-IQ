package com.cinemaplus.rebuilt.ui.iptv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** A provider on the local network can work without Android's validated-internet capability. */
internal fun iptvDefaultRoute(context: Context) = callbackFlow<Boolean> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
        trySend(true)
        close()
        return@callbackFlow
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(true) }
        override fun onLost(network: Network) { trySend(manager.activeNetwork != null) }
    }
    try {
        manager.registerDefaultNetworkCallback(callback)
    } catch (_: RuntimeException) {
        // If route monitoring is unavailable, continue with timed provider retries.
        trySend(true)
        close()
        return@callbackFlow
    }
    trySend(manager.activeNetwork != null)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
