package com.cinemaplus.rebuilt.cloud

import android.content.Context
import androidx.compose.runtime.*
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Separate from the content providers; source credentials never enter Firebase. */
object CloudAccount {
    var uid by mutableStateOf<String?>(null); private set
    var email by mutableStateOf<String?>(null); private set
    var verified by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    private lateinit var auth: FirebaseAuth
    fun init(context: Context) {
        if(ready) return
        val json = JSONObject(context.assets.open("firebase-config.json").bufferedReader().use { it.readText() })
        val client = json.getJSONArray("client").let { clients ->
            (0 until clients.length()).map { clients.getJSONObject(it) }.first {
                it.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name") == context.packageName
            }
        }
        val options = FirebaseOptions.Builder()
            .setApplicationId(client.getJSONObject("client_info").getString("mobilesdk_app_id"))
            .setApiKey(client.getJSONArray("api_key").getJSONObject(0).getString("current_key"))
            .setProjectId(json.getJSONObject("project_info").getString("project_id"))
            .build()
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == "cinema-cloud" }
            ?: FirebaseApp.initializeApp(context, options, "cinema-cloud")
        auth = FirebaseAuth.getInstance(app)
        auth.addAuthStateListener { update() }
        update(); ready = true
    }
    val app: FirebaseApp get() = auth.app
    private fun update() {
        val user = auth.currentUser
        // Unverified users cannot start cloud sync.
        email = user?.email; verified = user?.isEmailVerified == true
        uid = user?.uid?.takeIf { verified }
    }
    suspend fun login(email: String, password: String) { auth.signInWithEmailAndPassword(email.trim(), password).await(); update() }
    suspend fun register(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
        auth.currentUser?.sendEmailVerification()?.await(); update()
    }
    suspend fun verifyAgain() { auth.currentUser?.reload()?.await(); auth.currentUser?.getIdToken(true)?.await(); update() }
    suspend fun resendVerification() { auth.currentUser?.sendEmailVerification()?.await() }
    suspend fun reset(email: String) { auth.sendPasswordResetEmail(email.trim()).await() }
    fun logout() { auth.signOut(); update() }
    fun prefsName(base: String): String = uid?.let { "${base}_cloud_$it" } ?: base
}
