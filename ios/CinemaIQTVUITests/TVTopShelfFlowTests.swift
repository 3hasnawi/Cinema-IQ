import XCTest

/// Uses the actual TVServices content loader, App Group container and registered system URL scheme.
final class TVTopShelfFlowTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared
    override func setUp() { continueAfterFailure = false }
    override func tearDown() {
        let capture = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); capture.name = name; capture.lifetime = .keepAlways; add(capture)
        let tree = XCTAttachment(string: app.debugDescription); tree.name = "top-shelf-hierarchy"; tree.lifetime = .keepAlways; add(tree)
        app.terminate()
    }
    func testSharedSnapshotLoadsNativeTopShelfContent() {
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-top-shelf-testing", "--cinema-ui-testing-player"]
        app.launch()
        let audit = app.staticTexts["tv.topshelf.audit"]
        XCTAssertTrue(audit.waitForExistence(timeout: 30))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "shared-ready"), object: audit)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed,
                       "The ad-hoc simulator must actually write/read the shared snapshot and create native TVServices content")
        XCTAssertTrue(app.buttons["tv.home.showcase"].exists)
    }
    func testSystemDisplayURLShowsDetails() {
        launchForLink()
        openLink(item: "big-buck-bunny", action: "play")
        let paused = pauseSystemLinkedMovie()
        XCTAssertEqual(paused.label, "Big Buck Bunny")
        // Open the next real OS URL while the movie cover is present.
        openLink(item: "sintel", action: "display")
        let title = app.staticTexts.matching(NSPredicate(format: "identifier == %@ AND label == %@", "tv.detail.title", "Sintel")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 45))
        XCTAssertEqual(title.label, "Sintel")
        XCTAssertTrue(app.buttons["tv.detail.play"].exists)
        remote.press(.menu)
        XCTAssertTrue(app.buttons["tv.home.showcase"].waitForExistence(timeout: 15))
    }
    func testSystemPlayURLStartsTheExistingPlayer() {
        launchForLink()
        openLink(item: "big-buck-bunny", action: "play")
        let paused = pauseSystemLinkedMovie()
        XCTAssertEqual(paused.label, "Big Buck Bunny")
        for _ in 0..<3 {
            remote.press(.menu)
            if app.buttons["tv.detail.play"].waitForExistence(timeout: 2) { break }
        }
        XCTAssertTrue(app.buttons["tv.detail.play"].exists)
    }
    private func launchForLink() {
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player", "--cinema-top-shelf-link-testing"]
        app.launch()
        XCTAssertTrue(app.buttons["tv.home.showcase"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.state, .runningForeground)
    }
    private func openLink(item: String, action: String) {
        let url = URL(string: "cinemaiq://top-shelf?media=ext%3Ademo%3A" + item + "&action=" + action)!
        // Public XCTest URL launch (tvOS 16.4+), not an invocation of the app's handler.
        app.open(url)
        let system = XCUIApplication(bundleIdentifier: "com.apple.PineBoard")
        let open = system.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Open", "فتح")).firstMatch
        if open.waitForExistence(timeout: 5) {
            for _ in 0..<3 {
                if open.hasFocus { break }
                remote.press(.left)
            }
            XCTAssertTrue(open.hasFocus, "The remote must select Open, not Cancel")
            remote.press(.select)
        }
    }
    private func pauseSystemLinkedMovie() -> XCUIElement {
        // Wait for real AVKit playback before sending the remote Play/Pause command.
        let home = app.buttons["tv.home.showcase"]
        let leftHome = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: home)
        XCTAssertEqual(XCTWaiter.wait(for: [leftHome], timeout: 45), .completed)
        Thread.sleep(forTimeInterval: 5)
        remote.press(.playPause)
        let paused = app.staticTexts["tv.player.pausedTitle"]
        XCTAssertTrue(paused.waitForExistence(timeout: 30))
        return paused
    }

}
