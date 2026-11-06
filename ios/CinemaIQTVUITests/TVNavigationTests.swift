import XCTest

/// Focused acceptance checks use the built-in, licensed demo catalog, never a viewer's account or ISP catalog.
final class TVNavigationTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        if let testRun, !testRun.hasSucceeded {
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.name = "navigation-failure-hierarchy"
            hierarchy.lifetime = .keepAlways
            add(hierarchy)
            capture("navigation-failure-screen")
        }
        super.tearDown()
    }

    func testHomeCompositionCarouselAndDetailBack() {
        launch("home")
        let hero = app.buttons["tv.hero.details"]
        XCTAssertTrue(hero.waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["tv.navigation.home"].exists)
        XCTAssertTrue(app.buttons["tv.navigation.library"].exists)
        XCTAssertTrue(app.buttons["tv.navigation.search"].exists)
        XCTAssertTrue(app.buttons["tv.navigation.settings"].exists)
        XCTAssertLessThan(app.buttons["tv.navigation.home"].frame.maxY, hero.frame.minY)
        capture("01-cinematic-home")

        focusVertically(hero, direction: .down)
        remote.press(.select)
        wait(2)
        XCTAssertFalse(app.buttons["tv.navigation.home"].isHittable, "Root navigation must not cover title details")
        capture("02-title-details")
        remote.press(.menu)
        XCTAssertTrue(hero.waitForExistence(timeout: 10))
        XCTAssertEqual(app.state, .runningForeground)

        let second = app.buttons["tv.hero.page.1"]
        XCTAssertTrue(second.exists)
        focusVertically(second, direction: .down)
        remote.press(.select)
        wait(1)
        XCTAssertTrue(second.isSelected)
        capture("03-next-feature")
    }

    func testFourTabsAndRepeatedHomeSelection() {
        launch("home")
        XCTAssertTrue(app.buttons["tv.hero.details"].waitForExistence(timeout: 30))
        for tab in ["library", "search", "settings", "home", "home"] {
            selectTab(tab)
            XCTAssertEqual(app.state, .runningForeground)
            XCTAssertTrue(app.buttons["tv.navigation.\(tab)"].isSelected)
            capture("tab-\(tab)")
        }
        XCTAssertTrue(app.buttons["tv.hero.details"].exists)
    }

    func testSearchQuerySurvivesTabRoundTrip() {
        launch("search")
        let field = app.textFields["tv.search.query"]
        XCTAssertTrue(field.waitForExistence(timeout: 20))
        focusVertically(field, direction: .down)
        remote.press(.select)
        wait(1)
        // Type into the active native editor, which tvOS may host separately from the original field.
        app.typeText("Bunny\n")
        wait(2)
        if app.keyboards.firstMatch.exists { remote.press(.menu); wait(1) }
        XCTAssertTrue((field.value as? String ?? "").contains("Bunny"))
        selectTab("library")
        selectTab("search")
        XCTAssertTrue((field.value as? String ?? "").contains("Bunny"), "Switching tabs must retain the query")
        capture("search-query-retained")
    }

    func testCatalogShortcutAndMenuReturn() {
        launch("movies")
        XCTAssertTrue(app.buttons["tv.navigation.home"].waitForExistence(timeout: 20))
        wait(2)
        remote.press(.menu)
        XCTAssertTrue(app.buttons["tv.hero.details"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["tv.hero.details"].isHittable)
        XCTAssertEqual(app.state, .runningForeground)
        capture("catalog-menu-returns-home")
    }

    func testPlaybackInfoOverlayWithNativeControls() {
        launch("home")
        let hero = app.buttons["tv.hero.details"]
        XCTAssertTrue(hero.waitForExistence(timeout: 30))
        focusVertically(hero, direction: .down)
        remote.press(.playPause)
        let title = app.staticTexts["player.infoTitle"]
        wait(8)
        remote.press(.playPause)
        XCTAssertTrue(title.waitForExistence(timeout: 40), "Pausing the demo player reveals its title without replacing native controls")
        XCTAssertTrue(title.label.contains("Big Buck Bunny"))
        let year = app.staticTexts["player.infoYear"]
        XCTAssertTrue(year.exists)
        XCTAssertTrue(year.label.contains("2008"))
        XCTAssertGreaterThan(title.frame.midX, app.frame.midX)
        XCTAssertLessThan(title.frame.maxY, app.frame.midY, "The title must leave the lower transport bar clear")
        capture("player-paused-info-overlay")
        remote.press(.playPause)
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: title)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 20), .completed, "The card must hide with native controls during playback")
        // Native tvOS uses Up to reveal transport controls; Down opens content information.
        remote.press(.up)
        XCTAssertTrue(title.waitForExistence(timeout: 10), "Showing native controls reveals the same card")
        capture("player-native-controls-info-overlay")
        for _ in 0..<3 where !hero.isHittable { remote.press(.menu); wait(1) }
        XCTAssertTrue(hero.isHittable)
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func launch(_ tab: String) {
        app = XCUIApplication()
        app.launchArguments = ["-tv.testSource", "ext:demo", "-tv.initialTab", tab]
        app.launch()
    }

    private func selectTab(_ tab: String) {
        let target = app.buttons["tv.navigation.\(tab)"]
        XCTAssertTrue(target.waitForExistence(timeout: 15))
        for _ in 0..<12 {
            if focusedNavigation() != nil { break }
            remote.press(.up); wait(0.4)
        }
        for _ in 0..<8 where !target.hasFocus {
            guard let focused = focusedNavigation() else {
                remote.press(.up); wait(0.4); continue
            }
            remote.press(target.frame.midX < focused.frame.midX ? .left : .right)
            wait(0.4)
        }
        XCTAssertTrue(target.hasFocus, "Cannot focus \(tab)")
        remote.press(.select)
        wait(1)
    }

    private func focusedNavigation() -> XCUIElement? {
        ["home", "library", "search", "settings"].map { app.buttons["tv.navigation.\($0)"] }
            .first { $0.exists && $0.hasFocus }
    }

    private func focusVertically(_ target: XCUIElement, direction: XCUIRemote.Button) {
        for _ in 0..<10 where !target.hasFocus {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            // Carousel dots share a row, so reach the requested one horizontally once in that row.
            if target.identifier.hasPrefix("tv.hero.page."), focused.identifier.hasPrefix("tv.hero.page.") {
                remote.press(target.frame.midX < focused.frame.midX ? .left : .right)
            } else {
                remote.press(direction)
            }
            wait(0.4)
        }
        XCTAssertTrue(target.hasFocus, "Cannot focus \(target.identifier)")
    }

    private func wait(_ duration: TimeInterval) { Thread.sleep(forTimeInterval: duration) }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
