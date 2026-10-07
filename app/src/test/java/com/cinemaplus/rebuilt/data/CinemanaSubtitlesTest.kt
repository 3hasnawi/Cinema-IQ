package com.cinemaplus.rebuilt.data

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CinemanaSubtitlesTest {
    @Test fun validatesRealResponsesAndFallsBackWithoutRemovingOtherLanguages() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val responses = mapOf(
            "/broken.vtt" to "<html>Not a subtitle</html>",
            "/arabic.srt" to "1\n00:00:01,000 --> 00:00:03,000\nمرحبا\n",
            "/english.vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello\n")
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val bytes = responses[path].orEmpty().toByteArray(Charsets.UTF_8)
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush()
                        }
                    }
                } catch (_: java.io.IOException) { if (server.isClosed) break }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val resolver = CinemanaSubtitles()
            val choices = resolver.resolve(listOf(
                Subtitle("$base/broken.vtt", "ar", "العربية · 1"),
                Subtitle("$base/arabic.srt", "ar", "العربية · 2"),
                Subtitle("$base/english.vtt", "en", "الإنجليزية"),
                Subtitle("$base/english.vtt", "en", "الإنجليزية")))
            assertEquals(2, choices.size)
            assertEquals("application/x-subrip", choices.first { it.language == "ar" }.mimeType)
            assertEquals("العربية", choices.first { it.language == "ar" }.title)
            assertEquals("text/vtt", choices.first { it.language == "en" }.mimeType)
            assertEquals(choices, resolver.resolve(choices))
        } finally { server.close(); worker.join(1000) }
    }
}
