package com.cinemaplus.rebuilt.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Source-compatible authentication; passwords are never persisted or logged. */
class SourceAccount(context: Context) {
    private val prefs = context.getSharedPreferences("source_account", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("cinema_iq_account", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("cinema_iq_account", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun token(): String? = runCatching {
        val value = prefs.getString("session", null) ?: return null
        val parts = value.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    private fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("session", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    private fun send(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        if(!response.isSuccessful) error("تعذّر الاتصال بخدمة الحساب (${response.code}).")
        val json = JSONObject(response.body?.string() ?: error("استجابة حساب فارغة"))
        if(json.has("error")) error("تعذّر تسجيل الدخول. تحقق من بيانات الحساب.")
        json
    }
    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotEmpty()) { "أدخل اسم المستخدم وكلمة المرور." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("username", username.trim()).addFormDataPart("password", password)
            .addFormDataPart("grant_type", "password").addFormDataPart("scope", "openid email offline_access earthlink.profile").build()
        val result = send(Request.Builder().url("https://account.shabakaty.cc/core/connect/token")
            .header("Authorization", Credentials.basic("Cinemana.Mobile.Id", "secret")).post(body).build())
        val access = result.optString("access_token").takeIf { it.isNotBlank() } ?: error("لم تُرجع الخدمة رمز دخول.")
        val profile = send(Request.Builder().url("https://cinemana.shabakaty.cc/api/android/login/")
            .header("Authorization", access).post(FormBody.Builder().build()).build())
        if(profile.optString("status") == "false") error("تعذّر ربط الحساب بسينمانا.")
        save(access)
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        token()?.let { send(Request.Builder().url("https://cinemana.shabakaty.cc/api/android/logout")
            .header("Authorization", it).post(FormBody.Builder().build()).build()) }
        prefs.edit().clear().apply()
    }
}
