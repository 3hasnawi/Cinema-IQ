import XCTest

/// Real playback time (no seek/accelerated clock), Back, remote navigation and resume with both engines.
final class TVMovieExitTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared

    override func tearDown() {
        shot("movie-exit-final-state")
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "movie-exit-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        app.terminate()
    }

    func testNativeMovieBackAfterTwoMinutesAndImmediateResume() { checkExit(compatibility: false, seconds: 125) }
    func testCompatibilityMovieBackAfterOneMinuteAndImmediateResume() { checkExit(compatibility: true, seconds: 65) }

    func testPausedOpeningKeepsBackgroundIntentAndQueuedSeekUntilRemoteResume() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player",
                               "--cinema-ui-testing-pending-pause", "-ios.local.progress.v1", "",
                               "-AppleLanguages", "(ar)", "-AppleLocale", "ar_IQ"]
        app.launch()
        let home = element("tv.home.showcase")
        XCTAssertTrue(home.waitForExistence(timeout: 30)); expectFocus(home); remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); expectFocus(play); remote.press(.select)
        let clock = element("tv.player.testClock")
        waitFor(30) {
            let fields = clock.label.split(separator: "|")
            return fields.count >= 3 && fields[0] == "false" && self.time(clock) >= 29 && (Int(fields[2]) ?? 0) > 0
        }
        let paused = time(clock)
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertEqual(time(clock), paused)
        shot("queued-background-pause-and-seek-applied-without-auto-playing")
        remote.press(.playPause)
        waitFor(15) { clock.label.hasPrefix("true|") && self.time(clock) > paused + 1 }
        returnToDetails(play)
    }

    func testBackWhileMovieIsOpeningDoesNotReappearOrTrapRemote() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player",
                               "--cinema-ui-testing-compatibility", "-ios.local.progress.v1", "",
                               "-AppleLanguages", "(ar)", "-AppleLocale", "ar_IQ"]
        app.launch()
        let home = element("tv.home.showcase")
        XCTAssertTrue(home.waitForExistence(timeout: 30)); expectFocus(home); remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); expectFocus(play)
        for _ in 0..<3 {
            remote.press(.select)
            // Back must target an actually presented loading/player screen. Before it
            // exists, Menu legitimately pops the detail page instead of cancelling playback.
            XCTAssertTrue(element("tv.player.testClock").waitForExistence(timeout: 10))
            returnToDetails(play)
            XCTAssertFalse(element("tv.player.testClock").exists)
        }
        remote.press(.left)
        waitFor(10) { !play.hasFocus }
        shot("details-responds-after-three-fast-back-cancellations")
    }

    private func checkExit(compatibility: Bool, seconds: Int) {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player",
                               "-ios.local.progress.v1", "", "-ios.local.recent.v1", "",
                               "-AppleLanguages", "(ar)", "-AppleLocale", "ar_IQ"]
        if compatibility { app.launchArguments.append("--cinema-ui-testing-compatibility") }
        app.launch()
        let home = element("tv.home.showcase")
        XCTAssertTrue(home.waitForExistence(timeout: 30)); expectFocus(home); remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); expectFocus(play); remote.press(.select)
        let clock = element("tv.player.testClock")
        waitFor(30) { self.time(clock) > 0 }
        // Advance by wall-clock decoding, not by pre-seeding a long resume position.
        let start = time(clock)
        waitFor(TimeInterval(seconds + 20)) { self.time(clock) >= start + seconds }
        let before = time(clock)
        shot(compatibility ? "compatibility-playing-before-back" : "native-playing-before-back")
        returnToDetails(play)
        shot("details-responsive-after-long-playback")
        remote.press(.left)
        waitFor(10) { !play.hasFocus && self.app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists }
        remote.press(.right); expectFocus(play)
        remote.press(.select)
        // A seek can expose the saved time before the first frame renders. Require the
        // real clock to advance beyond it, rather than mistaking buffering for resume.
        waitFor(30) { self.time(clock) >= before + 2 && clock.label.hasPrefix("true|") }
        remote.press(.playPause)
        waitFor(15) { clock.label.hasPrefix("false|") }
        shot("movie-reopened-at-saved-progress")
        returnToDetails(play)
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func returnToDetails(_ play: XCUIElement) {
        // One Menu may first dismiss AVKit's transport. Existence alone also matches covered details.
        for _ in 0..<4 {
            if !element("tv.player.testClock").exists { break }
            remote.press(.menu)
            let returned = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                !self.element("tv.player.testClock").exists
            }, object: nil)
            if XCTWaiter.wait(for: [returned], timeout: 5) == .completed { break }
            if app.state != .runningForeground { break }
        }
        waitFor(15) { self.app.state == .runningForeground && play.exists && play.isHittable }; expectFocus(play)
    }
    private func time(_ clock: XCUIElement) -> Int { Int(clock.label.split(separator: "|").dropFirst().first ?? "-1") ?? -1 }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func expectFocus(_ target: XCUIElement) { waitFor(15) { target.hasFocus } }
    private func waitFor(_ timeout: TimeInterval, _ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed)
    }
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
