package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkErrorsTest {
    @Test fun httpStatusesGetHonestArabicMessages() {
        assertTrue(NetworkErrors.http(401).contains("سجّل الدخول"))
        assertTrue(NetworkErrors.http(503).contains("لا يستجيب"))
        assertTrue(NetworkErrors.http(429).contains("انتظر"))
        assertFalse(NetworkErrors.http(500).contains("الشبكة المحلية"))
    }

    @Test fun rawLibraryTextIsReplacedButOurArabicMessagesPass() {
        assertEquals("fallback", NetworkErrors.message(IOException("unexpected end of stream"), "fallback"))
        assertEquals("استجابة فارغة", NetworkErrors.message(IllegalStateException("استجابة فارغة"), "fallback"))
        assertTrue(NetworkErrors.message(UnknownHostException("host"), "f").contains("الإنترنت"))
        assertTrue(NetworkErrors.message(SocketTimeoutException(), "f").contains("مهلة"))
        assertTrue(NetworkErrors.message(SSLHandshakeException("x"), "f").contains("تاريخ"))
        assertEquals(NetworkErrors.http(404, "VODU"), NetworkErrors.message(SourceHttpException(404, "VODU"), "f"))
    }

    @Test fun onlyTransientFailuresAreRetried() {
        assertTrue(NetworkErrors.shouldRetry(SocketTimeoutException()))
        assertTrue(NetworkErrors.shouldRetry(ConnectException()))
        assertTrue(NetworkErrors.shouldRetry(SourceHttpException(502)))
        assertFalse(NetworkErrors.shouldRetry(SourceHttpException(404)))
        assertFalse(NetworkErrors.shouldRetry(UnknownHostException()))
        assertFalse(NetworkErrors.shouldRetry(SSLHandshakeException("x")))
    }

    @Test fun retryOnceTriesASecondTimeForTransientFailureOnly() = runBlocking {
        var calls = 0
        val value = NetworkErrors.retryOnce { if (++calls == 1) throw SocketTimeoutException() else "ok" }
        assertEquals("ok", value)
        assertEquals(2, calls)
        calls = 0
        try { NetworkErrors.retryOnce<String> { calls++; throw SourceHttpException(404) }; fail() }
        catch (e: SourceHttpException) { assertEquals(1, calls) }
    }
}
