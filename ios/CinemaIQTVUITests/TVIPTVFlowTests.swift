import XCTest
import CoreGraphics

/// Real remote navigation and isolated actual Keychain storage; accounts and network responses are fixtures.
final class TVIPTVFlowTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared
    private let base = ["-ios.contentSource.v1", "ext:demo", "--cinema-tv-ui-testing-iptv", "--cinema-tv-ui-testing-iptv-player"]
    override func setUp() { continueAfterFailure = false }
    override func tearDown() {
        shot("last-state")
        let hierarchy = app.debugDescription
        print("IPTV-HIERARCHY-BEGIN\n" + hierarchy + "\nIPTV-HIERARCHY-END")
        let tree = XCTAttachment(string: hierarchy); tree.name = "iptv-hierarchy"; tree.lifetime = .keepAlways; add(tree)
        app.terminate()
    }
    func testSourcesGroupsPlaybackAndSelectionPersistence() {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native")
        expectFocus(player); remote.press(.select); expectFocus(guideChannel("قناة الأخبار"))
        remote.press(.up); expectFocus(element("tv.iptv.guide.search"))
        focus(element("tv.iptv.guide.sources"), direction: .left); remote.press(.select)
        focus(element("tv.iptv.source.tv-fixture-xtream"), direction: .down); remote.press(.select)
        expectFocus(player); waitFor { player.label == "Xtream News" }
        remote.press(.select); expectFocus(guideChannel("Xtream News"))
        remote.press(.right); expectFocus(element("tv.iptv.guide.group.all"))
        focus(element("tv.iptv.guide.group.أخبار"), direction: .down); remote.press(.select)
        expectFocus(guideChannel("Xtream News"))
        XCTAssertFalse(guideChannel("Xtream Sport").exists)
        shot("xtream-filtered-guide")
        app.terminate(); app.launchArguments = base; app.launch(); openIPTV()
        expectFocus(player); XCTAssertEqual(player.label, "Xtream News")
        remote.press(.down); waitFor { player.label == "Xtream Sport" }
        remote.press(.playPause)
        waitFor { self.app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "نشرة الأخبار التجريبية")).firstMatch.exists }
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "البرنامج التالي التجريبي")).firstMatch.exists)
        shot("paused-live-player")
        exitLive()
    }
    func testRemoteChannelSurfingAndFloatingGuide() throws {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native")
        expectFocus(player); waitFor { (player.value as? String) == "بث مباشر" }
        XCTAssertFalse(element("tv.iptv.guide").exists, "Entry must immediately play the last channel at full size")
        try assertReferenceVideoVisible("television-full-screen-entry")
        remote.press(.down); waitFor { player.label == "قناة الرياضة" }
        remote.press(.up); waitFor { player.label == "قناة الأخبار" }
        remote.press(.select)
        XCTAssertTrue(element("tv.iptv.guide").waitForExistence(timeout: 15))
        expectFocus(guideChannel("قناة الأخبار"))
        XCTAssertFalse(element("tv.iptv.guide.group.all").exists)
        XCTAssertFalse(element("tv.iptv.player.channels").exists)
        XCTAssertFalse(element("tv.iptv.player.title").exists)
        let footer = element("tv.iptv.guide.categories")
        XCTAssertGreaterThan(footer.frame.midY, app.frame.height * 0.9)
        shot("floating-channel-list")
        remote.press(.down)
        XCTAssertEqual(player.label, "قناة الأخبار", "Focus must not tune a channel before OK")
        remote.press(.select)
        waitFor { !self.element("tv.iptv.guide").exists && player.label == "قناة الرياضة" }
        expectFocus(player)
        remote.press(.select); expectFocus(guideChannel("قناة الرياضة"))
        remote.press(.right); expectFocus(element("tv.iptv.guide.group.all"))
        shot("floating-categories-and-channels")
        remote.press(.left); expectFocus(guideChannel("قناة الرياضة"))
        remote.press(.right); expectFocus(element("tv.iptv.guide.group.all"))
        remote.press(.down); expectFocus(element("tv.iptv.guide.group.favorites"))
        for group in ["أخبار", "رياضة", "وثائقيات"] {
            remote.press(.down); expectFocus(element("tv.iptv.guide.group." + group))
        }
        remote.press(.select); expectFocus(guideChannel("قناة الوثائقيات"))
        remote.press(.select)
        waitFor { !self.element("tv.iptv.guide").exists && player.label == "قناة الوثائقيات" }
        remote.press(.select); remote.press(.menu); expectFocus(player)
        exitLive()
        openIPTV(); expectFocus(player)
        XCTAssertEqual(player.label, "قناة الوثائقيات")
        app.terminate(); app.launchArguments = base; app.launch(); openIPTV()
        expectFocus(player); XCTAssertEqual(player.label, "قناة الوثائقيات")
        shot("last-channel-restored-after-relaunch")
    }
    func testToolbarHidesWhileFocusedAndPopupsRestoreTheirIcons() {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native")
        expectFocus(player); waitFor { (player.value as? String) == "بث مباشر" }
        remote.press(.left); expectFocus(element("tv.iptv.player.channels"))
        waitFor { !self.element("tv.iptv.player.channels").exists }
        expectFocus(player); shot("hud-auto-hidden-from-focused-toolbar")
        for kind in ["quality", "audio", "subtitles"] {
            remote.press(.left)
            let icon = element("tv.iptv.player." + kind)
            focusControl(kind); remote.press(.select)
            expectFocus(element("tv.iptv.track.first"))
            let panel = element("tv.iptv.track.panel")
            XCTAssertTrue(panel.waitForExistence(timeout: 10))
            let iconFrame = icon.images.firstMatch.frame
            XCTAssertLessThanOrEqual(panel.frame.width, 600)
            XCTAssertLessThanOrEqual(panel.frame.height, app.frame.height * 0.56)
            XCTAssertLessThanOrEqual(panel.frame.maxY, iconFrame.minY + 2, "The drop panel must sit above its own icon")
            XCTAssertGreaterThanOrEqual(iconFrame.midX, panel.frame.minX)
            XCTAssertLessThanOrEqual(iconFrame.midX, panel.frame.maxX, "RTL must not mirror the popup to another action")
            // AX queries on hosted tvOS can exceed the five-second idle timeout. Inspect the HUD
            // while a track panel legitimately holds it open, not after it has correctly hidden.
            if kind == "quality" {
                XCTAssertFalse(element("tv.iptv.player.play").exists)
                XCTAssertFalse(element("tv.iptv.player.close").exists)
                XCTAssertTrue(app.staticTexts["لا تتوفر معلومات"].exists)
            }
            if kind == "audio" {
                XCTAssertLessThanOrEqual(app.buttons.matching(NSPredicate(format: "value == %@", "محدد")).count, 1)
            }
            shot("compact-" + kind + "-popup")
            remote.press(.menu); expectFocus(icon)
            remote.press(.select); expectFocus(element("tv.iptv.track.first"))
            remote.press(.select); expectFocus(icon)
        }
        exitLive()
    }
    func testGuideSearchDismissesNativeKeyboardAndRestoresNavigation() {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native")
        expectFocus(player); remote.press(.select)
        XCTAssertTrue(element("tv.iptv.guide").waitForExistence(timeout: 15))
        expectFocus(guideChannel("قناة الأخبار"))
        let search = element("tv.iptv.guide.search")
        remote.press(.up); expectFocus(search); remote.press(.select)
        let editor = element("tv.iptv.guide.editor")
        expectFocus(editor); remote.press(.select)
        app.typeText("2")
        remote.press(.menu)
        waitFor { !self.app.keyboards.firstMatch.exists }
        XCTAssertTrue(element("tv.iptv.guide").exists, "Back must dismiss the keyboard before dismissing the channel list")
        expectFocus(editor)
        // Reopen and submit the native keyboard as well as exercising Back above.
        remote.press(.select)
        let done = app.keyboards.buttons.matching(NSPredicate(format: "label IN %@", ["done", "Done", "تم", "بحث", "search", "Search"])).firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        shot("guide-native-keyboard-before-done")
        // A newline typed by XCTest is not tvOS's on-screen Done button. Reach that button with the remote.
        for _ in 0..<6 {
            if done.hasFocus { break }
            remote.press(.down)
        }
        XCTAssertTrue(done.hasFocus)
        remote.press(.select)
        waitFor { !self.app.keyboards.firstMatch.exists }
        expectFocus(guideChannel("قناة الرياضة"))
        XCTAssertFalse(guideChannel("قناة الأخبار").exists)
        shot("guide-search-after-keyboard-dismissal")
        remote.press(.select); expectFocus(player)
        XCTAssertEqual(player.label, "قناة الرياضة")
        remote.press(.select); expectFocus(guideChannel("قناة الرياضة"))
        remote.press(.right); expectFocus(element("tv.iptv.guide.group.all"))
        remote.press(.menu); expectFocus(guideChannel("قناة الرياضة"))
        remote.press(.menu); expectFocus(player)
    }
    func testLockedCategoryRequiresFreshPINAfterLeaving() {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native"), news = guideChannel("قناة الأخبار")
        expectFocus(player); remote.press(.select); expectFocus(news)
        remote.press(.up); expectFocus(element("tv.iptv.guide.search"))
        focus(element("tv.iptv.guide.manage"), direction: .left); remote.press(.select)
        focus(element("tv.iptv.manage.group.رياضة"), direction: .down); remote.press(.select)
        focus(element("tv.iptv.manage.lock"), direction: .down); remote.press(.select)
        let one = element("tv.iptv.pin.1")
        expectFocus(one)
        for _ in 0..<8 { remote.press(.select) }
        expectFocus(element("tv.iptv.manage.group.رياضة"))
        remote.press(.menu); expectFocus(element("tv.iptv.guide.manage"))
        XCTAssertFalse(guideChannel("قناة الرياضة").exists)
        focus(news, direction: .down); remote.press(.right)
        let group = element("tv.iptv.guide.group.رياضة")
        focus(group, direction: .down); remote.press(.select); expectFocus(one)
        for _ in 0..<4 { remote.press(.select) }
        let sport = guideChannel("قناة الرياضة")
        expectFocus(sport); remote.press(.select); expectFocus(player)
        XCTAssertEqual(player.label, "قناة الرياضة")
        remote.press(.select); expectFocus(one)
        remote.press(.menu); expectFocus(group)
        XCTAssertFalse(guideChannel("قناة الرياضة").exists)
        remote.press(.select); expectFocus(one); shot("category-requires-pin-on-reentry")
    }

    func testFocusedVideoHasNoButtonBackdrop() throws {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native")
        expectFocus(player)
        waitFor { (player.value as? String) == "بث مباشر" }
        try assertReferenceVideoVisible("focused-video-playing")
        remote.press(.playPause)
        waitFor { (player.value as? String) == "متوقف مؤقتًا" }
        try assertReferenceVideoVisible("focused-video-paused")
        remote.press(.select)
        XCTAssertTrue(element("tv.iptv.guide").waitForExistence(timeout: 15))
        remote.press(.menu); expectFocus(player)
        try assertReferenceVideoVisible("focused-video-after-guide")
        remote.press(.playPause)
        waitFor { (player.value as? String) == "بث مباشر" }
        remote.press(.down); waitFor { player.label == "قناة الرياضة" }
        try assertReferenceVideoVisible("focused-video-after-channel-change")
        exitLive()
    }
    func testCompatibilityDecoderWithRemoteAndTracks() throws {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-ui-testing-compatibility", "--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native"); expectFocus(player)
        waitFor { (player.value as? String) == "بث مباشر" }
        try assertReferenceVideoVisible("compatibility-mpeg2-mp2-playing")
        remote.press(.playPause); waitFor { (player.value as? String) == "متوقف مؤقتًا" }
        remote.press(.left); focusControl("audio"); remote.press(.select)
        expectFocus(element("tv.iptv.track.first")); shot("compatibility-audio-tracks")
        remote.press(.down); remote.press(.select); expectFocus(element("tv.iptv.player.audio"))
        XCTAssertEqual(player.value as? String, "متوقف مؤقتًا")
        remote.press(.left); focusControl("subtitles"); remote.press(.select)
        expectFocus(element("tv.iptv.track.first")); remote.press(.down); remote.press(.select); expectFocus(element("tv.iptv.player.subtitles"))
        remote.press(.left); focusControl("quality"); remote.press(.select)
        expectFocus(element("tv.iptv.track.first")); shot("compatibility-quality-options")
        remote.press(.down); expectFocus(element("tv.iptv.track.highest")); remote.press(.select)
        expectFocus(element("tv.iptv.player.quality")); waitFor { (player.value as? String) == "متوقف مؤقتًا" }
        try assertReferenceVideoVisible("compatibility-paused-highest-quality")
        waitFor { !self.element("tv.iptv.player.quality").exists }; expectFocus(player)
        remote.press(.select)
        XCTAssertTrue(element("tv.iptv.guide").waitForExistence(timeout: 15)); shot("compatibility-floating-guide")
        let current = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tv.iptv.guide.channel.' AND label CONTAINS %@", "قناة الأخبار")).firstMatch
        expectFocus(current); remote.press(.select); expectFocus(player)
        XCTAssertEqual(player.value as? String, "متوقف مؤقتًا", "Selecting the current channel must not restart or resume it")
        try assertReferenceVideoVisible("compatibility-paused-after-guide")
        remote.press(.playPause); waitFor { (player.value as? String) == "بث مباشر" }
        remote.press(.down); waitFor { player.label == "قناة الرياضة" }
        waitFor { (player.value as? String) == "بث مباشر" }
        try assertReferenceVideoVisible("compatibility-after-channel-change")
        exitLive()
    }
    func testCompatibilityMovieTransportAndReturnToDetails() throws {
        executionTimeAllowance = 360
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player", "--cinema-ui-testing-compatibility"]
        app.launch()
        let home = element("tv.home.showcase"); XCTAssertTrue(home.waitForExistence(timeout: 30))
        expectFocus(home); remote.press(.select)
        let play = app.buttons["tv.detail.play"]; expectFocus(play); remote.press(.select)
        let surface = element("tv.compatibility.surface"); expectFocus(surface)
        waitFor { (surface.value as? String) == "تشغيل" }
        // Hide transport before checking video pixels, then pause using the real remote.
        try assertReferenceVideoVisible("compatibility-movie-playing")
        remote.press(.playPause)
        waitFor { (surface.value as? String) == "متوقف مؤقتًا" }
        XCTAssertTrue(app.staticTexts["tv.player.pausedTitle"].waitForExistence(timeout: 15))
        shot("compatibility-movie-paused")
        remote.press(.playPause); waitFor { (surface.value as? String) == "تشغيل" }
        remote.press(.menu)
        XCTAssertTrue(play.waitForExistence(timeout: 15)); expectFocus(play)
        XCTAssertEqual(app.state, .runningForeground)
    }
    private func assertReferenceVideoVisible(_ name: String) throws {
        var capture = XCUIScreen.main.screenshot()
        var fraction = try saturatedFraction(capture)
        let deadline = Date().addingTimeInterval(8)
        while fraction <= 0.70 && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            capture = XCUIScreen.main.screenshot(); fraction = try saturatedFraction(capture)
        }
        let attachment = XCTAttachment(screenshot: capture); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        XCTAssertGreaterThan(fraction, 0.70, "Focused playback must show the video, without a screen-sized button backdrop")
    }
    private func saturatedFraction(_ capture: XCUIScreenshot) throws -> Double {
        try saturatedFraction(XCTUnwrap(capture.image.cgImage))
    }
    private func saturatedFraction(_ image: CGImage) throws -> Double {
        XCTAssertGreaterThan(image.width, 100)
        // The bundled SMPTE clip has saturated bars in the middle, away from all transport/title overlays.
        // A system focus backdrop washes those bars white even though accessibility/remote tests still pass.
        let width = 128, height = 72
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        return try rgba.withUnsafeMutableBytes { bytes -> Double in
            let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            let pixels = bytes.bindMemory(to: UInt8.self)
            var saturated = 0, count = 0
            for y in 24..<48 { for x in 16..<112 {
                let offset = (y * width + x) * 4
                let colors = [Int(pixels[offset]), Int(pixels[offset + 1]), Int(pixels[offset + 2])]
                if colors.max()! - colors.min()! > 100 { saturated += 1 }
                count += 1
            } }
            return Double(saturated) / Double(count)
        }
    }
    func testAddM3USourceWithNativeKeyboard() {
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset"]
        app.launch(); openIPTV()
        expectFocus(element("tv.iptv.add")); remote.press(.select)
        XCTAssertTrue(element("tv.iptv.form.m3u").waitForExistence(timeout: 15))
        fill("tv.iptv.form.name", "Remote List")
        fill("tv.iptv.form.address", "http://iptv.example/channels.m3u")
        focus(element("tv.iptv.form.allowHTTP"), direction: .down); remote.press(.select)
        focus(element("tv.iptv.form.save"), direction: .down); remote.press(.select)
        expectFocus(element("tv.iptv.player.native")); shot("source-added-with-remote")
        XCTAssertEqual(app.state, .runningForeground)
    }
    func testHighestQualityPreferenceAndAutomaticSelection() {
        executionTimeAllowance = 360
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let player = element("tv.iptv.player.native")
        XCTAssertTrue(player.waitForExistence(timeout: 20))
        waitFor { (player.value as? String) == "بث مباشر" }
        remote.press(.playPause)
        remote.press(.left)
        focusControl("quality"); remote.press(.select)
        expectFocus(element("tv.iptv.track.first"))
        remote.press(.down); expectFocus(element("tv.iptv.track.highest")); remote.press(.select)
        remote.press(.left)
        focusControl("quality"); remote.press(.select)
        XCTAssertTrue(element("tv.iptv.track.highest").waitForExistence(timeout: 10))
        XCTAssertEqual(element("tv.iptv.track.highest").value as? String, "محدد")
        shot("highest-quality-options")
        expectFocus(element("tv.iptv.track.first")); remote.press(.select)
        XCTAssertEqual(player.value as? String, "متوقف مؤقتًا")
        waitFor { !self.element("tv.iptv.player.quality").exists }; expectFocus(player)
        remote.press(.select)
        XCTAssertTrue(element("tv.iptv.guide").waitForExistence(timeout: 10))
        remote.press(.menu); expectFocus(player); exitLive()
    }
    func testXtreamFormShowsCredentialsAndMenuReturnsSafely() {
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset"]
        app.launch(); openIPTV()
        expectFocus(element("tv.iptv.add")); remote.press(.select)
        expectFocus(element("tv.iptv.form.m3u"))
        focus(element("tv.iptv.form.xtream"), direction: .left); remote.press(.select)
        XCTAssertTrue(element("tv.iptv.form.username").waitForExistence(timeout: 10))
        XCTAssertTrue(element("tv.iptv.form.password").exists)
        let name = element("tv.iptv.form.name")
        focus(name, direction: .down); remote.press(.select); app.typeText("Remote Xtream")
        let next = app.keyboards.buttons.matching(NSPredicate(format: "label IN %@", ["Next", "next", "التالي", "Done", "done", "تم"])).firstMatch
        XCTAssertTrue(next.waitForExistence(timeout: 10))
        for _ in 0..<8 { if next.hasFocus { break }; remote.press(.down) }
        XCTAssertTrue(next.hasFocus); remote.press(.select)
        waitFor { !self.app.keyboards.firstMatch.exists }
        expectFocus(element("tv.iptv.form.address"))
        fill("tv.iptv.form.address", "https://xtream.example")
        fill("tv.iptv.form.username", "demo")
        fill("tv.iptv.form.password", "demo")
        focus(element("tv.iptv.form.save"), direction: .down)
        XCTAssertTrue(element("tv.iptv.form.save").isEnabled)
        shot("xtream-form")
        remote.press(.menu)
        expectFocus(element("tv.iptv.add"))
        remote.press(.menu)
        XCTAssertTrue(element("tv.home.showcase").waitForExistence(timeout: 15))
    }
    private func fill(_ id: String, _ text: String) {
        let field = element(id)
        focus(field, direction: .down); remote.press(.select)
        app.typeText(text)
        remote.press(.menu) // Native tvOS text bindings retain the entered value when leaving the keyboard.
        waitFor { !self.app.keyboards.firstMatch.exists }
        XCTAssertTrue(element("tv.iptv.form.save").exists)
    }
    private func openIPTV() {
        expectFocus(element("tv.home.showcase"))
        remote.press(.menu)
        let order = ["البحث", "الرئيسية", "أفلام", "مسلسلات", "مكتبتي", "التلفزيون", "الإعدادات"]
        for _ in 0..<14 {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let label = focused.exists ? focused.label : ""
            if label.contains("التلفزيون") { remote.press(.select); break }
            remote.press((order.firstIndex(where: { label.contains($0) }) ?? 0) < 5 ? .down : .up)
        }
        let seeded = app.launchArguments.contains("--cinema-tv-ui-testing-iptv-seed") || !app.launchArguments.contains("--cinema-tv-ui-testing-iptv-reset")
        if seeded {
            XCTAssertTrue(element("tv.iptv.player.native").waitForExistence(timeout: 25))
        } else { XCTAssertTrue(element("tv.iptv.heading").waitForExistence(timeout: 20)) }
    }
    private func exitLive() {
        let player = element("tv.iptv.player.native")
        if element("tv.iptv.player.title").exists {
            remote.press(.menu)
            waitFor { !self.element("tv.iptv.player.title").exists }
            expectFocus(player)
        }
        remote.press(.menu)
        expectFocus(element("tv.home.showcase"))
    }
    private func guideChannel(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tv.iptv.guide.channel.' AND label == %@", name)).firstMatch
    }
    private func focusControl(_ kind: String) {
        let order = ["channels", "favorite", "audio", "quality", "subtitles"]
        let target = "tv.iptv.player." + kind
        remote.press(.left)
        for _ in 0..<10 {
            let current = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let id = current.exists ? current.identifier : ""
            if id == target { return }
            if let index = order.firstIndex(where: { id == "tv.iptv.player." + $0 }), let wanted = order.firstIndex(of: kind) {
                remote.press(index < wanted ? .left : .right)
            } else { remote.press(.left) }
        }
        expectFocus(element(target))
    }
    private func focus(_ element: XCUIElement, direction: XCUIRemote.Button) {
        // Missing controls must fail immediately so the complete result bundle retains the hierarchy.
        guard element.waitForExistence(timeout: 5) else { XCTFail("Missing remote target"); return }
        for _ in 0..<12 {
            if element.exists && isFocused(element) { return }
            guard element.exists else { remote.press(direction); continue }
            let current = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            guard current.exists else { remote.press(direction); continue }
            let dx = element.frame.midX - current.frame.midX, dy = element.frame.midY - current.frame.midY
            if abs(dy) > 65 { remote.press(dy > 0 ? .down : .up) }
            else if abs(dx) > 20 { remote.press(dx > 0 ? .right : .left) }
            else { remote.press(direction) }
        }
        XCTAssertTrue(isFocused(element), "The remote could not reach \(element.identifier)")
    }
    private func expectFocus(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 25))
        let focused = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.isFocused(element) }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [focused], timeout: 20), .completed)
    }
    private func isFocused(_ element: XCUIElement) -> Bool {
        if element.hasFocus { return true }
        if element.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists { return true }
        // Native tvOS list rows can own focus on behalf of their single action/field.
        let id = element.identifier.isEmpty ? element.label : element.identifier
        guard !id.isEmpty else { return false }
        let cell = app.cells.containing(.any, identifier: id).matching(NSPredicate(format: "hasFocus == true")).firstMatch
        guard cell.exists else { return false }
        let actions = Set(cell.buttons.allElementsBoundByIndex.map { $0.identifier.isEmpty ? $0.label : $0.identifier })
        return actions.count <= 1
    }
    private func waitFor(_ block: @escaping () -> Bool) {
        let value = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in block() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [value], timeout: 25), .completed)
    }
    private func channel(_ name: String) -> XCUIElement { app.buttons.matching(NSPredicate(format: "label == %@", name)).firstMatch }
    private func element(_ id: String) -> XCUIElement {
        // Typed queries avoid walking the entire background navigation tree for each live-guide key.
        if ["tv.iptv.heading", "tv.iptv.guide", "tv.iptv.guide.groups", "tv.iptv.player.title", "tv.iptv.track.popup"].contains(id) {
            return app.staticTexts[id]
        }
        if id == "tv.iptv.guide.editor" { return app.textFields[id] }
        if id == "tv.iptv.track.panel" { return app.otherElements[id] }
        // Native Form rows may expose the field/action through their cell or switch; preserve that path.
        if id.hasPrefix("tv.iptv.form.") { return app.descendants(matching: .any).matching(identifier: id).firstMatch }
        return app.buttons[id]
    }
    private func shot(_ name: String) { let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot) }
}
