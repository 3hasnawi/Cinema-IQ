package com.cinemaplus.rebuilt.data.iptv

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IptvNetworkTest {
    @Test fun realHttpTransportRequiresConsentFollowsSafeRedirectsAndSurfacesStatuses() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/list") { exchange ->
            val body = "#EXTM3U\n#EXTINF:-1,News\nhttps://media.example/news.m3u8\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { it.responseHeaders.add("Location", "/list"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/other") { it.responseHeaders.add("Location", "http://localhost:${server.address.port}/list"); it.sendResponseHeaders(302, -1); it.close() }
        for (code in listOf(403, 404, 429, 500)) server.createContext("/code$code") { it.sendResponseHeaders(code, -1); it.close() }
        server.start()
        val network = IptvNetworkTransport()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            try { network.get("$base/list", false); fail() } catch (e: IptvException) { assertEquals(IptvException.Reason.HttpConsent, e.reason) }
            val data = network.get("$base/redirect", true)
            assertTrue(data.toString(Charsets.UTF_8).startsWith("#EXTM3U"))
            try { network.get("$base/other", true); fail() } catch (e: IptvException) { assertEquals(IptvException.Reason.Redirect, e.reason) }
            for (code in listOf(403, 404, 429, 500)) {
                try { network.get("$base/code$code", true); fail() } catch (e: IptvException) { assertEquals(code, e.status) }
            }
        } finally { network.close(); server.stop(0) }
    }
    @Test fun cancellationStopsWaitingForAResponse() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool(); server.executor = pool
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.createContext("/slow") { exchange -> entered.countDown(); release.await(5, TimeUnit.SECONDS); exchange.close() }
        server.start(); val network = IptvNetworkTransport()
        try {
            val request = async(Dispatchers.IO) { network.get("http://127.0.0.1:${server.address.port}/slow", true) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        } finally { release.countDown(); network.close(); server.stop(0); pool.shutdownNow() }
    }
}
