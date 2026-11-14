import XCTest

/// Captures the real app with the licensed demo source. The screenshots are reviewed alongside assertions.
final class TVVisualAuditTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo"]
        app.launch()
        XCTAssertTrue(showcase.waitForExistence(timeout: 30))
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

    private var showcase: XCUIElement {
        app.descendants(matching: .any).matching(identifier: "tv.home.showcase").firstMatch
    }

    func testHomeDetailsAndPlayback() {
        shot("01-home")
        XCTAssertTrue(showcase.isHittable)
        XCTAssertTrue(app.frame.contains(showcase.frame), "The showcase must fit the screen")
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tv.home.feature.")).count, 0)
        XCTAssertFalse(app.staticTexts["أعمال مختارة"].exists)
        remote.press(.left); wait(1)
        shot("02-next-feature")
        XCTAssertTrue(showcase.hasFocus)
        let selectedTitle = showcase.label
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
        XCTAssertTrue(showcase.isHittable)
        XCTAssertTrue(showcase.hasFocus, "Returning from details must restore showcase focus")
        XCTAssertEqual(showcase.label, selectedTitle, "Returning must preserve the selected featured work")
        shot("06-home-restored")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testCarouselRemoteWrapAndAutomaticAdvance() {
        // Launch/snapshot work can span an automatic dwell. Start the manual checks immediately after
        // a real remote input rather than assuming the initial backdrop remains still during setup.
        for _ in 0..<6 {
            remote.press(.left)
            if showcase.label == "Big Buck Bunny" { break }
        }
        let initial = showcase.label
        XCTAssertEqual(initial, "Big Buck Bunny")
        XCTAssertEqual(showcase.value as? String, "1 من 4", "Only the demo's four films belong in the movie showcase")
        remote.press(.right)
        expectShowcase("Elephants Dream")
        remote.press(.left)
        expectShowcase(initial)
        remote.press(.left)
        expectShowcase("Sintel")
        remote.press(.right)
        expectShowcase(initial)
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label != %@", initial), object: showcase)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 12), .completed, "The backdrop should advance automatically")
        XCTAssertTrue(showcase.hasFocus, "Rotation must not reset remote focus")
        shot("07-automatic-backdrop")
    }

    func testCarouselStopsWhileBrowsingShelves() {
        remote.press(.down); wait(2)
        XCTAssertFalse(showcase.hasFocus, "Down must enter the shelves")
        let selected = showcase.label
        wait(10)
        XCTAssertEqual(showcase.label, selected, "Rotation must stop while browsing shelves")
        remote.press(.menu); wait(2)
        XCTAssertTrue(showcase.hasFocus, "Menu should restore the full-screen backdrop")
        XCTAssertEqual(showcase.label, selected)
        shot("08-backdrop-after-shelves")
    }

    private func expectShowcase(_ title: String) {
        let matches = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", title), object: showcase)
        XCTAssertEqual(XCTWaiter.wait(for: [matches], timeout: 5), .completed)
        XCTAssertTrue(showcase.hasFocus)
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
        remote.press(.select); wait(1)
        XCTAssertTrue(setting("source").waitForExistence(timeout: 10))
        remote.press(.select); wait(2)
        shot("17-settings-popup")
        XCTAssertTrue(element("tv.popup.option.ext:demo").exists)
        remote.press(.menu); wait(1)
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testCinemanaHomeAndDetailsWhenReachable() throws {
        app.terminate()
        app.launchArguments = ["-ios.contentSource.v1", "cinemana"]
        app.launch()
        guard showcase.waitForExistence(timeout: 35) else {
            shot("21-cinemana-network-unavailable")
            throw XCTSkip("Cinemana catalogue could not be reached from the simulator runner")
        }
        wait(2); shot("22-cinemana-home")
        remote.press(.select)
        XCTAssertTrue(app.buttons["tv.detail.play"].waitForExistence(timeout: 20))
        wait(5); shot("23-cinemana-detail")
    }

    func testSettingsQualityPreferencePersists() {
        openTab("الإعدادات")
        enterSettings("playback")
        let quality = setting("quality")
        XCTAssertTrue(quality.waitForExistence(timeout: 10))
        remote.press(.select); wait(1)
        XCTAssertTrue(element("tv.popup.option.auto").exists)
        for _ in 0..<3 { remote.press(.down) }
        XCTAssertTrue(element("tv.popup.option.720").hasFocus)
        remote.press(.select)
        expectValue(quality, "720p")
        shot("30-settings-quality")
        app.terminate(); app.launch()
        XCTAssertTrue(showcase.waitForExistence(timeout: 30))
        openTab("الإعدادات")
        enterSettings("playback")
        expectValue(setting("quality"), "720p")
        remote.press(.select); wait(1)
        for _ in 0..<3 { remote.press(.up) }
        remote.press(.select)
        expectValue(setting("quality"), "تلقائي")
        shot("31-settings-automatic")
    }

    func testSubtitleSettingsPreview() {
        openTab("الإعدادات")
        enterSettings("subtitles")
        XCTAssertTrue(setting("language").waitForExistence(timeout: 10))
        remote.press(.down)
        // A native tvOS List focuses its cell, while the labelled button remains inside that cell.
        let sizeCell = app.cells.containing(.button, identifier: "tv.settings.option.size").firstMatch
        XCTAssertTrue(sizeCell.hasFocus, "Down must focus the subtitle size row")
        remote.press(.select); wait(1)
        remote.press(.down)
        remote.press(.select)
        expectValue(setting("size"), "كبير")
        remote.press(.down)
        remote.press(.select); wait(1)
        remote.press(.down)
        remote.press(.select)
        expectValue(setting("background"), "داكنة")
        XCTAssertTrue(element("tv.settings.subtitle.preview").exists)
        shot("32-settings-subtitle-preview")
        remote.press(.up); remote.press(.up)
        remote.press(.select); wait(1)
        remote.press(.down); remote.press(.down)
        remote.press(.select)
        expectValue(setting("language"), "بدون ترجمة")
        shot("33-settings-subtitles-off")
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
    private func setting(_ key: String) -> XCUIElement { element("tv.settings.option." + key) }
    private func enterSettings(_ section: String) {
        let order = ["source", "account", "playback", "subtitles", "about"]
        let target = element("tv.settings.category." + section)
        XCTAssertTrue(target.waitForExistence(timeout: 15))
        for _ in 0..<8 {
            if target.hasFocus { remote.press(.select); wait(1); return }
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let key = focused.identifier.replacingOccurrences(of: "tv.settings.category.", with: "")
            guard let current = order.firstIndex(of: key), let requested = order.firstIndex(of: section) else {
                XCTFail("Settings category did not receive focus: \(focused.identifier)"); return
            }
            remote.press(current < requested ? .down : .up)
        }
        XCTFail("Settings category could not be reached")
    }
    private func expectValue(_ control: XCUIElement, _ value: String) {
        let expected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", value), object: control)
        XCTAssertEqual(XCTWaiter.wait(for: [expected], timeout: 10), .completed)
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
