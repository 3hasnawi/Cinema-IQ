import XCTest

/// Real decoder playback and the unchanged production HUD timeout; all taps use screen coordinates.
final class MovieControlsTests: XCTestCase {
    private let app = XCUIApplication()
    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player", "--cinema-ui-testing-player-controls"]
        if name.contains("Compatibility") { app.launchArguments.append("--cinema-ui-testing-compatibility") }
        app.launch()
    }
    override func tearDown() {
        shot("last-state")
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "movie-controls-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        app.terminate()
    }
    func testAppleControlsReturnAfterAutomaticHide() { verifyControls() }
    func testCompatibilityControlsReturnAfterAutomaticHide() { verifyControls() }
    private func verifyControls() {
        let browse = app.buttons.matching(NSPredicate(format: "label == %@", "تصفّح")).firstMatch
        XCTAssertTrue(browse.waitForExistence(timeout: 25)); browse.tap()
        let poster = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.poster.grid-")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 20)); poster.tap()
        let start = element("ios.detail.play")
        XCTAssertTrue(start.waitForExistence(timeout: 20)); start.tap()
        let video = element("ios.player.video")
        XCTAssertTrue(video.waitForExistence(timeout: 20))
        waitFor { self.seconds(video) >= 1 }
        waitFor { !self.element("ios.player.close").exists }
        shot("automatic-hide")
        centerTap()
        XCTAssertTrue(element("ios.player.close").waitForExistence(timeout: 10), "A real centre tap must restore controls after their production timeout")
        let pause = element("ios.player.playPause")
        pause.tap()
        waitFor { pause.label == "تشغيل" }
        shot("controls-restored-paused")
        element("ios.player.options").tap()
        XCTAssertTrue(element("ios.player.options.panel").waitForExistence(timeout: 10))
        shot("options-reachable")
        element("ios.player.options").tap()
        pause.tap(); waitFor { pause.label == "إيقاف مؤقت" }
        XCUIDevice.shared.orientation = .landscapeLeft
        waitFor { self.app.frame.width > self.app.frame.height }
        waitFor { !self.element("ios.player.close").exists }
        centerTap()
        XCTAssertTrue(element("ios.player.close").waitForExistence(timeout: 10))
        shot("landscape-controls-restored")
        element("ios.player.close").tap()
        XCTAssertTrue(start.waitForExistence(timeout: 15))
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
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = (self.name.contains("Compatibility") ? "compatibility-" : "apple-") + name
        attachment.lifetime = .keepAlways; add(attachment)
    }
}
