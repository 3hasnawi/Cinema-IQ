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

    func testQualityAndSpeedMenusRemainInsideScreen() {
        openMenu("الجودة")
        let automatic = button("تلقائي")
        assertInsideScreen(automatic)
        shot("quality-menu")
        remote.press(.menu)
        openMenu("السرعة")
        let normal = button("عادية")
        assertInsideScreen(normal)
        shot("speed-menu")
        focus(normal)
        remote.press(.select)
        closePlayerAndExpectDetails()
    }

    func testSubtitleMenuAndNestedAppearanceOptionsRemainInsideScreen() {
        openMenu("الترجمة")
        assertInsideScreen(button("بدون ترجمة"))
        assertInsideScreen(button("الحجم"))
        assertInsideScreen(button("الخلفية"))
        shot("subtitle-menu")
        focus(button("الحجم"))
        remote.press(.select)
        let large = button("كبير")
        assertInsideScreen(large)
        shot("subtitle-size-menu")
        focus(large)
        remote.press(.select)
        openMenu("الترجمة")
        focus(button("الخلفية"))
        remote.press(.select)
        let dark = button("داكنة")
        assertInsideScreen(dark)
        shot("subtitle-background-menu")
        focus(dark)
        remote.press(.select)
        openMenu("الترجمة")
        focus(button("بدون ترجمة"))
        remote.press(.select)
        closePlayerAndExpectDetails()
    }

    private func button(_ label: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func openMenu(_ title: String) {
        let target = button(title)
        XCTAssertTrue(target.waitForExistence(timeout: 10), "Missing native transport menu: \(title)")
        focus(target)
        remote.press(.select)
    }

    private func focus(_ target: XCUIElement) {
        XCTAssertTrue(target.waitForExistence(timeout: 10))
        for _ in 0..<12 {
            if target.hasFocus { return }
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            if !focused.exists { remote.press(.up); continue }
            let source = focused.frame, destination = target.frame
            if abs(source.midY - destination.midY) > 35 {
                remote.press(destination.midY < source.midY ? .up : .down)
            } else {
                remote.press(destination.midX < source.midX ? .left : .right)
            }
        }
        XCTAssertTrue(target.hasFocus, "The remote could not focus \(target.label)")
    }

    private func expectFocus(_ target: XCUIElement) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: target)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed)
    }

    private func assertInsideScreen(_ target: XCUIElement) {
        XCTAssertTrue(target.waitForExistence(timeout: 10))
        XCTAssertTrue(target.isHittable, "The native option must be visible")
        XCTAssertTrue(app.frame.insetBy(dx: 24, dy: 24).contains(target.frame),
                      "Native option is clipped by the screen: \(target.label) \(target.frame)")
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
