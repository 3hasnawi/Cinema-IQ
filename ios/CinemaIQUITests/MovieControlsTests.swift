import XCTest

/// Real decoder playback and the unchanged production HUD timeout; all taps use screen coordinates.
final class MovieControlsTests: XCTestCase {
    private let app = XCUIApplication()
    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player", "--cinema-ui-testing-player-controls"]
        if name.contains("Compatibility") { app.launchArguments.append("--cinema-ui-testing-compatibility") }
        if name.contains("PendingPause") { app.launchArguments.append("--cinema-ui-testing-pending-pause") }
        if !name.contains("Compatibility") {
            // Separate first-install hydration/accessibility startup from the player regression.
            app.launch()
            XCTAssertTrue(element("ios.home.hero").waitForExistence(timeout: 25))
            app.terminate()
        }
        app.launch()
    }
    override func tearDown() {
        shot("last-state")
        let text = app.debugDescription
        SimulatorEvidence.hierarchy(text, test: name)
        let hierarchy = XCTAttachment(string: text)
        hierarchy.name = "movie-controls-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        app.terminate()
    }
    func testAppleControlsReturnAfterAutomaticHide() { verifyControls() }
    func testCompatibilityControlsReturnAfterAutomaticHide() { verifyControls() }
    func testPendingPauseSeekAndAutomaticQualityPreserveNativeClock() throws {
        openFirstMovie()
        let pausedAt = try nativePausedPosition()
        shot("native-startup-seek-paused")
        element("ios.player.options").tap()
        let quality = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "الجودة")).firstMatch
        XCTAssertTrue(quality.waitForExistence(timeout: 10)); quality.tap()
        let automatic = element("ios.player.quality.auto")
        XCTAssertTrue(automatic.waitForExistence(timeout: 10)); automatic.tap()
        waitFor { !self.element("ios.player.options.panel").exists }
        try assertNativeClockStaysPaused(at: pausedAt)
        shot("automatic-quality-preserves-native-pause")
        let closePoint = try XCTUnwrap(snapshotPoint("ios.player.close"), "Cache Close while the paused HUD is stable")
        element("ios.player.playPause").tap()
        waitFor { (self.nativeClock()?.seconds ?? 0) >= pausedAt + 2 && self.nativeClock()?.playing == true }
        shot("native-resume-advances-from-seek")
        waitForHUDHidden()
        // One synthesized gesture reveals and then hits Close; no slow AX lookup consumes the HUD interval.
        doubleTap(at: closePoint)
        XCTAssertTrue(element("ios.detail.play").waitForExistence(timeout: 15))
    }
    func testPendingPauseEpisodeSheetKeepsCurrentAndSelectsNext() throws {
        openBrowse()
        let series = app.segmentedControls.buttons["مسلسلات"]
        XCTAssertTrue(series.waitForExistence(timeout: 10)); series.tap()
        let poster = firstPoster()
        waitFor { (poster.value as? String) == "مسلسل" }; poster.tap()
        XCTAssertTrue(element("ios.detail.play").waitForExistence(timeout: 20))
        let first = episodeButton(prefix: "ios.detail.episode.", number: 1)
        for _ in 0..<8 {
            if first.exists && app.frame.insetBy(dx: -2, dy: -2).contains(first.frame) && first.isHittable { break }
            element("ios.detail").swipeUp()
        }
        XCTAssertTrue(first.exists)
        XCTAssertTrue(app.frame.insetBy(dx: -2, dy: -2).contains(first.frame))
        XCTAssertTrue(first.isHittable); first.tap()
        let initial = try nativePausedPosition()
        let pausePoint = try XCTUnwrap(snapshotPoint("ios.player.playPause"), "Cache Pause before resuming the episode")
        // Move beyond the launch hook's 30-second seek: reopening the current episode
        // would reset to 30, so this assertion detects a restart rather than merely its title.
        element("ios.player.playPause").tap()
        waitFor { (self.nativeClock()?.seconds ?? 0) >= initial + 4 && self.nativeClock()?.playing == true }
        pauseForInspection(at: pausePoint)
        let beforeSheet = try nativePausedPosition()
        openEpisodes()
        let current = episodeButton(prefix: "ios.player.episode.", number: 1)
        XCTAssertTrue(current.waitForExistence(timeout: 10)); current.tap()
        waitFor { !self.element("ios.player.episodes.sheet").exists }
        try assertNativeClockStaysPaused(at: beforeSheet)
        shot("current-episode-selection-preserves-native-clock")
        openEpisodes()
        let next = episodeButton(prefix: "ios.player.episode.", number: 2)
        for _ in 0..<5 {
            if next.exists && app.frame.insetBy(dx: -2, dy: -2).contains(next.frame) && next.isHittable { break }
            current.swipeRight()
        }
        XCTAssertTrue(next.exists)
        XCTAssertTrue(app.frame.insetBy(dx: -2, dy: -2).contains(next.frame))
        XCTAssertTrue(next.isHittable); next.tap()
        waitFor { !self.element("ios.player.episodes.sheet").exists }
        waitFor {
            let info = self.element("ios.player.episode.info").label.replacingOccurrences(of: "٢", with: "2")
            return info.contains("الحلقة 2")
        }
        _ = try nativePausedPosition()
        shot("next-episode-selected-from-touch-sheet")
        element("ios.player.close").tap()
        XCTAssertTrue(element("ios.detail.play").waitForExistence(timeout: 15))
    }
    private func verifyControls() {
        openFirstMovie()
        let start = element("ios.detail.play")
        let video = element("ios.player.video")
        XCTAssertTrue(video.waitForExistence(timeout: 20))
        waitFor { self.seconds(video) >= 1 }
        waitForHUDHidden()
        shot("automatic-hide")
        centerTap()
        waitForHUDVisible()
        pauseForInspection()
        let pause = element("ios.player.playPause")
        shot("controls-restored-paused")
        element("ios.player.options").tap()
        XCTAssertTrue(element("ios.player.options.panel").waitForExistence(timeout: 10))
        shot("options-reachable")
        element("ios.player.options").tap()
        pause.tap(); waitFor { pause.label == "إيقاف مؤقت" }
        XCUIDevice.shared.orientation = .landscapeLeft
        waitFor { self.app.frame.width > self.app.frame.height }
        waitForHUDHidden()
        centerTap()
        waitForHUDVisible()
        pauseForInspection()
        shot("landscape-controls-restored")
        element("ios.player.close").tap()
        XCTAssertTrue(start.waitForExistence(timeout: 15))
    }
    private func openBrowse() {
        let browse = app.buttons.matching(NSPredicate(format: "label == %@", "تصفّح")).firstMatch
        XCTAssertTrue(browse.waitForExistence(timeout: 25)); browse.tap()
    }
    private func firstPoster() -> XCUIElement {
        let poster = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.poster.grid-")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 20)); return poster
    }
    private func openFirstMovie() {
        openBrowse(); firstPoster().tap()
        let start = element("ios.detail.play")
        XCTAssertTrue(start.waitForExistence(timeout: 20)); start.tap()
    }
    private func episodeButton(prefix: String, number: Int) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier CONTAINS %@", prefix, "open-movies-\(number)")).firstMatch
    }
    private func openEpisodes() {
        element("ios.player.options").tap()
        let episodes = element("ios.player.episodes")
        XCTAssertTrue(episodes.waitForExistence(timeout: 10)); episodes.tap()
        XCTAssertTrue(element("ios.player.episodes.sheet").waitForExistence(timeout: 10))
    }
    private func nativeClock() -> (seconds: Int, playing: Bool)? {
        let clock = element("ios.player.testClock")
        guard clock.exists else { return nil }
        let parts = clock.label.split(separator: "|")
        guard parts.count == 2, let seconds = Int(parts[0]), let playing = Bool(String(parts[1])) else { return nil }
        return (seconds, playing)
    }
    private func nativePausedPosition() throws -> Int {
        waitFor { (self.nativeClock()?.seconds ?? 0) >= 29 && self.nativeClock()?.playing == false }
        let value = try XCTUnwrap(nativeClock())
        XCTAssertGreaterThanOrEqual(value.seconds, 29); XCTAssertFalse(value.playing)
        return value.seconds
    }
    private func assertNativeClockStaysPaused(at position: Int) throws {
        let deadline = Date().addingTimeInterval(2)
        repeat {
            let value = try XCTUnwrap(nativeClock())
            XCTAssertFalse(value.playing, "Quality and sheet actions must retain the native paused state")
            XCTAssertEqual(Double(value.seconds), Double(position), accuracy: 1, "Selecting the current episode must not reopen or reset the decoder")
            Thread.sleep(forTimeInterval: 0.2)
        } while Date() < deadline
    }
    private func snapshotPoint(_ identifier: String) -> CGPoint? {
        guard let root = try? app.snapshot() else { return nil }
        var nodes = [root]
        while let node = nodes.popLast() {
            if node.identifier == identifier, !node.frame.isEmpty {
                return CGPoint(x: node.frame.midX, y: node.frame.midY)
            }
            nodes.append(contentsOf: node.children)
        }
        return nil
    }
    private func pauseForInspection(at cachedPoint: CGPoint? = nil) {
        // The first visible snapshot can finish after auto-hide. In that case the picture centre
        // targets the central transport button; the actual decoder/paused-label checks remain required.
        let point = cachedPoint ?? snapshotPoint("ios.player.playPause") ?? CGPoint(x: app.frame.midX, y: app.frame.midY)
        waitForHUDHidden()
        // With the HUD hidden, the first tap reveals it and the second immediately hits Pause.
        // The native decoder state and persistent paused label must both confirm that it worked.
        doubleTap(at: point)
        waitFor {
            self.nativeClock()?.playing == false && self.element("ios.player.playPause").exists &&
            self.element("ios.player.playPause").label == "تشغيل"
        }
    }
    private func doubleTap(at point: CGPoint) {
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: point.x, dy: point.y)).doubleTap()
    }
    private func waitForHUDHidden() {
        waitFor {
            let toggle = self.element("ios.player.controls.toggle"), close = self.element("ios.player.close")
            guard toggle.exists && toggle.label == "إظهار أدوات المشغل" else { return false }
            // Mounted SwiftUI controls can retain an AX node at zero opacity on older systems.
            // Hidden state must also make the real Close target unavailable to a finger.
            return !close.exists || !close.isHittable
        }
    }
    private func waitForHUDVisible() {
        let predicate = NSPredicate { _, _ in
            let toggle = self.element("ios.player.controls.toggle"), close = self.element("ios.player.close")
            return toggle.exists && toggle.label == "إخفاء أدوات المشغل" && close.exists && close.isHittable
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: nil)], timeout: 10), .completed,
                       "A single real picture tap must restore a visible, hittable Close button")
    }
    private func centerTap() { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    private func seconds(_ element: XCUIElement) -> Int {
        (element.value as? String ?? "").split(separator: ":").compactMap { Int($0) }.reduce(0) { $0 * 60 + $1 }
    }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func waitFor(_ condition: @escaping () -> Bool) {
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)], timeout: 20), .completed)
    }
    private func shot(_ name: String) {
        let capture = XCUIScreen.main.screenshot()
        let label = (self.name.contains("Compatibility") ? "compatibility-" : "apple-") + name
        SimulatorEvidence.screenshot(capture, test: self.name, name: label)
        let attachment = XCTAttachment(screenshot: capture)
        attachment.name = label
        attachment.lifetime = .keepAlways; add(attachment)
    }
}
