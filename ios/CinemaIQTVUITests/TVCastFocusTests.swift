import XCTest

/// Deterministic Siri Remote coverage. No metadata keys, viewer accounts, or live catalogs are needed.
final class TVCastFocusTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-tv.testCastFocus"]
        app.launch()
        XCTAssertTrue(app.buttons["tv.cast.fixture.before"].waitForExistence(timeout: 10))
    }

    override func tearDown() {
        if let testRun, testRun.failureCount > 0 { capture("cast-focus-failure") }
        app.terminate()
        super.tearDown()
    }

    func testCastRemainsInformationalAndCanBeEnteredAndLeftRepeatedly() {
        for round in 1...2 {
            focusFirstCredit()
            let first = person(1)
            XCTAssertEqual(first.label, "ممثل بدون صورة")
            XCTAssertFalse(app.buttons["tv.cast.person.1"].exists, "A credit is not an empty action button")
            remote.press(.select)
            XCTAssertTrue(first.hasFocus, "Selecting a credit must not navigate or lose focus")
            XCTAssertEqual(app.staticTexts["tv.cast.fixture.activations"].label, "0")
            capture("cast-circular-focus-round-\(round)")

            remote.press(.down)
            expectFocus(app.buttons["tv.cast.fixture.after"])
            remote.press(.up)
            expectFocus(first)
            remote.press(.up)
            expectFocus(app.buttons["tv.cast.fixture.before"])
        }
    }

    func testRemoteTraversesOffscreenCastAndReturns() {
        focusFirstCredit()
        // The app is RTL: Left advances through the cast, Right returns to its beginning.
        for index in 2...12 {
            remote.press(.left)
            expectFocus(person(index))
        }
        capture("cast-last-credit-scrolled-into-view")
        for index in (1...11).reversed() {
            remote.press(.right)
            expectFocus(person(index))
        }
        capture("cast-first-credit-after-return")
    }

    func testAttributionOpensAndRestoresFocusAfterMenu() {
        let attribution = app.buttons["tv.cast.fixture.attribution"]
        for _ in 0..<6 where !attribution.hasFocus {
            remote.press(.down)
            Thread.sleep(forTimeInterval: 0.2)
        }
        expectFocus(attribution)
        remote.press(.select)
        XCTAssertTrue(app.buttons["tv.cast.attribution.close"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["https://www.tvmaze.com/shows/73612"].exists)
        XCTAssertTrue(app.staticTexts["https://creativecommons.org/licenses/by-sa/4.0/"].exists)
        capture("cast-source-and-license-attribution")
        remote.press(.menu)
        expectFocus(attribution)
    }

    private func person(_ id: Int) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "tv.cast.person.\(id)").firstMatch
    }

    private func focusFirstCredit() {
        remote.press(.down)
        Thread.sleep(forTimeInterval: 0.3)
        for _ in 0..<12 where !person(1).hasFocus {
            remote.press(.right)
            Thread.sleep(forTimeInterval: 0.15)
        }
        expectFocus(person(1))
    }

    private func expectFocus(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 5), .completed,
                       "Could not focus \(element.identifier)", file: file, line: line)
    }

    private func capture(_ name: String) {
        let image = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        image.name = name
        image.lifetime = .keepAlways
        add(image)
    }
}
