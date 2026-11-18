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
        // The workflow delivered the URL with simctl openurl before this test; activating keeps that launch intact.
        app.activate()
        let title = app.staticTexts["tv.detail.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 30))
        XCTAssertEqual(title.label, "Sintel")
        XCTAssertTrue(app.buttons["tv.detail.play"].exists)
        remote.press(.menu)
        XCTAssertTrue(app.buttons["tv.home.showcase"].waitForExistence(timeout: 15))
    }
    func testSystemPlayURLStartsTheExistingPlayer() {
        app.activate()
        // A local 10-minute reference clip avoids provider account/CDN timing.
        Thread.sleep(forTimeInterval: 5)
        remote.press(.playPause)
        let paused = app.staticTexts["tv.player.pausedTitle"]
        XCTAssertTrue(paused.waitForExistence(timeout: 25))
        XCTAssertEqual(paused.label, "Big Buck Bunny")
        for _ in 0..<3 {
            remote.press(.menu)
            if app.buttons["tv.detail.play"].waitForExistence(timeout: 2) { break }
        }
        XCTAssertTrue(app.buttons["tv.detail.play"].exists)
    }
}
