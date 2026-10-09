package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test

class HttpCancellationTest {
    private class FakeCall : Call {
        lateinit var callback: Callback
        var cancelled = false
        override fun request() = Request.Builder().url("https://example.com/").build()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = error("Not used")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }
    private class TrackedBody : ResponseBody() {
        var closed = false
        private val buffered = object : ForwardingSource(Buffer().writeUtf8("ok")) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = 2L
        override fun source() = buffered
    }
    private fun response(call: Call, body: ResponseBody) = Response.Builder()
        .request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    @Test fun cancellationAfterResumeClosesUndeliveredResponse() = runBlocking {
        val call = FakeCall()
        val body = TrackedBody()
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.executeCancellable().use { delivered = true }
        }
        // Resume is queued on runBlocking; cancel before the caller can receive the response.
        call.callback.onResponse(call, response(call, body))
        job.cancel()
        job.join()
        assertFalse(delivered)
        assertTrue(call.cancelled)
        assertTrue(body.closed)
    }
    @Test fun lateResponseAfterCancellationIsClosed() = runBlocking {
        val call = FakeCall()
        val body = TrackedBody()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.executeCancellable().use { } }
        job.cancel()
        call.callback.onResponse(call, response(call, body))
        job.join()
        assertTrue(body.closed)
    }
    @Test fun successfulResponseRemainsOpenUntilCallerClosesIt() = runBlocking {
        val call = FakeCall()
        val body = TrackedBody()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.executeCancellable().use { assertFalse(body.closed) }
        }
        call.callback.onResponse(call, response(call, body))
        job.join()
        assertTrue(body.closed)
        assertFalse(call.cancelled)
    }
}
