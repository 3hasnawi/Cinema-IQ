package com.cinemaplus.rebuilt.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private fun manifest() = JSONObject().put("packageName", "com.cinemaplus.rebuilt")
        .put("versionCode", 45).put("versionName", "4.3").put("minSdk", 24)
        .put("apkUrl", "https://github.com/3hasnawi/Cinema-IQ-Releases/releases/download/v4.3/Cinema-iQ.apk")
        .put("sha256", "a".repeat(64))
    @Test fun parsesRealAndroidVersionCodeAndTrustedAsset() {
        val update = UpdateManifest.parse(manifest())
        assertEquals(45, update.versionCode)
        assertEquals("4.3", update.versionName)
    }
    @Test fun rejectsWrongPackagesMissingChecksumsAndUnsafeHosts() {
        for (json in listOf(manifest().put("packageName", "other.package"),
            manifest().put("sha256", ""), manifest().put("apkUrl", "https://evil.example/app.apk"))) {
            assertTrue(runCatching { UpdateManifest.parse(json) }.isFailure)
        }
    }
    @Test fun rejectsOtherRepositoriesAndHttp() {
        assertFalse(UpdateManifest.allowedAsset("http://github.com/3hasnawi/Cinema-IQ-Releases/releases/download/v4.3/app.apk"))
        assertFalse(UpdateManifest.allowedAsset("https://github.com/other/app/releases/download/v4.3/app.apk"))
        assertFalse(UpdateManifest.allowedAsset("https://github.com@evil.example/3hasnawi/Cinema-IQ-Releases/releases/download/v4.3/app.apk"))
    }
    @Test fun rejectsTraversalAndInvalidVersions() {
        assertFalse(UpdateManifest.allowedAsset("https://github.com/3hasnawi/Cinema-IQ-Releases/releases/download/%2e%2e/x.apk"))
        assertTrue(runCatching { UpdateManifest.parse(manifest().put("versionCode", 0)) }.isFailure)
    }
}
