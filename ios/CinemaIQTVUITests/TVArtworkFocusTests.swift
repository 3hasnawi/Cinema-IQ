import XCTest
import CoreGraphics

final class TVArtworkFocusTests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared
    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-hero-artwork"]
        app.launch()
        XCTAssertTrue(element("tv.home.showcase").waitForExistence(timeout: 30))
    }
    override func tearDown() {
        shot("last-state")
        let tree = XCTAttachment(string: app.debugDescription); tree.name = "artwork-focus-hierarchy"; tree.lifetime = .keepAlways; add(tree)
        app.terminate()
    }
    func testPortraitTopAndBottomRemainVisibleAtFullScreen() throws {
        var capture = XCUIScreen.main.screenshot()
        var pink = 0.0, green = 0.0
        let deadline = Date().addingTimeInterval(10)
        repeat {
            capture = XCUIScreen.main.screenshot()
            let image = try XCTUnwrap(capture.image.cgImage)
            pink = try coloredFraction(image, y: 0.045, pink: true)
            green = try coloredFraction(image, y: 0.94, pink: false)
            if pink > 0.75 && green > 0.65 { break }
            Thread.sleep(forTimeInterval: 0.25)
        } while Date() < deadline
        // These colored edges disappear with aspect-fill cropping of the 2:3 portrait fixture.
        XCTAssertGreaterThan(pink, 0.75)
        XCTAssertGreaterThan(green, 0.65)
        let attachment = XCTAttachment(screenshot: capture)
        attachment.name = "portrait-complete-hero-with-edge-markers"; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testDescriptionIsNotFocusableAndCircularActionsRestoreAfterReading() {
        expectFocus(element("tv.home.showcase")); remote.press(.select)
        let play = element("tv.detail.play"), overview = element("tv.detail.overview")
        expectFocus(play); XCTAssertTrue(overview.waitForExistence(timeout: 20))
        remote.press(.up)
        XCTAssertFalse(overview.hasFocus)
        XCTAssertFalse(overview.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists)
        let trailer = element("tv.detail.trailer"), list = element("tv.detail.list"), story = element("tv.detail.story")
        focus(trailer); XCTAssertEqual(trailer.frame.width, trailer.frame.height, accuracy: 2)
        XCTAssertLessThan(trailer.frame.width, 100)
        shot("detail-balanced-trailer-icon-and-unframed-description")
        let storyText = overview.label
        focus(list); focus(story); remote.press(.select)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", storyText)).firstMatch.waitForExistence(timeout: 10))
        remote.press(.menu); expectFocus(story)
        focus(play); remote.press(.menu); expectFocus(element("tv.home.showcase"))
    }
    private func coloredFraction(_ image: CGImage, y: CGFloat, pink: Bool) throws -> Double {
        let rect = CGRect(x: CGFloat(image.width) * 0.48, y: CGFloat(image.height) * y, width: CGFloat(image.width) * 0.04, height: 8)
        let crop = try XCTUnwrap(image.cropping(to: rect)), w = crop.width, h = crop.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        return try pixels.withUnsafeMutableBytes { bytes in
            let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.draw(crop, in: CGRect(x: 0, y: 0, width: w, height: h))
            let rgba = bytes.bindMemory(to: UInt8.self)
            var count = 0
            for i in 0..<(w * h) {
                let r = Int(rgba[i*4]), g = Int(rgba[i*4+1]), b = Int(rgba[i*4+2])
                if pink ? (r > g * 2 && r > 30) : (g > r + 2 && g > b + 2 && g > 4) { count += 1 }
            }
            return Double(count) / Double(w * h)
        }
    }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func focused(_ target: XCUIElement) -> Bool {
        target.hasFocus || target.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists
    }
    private func expectFocus(_ target: XCUIElement) {
        XCTAssertTrue(target.waitForExistence(timeout: 20))
        let condition = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.focused(target) }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [condition], timeout: 20), .completed)
    }
    private func focus(_ target: XCUIElement) {
        for _ in 0..<12 {
            if focused(target) { return }
            let current = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            if !current.exists { remote.press(.down); continue }
            let dx = target.frame.midX - current.frame.midX, dy = target.frame.midY - current.frame.midY
            remote.press(abs(dy) > 70 ? (dy > 0 ? .down : .up) : (dx > 0 ? .right : .left))
        }
        expectFocus(target)
    }
    private func shot(_ name: String) { let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
}
