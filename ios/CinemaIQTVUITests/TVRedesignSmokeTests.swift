import XCTest

/// Navigation smoke on the built-in demo catalogue. No account, provider network or API key is required.
final class TVRedesignSmokeTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo"]
        app.launch()
    }

    func testShowcaseOpensDetailsAndBackRestoresHome() {
        let first = app.descendants(matching: .any).matching(identifier: "tv.home.showcase").firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 30), "The demo home did not load a featured backdrop")
        let focused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: first)
        XCTAssertEqual(XCTWaiter.wait(for: [focused], timeout: 10), .completed,
                       "The first featured backdrop should receive initial remote focus")
        remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20), "Selecting the backdrop should open the details page")
        let playFocus = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: play)
        XCTAssertEqual(XCTWaiter.wait(for: [playFocus], timeout: 10), .completed)
        remote.press(.select)
        Thread.sleep(forTimeInterval: 5)
        XCTAssertEqual(app.state, .runningForeground, "Opening the player must not crash the app")
        // AVKit may consume the first Menu press to hide its transport controls.
        for _ in 0..<3 {
            remote.press(.menu)
            Thread.sleep(forTimeInterval: 1)
            if play.exists && play.isHittable { break }
        }
        XCTAssertTrue(play.exists && play.isHittable, "Closing the player should return to details")
        remote.press(.menu)
        let homeVisible = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: first)
        XCTAssertEqual(XCTWaiter.wait(for: [homeVisible], timeout: 15), .completed,
                       "Back should restore the home page")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testIPTVCanLeaveAndReturn() {
        let first = app.descendants(matching: .any).matching(identifier: "tv.home.showcase").firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 30))
        openIPTV()
        let back = app.buttons["tv.iptv.return"]
        expectIPTVEntryFocus()
        screenshot("iptv-entry")
        remote.press(.menu)
        expectFocus(first)
        XCTAssertTrue(first.isHittable, "Menu must leave IPTV and restore the home backdrop")

        // Re-enter to catch stale focus after the first visit, then leave with the visible action too.
        openIPTV()
        expectIPTVEntryFocus()
        for _ in 0..<3 { if back.hasFocus { break }; remote.press(.left) }
        expectFocus(back)
        remote.press(.select)
        expectFocus(first)
        XCTAssertTrue(first.isHittable, "The return action must restore the home backdrop")
        screenshot("iptv-returned-home")
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func openIPTV() {
        remote.press(.menu)
        let order = ["البحث", "الرئيسية", "أفلام", "مسلسلات", "مكتبتي", "IPTV", "الإعدادات"]
        for _ in 0..<12 {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).allElementsBoundByIndex
            let label = focused.first?.label ?? ""
            if label.contains("IPTV") { remote.press(.select); break }
            let position = order.firstIndex(where: { label.contains($0) })
            remote.press((position ?? 0) < 5 ? .down : .up)
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTAssertTrue(app.staticTexts["tv.iptv.heading"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["tv.iptv.add"].exists)
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func expectIPTVEntryFocus() {
        let add = app.buttons["tv.iptv.add"], back = app.buttons["tv.iptv.return"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (add.isEnabled && add.hasFocus) || back.hasFocus
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
    }

    private func expectFocus(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 15))
        let focused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [focused], timeout: 10), .completed)
    }

    private func screenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
