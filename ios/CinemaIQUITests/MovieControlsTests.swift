import XCTest

/// Real decoder playback and the unchanged production HUD timeout; all taps use screen coordinates.
final class MovieControlsTests: XCTestCase {
    private let app = XCUIApplication()
    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player", "--cinema-ui-testing-player-controls",
                               "--cinema-ui-testing-hero-artwork"]
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
        // Two cached-coordinate taps reveal and then hit Close without an AX lookup between them.
        revealAndTap(at: closePoint)
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
        XCTAssertTrue(next.waitForExistence(timeout: 10))
        let row = try visibleEpisodeRow()
        revealEpisode(next, in: row)
        XCTAssertTrue(next.exists)
        XCTAssertTrue(row.frame.intersection(app.frame).insetBy(dx: -2, dy: -2).contains(next.frame))
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
    private func visibleEpisodeRow() throws -> XCUIElement {
        let prefix = NSPredicate(format: "identifier BEGINSWITH %@", "ios.player.episode.")
        let candidates = element("ios.player.episodes.sheet").descendants(matching: .scrollView)
            .allElementsBoundByIndex.compactMap { row -> (XCUIElement, CGRect)? in
                let viewport = row.frame.intersection(app.frame)
                guard !viewport.isEmpty, row.buttons.matching(prefix).firstMatch.exists else { return nil }
                return (row, viewport)
            }
        // The outer sheet also contains episodes; gesture on its smallest actual episode viewport.
        return try XCTUnwrap(candidates.min { $0.1.width * $0.1.height < $1.1.width * $1.1.height }?.0,
                             "A visible horizontal episode row must exist inside the sheet")
    }
    private func revealEpisode(_ episode: XCUIElement, in row: XCUIElement) {
        for _ in 0..<6 {
            let viewport = row.frame.intersection(app.frame), frame = episode.frame
            if viewport.insetBy(dx: -2, dy: -2).contains(frame) && episode.isHittable { return }
            // Derive physical direction from the current frame: a fling may have passed the target.
            let moveRight = frame.minX < viewport.minX
            let start = row.coordinate(withNormalizedOffset: CGVector(dx: moveRight ? 0.3 : 0.7, dy: 0.5))
            let end = row.coordinate(withNormalizedOffset: CGVector(dx: moveRight ? 0.7 : 0.3, dy: 0.5))
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.2)
        }
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
        // With the HUD hidden, the first independent tap reveals it and the next hits Pause.
        // The native decoder state and persistent paused label must both confirm that it worked.
        revealAndTap(at: point)
        waitFor {
            self.nativeClock()?.playing == false && self.element("ios.player.playPause").exists &&
            self.element("ios.player.playPause").label == "تشغيل"
        }
    }
    private func revealAndTap(at point: CGPoint) {
        let target = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: point.x, dy: point.y))
        // Two ordinary taps let SwiftUI commit the revealed controls between events. An accelerated
        // double-tap can deliver both touches to the picture's original gesture recognizer.
        // Cache the coordinate first: there are no live AX queries between these touches.
        target.tap()
        target.tap()
    }
    private func waitForHUDHidden() {
        waitFor {
            let toggle = self.element("ios.player.controls.toggle")
            // The hidden, zero-opacity button may retain an AX node whose hit-point query throws.
            // This stable picture action names the current state; screenshots retain its real paint.
            return toggle.exists && toggle.label == "إظهار أدوات المشغل"
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
