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
import com.cinemaplus.rebuilt.Page
import com.cinemaplus.rebuilt.data.Demo
import com.cinemaplus.rebuilt.data.Shelf
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
        val output = File(targetContext.filesDir, "player-smoke").apply { mkdirs() }
        var activity: MainActivity? = null
        var browseFailure: String? = null
        var playerVm: CinemaViewModel? = null
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
            File(output, "$name.json").writeText(JSONObject().put("focused", focusedText())
                .put("visible", visibleText()).toString(2))
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
            try { runBrowseChecks(activity!!, ::record, ::screenshot) }
            catch (error: Throwable) {
                browseFailure = error.stackTraceToString()
                screenshot("browse-failure")
            }
            runOnMainSync {
                val vm = CinemaViewModel(activity!!.application)
                playerVm = vm
                vm.selected = Film("demo-show", "اختبار مشغّل التلفزيون", series = true)
                vm.playing = Film("demo-smoke-fixture", "حلقة الاختبار", description = "وصف الحلقة لاختبار موضع المعلومات فوق شريط التقدم، مع بقاء الفيديو واضحاً خلفها.",
                    year = "2026", season = 2, episode = 3, episodeTitle = "رحلة الاختبار", contentQuality = "480p", subtitles = listOf(
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
            waitUntil("Paused overlay shows season, episode, synopsis and actual metadata") {
                val text = visibleText()
                text.contains("الموسم 2 · الحلقة 3") && text.contains("رحلة الاختبار") && text.contains("وصف الحلقة") && text.contains("2026")
            }
            assertPauseGeometry()
            record("Paused work title is upper right; episode information lower right above transport")
            screenshot("player-paused")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Visible focused Play resumes playback", predicate = { visibleText().contains("إيقاف مؤقت") })
            waitUntil("Paused metadata disappears on resume") { !visibleText().contains("وصف الحلقة") }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            waitUntil("D-pad Up reaches the seek bar", predicate = { visibleText().contains("اختر موضع المشاهدة") })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitUntil("Seek preview exposes the target timestamp", predicate = { visibleText().contains("انتقال إلى") })
            screenshot("player-seek")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Seek confirmation restores transport focus", predicate = { !visibleText().contains("انتقال إلى") && visibleText().contains("إيقاف مؤقت") })
            focusWithRemote("الترجمة والصوت", KeyEvent.KEYCODE_DPAD_RIGHT)
            record("Remote reaches subtitle/audio button from transport")
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
            focusWithRemote("480p", KeyEvent.KEYCODE_DPAD_LEFT)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Quality and speed panel opens from remote") { visibleText().contains("السرعة:") }
            screenshot("player-quality-panel")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            runOnMainSync {
                val vm = playerVm!!
                vm.playing = vm.playing!!.copy(markers = com.cinemaplus.rebuilt.data.SkipMarkers(introEndMs = 30_000))
                withPlayer { it.seekTo(5_000) }
            }
            waitUntil("Skip intro automatically receives focus") { focusedText().contains("تخطي المقدمة") }
            screenshot("player-skip-intro-focused")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("Skip intro actually seeks past intro") {
                var skipped = false
                runOnMainSync { withPlayer { skipped = it.currentPosition >= 30_000 } }
                skipped && !visibleText().contains("تخطي المقدمة")
            }
            runOnMainSync {
                val vm = playerVm!!
                vm.chooseAutoNext(false)
                val current = vm.playing!!.copy(markers = com.cinemaplus.rebuilt.data.SkipMarkers(creditsStartMs = 150_000))
                vm.playing = current
                vm.seasons = listOf(com.cinemaplus.rebuilt.data.Season(2,
                    listOf(current, current.copy(id = "demo-next-fixture", episode = 4, title = "الحلقة التالية"))))
                withPlayer { it.seekTo(155_000) }
            }
            waitUntil("Next episode automatically receives focus") { focusedText().contains("الحلقة التالية") }
            screenshot("player-next-episode-focused")
            result.putString("stream", "Media3 remote smoke passed: ${checks.length()} checks\n")
            status.putString("stream", result.getString("stream")); sendStatus(0, status)
            File(output, "results.json").writeText(JSONObject().put("passed", browseFailure == null).put("checks", checks)
                .put("browseError", browseFailure).put("fixture", "Locally generated test pattern; no live service request").toString(2))
        } catch(error: Throwable) {
            screenshot("player-failure")
            status.putString("stack", error.stackTraceToString()); sendStatus(-2, status)
            result.putString("stream", error.stackTraceToString())
            File(output, "results.json").writeText(JSONObject().put("passed", false).put("checks", checks)
                .put("browseError", browseFailure).put("error", error.stackTraceToString()).toString(2))
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


    private fun focusWithRemote(label: String, direction: Int) {
        repeat(12) {
            if (focusedText().contains(label)) return
            sendKeyDownUpSync(direction)
            Thread.sleep(200)
        }
        check(focusedText().contains(label)) { "Remote could not reach $label; focused=${focusedText()}" }
    }

    /** Must be called on the main thread. */
    private fun withPlayer(block: (androidx.media3.common.Player) -> Unit) {
        var found = false
        fun walk(view: View) {
            if (view is PlayerView) view.player?.let { found = true; block(it) }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        targetActivity?.window?.decorView?.let(::walk)
        check(found) { "No actual Media3 player attached" }
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all.add(node); node.children.forEach(::walk) }
        composeRoots.filter { it.view.isAttachedToWindow && it.view.isShown }
            .forEach { walk(it.semanticsOwner.rootSemanticsNode) }
        return all
    }

    private fun nodeText(node: SemanticsNode): String = buildString {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { append(it.text).append('\n') }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { append(it).append('\n') }
        node.children.forEach { append(nodeText(it)) }
    }

    private fun focusedText(): String {
        var text = ""
        runOnMainSync { text = nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
            .joinToString("\n") { nodeText(it) } }
        return text
    }

    private fun assertPauseGeometry() {
        runOnMainSync {
            fun rect(text: String) = nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
            }.boundsInRoot
            val title = rect("اختبار مشغّل التلفزيون")
            val info = rect("الموسم 2 · الحلقة 3")
            val description = rect("وصف الحلقة")
            val width = targetActivity!!.resources.displayMetrics.widthPixels.toFloat()
            val height = targetActivity!!.resources.displayMetrics.heightPixels.toFloat()
            check(title.right > width * .8f && title.top < height * .2f) { "Title not upper right: $title ($width x $height)" }
            check(info.right > width * .8f && info.top > height * .4f && description.bottom < height * .8f) {
                "Information not lower right above controls: $info; $description ($width x $height)"
            }
        }
    }

    private fun runBrowseChecks(activity: MainActivity, record: (String) -> Unit, screenshot: (String) -> Unit) {
        val vm = CinemaViewModel(activity.application)
        fun waitFor(name: String, predicate: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < end) {
                if (predicate()) { record(name); return }
                Thread.sleep(150)
            }
            throw AssertionError("$name; focused=${focusedText()}; visible=${visibleText()}")
        }
        fun key(code: Int) { sendKeyDownUpSync(code); Thread.sleep(650) }
        runOnMainSync { vm.changeDemo(true); vm.chooseTrailerPreview(false) }
        waitFor("Offline catalog loaded") { !vm.busy && vm.shelves.isNotEmpty() }
        val cards = (1..16).map { index -> Demo.films[(index - 1) % Demo.films.size]
            .copy(id = "demo-navigation-$index", title = "عمل الاختبار $index", series = false) }
        runOnMainSync {
            vm.shelves = listOf(Shelf("row-a", "صف الاختبار الأول", cards), Shelf("row-b", "صف الاختبار الثاني", cards.reversed()),
                Shelf("row-c", "صف الاختبار الثالث", cards))
            vm.library.saveProgress(cards[0], 30_000, 240_000)
            vm.library.saveProgress(cards[1], 30_000, 240_000)
            cards.forEach { vm.library.toggle(it) }
            activity.setContent { CinemaApp(vm, television = true) }
        }
        waitFor("Startup focuses full-screen featured card") { focusedText().contains("عمل الاختبار") }
        check(!visibleText().contains("شاهد الآن")) { "Old hero action still visible" }
        record("Hero has no Watch now action")
        screenshot("01-home-fullscreen")
        val hero = focusedText()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Left changes featured title") { focusedText().contains("عمل الاختبار") && focusedText() != hero }
        screenshot("02-home-next-feature")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Featured OK opens details") { vm.selected != null }
        screenshot("03-details")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("Back returns to the same featured card") { vm.selected == null && focusedText().contains("عمل الاختبار") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("Down enters first continue-watching card") { focusedText().contains("عمل الاختبار 2") }
        screenshot("04-continue-first")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Horizontal movement reaches second continue card") { focusedText().contains("عمل الاختبار 1") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val card = focusedText()
        // From the first card, Right enters the rail; Left must restore that exact shelf/card.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("Right opens the side rail") { focusedText().contains("الرئيسية") }
        screenshot("05-side-rail")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Left restores exact previous card from rail") { focusedText() == card }
        screenshot("06-rail-return")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("Down from a non-first card enters first item of next shelf") { focusedText().contains("عمل الاختبار 1") }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("Next shelf always starts at its first card") { focusedText().contains("عمل الاختبار 16") }
        screenshot("07-next-shelf-first")
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("Up also returns to first card of preceding shelf") { focusedText().contains("عمل الاختبار 1") }
        runOnMainSync { vm.showRecentOptions(cards[1]) }
        waitFor("Recent actions dialog starts on mark watched") { focusedText().contains("تعليم كمشاهَد") }
        Thread.sleep(1100)
        screenshot("08-recent-glass-dialog")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("Dialog Down reaches remove action") { focusedText().contains("إزالة من متابعة المشاهدة") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("Dialog Down reaches cancel") { focusedText().contains("إلغاء") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Cancel dismisses recent actions") { vm.recentOptions == null }
        // Enter actual pages from the remote rail so the fresh-entry flag follows production routing.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Remote enters Movies section at first card") { vm.page == Page.Movies && focusedText().contains("رحلة إلى المجهول") }
        screenshot("09-movies-first")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        val movie = focusedText()
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Movies rail return restores previous movie") { focusedText() == movie }
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Remote enters Series section at first card") { vm.page == Page.Series && focusedText().contains("أسرار الشمال") }
        screenshot("10-series-first")
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Explore starts on its first mood") { vm.page == Page.Explore && focusedText().contains("جريمة وغموض") }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("Explore Down enters first mood in next row") { focusedText().contains("حب وعلاقات") }
        screenshot("11-explore-next-row")
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("Explore Up enters first mood in previous row") { focusedText().contains("جريمة وغموض") }
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("My List starts on its first saved card") { vm.page == Page.Library && focusedText().contains("عمل الاختبار 1") }
        screenshot("12-library-first")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val libraryNext = focusedText()
        check(libraryNext.contains("عمل الاختبار") && !libraryNext.contains("عمل الاختبار 3\n")) { "Library did not move to next row: $libraryNext" }
        record("Library Down enters another grid row")
        screenshot("13-library-next-row")
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("Library Up resets column to first card") { focusedText().contains("عمل الاختبار 1") }
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Settings starts on Account") { vm.page == Page.Settings && focusedText().contains("الحساب") }
        screenshot("14-settings-account")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Account pane can receive remote focus") { focusedText().contains("Cinema") || focusedText().contains("تسجيل") }
        screenshot("15-account-controls")
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_BACK)
        repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Search starts on its first keyboard key") { vm.page == Page.Search && focusedText().trim() == "ا" }
        screenshot("16-search-first-key")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Remote search key updates query") { vm.query == "ا" }
        screenshot("17-search-results")
        runOnMainSync { activity.setContent { } }
        Thread.sleep(250)
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
