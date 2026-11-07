import XCTest

/// Required preview checks use a synthetic local clip; the demo keeps its genuine Sintel URL outside this debug flag.
final class TVHeroPlaybackTests: XCTestCase {
    private var app = XCUIApplication()
    private let remote = XCUIRemote.shared

    override func setUp() { continueAfterFailure = false }

    override func tearDown() {
        if let testRun, testRun.failureCount > 0 {
            capture("hero-failure")
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.name = "hero-failure-hierarchy"
            hierarchy.lifetime = .keepAlways
            add(hierarchy)
        }
        app.terminate()
        super.tearDown()
    }

    func testRemoteSwitchesFeaturedTitlesAndWraps() {
        launch()
        let hero = app.buttons["tv.hero.details"]
        XCTAssertTrue(hero.waitForExistence(timeout: 30))
        focusDown(hero)
        let title = app.staticTexts["tv.hero.title"]
        XCTAssertEqual(title.label, "Big Buck Bunny")
        remote.press(.left); wait(0.6)
        XCTAssertEqual(title.label, "Sintel")
        XCTAssertTrue(app.buttons["tv.hero.page.1"].isSelected)
        XCTAssertTrue(hero.hasFocus, "Changing feature must not drop the remote focus")
        remote.press(.right); wait(0.6)
        XCTAssertEqual(title.label, "Big Buck Bunny")
        remote.press(.right); wait(0.6)
        XCTAssertNotEqual(title.label, "Big Buck Bunny", "Previous from the first item wraps")
        remote.press(.left); wait(0.6)
        XCTAssertEqual(title.label, "Big Buck Bunny")
        remote.press(.select)
        wait(1)
        remote.press(.menu)
        XCTAssertTrue(hero.waitForExistence(timeout: 15))
    }

    func testPreviewControlsCompletionFocusAndRapidTitleChange() {
        launch()
        let hero = app.buttons["tv.hero.details"]
        XCTAssertTrue(hero.waitForExistence(timeout: 30))
        focusDown(hero)
        remote.press(.left)
        let toggle = app.buttons["tv.hero.trailer.toggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 15), "The local fixture must render and expose controls")
        focusDown(toggle)
        remote.press(.select); wait(0.4)
        XCTAssertTrue(toggle.label.contains("تشغيل"), "Pause exposes replay/resume")
        remote.press(.select); wait(0.4)
        XCTAssertTrue(toggle.label.contains("إيقاف"))
        capture("hero-preview-playing")
        let mute = app.buttons["tv.hero.trailer.mute"]
        remote.press(mute.frame.midX < toggle.frame.midX ? .left : .right)
        XCTAssertTrue(mute.hasFocus)
        let before = mute.label
        remote.press(.select); wait(0.4)
        XCTAssertNotEqual(mute.label, before)
        remote.press(.select)
        remote.press(toggle.frame.midX < mute.frame.midX ? .left : .right)
        XCTAssertTrue(toggle.hasFocus)
        wait(18)
        XCTAssertTrue(toggle.exists, "A completed short preview must keep Replay after the startup watchdog")
        XCTAssertTrue(toggle.hasFocus, "The carousel must not rotate while Replay is focused")
        XCTAssertTrue(toggle.label.contains("تشغيل"))
        XCTAssertEqual(app.staticTexts["tv.hero.title"].label, "Sintel")
        capture("hero-preview-completed-replay-focus")
        remote.press(.select); wait(0.4)
        XCTAssertTrue(toggle.label.contains("إيقاف"), "Replay starts the completed preview again")
        for _ in 0..<5 where !hero.hasFocus { remote.press(.up); wait(0.3) }
        XCTAssertTrue(hero.hasFocus)
        for _ in 0..<3 { remote.press(.left); wait(0.1); remote.press(.right); wait(0.1) }
        remote.press(.right); wait(0.5)
        XCTAssertEqual(app.staticTexts["tv.hero.title"].label, "Big Buck Bunny")
        XCTAssertFalse(toggle.exists, "The previous title's video controls must disappear immediately")
        XCTAssertTrue(app.buttons["tv.hero.details"].hasFocus)
    }

    func testPreviewRecoversAfterDetailsAndBackgroundInterruption() {
        launch()
        let hero = app.buttons["tv.hero.details"]
        XCTAssertTrue(hero.waitForExistence(timeout: 30))
        focusDown(hero)
        remote.press(.left)
        let toggle = app.buttons["tv.hero.trailer.toggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 15))
        XCTAssertTrue(hero.hasFocus)
        remote.press(.select)
        wait(1)
        XCTAssertFalse(toggle.isHittable, "The preview controls must not leak over title details")
        remote.press(.menu)
        XCTAssertTrue(hero.waitForExistence(timeout: 10))
        XCTAssertTrue(toggle.waitForExistence(timeout: 15), "Returning to Home starts a fresh preview")
        remote.press(.home)
        let backgrounded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.state != .runningForeground
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [backgrounded], timeout: 10), .completed,
                       "Wait for SpringBoard to finish backgrounding the app")
        app.activate()
        XCTAssertTrue(hero.waitForExistence(timeout: 10))
        XCTAssertTrue(toggle.waitForExistence(timeout: 15), "Foregrounding restarts the current title preview")
        XCTAssertEqual(app.staticTexts["tv.hero.title"].label, "Sintel")
        capture("hero-preview-after-background-return")
    }

