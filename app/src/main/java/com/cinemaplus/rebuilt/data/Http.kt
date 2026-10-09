package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One connection pool and dispatcher for every source, so TLS handshakes and sockets are reused across
 * the catalog, metadata and subtitle calls. Each caller keeps its own timeouts through [client].
 */
object Http {
    private val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 12 })
            .build()
    }

    /** A client sharing the common pool with its own timeouts (seconds; 0 keeps the default). */
    fun client(connect: Long = 12, read: Long = 20, call: Long = 30): OkHttpClient =
        shared.newBuilder().connectTimeout(connect, TimeUnit.SECONDS).readTimeout(read, TimeUnit.SECONDS)
            .apply { if (call > 0) callTimeout(call, TimeUnit.SECONDS) }.build()
}

/** Runs the call without blocking a thread, and cancels the network request when the coroutine is cancelled. */
suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            // The coroutine may be cancelled after resume but before it receives the resource.
            continuation.resume(response) { _, delivered, _ -> delivered.close() }
        }
    })
}
