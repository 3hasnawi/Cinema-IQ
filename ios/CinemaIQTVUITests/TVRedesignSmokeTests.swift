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

    func testFeaturedCardOpensDetailsAndBackRestoresHome() {
        let cards = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tv.home.feature."))
        let first = cards.firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 30), "The demo home did not load a featured card")
        XCTAssertTrue(first.hasFocus, "The first featured card should receive initial remote focus")
        remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20), "Selecting a card should open the details page")
        remote.press(.menu)
        XCTAssertTrue(first.waitForExistence(timeout: 10), "Back should restore the home page")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testIPTVIsAComingSoonSection() {
        let first = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tv.home.feature.")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 30))
        remote.press(.menu)
        let order = ["البحث", "الرئيسية", "أفلام", "مسلسلات", "مكتبتي", "IPTV", "الإعدادات"]
        for _ in 0..<12 {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let label = focused.exists ? focused.label : ""
            if label.contains("IPTV") { remote.press(.select); break }
            let position = order.firstIndex(where: { label.contains($0) })
            remote.press((position ?? 0) < 5 ? .down : .up)
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTAssertTrue(app.staticTexts["tv.iptv.heading"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["قريبًا"].exists)
        XCTAssertEqual(app.state, .runningForeground)
    }
}
