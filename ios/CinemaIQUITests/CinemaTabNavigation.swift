import XCTest
import UIKit

enum CinemaTabNavigation {
    static func open(_ title: String, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let close = app.buttons["ios.settings.close"]
        if close.exists && close.isHittable {
            if title == "الإعدادات" { return }
            close.tap()
            let gone = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !close.isHittable }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 10), .completed, file: file, line: line)
        }
        if title == "الإعدادات" {
            let profile = app.buttons["ios.home.profile"]
            if !profile.exists || !profile.isHittable { open("الرئيسية", in: app, file: file, line: line) }
            XCTAssertTrue(profile.waitForExistence(timeout: 10), file: file, line: line)
            profile.tap()
            return
        }
        let label = NSPredicate(format: "label == %@", title)
        let cells = app.cells.matching(label)
        let buttons = app.buttons.matching(label)
        func visible(_ matches: XCUIElementQuery) -> XCUIElement? {
            matches.allElementsBoundByIndex.first { $0.exists && $0.isHittable }
        }
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        if tablet && visible(cells) == nil {
            let show = app.buttons.matching(NSPredicate(format:
                "label CONTAINS[c] 'show sidebar' OR label CONTAINS[c] 'عرض الشريط' OR label CONTAINS[c] 'إظهار الشريط'"))
            visible(show)?.tap()
        }
        func destination() -> XCUIElement? {
            visible(cells) ?? visible(app.tabBars.buttons.matching(label)) ?? visible(buttons)
        }
        let reachable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in destination() != nil }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [reachable], timeout: 12), .completed,
                       "The native tab or sidebar row must be reachable: \(title)", file: file, line: line)
        destination()?.tap()
    }
}
