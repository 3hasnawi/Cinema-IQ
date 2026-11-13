import XCTest

/// Real AVKit menus over a local reference clip: no provider account or CDN is needed.
final class TVPlayerMenuTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player",
                               "-AppleLanguages", "(ar)", "-AppleLocale", "ar_IQ"]
        app.launch()
        let home = app.descendants(matching: .any).matching(identifier: "tv.home.showcase").firstMatch
        XCTAssertTrue(home.waitForExistence(timeout: 30))
        remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        expectFocus(play)
        remote.press(.select)
        Thread.sleep(forTimeInterval: 5)
        remote.press(.playPause)
        XCTAssertTrue(app.staticTexts["tv.player.pausedTitle"].waitForExistence(timeout: 15))
        shot("paused-without-app-shadow")
    }

    override func tearDown() {
        shot("last-state")
        let hierarchy = app.debugDescription
        print(hierarchy)
        let attachment = XCTAttachment(string: hierarchy)
        attachment.name = name + "-hierarchy"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    func testQualityAndSpeedMenusSupportRemoteSelection() {
        openMenu("الجودة")
        expectNativeOption("تلقائي")
        shot("quality-menu")
        remote.press(.menu)
        openMenu("السرعة")
        expectNativeOption("عادية")
        shot("speed-menu")
        remote.press(.select)
        closePlayerAndExpectDetails()
    }

    func testSubtitleMenuAndNestedAppearanceOptionsSupportRemoteSelection() {
        openMenu("الترجمة")
        expectNativeOption("بدون ترجمة")
        expectNativeOption("الحجم")
        expectNativeOption("الخلفية")
        shot("subtitle-menu")
        focus("الحجم")
        remote.press(.select)
        expectNativeOption("كبير")
        shot("subtitle-size-menu")
        remote.press(.select)
        openMenu("الترجمة")
        focus("الخلفية")
        remote.press(.select)
        expectNativeOption("داكنة")
        shot("subtitle-background-menu")
        remote.press(.select)
        openMenu("الترجمة")
        focus("بدون ترجمة")
        remote.press(.select)
        closePlayerAndExpectDetails()
    }

    private func menuItems(_ label: String) -> XCUIElementQuery {
        // Liquid Glass exposes both sizing and interactive collections. Their order can change.
        // Query focus across both copies; never bind interaction to a guessed collection index.
        app.cells.matching(NSPredicate(format: "label == %@ OR label == %@", label, "selected, " + label))
    }

    private func openMenu(_ title: String) {
        let target = menuItems(title).firstMatch
        XCTAssertTrue(target.waitForExistence(timeout: 10), "Missing native transport menu: \(title)")
        XCTAssertTrue(app.frame.insetBy(dx: 24, dy: 24).contains(target.frame),
                      "The transport menu anchor must fit the screen")
        focus(title)
        remote.press(.select)
    }

    private func focus(_ label: String) {
        let matches = menuItems(label)
        XCTAssertTrue(matches.firstMatch.waitForExistence(timeout: 10))
        let focusedTarget = matches.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        for _ in 0..<12 {
            if focusedTarget.exists { return }
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            if !focused.exists { remote.press(.up); continue }
            let source = focused.frame, destination = matches.firstMatch.frame
            // AVKit reports its full-screen scrubbing focus proxy, rather than the timeline's frame.
            if source.height > app.frame.height * 0.8 {
                remote.press(.up)
            } else if abs(source.midY - destination.midY) > 35 {
                remote.press(destination.midY < source.midY ? .up : .down)
            } else {
                remote.press(destination.midX < source.midX ? .left : .right)
            }
        }
        XCTAssertTrue(focusedTarget.exists, "The remote could not focus \(label)")
    }

    private func expectFocus(_ target: XCUIElement) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: target)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed)
    }

    private func expectNativeOption(_ label: String) {
        XCTAssertTrue(menuItems(label).firstMatch.waitForExistence(timeout: 10))
        // tvOS 26 exposes menu-local AX frames (e.g. x=5) for a popup drawn at x=1200.
        // Real remote focus/selection are asserted; screenshots verify actual screen geometry.
        focus(label)
    }

    private func closePlayerAndExpectDetails() {
        let play = app.buttons["tv.detail.play"]
        for _ in 0..<4 {
            remote.press(.menu)
            if play.exists && play.isHittable { break }
        }
        XCTAssertTrue(play.waitForExistence(timeout: 10) && play.isHittable)
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func shot(_ label: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name + "-" + label
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
