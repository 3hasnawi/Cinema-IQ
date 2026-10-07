package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/** Publish each successful source immediately; preserve cancellation and final source ordering. */
suspend fun <T> progressiveSources(
    requests: List<suspend () -> T>,
    onReady: suspend (T) -> Unit
): List<Result<T>> = supervisorScope {
    requests.map { request -> async {
        val result = try { Result.success(request()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure<T>(e) }
        coroutineContext.ensureActive()
        result.getOrNull()?.let { onReady(it) }
        result
    } }.awaitAll()
}
