package com.cinemaplus.rebuilt.data.iptv

enum class IptvFailure { Temporary, Access, RateLimited, Unsupported, HttpConsent }
data class IptvRetry(val delayMillis: Long, val refreshLink: Boolean)

/** Retry transient live-stream failures without a modal dialog or a tight request loop. */
object IptvReconnectPolicy {
    fun retry(failure: IptvFailure, attempts: Int, retryAfterMillis: Long = 0): IptvRetry? {
        if (failure == IptvFailure.Unsupported || failure == IptvFailure.HttpConsent) return null
        val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
        val delay = delays[attempts.coerceIn(0, delays.lastIndex)]
        return IptvRetry(maxOf(delay, retryAfterMillis.coerceAtLeast(0),
            if (failure == IptvFailure.RateLimited) 30_000 else 0), (failure == IptvFailure.Access && attempts > 0) || (failure == IptvFailure.Temporary && attempts == 2))
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
