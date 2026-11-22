package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class IptvFailure { Temporary, Access, Authentication, RateLimited, Unsupported, HttpConsent }
data class IptvRetry(val delayMillis: Long, val refreshLink: Boolean)

/** A channel can be tuned again while an earlier renewal is still completing. */
internal class IptvPendingReconnect(channel: IptvChannel, private val generation: Int) {
    private val id = channel.id
    private val sourceId = channel.sourceId

    fun matches(current: IptvChannel, currentGeneration: Int): Boolean =
        currentGeneration == generation && current.id == id && current.sourceId == sourceId
}

/** Retry transient live-stream failures without a modal dialog or a tight request loop. */
object IptvReconnectPolicy {
    /** TV may renew its own expired link once; rejected credentials must not loop forever. */
    fun televisionRetry(failure: IptvFailure, attempts: Int, retryAfterMillis: Long = 0, linkRenewed: Boolean = false): IptvRetry? {
        if (failure == IptvFailure.Authentication) return if (linkRenewed) null else
            retry(IptvFailure.Temporary, attempts, retryAfterMillis)?.copy(refreshLink = true)
        return retry(failure, attempts, retryAfterMillis)
    }
    fun retry(failure: IptvFailure, attempts: Int, retryAfterMillis: Long = 0): IptvRetry? {
        if (failure == IptvFailure.Unsupported || failure == IptvFailure.HttpConsent) return null
        val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
        val delay = delays[attempts.coerceIn(0, delays.lastIndex)]
        return IptvRetry(maxOf(delay, retryAfterMillis.coerceAtLeast(0),
            if (failure == IptvFailure.RateLimited) 30_000 else 0), (failure == IptvFailure.Access && attempts > 0) || (failure == IptvFailure.Temporary && attempts == 2))
    }
    fun retryableRenewal(error: IptvException): Boolean =
        error.reason == IptvException.Reason.Network || (error.reason == IptvException.Reason.Http &&
            (error.status == 408 || error.status == 429 || error.status in 500..599))

    /** A restored route can wake a transport retry, but never shorten a server cooldown. */
    suspend fun awaitTvRoute(waitMillis: Long, expediteOnReturn: Boolean, route: Flow<Boolean>) {
        if (expediteOnReturn) {
            withTimeoutOrNull(waitMillis) { route.first { !it } }
        } else delay(waitMillis)
        route.first { it }
    }

    fun retryAfter(value: String, now: Long): Long {
        val seconds = value.trim().toLongOrNull()
        if (seconds != null) return seconds.coerceIn(0, 86_400) * 1000
        return runCatching {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.isLenient = false
            ((format.parse(value)?.time ?: now) - now).coerceIn(0, 86_400_000)
        }.getOrDefault(0)
    }
    fun allowed(foreground: Boolean, pausedByUser: Boolean) = foreground && !pausedByUser
}
