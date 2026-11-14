package com.cinemaplus.rebuilt.data.iptv

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.UUID

class IptvException(val reason: Reason, val status: Int? = null) : Exception(reason.name) {
    enum class Reason { InvalidUrl, HttpConsent, Credentials, InvalidServer, InvalidPlaylist, InvalidResponse,
        Inactive, NoChannels, TooLarge, Redirect, Http, Network, Storage, SourceLimit }
    fun arabic(): String = when (reason) {
        Reason.InvalidUrl -> "أدخل رابط HTTP أو HTTPS صالحًا، دون بيانات دخول في اسم الخادم."
        Reason.HttpConsent -> "فعّل السماح باتصال HTTP لهذا المصدر أو استخدم HTTPS."
        Reason.Credentials -> "تحقق من اسم المستخدم وكلمة المرور للاشتراك."
        Reason.InvalidServer -> "أدخل عنوان خادم Xtream فقط، دون get.php أو player_api.php أو معاملات الرابط."
        Reason.InvalidPlaylist -> "الرابط لا يحتوي قائمة M3U صالحة."
        Reason.InvalidResponse -> "أعاد المصدر بيانات غير صالحة. تحقق من عنوان الخادم."
        Reason.Inactive -> "الاشتراك غير مفعّل أو منتهي. راجع مزوّد الاشتراك."
        Reason.NoChannels -> "لم يوفّر المصدر قنوات بروابط متوافقة. تحقق أيضًا من خيار HTTP."
        Reason.TooLarge -> "قائمة القنوات أكبر من الحد المدعوم. استخدم قائمة أصغر."
        Reason.Redirect -> "نقل المصدر الطلب إلى خادم مختلف. استخدم رابط القائمة المباشر."
        Reason.Http -> when (status) {
            401, 403 -> "رفض المصدر الاتصال. تحقق من الاشتراك والشبكة."
            404 -> "لم يجد المصدر قائمة القنوات. تحقق من الرابط."
            429 -> "المصدر يطلب الانتظار لكثرة الطلبات. أعد المحاولة لاحقًا."
            else -> "تعذّر الاتصال بخادم IPTV. أعد المحاولة بعد قليل."
        }
        Reason.Network -> "تعذّر الاتصال بمصدر IPTV. تحقق من الشبكة والاتصال الآمن."
        Reason.Storage -> "تعذّر فتح مخزن الاشتراكات الآمن. لم تُحذف بياناتك."
        Reason.SourceLimit -> "الحد الأقصى 20 مصدرًا. احذف مصدرًا قبل إضافة غيره."
    }
}

fun iptvMessage(error: Throwable): String = (error as? IptvException)?.arabic()
    ?: "تعذّر إكمال العملية. تحقق من المصدر والشبكة وأعد المحاولة."

enum class IptvKind { M3U, Xtream }
enum class IptvOutput(val extension: String) { Hls("m3u8"), TransportStream("ts") }

/** Only encrypted device-local storage serializes these credentials. Never log this object or URLs. */
data class IptvSource(
    val id: String = UUID.randomUUID().toString(), val name: String, val kind: IptvKind,
    val address: String, val username: String = "", val password: String = "",
    val allowHttp: Boolean = false, val output: IptvOutput = IptvOutput.Hls
) {
    fun validated(): IptvSource {
        if (id.isBlank() || id.length > 100 || address.length > 8192 || username.length > 1024 || password.length > 4096)
            throw IptvException(IptvException.Reason.InvalidUrl)
        val url = IptvUrls.validate(address, allowHttp)
        if (kind == IptvKind.Xtream) {
            if (url.query != null || url.pathSegments.last().lowercase() in listOf("get.php", "player_api.php"))
                throw IptvException(IptvException.Reason.InvalidServer)
            if (username.isBlank() || password.isEmpty() || username in listOf(".", "..") || password in listOf(".", "..")) throw IptvException(IptvException.Reason.Credentials)
        }
        return copy(name = name.trim().take(100).ifEmpty { if (kind == IptvKind.M3U) "قائمة قنوات" else "اشتراك Xtream" },
            address = url.toString())
    }
    override fun toString(): String = "IptvSource(id=$id, kind=$kind)"
}

data class IptvChannel(val id: String, val sourceId: String, val name: String, val group: String,
    val url: String, val logo: String? = null, val tvgId: String = "") {
    override fun toString(): String = "IptvChannel(id=$id)"
}
data class IptvCatalog(val channels: List<IptvChannel>) {
    val groups: List<String> get() = channels.map { it.group }.distinct().sorted()
}

object IptvUrls {
    fun validate(raw: String, allowHttp: Boolean): HttpUrl {
        if (raw.length > 8192) throw IptvException(IptvException.Reason.InvalidUrl)
        val url = raw.trim().toHttpUrlOrNull() ?: throw IptvException(IptvException.Reason.InvalidUrl)
        if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() || url.fragment != null)
            throw IptvException(IptvException.Reason.InvalidUrl)
        if (!url.isHttps && !allowHttp) throw IptvException(IptvException.Reason.HttpConsent)
        return url
    }
    fun resolve(raw: String, base: HttpUrl, allowHttp: Boolean): String? {
        if (raw.isBlank() || raw.length > 8192) return null
        val url = base.resolve(raw.trim()) ?: return null
        return runCatching { validate(url.toString(), allowHttp).toString() }.getOrNull()
    }
    fun sameOrigin(from: HttpUrl, to: HttpUrl): Boolean = from.host == to.host &&
        ((from.scheme == to.scheme && from.port == to.port) || (!from.isHttps && to.isHttps && to.port == 443))
    fun channelId(source: String, metadata: String): String = source + ":" +
        MessageDigest.getInstance("SHA-256").digest(metadata.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
}
