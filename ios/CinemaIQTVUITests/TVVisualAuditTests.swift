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
        remote.press(.left); wait(1)
        shot("02-next-feature")
        remote.press(.select)
        let play = app.buttons["tv.detail.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); wait(2)
        shot("03-detail")
        // The default focused action on entry should be Play.
        XCTAssertTrue(play.hasFocus)
        remote.press(.select); wait(15)
        shot("04-player")
        remote.press(.playPause); wait(2)
        shot("05-paused")
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
        remote.press(.down); wait(2)
        shot("15-episodes")
        // Pop details, then open the native sidebar.
        remote.press(.menu); wait(1)
        openTab("الإعدادات")
        shot("16-settings")
        remote.press(.select); wait(2)
        shot("17-settings-popup")
        remote.press(.menu); wait(1)
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func openTab(_ title: String) {
        remote.press(.menu); wait(1)
        let order = ["البحث", "الرئيسية", "أفلام", "مسلسلات", "مكتبتي", "IPTV", "الإعدادات"]
        for _ in 0..<16 {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let label = focused.exists ? focused.label : ""
            if label.contains(title) { remote.press(.select); wait(3); return }
            let index = order.firstIndex(where: { label.contains($0) }) ?? 0
            let target = order.firstIndex(of: title) ?? 0
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
