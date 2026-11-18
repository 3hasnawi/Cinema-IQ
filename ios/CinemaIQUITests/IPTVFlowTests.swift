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
        // The two lists must stay above the system tab bar, including their last visible row.
        if !tablet {
            let tab = app.tabBars.firstMatch.frame
            for row in app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.iptv.channel.")).allElementsBoundByIndex where row.isHittable {
                XCTAssertLessThanOrEqual(row.frame.maxY, tab.minY + 1)
            }
        }
        screenshot("m3u-channels")
        // A different channel tunes in place; the current channel opens full screen on a second tap.
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        app.buttons["قناة الرياضة"].tap()
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        XCTAssertFalse(element("ios.iptv.player.close").exists, "Browsing another channel must keep both lists visible")
        element("ios.iptv.browser.favorite").tap()
        XCTAssertEqual(element("ios.iptv.browser.favorite").value as? String, "محدد")
        element("ios.iptv.browser.section.المفضلة").tap()
        waitFor { self.app.buttons["قناة الرياضة"].exists && !self.app.buttons["قناة الأخبار"].exists }
        screenshot("embedded-preview-favorites")
        element("ios.iptv.browser.section.الأخيرة").tap()
        XCTAssertTrue(app.buttons["قناة الأخبار"].waitForExistence(timeout: 10))
        screenshot("embedded-preview-recent")
        element("ios.iptv.browser.section.كل القنوات").tap()

        app.buttons["ios.iptv.groups"].tap()
        XCTAssertTrue(element("ios.iptv.groupPicker.رياضة").waitForExistence(timeout: 10))
        element("ios.iptv.groupPicker.رياضة").tap()
        waitFor { self.app.buttons["قناة الرياضة"].exists && !self.app.buttons["قناة الأخبار"].exists }
        app.buttons["ios.iptv.groups"].tap()
        element("ios.iptv.groupPicker.all").tap()
        screenshot("m3u-categories")
        // Keep the isolated Keychain service, remove only the reset launch flag, then relaunch.
        app.terminate(); app.launchArguments = baseArguments; app.launch(); openIPTV()
        XCTAssertTrue(app.buttons["قناة الأخبار"].waitForExistence(timeout: 20), "The saved source must reload from Keychain")
        app.buttons["قناة الأخبار"].tap()
        if !element("ios.iptv.player.close").exists { app.buttons["قناة الأخبار"].tap() }
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
        inside(app.buttons["قناة الأخبار"]); inside(element("ios.iptv.browser.fullscreen"))
        screenshot("embedded-preview-landscape")
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
        XCTAssertTrue(english.waitForExistence(timeout: 15)); screenshot("compatibility-subtitle-options"); english.tap()
        XCTAssertEqual(element("ios.iptv.player.surface").value as? String, "متوقف مؤقتًا")
        tapPlayerControl("channels")
        XCTAssertTrue(element("ios.iptv.guide").waitForExistence(timeout: 15))
        screenshot("compatibility-floating-guide")
        app.navigationBars.buttons["تم"].tap()
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
        element("ios.iptv.browser.search.toggle").tap()
        let search = app.textFields["ios.iptv.browser.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap(); search.typeText("News")
        waitFor { self.app.buttons["Xtream News"].exists && !self.app.buttons["Xtream Sport"].exists }
        let done = element("ios.iptv.browser.search.done")
        XCTAssertTrue(done.waitForExistence(timeout: 10)); done.tap()
        waitFor { !self.app.keyboards.firstMatch.exists }
        XCTAssertTrue(search.exists, "Search results must remain available after Done dismisses the keyboard")
        screenshot("xtream-search")
        XCTAssertEqual(app.state, .runningForeground)
    }
    func testKeyboardNextDoneMetadataPopoversAndOfflineLastChannelRestore() {
        openAdd()
        app.segmentedControls.buttons["Xtream"].tap()
        fill("ios.iptv.form.name", "Keyboard Cache\n")
        // Send input to the actual first responder to verify native Next traversal,
        // rather than tapping each destination field and masking a focus failure.
        app.typeText("https://xtream.example\n")
        app.typeText("demo\n")
        app.typeText("demo\n")
        waitFor { !self.app.keyboards.firstMatch.exists }
        XCTAssertEqual(app.textFields["ios.iptv.form.address"].value as? String, "https://xtream.example")
        XCTAssertEqual(app.textFields["ios.iptv.form.username"].value as? String, "demo")
        connect()
        XCTAssertTrue(app.buttons["Xtream News"].waitForExistence(timeout: 20))
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        app.buttons["Xtream Sport"].tap()
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        app.buttons["Xtream News"].tap()
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        element("ios.iptv.browser.fullscreen").tap()
        let programmes = element("ios.iptv.player.programmes")
        waitFor { programmes.exists && programmes.label.contains("نشرة الأخبار التجريبية") && programmes.label.contains("البرنامج التالي التجريبي") }
        tapPlayerControl("quality")
        let popup = element("ios.iptv.track.popup")
        XCTAssertTrue(popup.waitForExistence(timeout: 10)); inside(popup)
        XCTAssertLessThan(popup.frame.height, app.frame.height * 0.8, "Track options should fit their content instead of occupying a large sheet")
        element("ios.iptv.quality.auto").tap()
        waitFor { !popup.exists }
        tapPlayerControl("channels")
        XCTAssertTrue(element("ios.iptv.guide").waitForExistence(timeout: 10))
        let guideSearch = app.searchFields.firstMatch
        XCTAssertTrue(guideSearch.waitForExistence(timeout: 10)); guideSearch.tap(); guideSearch.typeText("Sport")
        let guideDone = element("ios.iptv.guide.search.done")
        XCTAssertTrue(guideDone.waitForExistence(timeout: 10)); guideDone.tap()
        waitFor { !self.app.keyboards.firstMatch.exists }
        element("ios.iptv.guide.group.الأخيرة").tap()
        let sport = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "ios.iptv.guide.channel.", "Xtream Sport")).firstMatch
        XCTAssertTrue(sport.waitForExistence(timeout: 10)); sport.tap()
        waitFor { self.element("ios.iptv.player.title").label == "Xtream Sport" }
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        screenshot("television-programmes-and-touch-popovers")
        tapPlayerControl("close")
        waitFor { (self.element("ios.iptv.browser.section.الأخيرة").value as? String) == "محدد" }
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        // Retain Keychain/cache and block every catalogue request on the next launch.
        // Reentry must still restore the recent channel from the encrypted local cache.
        app.terminate(); app.launchArguments = baseArguments + ["--cinema-ui-testing-iptv-offline"]; app.launch(); openIPTV()
        XCTAssertTrue(app.buttons["Xtream Sport"].waitForExistence(timeout: 20))
        waitFor { (self.element("ios.iptv.player.surface").value as? String) == "بث مباشر" }
        element("ios.iptv.browser.fullscreen").tap()
        waitFor { self.element("ios.iptv.player.title").label == "Xtream Sport" }
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        screenshot("cached-last-channel-restored-without-provider-network")
    }
    private func showPlayerControls() {
        for _ in 0..<4 {
            if element("ios.iptv.player.close").exists { return }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertTrue(element("ios.iptv.player.close").exists)
    }
    private func tapPlayerControl(_ name: String) {
        let identifier = "ios.iptv.player." + name
        let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        for _ in 0..<6 {
            // Read one atomic UI snapshot. Repeated live frame queries can consume the real HUD timeout
            // between locating a button and pressing it, especially on a busy hosted simulator.
            guard let root = try? app.snapshot() else { XCTFail("Player snapshot unavailable"); return }
            var pending = [root], nodes: [any XCUIElementSnapshot] = []
            while let node = pending.popLast() { nodes.append(node); pending.append(contentsOf: node.children) }
            // A single touch reveals a hidden HUD. Never toggle twice using a stale existence query:
            // on a busy iPad the first touch may reveal it and the second would immediately hide it.
            guard let target = nodes.first(where: { $0.identifier == identifier }) else { center.tap(); continue }
            let frame = target.frame, point = CGPoint(x: frame.midX, y: frame.midY)
            let clip = name == "close" ? root.frame :
                (nodes.first(where: { $0.identifier == "ios.iptv.player.controls" })?.frame ?? root.frame.insetBy(dx: 16, dy: 0))
            if !frame.isEmpty && point.x.isFinite && point.y.isFinite && root.frame.contains(point) && clip.insetBy(dx: 2, dy: 0).contains(point) {
                app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: point.x, dy: point.y)).tap()
                return
            }
            let strip = element("ios.iptv.player.controls")
            if point.x > clip.maxX { strip.swipeLeft() } else { strip.swipeRight() }
        }
        XCTFail("The player control must be on screen before a real touch: " + name)
    }
    private func openIPTV() {
        if tablet {
            // Exercise iPad's native IPTV tab/sidebar instead of a Browse stack restored across rotation.
            let tab = app.descendants(matching: .any).matching(NSPredicate(
                format: "label == %@ AND (elementType == %d OR elementType == %d)", "التلفزيون",
                XCUIElement.ElementType.button.rawValue, XCUIElement.ElementType.cell.rawValue)).firstMatch
            if !tab.exists || !tab.isHittable {
                let sidebar = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'sidebar' OR label CONTAINS 'الشريط الجانبي'")).firstMatch
                if sidebar.exists && sidebar.isHittable { sidebar.tap() }
            }
            XCTAssertTrue(tab.waitForExistence(timeout: 25)); tab.tap()
            // iPad may move Add into its native toolbar overflow beside the tab capsule.
            XCTAssertTrue(element("ios.iptv.groups").waitForExistence(timeout: 20))
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
    private func openAdd() {
        let empty = element("ios.iptv.empty.add")
        if tablet && empty.exists && empty.isHittable { empty.tap() }
        else {
            if tablet && !element("ios.iptv.add").exists {
                let overflow = app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", ["More", "المزيد"])).firstMatch
                XCTAssertTrue(overflow.waitForExistence(timeout: 10)); overflow.tap()
            }
            element("ios.iptv.add").tap()
        }
        XCTAssertTrue(element("ios.iptv.form.name").waitForExistence(timeout: 10))
    }
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
    private func saturatedFraction(_ capture: XCUIScreenshot, portraitPreview: Bool = false) throws -> Double {
        let full = try XCTUnwrap(capture.image.cgImage)
        let image: CGImage
        if portraitPreview {
            image = try XCTUnwrap(full.cropping(to: CGRect(x: CGFloat(full.width) * 0.15, y: CGFloat(full.height) * 0.16, width: CGFloat(full.width) * 0.7, height: CGFloat(full.height) * 0.12)))
        } else { image = full }
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
