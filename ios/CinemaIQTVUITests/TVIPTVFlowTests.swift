import XCTest

/// Real remote navigation and isolated actual Keychain storage; accounts and network responses are fixtures.
final class TVIPTVFlowTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared
    private let base = ["-ios.contentSource.v1", "ext:demo", "--cinema-tv-ui-testing-iptv", "--cinema-tv-ui-testing-iptv-player"]
    override func setUp() { continueAfterFailure = false }
    override func tearDown() {
        shot("last-state")
        let tree = XCTAttachment(string: app.debugDescription); tree.name = "iptv-hierarchy"; tree.lifetime = .keepAlways; add(tree)
        app.terminate()
    }
    func testSourcesGroupsPlaybackAndSelectionPersistence() {
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset", "--cinema-tv-ui-testing-iptv-seed"]
        app.launch(); openIPTV()
        let news = channel("قناة الأخبار")
        expectFocus(news); shot("m3u-channels")
        // Up from the first channel returns to the controls. Reach the source list by physical remote moves.
        focus(element("tv.iptv.sources"), direction: .up)
        remote.press(.select)
        focus(element("tv.iptv.source.tv-fixture-xtream"), direction: .down)
        remote.press(.select)
        let xtream = channel("Xtream News")
        expectFocus(xtream); shot("xtream-channels")
        focus(element("tv.iptv.groups"), direction: .up); remote.press(.select)
        focus(element("tv.iptv.group.أخبار"), direction: .down); remote.press(.select)
        XCTAssertTrue(xtream.waitForExistence(timeout: 20))
        waitFor { !self.channel("Xtream Sport").exists }
        shot("filtered-channels")
        // The second launch must read the saved Xtream source/selection from the real test Keychain.
        app.terminate(); app.launchArguments = base; app.launch(); openIPTV()
        expectFocus(xtream)
        XCTAssertTrue(channel("Xtream Sport").exists)
        let played = channel("Xtream Sport")
        focus(played, direction: .left)
        remote.press(.select)
        let player = element("tv.iptv.player.native")
        XCTAssertTrue(player.waitForExistence(timeout: 20))
        waitFor { (player.value as? String) == "بث مباشر" }
        remote.press(.playPause)
        XCTAssertTrue(element("tv.iptv.player.title").waitForExistence(timeout: 15))
        shot("paused-live-player")
        for _ in 0..<3 { remote.press(.menu); if played.waitForExistence(timeout: 3) { break } }
        expectFocus(played)
        remote.press(.menu)
        expectFocus(element("tv.home.showcase"))
        XCTAssertEqual(app.state, .runningForeground)
    }
    func testAddM3USourceWithNativeKeyboard() {
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset"]
        app.launch(); openIPTV()
        expectFocus(element("tv.iptv.add")); remote.press(.select)
        XCTAssertTrue(element("tv.iptv.form.m3u").waitForExistence(timeout: 15))
        fill("tv.iptv.form.name", "Remote List")
        fill("tv.iptv.form.address", "http://iptv.example/channels.m3u")
        focus(element("tv.iptv.form.allowHTTP"), direction: .down); remote.press(.select)
        focus(element("tv.iptv.form.save"), direction: .down); remote.press(.select)
        expectFocus(channel("قناة الأخبار")); shot("source-added-with-remote")
        XCTAssertEqual(app.state, .runningForeground)
    }
    func testXtreamFormShowsCredentialsAndMenuReturnsSafely() {
        app.launchArguments = base + ["--cinema-tv-ui-testing-iptv-reset"]
        app.launch(); openIPTV()
        expectFocus(element("tv.iptv.add")); remote.press(.select)
        expectFocus(element("tv.iptv.form.m3u"))
        focus(element("tv.iptv.form.xtream"), direction: .left); remote.press(.select)
        XCTAssertTrue(element("tv.iptv.form.username").waitForExistence(timeout: 10))
        XCTAssertTrue(element("tv.iptv.form.password").exists)
        shot("xtream-form")
        remote.press(.menu)
        expectFocus(element("tv.iptv.add"))
        remote.press(.menu)
        XCTAssertTrue(element("tv.home.showcase").waitForExistence(timeout: 15))
    }
    private func fill(_ id: String, _ text: String) {
        let field = element(id)
        focus(field, direction: .down); remote.press(.select)
        app.typeText(text)
        remote.press(.menu) // Native tvOS text bindings retain the entered value when leaving the keyboard.
        waitFor { !self.app.keyboards.firstMatch.exists }
        XCTAssertTrue(element("tv.iptv.form.save").exists)
    }
    private func openIPTV() {
        XCTAssertTrue(element("tv.home.showcase").waitForExistence(timeout: 30))
        remote.press(.menu)
        let order = ["البحث", "الرئيسية", "أفلام", "مسلسلات", "مكتبتي", "IPTV", "الإعدادات"]
        for _ in 0..<14 {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let label = focused.exists ? focused.label : ""
            if label.contains("IPTV") { remote.press(.select); break }
            remote.press((order.firstIndex(where: { label.contains($0) }) ?? 0) < 5 ? .down : .up)
        }
        XCTAssertTrue(element("tv.iptv.heading").waitForExistence(timeout: 20))
    }
    private func focus(_ element: XCUIElement, direction: XCUIRemote.Button) {
        // Move with the actual remote toward the element's live geometry, including horizontal toolbars.
        for _ in 0..<32 {
            if element.exists && isFocused(element) { return }
            guard element.exists else { remote.press(direction); continue }
            let current = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            guard current.exists else { remote.press(direction); continue }
            let dx = element.frame.midX - current.frame.midX, dy = element.frame.midY - current.frame.midY
            if abs(dy) > 65 { remote.press(dy > 0 ? .down : .up) }
            else if abs(dx) > 20 { remote.press(dx > 0 ? .right : .left) }
            else { remote.press(direction) }
        }
        XCTAssertTrue(isFocused(element), "The remote could not reach \(element.identifier)")
    }
    private func expectFocus(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 25))
        let focused = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.isFocused(element) }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [focused], timeout: 20), .completed)
    }
    private func isFocused(_ element: XCUIElement) -> Bool {
        if element.hasFocus { return true }
        // Native tvOS list rows can own focus on behalf of their single action/field.
        let id = element.identifier.isEmpty ? element.label : element.identifier
        guard !id.isEmpty else { return false }
        let cell = app.cells.containing(.any, identifier: id).matching(NSPredicate(format: "hasFocus == true")).firstMatch
        guard cell.exists else { return false }
        let actions = Set(cell.buttons.allElementsBoundByIndex.map { $0.identifier.isEmpty ? $0.label : $0.identifier })
        return actions.count <= 1
    }
    private func waitFor(_ block: @escaping () -> Bool) {
        let value = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in block() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [value], timeout: 25), .completed)
    }
    private func channel(_ name: String) -> XCUIElement { app.buttons.matching(NSPredicate(format: "label == %@", name)).firstMatch }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func shot(_ name: String) { let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot) }
}
