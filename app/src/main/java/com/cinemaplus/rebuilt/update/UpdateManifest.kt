package com.cinemaplus.rebuilt.update

import java.net.URI
import org.json.JSONObject

/** Only explicit version codes and the project's own public release assets are accepted. */
data class UpdateManifest(val versionCode: Int, val versionName: String, val apkUrl: String,
    val sha256: String, val minSdk: Int, val notes: String) {
    companion object {
        const val REPOSITORY = "3hasnawi/Cinema-IQ-Releases"
        fun parse(json: JSONObject): UpdateManifest {
            require(json.getString("packageName") == "com.cinemaplus.rebuilt") { "حزمة تحديث غير مطابقة." }
            val code = json.getInt("versionCode"); require(code > 0)
            val name = json.getString("versionName"); require(name.isNotBlank())
            val url = json.getString("apkUrl"); require(allowedAsset(url) && URI(url).path.endsWith(".apk")) { "رابط تحديث غير موثوق." }
            val hash = json.getString("sha256").lowercase()
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "بصمة تحديث غير صالحة." }
            val minSdk = json.getInt("minSdk"); require(minSdk >= 24)
            return UpdateManifest(code, name, url, hash, minSdk, json.optString("releaseNotes"))
        }
        fun allowedAsset(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.query == null && uri.fragment == null &&
                uri.path.startsWith("/$REPOSITORY/releases/download/") &&
                uri.path.split('/').none { it == ".." }
        }.getOrDefault(false)
    }
}