    func testPreferredQualitySurvivesRelaunch() {
        app = XCUIApplication()
        app.launchArguments = ["-tv.testSource", "ext:demo", "-tv.initialTab", "settings"]
        app.launch()
        var quality = openQualitySettings()
        remote.press(.select)
        let option = app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", "720p", "720p,")).firstMatch
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        focusVertically(option)
        remote.press(.select); wait(0.5)
        XCTAssertTrue(quality.label.contains("720p"))
        app.terminate()
        app.launch()
        quality = openQualitySettings()
        XCTAssertTrue(quality.label.contains("720p"), "Quality is a persisted preference, not a per-player state")
        capture("quality-720p-after-relaunch")
        // Restore Auto in the isolated test app so later tests do not depend on this selection.
        remote.press(.select)
        let automatic = app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", "تلقائي", "تلقائي,")).firstMatch
        XCTAssertTrue(automatic.waitForExistence(timeout: 5))
        focusVertically(automatic)
        remote.press(.select)
    }

    private func openQualitySettings() -> XCUIElement {
        let settings = app.buttons["tv.settings.playback"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        focusVertically(settings)
        remote.press(.select)
        let quality = app.buttons["tv.settings.playback.quality"]
        XCTAssertTrue(quality.waitForExistence(timeout: 10))
        focusVertically(quality)
        return quality
    }

    private func focusVertically(_ target: XCUIElement) {
        func focused() -> Bool {
            if target.hasFocus { return true }
            guard !target.identifier.isEmpty else { return false }
            let cell = app.cells.containing(.any, identifier: target.identifier).firstMatch
            return cell.exists && cell.hasFocus
        }
        for _ in 0..<12 where !focused() {
            let current = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            remote.press(current.exists && current.frame.midY > target.frame.midY ? .up : .down)
            wait(0.3)
        }
        XCTAssertTrue(focused(), "Cannot focus \(target.identifier)")
    }

    private func launch() {
        app = XCUIApplication()
        app.launchArguments = ["-tv.testSource", "ext:demo", "-tv.initialTab", "home",
                               "-tv.testHeroPreview"]
        app.launch()
    }
    private func focusDown(_ target: XCUIElement) {
        for _ in 0..<10 where !target.hasFocus { remote.press(.down); wait(0.4) }
        XCTAssertTrue(target.hasFocus, "Cannot focus \(target.identifier)")
    }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    private func wait(_ duration: TimeInterval) { Thread.sleep(forTimeInterval: duration) }
}
