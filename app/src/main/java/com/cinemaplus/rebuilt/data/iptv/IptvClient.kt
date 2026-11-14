package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface IptvTransport { suspend fun get(url: String, allowHttp: Boolean): ByteArray }

/** No cookie jar, disk cache, authenticator, URL logs or automatic cross-origin redirects. */
class IptvNetworkTransport : IptvTransport, java.io.Closeable {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    override fun close() { http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    private data class Reply(val status: Int, val bytes: ByteArray, val location: String?)
    override suspend fun get(url: String, allowHttp: Boolean): ByteArray {
        var current = IptvUrls.validate(url, allowHttp)
        repeat(6) {
            val reply = request(current)
            if (reply.status in listOf(301, 302, 303, 307, 308)) {
                val next = reply.location?.let { current.resolve(it) } ?: throw IptvException(IptvException.Reason.Redirect)
                IptvUrls.validate(next.toString(), allowHttp)
                if (!IptvUrls.sameOrigin(current, next)) throw IptvException(IptvException.Reason.Redirect)
                current = next
            } else {
                if (reply.status !in 200..299) throw IptvException(IptvException.Reason.Http, reply.status)
                return reply.bytes
            }
        }
        throw IptvException(IptvException.Reason.Redirect)
    }
    private suspend fun request(url: HttpUrl): Reply = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "Cinema-iQ-IPTV/1.0")
            .header("Accept", "application/json, application/x-mpegURL, audio/x-mpegurl, text/plain, */*").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IptvException(IptvException.Reason.Network))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val body = it.body
                        val bytes = if (it.code in 200..299 && body != null) {
                            if (body.contentLength() > IptvParsers.MAX_BYTES) throw IptvException(IptvException.Reason.TooLarge)
                            val output = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                            body.byteStream().use { input ->
                                while (true) {
                                    val count = input.read(chunk)
                                    if (count < 0) break
                                    if (output.size() + count > IptvParsers.MAX_BYTES) throw IptvException(IptvException.Reason.TooLarge)
                                    output.write(chunk, 0, count)
                                }
                            }
                            output.toByteArray()
                        } else ByteArray(0)
                        Reply(it.code, bytes, it.header("Location"))
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (e is IptvException) e else IptvException(IptvException.Reason.Network))
                }
            }
        })
    }
}

class IptvClient(private val transport: IptvTransport = IptvNetworkTransport()) : java.io.Closeable {
    override fun close() { (transport as? java.io.Closeable)?.close() }
    suspend fun load(raw: IptvSource): IptvCatalog = try { withTimeout(60_000) {
        val source = raw.validated()
        when (source.kind) {
            IptvKind.M3U -> IptvParsers.playlist(transport.get(source.address, source.allowHttp), source)
            IptvKind.Xtream -> {
                IptvParsers.authentication(transport.get(apiUrl(source), source.allowHttp))
                val groups = try { transport.get(apiUrl(source, "get_live_categories"), source.allowHttp) }
                    catch (e: CancellationException) { throw e }
                    catch (_: IptvException) { null }
                IptvParsers.xtream(transport.get(apiUrl(source, "get_live_streams"), source.allowHttp), groups, source)
            }
        }
    }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { throw IptvException(IptvException.Reason.Network) }
    companion object {
        fun apiUrl(source: IptvSource, action: String? = null): String {
            if (source.kind != IptvKind.Xtream) throw IptvException(IptvException.Reason.InvalidServer)
            return IptvUrls.validate(source.address, source.allowHttp).newBuilder().addPathSegment("player_api.php")
                .addQueryParameter("username", source.username).addQueryParameter("password", source.password)
                .apply { if (action != null) addQueryParameter("action", action) }.build().toString()
        }
        fun liveUrl(source: IptvSource, id: String): String {
            if (source.kind != IptvKind.Xtream || !id.matches(Regex("[1-9][0-9]{0,18}"))) throw IptvException(IptvException.Reason.InvalidResponse)
            return IptvUrls.validate(source.address, source.allowHttp).newBuilder().addPathSegment("live")
                .addPathSegment(source.username).addPathSegment(source.password).addPathSegment("$id.${source.output.extension}").build().toString()
        }
    }
}
