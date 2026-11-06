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
        shot("home tvOS " + ProcessInfo.processInfo.operatingSystemVersionString.replacingOccurrences(of: "/", with: "-"))

        // Settings first, on a fresh start: each option opens the glass popup.
        openTab("الإعدادات")
        shot("settings-first")
        press(.select, wait: 2)
        shot("settings-source-popup")
        press(.down, wait: 1)
        shot("settings-source-popup-down")
        press(.menu, wait: 2)
        shot("settings-popup-closed")
        for _ in 0..<5 { press(.down, wait: 1) }
        shot("settings-subtitles-row")
        press(.select, wait: 2)
        shot("settings-subtitles-popup")
        press(.menu, wait: 2)

        for pick in 0..<3 {
            openTab("مسلسلات")
            shot("series-grid")
            for _ in 0..<pick { press(.left) }
            press(.select, wait: 10)
            shot("series-page")
            // Down through the page until an episode has focus (or a few presses fail to get there).
            var pickedSeason = false
            for _ in 0..<8 {
                press(.down, wait: 2)
                shot("series-down")
                let label = focusedLabel()
                if label.contains("الحلقة") && !label.contains("الموسم") { break }
                // The season button: open the glass list, take the next season, then go on down.
                if label.contains("الموسم") && !pickedSeason {
                    pickedSeason = true
                    press(.select, wait: 2)
                    shot("season-list")
                    press(.down, wait: 1)
                    shot("season-list-down")
                    press(.select, wait: 3)
                    shot("season-chosen")
                    press(.up, wait: 1.5)
                    shot("season-up-to-buttons")
                }
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
            press(.menu, wait: 2)
            press(.menu, wait: 4)
            shot("after-player")
            // Up again to the seasons and the buttons.
            for _ in 0..<4 { press(.up, wait: 1.5) }
            shot("series-back-up")
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
        let field = app.textFields["tv.search.query"]
        if field.waitForExistence(timeout: 5) {
            field.typeText("حب")
            Thread.sleep(forTimeInterval: 8)
            shot("search-results")
        }
        press(.menu, wait: 3)

        openTab("الإعدادات")
        shot("settings")
        press(.select, wait: 2)
        shot("settings-popup")
        press(.down, wait: 1)
        shot("settings-popup-down")
        press(.menu, wait: 2)
        shot("settings-popup-closed")
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

    /// Uses the floating bar, with Home shortcuts for the two catalog destinations.
    private func openTab(_ name: String) {
        let ids = ["الرئيسية": "home", "مكتبتي": "library", "البحث": "search", "الإعدادات": "settings"]
        let destination = ids[name] ?? "home"
        let target = app.buttons["tv.navigation.\(destination)"]
        for _ in 0..<6 where !target.isHittable { press(.menu, wait: 1) }
        for _ in 0..<12 {
            if focusedNavigation() != nil { break }
            press(.up, wait: 0.5)
        }
        for _ in 0..<8 where !target.hasFocus {
            guard let focused = focusedNavigation() else { press(.up, wait: 0.5); continue }
            press(target.frame.midX < focused.frame.midX ? .left : .right, wait: 0.5)
        }
        guard target.hasFocus else { XCTFail("Could not reach floating navigation: \(name)"); return }
        press(.select, wait: 3)
        if ids[name] == nil {
            let catalog = app.buttons[name == "أفلام" ? "tv.catalog.movies" : "tv.catalog.series"]
            for _ in 0..<40 where !catalog.hasFocus {
                let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
                if focused.identifier.hasPrefix("tv.catalog.") {
                    press(catalog.frame.midX < focused.frame.midX ? .left : .right, wait: 0.5)
                } else { press(.down, wait: 0.5) }
            }
            guard catalog.hasFocus else { XCTFail("Could not reach catalog shortcut: \(name)"); return }
            press(.select, wait: 8)
        }
    }

    private func focusedNavigation() -> XCUIElement? {
        ["home", "library", "search", "settings"].map { app.buttons["tv.navigation.\($0)"] }
            .first { $0.exists && $0.hasFocus }
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
