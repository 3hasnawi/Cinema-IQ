import XCTest

/// Captures the real app with the licensed demo source. The screenshots are reviewed alongside assertions.
final class TVVisualAuditTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo"]
        app.launch()
        XCTAssertTrue(firstCard.waitForExistence(timeout: 30))
        wait(2)
    }

    override func tearDown() {
        shot("last-state")
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = name + "-hierarchy"
        tree.lifetime = .keepAlways
        add(tree)
        app.terminate()
    }

    private var firstCard: XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tv.home.feature.")).firstMatch
    }

    func testHomeDetailsAndPlayback() {
        shot("01-home")
        let title = app.staticTexts["tv.home.featuredTitle"]
        XCTAssertTrue(title.isHittable)
        XCTAssertTrue(app.frame.contains(title.frame), "The featured title must stay inside the screen")
        remote.press(.left); wait(1)
        shot("02-next-feature")
        let selectedCard = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND hasFocus == true", "tv.home.feature.")).firstMatch
        XCTAssertTrue(selectedCard.exists)
        let selectedID = selectedCard.identifier
        remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); wait(2)
        shot("03-detail")
        let detailTitle = app.staticTexts["tv.detail.title"]
        XCTAssertTrue(app.frame.contains(detailTitle.frame))
        XCTAssertGreaterThan(detailTitle.frame.minY, 30, "The title needs breathing room above it")
        XCTAssertTrue(app.buttons["tv.detail.trailer"].isHittable)
        XCTAssertTrue(app.buttons["tv.detail.list"].isHittable)
        // The default focused action on entry should be Play.
        XCTAssertTrue(play.hasFocus)
        remote.press(.select); wait(15)
        shot("04-player")
        remote.press(.playPause); wait(2)
        shot("05-paused")
        let pausedTitle = app.staticTexts["tv.player.pausedTitle"]
        XCTAssertTrue(pausedTitle.waitForExistence(timeout: 15), "Playback must start and show information when paused")
        XCTAssertGreaterThan(pausedTitle.frame.midX, app.frame.midX, "Pause information must be on the right")
        remote.press(.playPause)
        let resumed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: pausedTitle)
        XCTAssertEqual(XCTWaiter.wait(for: [resumed], timeout: 10), .completed)
        for _ in 0..<3 {
            remote.press(.menu); wait(1)
            if play.exists && play.isHittable { break }
        }
        XCTAssertTrue(play.exists && play.isHittable)
        remote.press(.menu); wait(2)
        let restored = app.buttons[selectedID]
        XCTAssertTrue(restored.isHittable)
        XCTAssertTrue(restored.hasFocus, "Returning from details must restore the selected featured card")
        shot("06-home-restored")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testShelvesSeriesAndSettings() {
        remote.press(.down); wait(2)
        shot("11-shelves")
        openTab("مسلسلات")
        shot("12-series-grid")
        remote.press(.select); wait(3)
        XCTAssertTrue(app.buttons["tv.detail.play"].waitForExistence(timeout: 15))
        shot("13-series-detail")
        remote.press(.down); wait(2)
        shot("14-series-down")
        XCTAssertTrue(app.buttons["tv.detail.seasons"].exists)
        remote.press(.down); wait(2)
        shot("15-episodes")
        let episodes = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tv.detail.episode."))
        XCTAssertTrue(episodes.firstMatch.isHittable, "The first episode should be reachable below the header")
        XCTAssertTrue(episodes.firstMatch.hasFocus, "Moving down from seasons must focus the first episode")
        // Pop details, then open the native sidebar.
        remote.press(.menu); wait(1)
        openTab("الإعدادات")
        shot("16-settings")
        remote.press(.select); wait(2)
        shot("17-settings-popup")
        remote.press(.menu); wait(1)
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testCinemanaHomeAndDetailsWhenReachable() throws {
        app.terminate()
        app.launchArguments = ["-ios.contentSource.v1", "cinemana"]
        app.launch()
        guard firstCard.waitForExistence(timeout: 35) else {
            shot("21-cinemana-network-unavailable")
            throw XCTSkip("Cinemana catalogue could not be reached from the simulator runner")
        }
        wait(2); shot("22-cinemana-home")
        remote.press(.select)
        XCTAssertTrue(app.buttons["tv.detail.play"].waitForExistence(timeout: 20))
        wait(5); shot("23-cinemana-detail")
    }

    private func openTab(_ title: String) {
        let order = ["البحث", "الرئيسية", "أفلام", "مسلسلات", "مكتبتي", "IPTV", "الإعدادات"]
        func focusedLabel() -> String {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            return focused.exists ? focused.label : ""
        }
        // From a lower shelf, Menu restores the home hero before it opens the sidebar.
        for _ in 0..<3 {
            if order.contains(focusedLabel()) { break }
            remote.press(.menu); wait(1)
        }
        XCTAssertTrue(order.contains(focusedLabel()), "Menu must return focus to the native sidebar")
        for _ in 0..<12 {
            let label = focusedLabel()
            if label == title { remote.press(.select); wait(3); return }
            guard let index = order.firstIndex(of: label), let target = order.firstIndex(of: title) else {
                XCTFail("Focus left the sidebar: \(label)"); return
            }
            remote.press(index < target ? .down : .up); wait(0.4)
        }
        XCTFail("Could not focus tab: \(title)")
    }

    private func wait(_ duration: TimeInterval) { Thread.sleep(forTimeInterval: duration) }
    private func shot(_ label: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name + "-" + label
        shot.lifetime = .keepAlways
        add(shot)
    }
}
