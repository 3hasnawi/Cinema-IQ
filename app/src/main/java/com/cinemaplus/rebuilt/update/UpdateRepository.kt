package com.cinemaplus.rebuilt.update

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class UpdateRepository {
    private val client = com.cinemaplus.rebuilt.data.Http.client(12, 30, 180)

    suspend fun latest(): UpdateManifest? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${UpdateManifest.REPOSITORY}/releases/latest"
        val release = client.newCall(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Cinema-iQ-Android").build())
            .execute().use { response ->
                if (response.code == 404) return@withContext null
                check(response.isSuccessful) { "تعذّر فحص التحديث (${response.code})." }
                JSONObject(response.body?.string() ?: error("استجابة تحديث فارغة."))
            }
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null
        val assets = release.getJSONArray("assets")
        val manifestUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "update.json" }?.optString("browser_download_url")
            ?: return@withContext null
        check(UpdateManifest.allowedAsset(manifestUrl)) { "مصدر تحديث غير موثوق." }
        client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { response ->
            check(response.isSuccessful) { "تعذّر تحميل معلومات التحديث." }
            UpdateManifest.parse(JSONObject(response.body?.string() ?: error("معلومات تحديث فارغة.")))
        }
    }

    suspend fun download(update: UpdateManifest, directory: File, progress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val file = File(directory, "Cinema-iQ-${update.versionCode}.apk")
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { "تعذّر تنزيل التحديث (${response.code})." }
                    val body = response.body ?: error("ملف تحديث فارغ.")
                    val total = body.contentLength()
                    check(total <= 256L * 1024 * 1024) { "حجم تحديث غير صالح." }
                    var received = 0L; var lastPercent = -1
                    body.byteStream().use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            received += count
                            check(received <= 256L * 1024 * 1024) { "حجم تحديث غير صالح." }
                            hash.update(buffer, 0, count); output.write(buffer, 0, count)
                            val percent = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (percent != lastPercent) { progress(percent); lastPercent = percent }
                        }
                    } }
                    check(received > 0 && (total < 0 || received == total)) { "ملف تحديث غير مكتمل." }
                }
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                check(actual == update.sha256) { "فشل التحقق من سلامة التحديث." }
                file
            } catch (e: Exception) { file.delete(); throw e }
        }
}
