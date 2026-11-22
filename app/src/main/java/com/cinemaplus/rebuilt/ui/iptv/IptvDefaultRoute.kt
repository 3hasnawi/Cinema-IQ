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
    val lock = Any()
    var current: Network? = null
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                current = network
                trySend(true)
            }
        }
        override fun onLost(network: Network) {
            synchronized(lock) {
                if (current == network) {
                    current = null
                    trySend(false)
                }
            }
        }
    }
    var registered = false
    try {
        synchronized(lock) {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            current = manager.activeNetwork
            trySend(current != null)
        }
    } catch (_: RuntimeException) {
        if (registered) manager.unregisterNetworkCallback(callback)
        // If route monitoring is unavailable, continue with timed provider retries.
        trySend(true)
        close()
        return@callbackFlow
    }
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
