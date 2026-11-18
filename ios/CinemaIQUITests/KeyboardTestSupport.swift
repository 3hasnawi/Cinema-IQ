import XCTest

/// Only acknowledges the first-use keyboard card observed in simulator PNG/AX evidence.
/// Other alerts and app actions, including authentication, remain untouched.
enum KeyboardTestSupport {
    private static let explanationText = "Speed up your typing by sliding your finger across the letters to compose a word."

    static func installIntroductionMonitor(on testCase: XCTestCase) {
        testCase.addUIInterruptionMonitor(withDescription: "First-use keyboard introduction") { introduction in
            let explanation = introduction.staticTexts[explanationText]
            let proceed = introduction.buttons["Continue"]
            guard explanation.exists && proceed.exists else { return false }
            proceed.tap()
            return true
        }
    }

    static func dismissIntroductionIfPresent(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        // A fresh hosted simulator can place this teaching card over a valid toolbar.
        // Match both its exact explanation and button, never a generic Continue action.
        let explanation = app.staticTexts[explanationText]
        let proceed = app.buttons["Continue"]
        guard explanation.exists && proceed.exists else { return }
        XCTAssertTrue(proceed.isHittable, "The observed keyboard introduction must be dismissible", file: file, line: line)
        proceed.tap()
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !explanation.exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed,
                       "Keyboard introduction must close before testing focus or Done", file: file, line: line)
    }
}
