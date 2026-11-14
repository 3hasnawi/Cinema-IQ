import XCTest
import UIKit

/// Fixture hosts/accounts, isolated real Keychain storage and the real AVPlayer reference clip.
final class IPTVFlowTests: XCTestCase {
    private let app = XCUIApplication()
    private var tablet: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    private let baseArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-iptv", "--cinema-ui-testing-player"]
    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = baseArguments + ["--cinema-ui-testing-iptv-reset"]
        if name.contains("testCompatibilityDecoderWithTracksAndRotation") { app.launchArguments += ["--cinema-ui-testing-compatibility"] }
        app.launch()
        openIPTV()
    }
    override func tearDown() {
        screenshot("last-state")
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "iptv-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        app.terminate()
    }
    func testM3UPersistsFiltersAndPlaysInsideApp() {
        openAdd()
        fill("ios.iptv.form.name", "M3U Test")
        fill("ios.iptv.form.address", "http://iptv.example/channels.m3u\n")
        // SwiftUI exposes a labelled row switch and a nested native UISwitch on iPad.
        // Tap the actual control, then verify consent before submitting an HTTP source.
        let consent = app.switches["ios.iptv.form.allowHTTP"]
        XCTAssertTrue(consent.waitForExistence(timeout: 10))
        consent.switches.firstMatch.tap()
        waitFor { (consent.value as? String) == "1" }
        connect()
        XCTAssertTrue(app.buttons["قناة الأخبار"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["قناة الرياضة"].exists)
        screenshot("m3u-channels")
        app.buttons["ios.iptv.groups"].tap()
        XCTAssertTrue(app.buttons["رياضة"].waitForExistence(timeout: 10))
        app.buttons["رياضة"].tap()
        waitFor { self.app.buttons["قناة الرياضة"].exists && !self.app.buttons["قناة الأخبار"].exists }
        app.buttons["ios.iptv.groups"].tap()
        app.buttons["كل القنوات"].tap()
        screenshot("m3u-categories")
        // Keep the isolated Keychain service, remove only the reset launch flag, then relaunch.
        app.terminate(); app.launchArguments = baseArguments; app.launch(); openIPTV()
        XCTAssertTrue(app.buttons["قناة الأخبار"].waitForExistence(timeout: 20), "The saved source must reload from Keychain")
        app.buttons["قناة الأخبار"].tap()
        XCTAssertTrue(element("ios.iptv.player.close").waitForExistence(timeout: 20))
        // Playback status stays accessible after the production HUD timer hides its labels.
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        // Exercise the real production timer and tap-to-reveal gesture before using the controls.
        waitFor { !self.element("ios.iptv.player.close").exists }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        waitFor { self.element("ios.iptv.player.close").exists }
        tapPlayerControl("next")
        waitFor { self.element("ios.iptv.player.title").label == "قناة الرياضة" }
        tapPlayerControl("previous")
        waitFor { self.element("ios.iptv.player.title").label == "قناة الأخبار" }
        tapPlayerControl("channels")
        XCTAssertTrue(element("ios.iptv.guide").waitForExistence(timeout: 15)); screenshot("floating-guide")
        let doc = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "ios.iptv.guide.channel.", "قناة الوثائقيات")).firstMatch
        XCTAssertTrue(doc.waitForExistence(timeout: 10)); doc.tap()
        waitFor { self.element("ios.iptv.player.title").label == "قناة الوثائقيات" }
        tapPlayerControl("play")
        waitFor { self.element("ios.iptv.player.status").label == "متوقف مؤقتًا" }
        element("ios.iptv.player.quality").tap()
        let highest = element("ios.iptv.quality.highest")
        XCTAssertTrue(highest.waitForExistence(timeout: 10)); highest.tap()
        element("ios.iptv.player.quality").tap()
        XCTAssertTrue(highest.waitForExistence(timeout: 10))
        XCTAssertEqual(highest.value as? String, "محدد")
        element("ios.iptv.quality.auto").tap()
        XCTAssertEqual(element("ios.iptv.player.surface").value as? String, "متوقف مؤقتًا")
        screenshot("playing-portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        waitFor { self.app.frame.width > self.app.frame.height }
        showPlayerControls(); inside(element("ios.iptv.player.close"))
        showPlayerControls(); XCTAssertEqual(element("ios.iptv.player.title").label, "قناة الوثائقيات")
        screenshot("playing-landscape")
        tapPlayerControl("close")
        XCTAssertTrue(app.buttons["قناة الأخبار"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.state, .runningForeground)
    }
    func testCompatibilityDecoderWithTracksAndRotation() throws {
        openAdd(); fill("ios.iptv.form.name", "Compatibility")
        fill("ios.iptv.form.address", "https://iptv.example/channels.m3u\n"); connect()
        XCTAssertTrue(app.buttons["قناة الأخبار"].waitForExistence(timeout: 20)); app.buttons["قناة الأخبار"].tap()
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        try assertCompatibilityVideoVisible(); screenshot("compatibility-mpeg2-mp2-playing")
        tapPlayerControl("play")
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "متوقف مؤقتًا" }
        tapPlayerControl("audio")
        let arabic = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'Arabic' OR label CONTAINS 'العربية'")).firstMatch
        XCTAssertTrue(arabic.waitForExistence(timeout: 15)); screenshot("compatibility-audio-tracks"); arabic.tap()
        tapPlayerControl("subtitles")
        let english = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'English' OR label CONTAINS 'الإنجليزية'")).firstMatch
        XCTAssertTrue(english.waitForExistence(timeout: 15)); english.tap()
        XCTAssertEqual(element("ios.iptv.player.surface").value as? String, "متوقف مؤقتًا")
        XCUIDevice.shared.orientation = .landscapeLeft; waitFor { self.app.frame.width > self.app.frame.height }
        screenshot("compatibility-landscape")
        tapPlayerControl("play"); waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        tapPlayerControl("next"); waitFor { self.element("ios.iptv.player.title").label == "قناة الرياضة" }
        tapPlayerControl("close"); XCTAssertTrue(app.buttons["قناة الأخبار"].waitForExistence(timeout: 15))
    }
    func testXtreamConnectsAndChannelSearchWorks() {
        openAdd()
        let xtream = app.segmentedControls.buttons["Xtream"]
        XCTAssertTrue(xtream.waitForExistence(timeout: 10))
        xtream.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        fill("ios.iptv.form.name", "Xtream Test")
        fill("ios.iptv.form.address", "https://xtream.example\n")
        fill("ios.iptv.form.username", "demo\n")
        let password = app.secureTextFields["ios.iptv.form.password"]
        XCTAssertTrue(password.waitForExistence(timeout: 10)); password.tap(); password.typeText("demo\n")
        connect()
        XCTAssertTrue(app.buttons["Xtream News"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Xtream Sport"].exists)
        screenshot("xtream-channels")
        var search = app.searchFields.firstMatch
        if !search.exists {
            let toggle = app.buttons.matching(NSPredicate(format: "label == 'Search' OR label == 'البحث'")).firstMatch
            if toggle.exists && toggle.isHittable { toggle.tap() }
            search = app.searchFields.firstMatch
        }
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap(); search.typeText("News")
        waitFor { self.app.buttons["Xtream News"].exists && !self.app.buttons["Xtream Sport"].exists }
        screenshot("xtream-search")
        XCTAssertEqual(app.state, .runningForeground)
    }
    private func showPlayerControls() {
        let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        // Refresh the real five-second timer rather than spending its remaining time on XCTest queries.
        if element("ios.iptv.player.close").exists { center.tap() }
        center.tap()
        XCTAssertTrue(element("ios.iptv.player.close").waitForExistence(timeout: 5))
    }
    private func tapPlayerControl(_ name: String) {
        showPlayerControls()
        let frame = element("ios.iptv.player." + name).frame
        guard !frame.isEmpty, frame.midX.isFinite, frame.midY.isFinite else {
            XCTFail("The visible player control must have a finite touch target: " + name); return
        }
        // Tap the measured physical target directly; native element.tap() can retry after the HUD has expired.
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
    }
    private func openIPTV() {
        if tablet {
            // Exercise iPad's native IPTV tab/sidebar instead of a Browse stack restored across rotation.
            let tab = app.descendants(matching: .any).matching(NSPredicate(
                format: "label == %@ AND (elementType == %d OR elementType == %d)", "IPTV",
                XCUIElement.ElementType.button.rawValue, XCUIElement.ElementType.cell.rawValue)).firstMatch
            if !tab.exists || !tab.isHittable {
                let sidebar = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'sidebar' OR label CONTAINS 'الشريط الجانبي'")).firstMatch
                if sidebar.exists && sidebar.isHittable { sidebar.tap() }
            }
            XCTAssertTrue(tab.waitForExistence(timeout: 25)); tab.tap()
            XCTAssertTrue(element("ios.iptv.add").waitForExistence(timeout: 20))
            screenshot("ipad-entry")
            return
        }
        let browse = app.buttons.matching(NSPredicate(format: "label == %@", "تصفّح")).firstMatch
        if !browse.exists || !browse.isHittable {
            let sidebar = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'sidebar' OR label CONTAINS 'الشريط الجانبي'")).firstMatch
            if sidebar.exists && sidebar.isHittable { sidebar.tap() }
        }
        XCTAssertTrue(browse.waitForExistence(timeout: 25)); browse.tap()
        let entry = element("ios.browse.iptv")
        XCTAssertTrue(entry.waitForExistence(timeout: 15)); entry.tap()
        XCTAssertTrue(element("ios.iptv.add").waitForExistence(timeout: 15))
        screenshot(tablet ? "ipad-entry" : "iphone-entry")
    }
    private func openAdd() { element("ios.iptv.add").tap(); XCTAssertTrue(element("ios.iptv.form.name").waitForExistence(timeout: 10)) }
    private func fill(_ id: String, _ value: String) {
        let field = app.textFields[id]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); field.tap(); field.typeText(value)
    }
    private func connect() {
        let button = element("ios.iptv.form.connect")
        for _ in 0..<4 { if button.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(button.isEnabled && button.isHittable); button.tap()
        waitFor { !self.element("ios.iptv.form.name").exists }
    }
    private func assertCompatibilityVideoVisible() throws {
        var fraction = try saturatedFraction(XCUIScreen.main.screenshot())
        let deadline = Date().addingTimeInterval(8)
        while fraction <= 0.70 && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            fraction = try saturatedFraction(XCUIScreen.main.screenshot())
        }
        XCTAssertGreaterThan(fraction, 0.70, "The decoder must render actual video pixels, not merely report playing")
    }
    private func saturatedFraction(_ capture: XCUIScreenshot) throws -> Double {
        let image = try XCTUnwrap(capture.image.cgImage)
        let width = 128, height = 72
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        return try rgba.withUnsafeMutableBytes { bytes -> Double in
            let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            let pixels = bytes.bindMemory(to: UInt8.self)
            var saturated = 0, count = 0
            // The middle strip stays inside the letterboxed 16:9 clip on both portrait device families.
            for y in 32..<40 { for x in 16..<112 {
                let i = (y * width + x) * 4
                let colors = [Int(pixels[i]), Int(pixels[i + 1]), Int(pixels[i + 2])]
                if colors.max()! - colors.min()! > 100 { saturated += 1 }; count += 1
            } }
            return Double(saturated) / Double(count)
        }
    }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func waitFor(_ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 25), .completed)
    }
    private func inside(_ element: XCUIElement) { XCTAssertTrue(element.exists && app.frame.insetBy(dx: -2, dy: -2).contains(element.frame)) }
    private func screenshot(_ title: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = (tablet ? "ipad-" : "iphone-") + title; attachment.lifetime = .keepAlways; add(attachment)
    }
}
