import XCTest

/// Unsupported-format movies retain the movie layout while the real compatibility decoder renders video.
final class TVMovieCompatibilityTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared

    override func tearDown() {
        shot("last-state")
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "movie-compatibility-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        app.terminate()
    }

    func testMovieLayoutMenusSeekAndRemoteReturn() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player", "--cinema-ui-testing-compatibility",
                               "-AppleLanguages", "(ar)", "-AppleLocale", "ar_IQ"]
        app.launch()
        let home = element("tv.home.showcase")
        XCTAssertTrue(home.waitForExistence(timeout: 30)); expectFocus(home); remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); expectFocus(play); remote.press(.select)
        let surface = element("tv.compatibility.surface")
        XCTAssertTrue(surface.waitForExistence(timeout: 20)); expectFocus(surface)
        waitFor { (surface.value as? String) == "تشغيل" }
        remote.press(.playPause)
        waitFor { (surface.value as? String) == "متوقف مؤقتًا" }
        XCTAssertTrue(app.staticTexts["tv.player.pausedTitle"].waitForExistence(timeout: 15))
        let timeline = element("tv.movie.compatibility.timeline")
        let quality = element("tv.movie.compatibility.quality")
        XCTAssertTrue(timeline.exists && quality.exists)
        XCTAssertLessThanOrEqual(quality.frame.width, 90)
        XCTAssertLessThanOrEqual(quality.frame.height, 90)
        XCTAssertLessThan(quality.frame.midY, timeline.frame.midY)
        let overview = app.staticTexts["tv.player.pausedOverview"]
        if overview.exists { XCTAssertLessThan(overview.frame.maxY, quality.frame.minY, "Movie information must not overlap the icon menus") }
        XCTAssertFalse(app.buttons["إخفاء"].exists)
        shot("movie-compatibility-restored-pause-layout")
        remote.press(.up); expectFocus(quality); remote.press(.select)
        let automatic = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR label == %@", "تلقائي", "selected, تلقائي")).firstMatch
        XCTAssertTrue(automatic.waitForExistence(timeout: 10))
        shot("movie-compatibility-quality-menu")
        remote.press(.menu); expectFocus(quality)
        remote.press(.menu); expectFocus(surface)
        let before = timeline.value as? String
        remote.press(.right)
        waitFor { (timeline.value as? String) != before }
        shot("movie-compatibility-remote-seek")
        remote.press(.playPause); waitFor { (surface.value as? String) == "تشغيل" }
        remote.press(.menu)
        XCTAssertTrue(play.waitForExistence(timeout: 15)); expectFocus(play)
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func expectFocus(_ target: XCUIElement) {
        // SwiftUI Menu exposes its focused native child beneath the labelled button.
        waitFor { target.hasFocus || target.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists }
    }
    private func waitFor(_ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 25), .completed)
    }
    private func shot(_ label: String) {
        let image = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        image.name = label; image.lifetime = .keepAlways; add(image)
    }
}
