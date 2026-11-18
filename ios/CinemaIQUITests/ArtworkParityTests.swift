import XCTest
import UIKit

/// Real touch navigation and rendered poster edges; the fixture does not need an external image service.
final class ArtworkParityTests: XCTestCase {
    private let app = XCUIApplication()
    private var tablet: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-hero-artwork"]
        app.launch()
        XCTAssertTrue(element("ios.home.hero").waitForExistence(timeout: 30))
    }

    override func tearDown() {
        shot("last-state")
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "artwork-parity-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        app.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testTallPosterKeepsBothColoredEdgesInHomeHero() throws {
        var pink = 0.0, green = 0.0
        let deadline = Date().addingTimeInterval(12)
        repeat {
            let candidates = app.descendants(matching: .any).matching(identifier: "ios.artwork.fixture.bounds").allElementsBoundByIndex
            if let bounds = candidates.map(\.frame).first(where: {
                $0.width > 100 && $0.height > 100 && $0.intersection(app.frame).width > $0.width * 0.8
            }) {
                let capture = XCUIScreen.main.screenshot()
                let image = try XCTUnwrap(capture.image.cgImage)
                // The generated fixture is 1:2. Aspect-fill removes its top/bottom bands on these frames.
                let height = min(bounds.height, bounds.width * 2)
                let fitted = CGRect(x: bounds.midX - height / 4, y: bounds.midY - height / 2,
                                    width: height / 2, height: height)
                pink = try coloredFraction(image, fitted: fitted, fractionY: 0.095, pink: true)
                green = try coloredFraction(image, fitted: fitted, fractionY: 0.905, pink: false)
                if pink > 0.8 && green > 0.8 { break }
            }
            Thread.sleep(forTimeInterval: 0.25)
        } while Date() < deadline
        XCTAssertGreaterThan(pink, 0.8, "The top edge of a tall poster must remain visible")
        XCTAssertGreaterThan(green, 0.8, "The bottom edge must remain visible through the existing hero gradient")
        shot("uncropped-portrait-hero")
    }

    func testDetailKeepsStaticStoryAndBalancedFavoriteAction() {
        openFirstDetail()
        let title = element("ios.detail.title").label
        let favorite = element("ios.detail.favorite")
        XCTAssertTrue(favorite.waitForExistence(timeout: 15))
        XCTAssertEqual(favorite.frame.width, favorite.frame.height, accuracy: 2)
        let original = favorite.label
        favorite.tap()
        waitFor { favorite.label != original }
        favorite.tap()
        waitFor { favorite.label == original }
        let story = element("ios.detail.overview")
        reveal(story)
        XCTAssertTrue(app.staticTexts["ios.detail.overview"].exists)
        XCTAssertFalse(app.buttons["ios.detail.overview"].exists)
        let expand = app.buttons["ios.detail.story.toggle"]
        reveal(expand); expand.tap()
        waitFor { expand.label == "أقل" }
        shot("static-description-and-touch-actions")
        if tablet {
            XCUIDevice.shared.orientation = .landscapeLeft
            waitFor { self.app.frame.width > self.app.frame.height }
            XCTAssertEqual(element("ios.detail.title").label, title)
            reveal(favorite)
            XCTAssertTrue(app.frame.insetBy(dx: -2, dy: -2).contains(favorite.frame))
            shot("detail-landscape-preserves-title")
        }
    }

    func testSeriesHasSeasonButtonAndHorizontallyScrollableEpisodeCards() {
        tab("تصفّح")
        let series = app.segmentedControls.buttons["مسلسلات"]
        XCTAssertTrue(series.waitForExistence(timeout: 10))
        series.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        waitFor { series.isSelected }
        let card = firstPoster()
        waitFor { (card.value as? String) == "مسلسل" }
        card.tap()
        XCTAssertTrue(element("ios.detail.play").waitForExistence(timeout: 20))
        let season = app.buttons["ios.detail.season.1"]
        reveal(season)
        XCTAssertGreaterThanOrEqual(season.frame.height, 44)
        season.tap()
        let first = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier CONTAINS %@", "ios.detail.episode.", "open-movies-1")).firstMatch
        reveal(first)
        XCTAssertLessThan(first.frame.width, app.frame.width)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@ OR label == %@", "الحلقة 1", "الحلقة ١")).firstMatch.exists,
                      "Episode numbers must remain beside an available episode title")
        shot("season-button-and-episode-cards")
        let row = element("ios.detail.episodes")
        // Arabic leading order starts at the right. Revealing later cards therefore drags toward the right.
        let last = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier CONTAINS %@", "ios.detail.episode.", "open-movies-4")).firstMatch
        for _ in 0..<5 {
            if last.exists && last.isHittable && app.frame.insetBy(dx: -2, dy: -2).contains(last.frame) { break }
            row.swipeRight()
        }
        XCTAssertTrue(last.exists && last.isHittable)
        XCTAssertTrue(app.frame.insetBy(dx: -2, dy: -2).contains(last.frame))
        shot("later-episode-card-reachable")
    }

    private func coloredFraction(_ image: CGImage, fitted: CGRect, fractionY: CGFloat, pink: Bool) throws -> Double {
        let scaleX = CGFloat(image.width) / app.frame.width, scaleY = CGFloat(image.height) / app.frame.height
        let rect = CGRect(x: (fitted.minX + fitted.width * 0.03 - app.frame.minX) * scaleX,
                          y: (fitted.minY + fitted.height * fractionY - app.frame.minY) * scaleY,
                          width: 4 * scaleX, height: 2 * scaleY)
        let crop = try XCTUnwrap(image.cropping(to: rect)), width = crop.width, height = crop.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        return try pixels.withUnsafeMutableBytes { bytes in
            let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height))
            let rgba = bytes.bindMemory(to: UInt8.self)
            var matching = 0
            for i in 0..<(width * height) {
                let r = Int(rgba[i * 4]), g = Int(rgba[i * 4 + 1]), b = Int(rgba[i * 4 + 2])
                if pink ? (r > g * 2 && b > g * 2 && r > 50) : (g > r + 5 && g > b + 5 && g > 30) { matching += 1 }
            }
            return Double(matching) / Double(width * height)
        }
    }
    private func openFirstDetail() {
        tab("تصفّح"); firstPoster().tap()
        XCTAssertTrue(element("ios.detail.play").waitForExistence(timeout: 20))
    }
    private func firstPoster() -> XCUIElement {
        let card = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.poster.grid-")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20)); return card
    }
    private func tab(_ title: String) {
        let button = app.buttons.matching(NSPredicate(format: "label == %@", title)).firstMatch
        if !button.exists || !button.isHittable {
            let sidebar = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'sidebar' OR label CONTAINS 'الشريط الجانبي'")).firstMatch
            if sidebar.exists && sidebar.isHittable { sidebar.tap() }
        }
        waitFor { button.exists && button.isHittable }; button.tap()
    }
    private func reveal(_ control: XCUIElement) {
        for _ in 0..<8 {
            if control.exists && control.isHittable && app.frame.insetBy(dx: -2, dy: -2).contains(control.frame) { return }
            element("ios.detail").swipeUp()
        }
        XCTAssertTrue(control.exists && control.isHittable)
    }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func waitFor(_ condition: @escaping () -> Bool) {
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)], timeout: 20), .completed)
    }
    private func shot(_ name: String) {
        let capture = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        capture.name = (tablet ? "ipad-" : "iphone-") + name; capture.lifetime = .keepAlways; add(capture)
    }
}
