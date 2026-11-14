package com.cinemaplus.rebuilt.data.iptv

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IptvNetworkTest {
    @Test fun realHttpTransportRequiresConsentFollowsSafeRedirectsAndSurfacesStatuses() = runBlocking {
        val server = LoopbackServer { path, port -> when {
            path == "/redirect" -> Reply(302, location = "/list")
            path == "/other" -> Reply(302, location = "http://localhost:$port/list")
            path.startsWith("/code") -> Reply(path.removePrefix("/code").toInt())
            path == "/huge" -> Reply(200, length = IptvParsers.MAX_BYTES + 1)
            else -> Reply(200, "#EXTM3U\n#EXTINF:-1,News\nhttps://media.example/news.m3u8\n")
        } }
        val network = IptvNetworkTransport(); val base = "http://127.0.0.1:${server.port}"
        try {
            try { network.get("$base/list", false); fail() } catch (e: IptvException) { assertEquals(IptvException.Reason.HttpConsent, e.reason) }
            assertTrue(network.get("$base/redirect", true).toString(Charsets.UTF_8).startsWith("#EXTM3U"))
            try { network.get("$base/other", true); fail() } catch (e: IptvException) { assertEquals(IptvException.Reason.Redirect, e.reason) }
            try { network.get("$base/huge", true); fail() } catch (e: IptvException) { assertEquals(IptvException.Reason.TooLarge, e.reason) }
            for (code in listOf(403, 404, 429, 500)) {
                try { network.get("$base/code$code", true); fail() } catch (e: IptvException) { assertEquals(code, e.status) }
            }
        } finally { network.close(); server.close() }
    }
    @Test fun cancellationStopsWaitingForAResponse() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = LoopbackServer { _, _ -> entered.countDown(); release.await(5, TimeUnit.SECONDS); Reply(200) }
        val network = IptvNetworkTransport()
        try {
            val request = async(Dispatchers.IO) { network.get("http://127.0.0.1:${server.port}/slow", true) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        } finally { release.countDown(); network.close(); server.close() }
    }
    private data class Reply(val code: Int, val body: String = "", val location: String? = null, val length: Int? = null)
    /** Test-only loopback HTTP peer; no external server, Android framework or extra test dependency. */
    private class LoopbackServer(val handler: (String, Int) -> Reply) : Closeable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { r -> Thread(r, "iptv-loopback").apply { isDaemon = true } }
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        val port: Int get() = socket.localPort
        init { workers.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                clients.add(client)
                workers.execute {
                    try { client.use {
                        it.soTimeout = 5000
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: "/"
                        while (true) { val line = reader.readLine(); if (line == null || line.isEmpty()) break }
                        val reply = handler(path, it.localPort); val data = reply.body.toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 ${reply.code} Response\r\nConnection: close\r\nContent-Length: ${reply.length ?: data.size}\r\n" +
                            (reply.location?.let { location -> "Location: $location\r\n" } ?: "") + "\r\n"
                        it.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(data); flush() }
                    } } catch (_: Exception) { /* Cancellation closes the peer while the handler is waiting. */ }
                    finally { clients.remove(client) }
                }
            }
        } }
        override fun close() { socket.close(); clients.forEach { runCatching { it.close() } }; workers.shutdownNow() }
    }
}
