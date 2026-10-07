package com.cinemaplus.rebuilt.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import com.cinemaplus.rebuilt.data.Subtitle
import com.cinemaplus.rebuilt.MainActivity
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Stream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Runs only from the test APK; no test video or launch hook is shipped in the application. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PlayerSmokeInstrumentation : Instrumentation() {
    private var targetActivity: MainActivity? = null
    private val composeRoots = mutableSetOf<ViewRootForTest>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val previousRootCallback = ViewRootForTest.onViewCreatedCallback
        runOnMainSync { ViewRootForTest.onViewCreatedCallback = { root -> composeRoots.add(root); previousRootCallback?.invoke(root) } }
        val checks = JSONArray()
        val prefs = targetContext.getSharedPreferences("cinema_library", Context.MODE_PRIVATE)
        val backup = prefs.all.toMap()
        val output = File(targetContext.getExternalFilesDir(null), "player-smoke").apply { mkdirs() }
        var activity: MainActivity? = null
        val result = Bundle()
        fun record(name: String) { checks.put(JSONObject().put("name", name).put("passed", true)) }
        fun waitUntil(name: String, timeout: Long = 45_000, predicate: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeout
            while(SystemClock.uptimeMillis() < end) {
                if(predicate()) { record(name); return }
                Thread.sleep(250)
            }
            throw AssertionError("Timed out: $name; Media3: ${mediaState()}; visible semantics: ${visibleText()}")
        }
        fun screenshot(name: String) {
            val bitmap = uiAutomation.takeScreenshot() ?: return
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val status = Bundle().apply {
            putString("id", "PlayerSmoke"); putString("class", javaClass.name)
            putString("test", "remoteTransportWithRealMedia3"); putInt("numtests", 1); putInt("current", 1)
        }
        sendStatus(1, status)
        try {
            prefs.edit().putBoolean("demo", true).commit()
            val video = File(targetContext.cacheDir, "player-smoke.mp4")
            context.assets.open("player-smoke.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            val subtitleFiles = listOf("player-ar.vtt", "player-en.vtt").map { name ->
                File(targetContext.cacheDir, name).also { file -> context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } } }
            }
            activity = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            targetActivity = activity
            runOnMainSync {
                val vm = CinemaViewModel(activity!!.application)
                vm.playing = Film("smoke-fixture", "اختبار مشغّل التلفزيون", subtitles = listOf(
                    Subtitle(android.net.Uri.fromFile(subtitleFiles[0]).toString(), "ar", "العربية"),
                    Subtitle(android.net.Uri.fromFile(subtitleFiles[1]).toString(), "en", "الإنجليزية")))
                vm.streams = listOf(Stream(android.net.Uri.fromFile(video).toString(), "480p"))
                activity!!.setContent { CinemaApp(vm) }
            }
            waitUntil("Real Media3 video starts", predicate = { mediaIsPlaying() })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("Compose controller is visible", predicate = { visibleText().contains("إيقاف مؤقت") })
            screenshot("player-controls")
            waitUntil("Controller and title auto-hide after inactivity", 10_000) {
                !visibleText().contains("اختبار مشغّل التلفزيون") && !visibleText().contains("إيقاف مؤقت")
            }
            screenshot("player-hidden")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Hidden Center reveals and pauses exactly once") {
                val text = visibleText(); text.contains("اختبار مشغّل التلفزيون") && text.contains("تشغيل") && !text.contains("إيقاف مؤقت")
            }
            // Wait beyond the timeout; a paused player must retain its controls.
            Thread.sleep(3500)
            if(!visibleText().contains("تشغيل")) throw AssertionError("Paused controls were hidden or Select toggled twice")
            record("Paused controls remain visible; Select key-up does not toggle again")
            screenshot("player-paused")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Visible focused Play resumes playback", predicate = { visibleText().contains("إيقاف مؤقت") })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            waitUntil("D-pad Up reaches the seek bar", predicate = { visibleText().contains("اختر موضع المشاهدة") })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitUntil("Seek preview exposes the target timestamp", predicate = { visibleText().contains("انتقال إلى") })
            screenshot("player-seek")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Seek confirmation restores transport focus", predicate = { !visibleText().contains("انتقال إلى") && visibleText().contains("إيقاف مؤقت") })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            // Hardware media keys must bypass whichever controller item currently owns focus.
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            waitUntil("Hardware Play/Pause works from another focused control", predicate = { visibleText().contains("تشغيل") && !visibleText().contains("إيقاف مؤقت") })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Track dialog exposes real Arabic and English subtitle tracks") {
                val text = visibleText(); text.contains("العربية") && text.contains("الإنجليزية") && text.contains("إيقاف الترجمة")
            }
            screenshot("player-track-dialog")
            Thread.sleep(250)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Subtitle selection closes the dialog without losing transport") { !visibleText().contains("إيقاف الترجمة") }
            Thread.sleep(750)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("English subtitle selection is confirmed by actual Media3 tracks") { visibleText().contains("✓ الإنجليزية") }
            screenshot("player-english-selected")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            result.putString("stream", "Media3 remote smoke passed: ${checks.length()} checks\n")
            status.putString("stream", result.getString("stream")); sendStatus(0, status)
            File(output, "results.json").writeText(JSONObject().put("passed", true).put("checks", checks)
                .put("fixture", "Locally generated test pattern; no live service request").toString(2))
        } catch(error: Throwable) {
            screenshot("player-failure")
            status.putString("stack", error.stackTraceToString()); sendStatus(-2, status)
            result.putString("stream", error.stackTraceToString())
            File(output, "results.json").writeText(JSONObject().put("passed", false).put("checks", checks)
                .put("error", error.stackTraceToString()).toString(2))
        } finally {
            runOnMainSync { activity?.setContent { }; activity?.finish() }
            Thread.sleep(500)
            waitForIdleSync()
            // Restore the complete preference snapshot after the player's disposal saves progress.
            val editor = prefs.edit().clear()
            backup.forEach { (key, value) -> when(value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
            } }
            editor.commit()
            runOnMainSync { ViewRootForTest.onViewCreatedCallback = previousRootCallback }
            finish(Activity.RESULT_OK, result)
        }
    }

    private fun visibleText(): String {
        val output = StringBuilder()
        runOnMainSync {
            fun walk(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { output.append(it.text).append('\n') }
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { output.append(it).append('\n') }
                node.children.forEach(::walk)
            }
            composeRoots.filter { it.view.isAttachedToWindow && it.view.isShown }
                .forEach { walk(it.semanticsOwner.rootSemanticsNode) }
        }
        return output.toString()
    }

    private fun mediaState(): String {
        var result = "no player"
        runOnMainSync {
            fun views(view: View) {
                if(view is PlayerView) view.player?.let { result = "playing=${it.isPlaying}, ready=${it.playWhenReady}, state=${it.playbackState}, position=${it.currentPosition}, error=${it.playerError}" }
                if(view is ViewGroup) for(i in 0 until view.childCount) views(view.getChildAt(i))
            }
            targetActivity?.window?.decorView?.let(::views)
        }
        return result
    }

    private fun mediaIsPlaying(): Boolean {
        var playing = false
        runOnMainSync {
            fun views(view: View) {
                if(view is PlayerView) playing = view.player?.isPlaying == true
                if(view is ViewGroup) for(i in 0 until view.childCount) views(view.getChildAt(i))
            }
            targetActivity?.window?.decorView?.let(::views)
        }
        return playing
    }
}
