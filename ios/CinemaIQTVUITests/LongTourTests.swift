import XCTest

/// A long walk through the app with the default source, as a viewer would use it: series and their episodes, the
/// player's panels, movies, search, library and settings. Every step keeps a screenshot named after the focused
/// element, and a crash is recorded (and the app relaunched) instead of stopping the walk.
final class LongTourTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app = XCUIApplication()
    private var step = 0
    private var crashes = 0

    override func setUp() {
        continueAfterFailure = true
    }

    func testLongTour() {
        launch()
        shot("home")

        for pick in 0..<3 {
            openTab("مسلسلات")
            shot("series-grid")
            for _ in 0..<pick { press(.left) }
            press(.select, wait: 10)
            shot("series-page")
            // Down through the page until an episode has focus (or a few presses fail to get there).
            for _ in 0..<6 {
                press(.down, wait: 2)
                shot("series-down")
                if focusedLabel().contains("الحلقة") { break }
            }
            press(.left, wait: 2)
            shot("episode-next")
            press(.select, wait: 15)
            shot("episode-playing")
            press(.down, wait: 3)
            shot("player-panel")
            press(.down, wait: 3)
            shot("player-panel-down")
            press(.right, wait: 2)
            shot("player-panel-right")
            press(.menu, wait: 2)
            press(.menu, wait: 4)
            shot("after-player")
            // Up again to the seasons and the buttons.
            for _ in 0..<4 { press(.up, wait: 1.5) }
            shot("series-back-up")
            press(.menu, wait: 3)
            shot("series-left-page")
        }

        for pick in 0..<2 {
            openTab("أفلام")
            for _ in 0..<pick { press(.down) }
            press(.select, wait: 10)
            shot("movie-page")
            press(.select, wait: 15)
            shot("movie-playing")
            press(.playPause, wait: 2)
            shot("movie-paused")
            press(.right, wait: 1)
            press(.right, wait: 1)
            shot("movie-seek")
            press(.menu, wait: 2)
            press(.menu, wait: 4)
            shot("after-movie")
            press(.menu, wait: 3)
        }

        openTab("الرئيسية")
        for _ in 0..<6 { press(.down, wait: 1.5) }
        shot("home-deep")
        for _ in 0..<6 { press(.left, wait: 0.8) }
        shot("home-deep-left")
        for _ in 0..<8 { press(.up, wait: 1.2) }
        shot("home-top-again")

        openTab("مكتبتي")
        shot("library")
        press(.down, wait: 2)
        press(.select, wait: 8)
        shot("library-open")
        press(.menu, wait: 3)

        openTab("البحث")
        shot("search")
        let field = app.searchFields.firstMatch
        if field.waitForExistence(timeout: 5) {
            field.typeText("حب")
            Thread.sleep(forTimeInterval: 8)
            shot("search-results")
        }
        press(.menu, wait: 3)

        openTab("الإعدادات")
        shot("settings")
        for _ in 0..<5 { press(.down, wait: 1) }
        shot("settings-down")

        XCTAssertEqual(crashes, 0, "The app crashed \(crashes) time(s) during the tour")
    }

    // MARK: Helpers

    private func launch() {
        app = XCUIApplication()
        app.launch()
        Thread.sleep(forTimeInterval: 25)
    }

    /// Opens the sidebar with Menu and moves to the named tab.
    private func openTab(_ name: String) {
        for _ in 0..<3 {
            press(.menu, wait: 2)
            for _ in 0..<8 {
                if focusedLabel().contains(name) { press(.select, wait: 8); return }
                press(focusedIsAbove(name) ? .down : .up, wait: 0.8)
            }
        }
        press(.select, wait: 8)
    }

    private let order = ["البحث", "الرئيسية", "أفلام", "مسلسلات", "مكتبتي", "الإعدادات"]
    private func focusedIsAbove(_ name: String) -> Bool {
        let label = focusedLabel()
        guard let now = order.firstIndex(where: { label.contains($0) }), let target = order.firstIndex(of: name) else { return true }
        return now < target
    }

    private func focusedLabel() -> String {
        let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
        return focused.exists ? focused.label : ""
    }

    private func press(_ button: XCUIRemote.Button, wait: TimeInterval = 1.5) {
        checkAlive()
        remote.press(button)
        Thread.sleep(forTimeInterval: wait)
    }

    /// A crash is counted, kept as a screenshot and the app is started again so the tour goes on.
    private func checkAlive() {
        guard app.state != .runningForeground else { return }
        crashes += 1
        shot("CRASH-\(crashes)")
        launch()
    }

    private func shot(_ name: String) {
        step += 1
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        let label = focusedLabel().prefix(30).replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: "_", with: "-")
        attachment.name = String(format: "L%03d-", step) + name + " [" + label + "]"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
