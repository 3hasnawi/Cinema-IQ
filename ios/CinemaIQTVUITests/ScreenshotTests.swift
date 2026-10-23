import XCTest

/// Drives the Apple TV app with the Siri Remote in the simulator and keeps a screenshot of every main screen
/// (the "Apple TV screenshots" workflow exports them). Nothing is asserted: the pictures are the result.
final class ScreenshotTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = true
    }

    /// The licensed demo source: always reachable, and it has a series with episodes.
    func testDemoSource() {
        launch(source: "ext:demo")
        shot("01-home")
        press(.down, wait: 3)
        shot("02-home-shelves")
        let series = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Blender المفتوحة")).firstMatch
        for _ in 0..<8 where !series.hasFocus { press(.down) }
        shot("03-series-focused")
        press(.select, wait: 8)
        shot("04-series-page")
        press(.down, wait: 3)
        shot("05-seasons")
        press(.down, wait: 3)
        shot("06-episodes")
        press(.select, wait: 15)
        shot("07-player")
        press(.playPause, wait: 3)
        shot("08-player-controls")
        press(.menu, wait: 2)
        press(.menu, wait: 4)
        press(.menu, wait: 4)
        shot("09-back-home")
        press(.menu, wait: 3)
        shot("10-sidebar")
        press(.down, wait: 2)
        press(.select, wait: 10)
        shot("11-movies")
    }

    /// The default source as viewers see it, when the runner can reach it.
    func testDefaultSource() {
        launch(source: nil)
        shot("21-home")
        press(.left, wait: 1)
        press(.left, wait: 1)
        press(.left, wait: 3)
        shot("22-home-next-title")
        press(.right, wait: 1)
        press(.right, wait: 1)
        press(.down, wait: 3)
        shot("23-shelves")
        press(.select, wait: 10)
        shot("24-title-page")
        press(.down, wait: 3)
        shot("25-title-list-button")
        press(.down, wait: 3)
        shot("26-below-fold")
        press(.menu, wait: 4)
        shot("27-back-on-home")
        press(.up, wait: 3)
        shot("28-home-top")
    }

    private func launch(source: String?) {
        app = XCUIApplication()
        if let source { app.launchArguments = ["-ios.contentSource.v1", source] }
        app.launch()
        Thread.sleep(forTimeInterval: 25)
    }

    private func press(_ button: XCUIRemote.Button, wait: TimeInterval = 1.5) {
        remote.press(button)
        Thread.sleep(forTimeInterval: wait)
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
