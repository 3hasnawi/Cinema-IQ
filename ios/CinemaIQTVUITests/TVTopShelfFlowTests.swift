import XCTest

/// Uses the actual TVServices content loader, App Group container and registered system URL scheme.
final class TVTopShelfFlowTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared
    override func setUp() {
        continueAfterFailure = false
        for stage in ["prime", "display", "play"] { try? FileManager.default.removeItem(at: marker(stage)) }
    }
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
        ready("prime")
        let paused = pauseSystemLinkedMovie()
        XCTAssertEqual(paused.label, "Big Buck Bunny")
        // Only now does the host driver send display, while the real movie cover is open.
        ready("display")
        let title = app.staticTexts.matching(NSPredicate(format: "identifier == %@ AND label == %@", "tv.detail.title", "Sintel")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 45))
        XCTAssertEqual(title.label, "Sintel")
        XCTAssertTrue(app.buttons["tv.detail.play"].exists)
        remote.press(.menu)
        XCTAssertTrue(app.buttons["tv.home.showcase"].waitForExistence(timeout: 15))
    }
    func testSystemPlayURLStartsTheExistingPlayer() {
        launchForLink()
        ready("play")
        let paused = pauseSystemLinkedMovie()
        XCTAssertEqual(paused.label, "Big Buck Bunny")
        for _ in 0..<3 {
            remote.press(.menu)
            if app.buttons["tv.detail.play"].waitForExistence(timeout: 2) { break }
        }
        XCTAssertTrue(app.buttons["tv.detail.play"].exists)
    }
    private func launchForLink() {
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player"]
        app.launch()
        XCTAssertTrue(app.buttons["tv.home.showcase"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.state, .runningForeground)
    }
    private func marker(_ stage: String) -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("top-shelf-link-ready-" + stage)
    }
    private func ready(_ stage: String) {
        do {
            let url = marker(stage)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("ready".utf8).write(to: url, options: .atomic)
        } catch { XCTFail("Could not notify the host system-link driver") }
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
