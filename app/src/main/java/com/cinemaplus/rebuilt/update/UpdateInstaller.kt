package com.cinemaplus.rebuilt.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import com.cinemaplus.rebuilt.BuildConfig

object UpdateInstaller {
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        return signatures.map { signature -> java.security.MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, update: UpdateManifest) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("ملف APK غير صالح.")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(archive.packageName == context.packageName && code == update.versionCode.toLong() && code > BuildConfig.VERSION_CODE) {
            "حزمة التحديث أو رقم الإصدار غير مطابق."
        }
        check(signatures(archive).isNotEmpty() && signatures(archive) == signatures(installed)) {
            "توقيع التحديث لا يطابق التطبيق المثبّت."
        }
    }
    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
