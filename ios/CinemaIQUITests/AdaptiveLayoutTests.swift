import XCTest
import UIKit

/// Native touch navigation on the demo catalogue and AVPlayer with a local reference clip.
final class AdaptiveLayoutTests: XCTestCase {
    private let app = XCUIApplication()
    private var tablet: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    private var deviceName: String { tablet ? "ipad" : "iphone" }

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player", "--cinema-ui-testing-static-carousel"]
        addUIInterruptionMonitor(withDescription: "System permission") { alert in
            for title in ["Allow", "OK", "السماح", "موافق"] where alert.buttons[title].exists {
                alert.buttons[title].tap(); return true
            }
            return false
        }
        app.launch()
        XCTAssertTrue(element("ios.home.hero").waitForExistence(timeout: 30))
    }

    override func tearDown() {
        screenshot("last-state")
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = deviceName + "-hierarchy"
        tree.lifetime = .keepAlways
        add(tree)
        // Keep the hierarchy readable even if XCTest fails to finalize its result bundle.
        print("CINEMA_UI_HIERARCHY \(deviceName):\n\(app.debugDescription)")
        app.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testCinematicHomeAndDirectTelevision() {
        let hero = element("ios.home.hero")
        let detail = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.home.detail.")).firstMatch
        waitFor("The visible featured title must open with a single tap") { detail.exists && detail.isHittable }
        XCTAssertTrue(hero.frame.intersects(detail.frame))
        screenshot("cinematic-home-portrait")
        detail.tap()
        XCTAssertTrue(element("ios.detail.play").waitForExistence(timeout: 15))
        let back = app.navigationBars.buttons.matching(NSPredicate(
            format: "identifier == 'Back' OR label IN %@", ["رجوع", "Back", "الرئيسية"])).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 10))
        back.tap()
        XCTAssertTrue(hero.waitForExistence(timeout: 15))
        if tablet {
            landscape()
            XCTAssertTrue(element("ios.home.profile").isHittable)
            screenshot("cinematic-home-landscape")
        }
        tab("التلفزيون")
        XCTAssertTrue(element("ios.iptv.empty.add").waitForExistence(timeout: 15))
        screenshot("cinematic-tv-entry")
        tab("الرئيسية")
        XCTAssertTrue(hero.waitForExistence(timeout: 15))
        tab("الإعدادات")
        XCTAssertTrue(element("ios.settings").waitForExistence(timeout: 10))
        tab("الرئيسية")
        XCTAssertTrue(element("ios.home.profile").isHittable)
    }

    func testResumeCardSizesAndAdaptiveFeaturedArt() {
        executionTimeAllowance = 420
        let hero = element("ios.home.hero")
        if tablet { XCTAssertGreaterThan(hero.frame.width, hero.frame.height) }
        else { XCTAssertGreaterThan(hero.frame.height, hero.frame.width) }

        tab("تصفّح")
        firstPoster().tap()
        func watchBriefly() {
            let play = element("ios.detail.play")
            waitFor("The selected title should be playable") { play.exists && play.isEnabled && play.isHittable }
            play.tap()
            let video = element("ios.player.video")
            waitFor("Save progress from the local AVPlayer clip") { self.seconds(video) >= 2 }
            element("ios.player.close").tap()
            XCTAssertTrue(play.waitForExistence(timeout: 15))
        }
        watchBriefly()
        let back = app.navigationBars.buttons.matching(NSPredicate(
            format: "identifier == 'Back' OR label IN %@", ["Back", "رجوع", "تصفّح"])).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 10)); back.tap()
        let series = app.segmentedControls.buttons["مسلسلات"]
        series.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let poster = firstPoster()
        waitFor("The series should replace the movie grid") { (poster.value as? String) == "مسلسل" }
        poster.tap()
        watchBriefly()
        tab("الرئيسية")

        let cards = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.continue."))
        waitFor("Both a film and an episode must have persisted") { cards.count >= 2 }
        let episode = element("ios.continue.ext:demo:open-movies-1")
        let film = element("ios.continue.ext:demo:big-buck-bunny")
        XCTAssertTrue(episode.exists && film.exists)
        XCTAssertEqual(episode.frame.width, film.frame.width, accuracy: 1)
        XCTAssertEqual(episode.frame.height, film.frame.height, accuracy: 1)
        XCTAssertEqual(episode.frame.minY, film.frame.minY, accuracy: 1)
        XCTAssertGreaterThan(film.frame.width, 130)
        screenshot("adaptive-home-with-history")
        landscape()
        waitFor("The wide window must use landscape artwork") { hero.frame.width > hero.frame.height }
        screenshot("adaptive-home-wide")
    }

    func testRepeatedNativeTabSwitches() throws {
        let options = XCTMeasureOptions()
        options.iterationCount = 2
        var metrics: [XCTMetric] = [XCTClockMetric()]
        #if compiler(>=6.2)
        if #available(iOS 26, *) { metrics.append(XCTHitchMetric(application: app)) }
        #endif
        // Warm the first visit, then repeatedly switch the native destinations with their real content.
        func switchTabs() {
            tab("تصفّح")
            XCTAssertTrue(element("ios.catalog").waitForExistence(timeout: 12))
            tab("التلفزيون")
            XCTAssertTrue(element("ios.iptv.empty.add").waitForExistence(timeout: 12))
            tab("مكتبتي")
            XCTAssertTrue(app.segmentedControls.buttons["قائمتي"].waitForExistence(timeout: 12))
            tab("الرئيسية")
            XCTAssertTrue(element("ios.home.hero").waitForExistence(timeout: 12))
        }
        switchTabs()
        measure(metrics: metrics, options: options) { switchTabs() }
    }

    func testNavigationAndRotation() {
        screenshot("home-portrait")
        tab("تصفّح")
        let card = firstPoster()
        inside(card)
        screenshot("browse-portrait")
        card.tap()
        let play = element("ios.detail.play")
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        inside(play)
        let title = element("ios.detail.title").label
        screenshot("detail-portrait")
        if tablet {
            landscape()
            XCTAssertEqual(element("ios.detail.title").label, title, "Rotation must preserve the opened title")
            inside(play)
            inside(element("ios.detail.favorite"))
            screenshot("detail-landscape")
        }
        let favorite = element("ios.detail.favorite")
        if favorite.label.contains("إضافة") { favorite.tap() }
        tab("مكتبتي")
        let saved = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.poster.library-")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 10))
        inside(saved)
        screenshot("library")
    }

    func testSettingsAndSearch() {
        tab("الإعدادات")
        XCTAssertTrue(element("ios.settings").waitForExistence(timeout: 10))
        screenshot("settings")
        tab("البحث")
        let search = app.searchFields.firstMatch
        // iPad's native navigation bar initially presents search as a toolbar button.
        if !search.exists {
            let activate = app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", ["Search", "بحث", "البحث"])).firstMatch
            XCTAssertTrue(activate.waitForExistence(timeout: 10))
            activate.tap()
        }
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("Big")
        inside(firstPoster())
        screenshot("search")
    }

    func testPlayerSurvivesRotationAndOptionsFit() {
        tab("تصفّح")
        firstPoster().tap()
        let play = element("ios.detail.play")
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        let title = element("ios.detail.title").label
        play.tap()
        let video = element("ios.player.video")
        XCTAssertTrue(video.waitForExistence(timeout: 20))
        waitFor("Playback should advance") { self.seconds(video) >= 1 }
        let position = seconds(video)
        landscape()
        waitFor("Rotation must retain playback rather than reopen at zero") { self.seconds(video) > position }
        let pause = element("ios.player.playPause")
        XCTAssertTrue(pause.waitForExistence(timeout: 10))
        pause.tap()
        waitFor("The film should pause") { pause.label == "تشغيل" }
        inside(element("ios.player.close"))
        element("ios.player.options").tap()
        let panel = element("ios.player.options.panel")
        XCTAssertTrue(panel.waitForExistence(timeout: 10))
        inside(panel)
        let subtitles = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "الترجمة"))
            .allElementsBoundByIndex.first { $0.isHittable }
        XCTAssertNotNil(subtitles)
        guard let subtitles else { return }
        subtitles.tap()
        XCTAssertTrue(app.staticTexts["الخلفية"].waitForExistence(timeout: 10))
        inside(panel)
        screenshot("player-options-landscape")
        element("ios.player.options").tap()
        screenshot("player-paused-landscape")
        element("ios.player.close").tap()
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        XCTAssertEqual(element("ios.detail.title").label, title)
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testSeriesEpisodesFit() {
        tab("تصفّح")
        let series = app.segmentedControls.buttons["مسلسلات"]
        XCTAssertTrue(series.waitForExistence(timeout: 10))
        // Use the segment's visible centre, not UIKit's mirrored RTL activation point.
        series.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        waitFor("The series segment should be selected") { series.isSelected }
        waitFor("The series catalogue should replace the movies") {
            let first = self.app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.poster.grid-")).firstMatch
            return first.exists && (first.value as? String) == "مسلسل"
        }
        firstPoster().tap()
        XCTAssertTrue(element("ios.detail.play").waitForExistence(timeout: 20))
        if tablet { landscape() }
        let episode = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.detail.episode.")).firstMatch
        for _ in 0..<6 {
            if episode.exists && episode.isHittable && app.frame.insetBy(dx: -2, dy: -2).contains(episode.frame) { break }
            element("ios.detail").swipeUp()
        }
        XCTAssertTrue(episode.exists && episode.isHittable)
        inside(episode)
        screenshot("episodes")
    }

    private func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }
    private func firstPoster() -> XCUIElement {
        let card = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "ios.poster.grid-")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        return card
    }
    private func tab(_ title: String) {
        CinemaTabNavigation.open(title, in: app)
    }
    private func landscape() {
        XCUIDevice.shared.orientation = .landscapeLeft
        waitFor("The window should rotate") { self.app.frame.width > self.app.frame.height }
    }
    private func seconds(_ video: XCUIElement) -> Int {
        guard video.exists, let value = video.value as? String else { return 0 }
        return value.split(separator: ":").compactMap { Int($0) }.reduce(0) { $0 * 60 + $1 }
    }
    private func waitFor(_ message: String, condition: @escaping () -> Bool) {
        let expected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expected], timeout: 25), .completed, message)
    }
    private func inside(_ control: XCUIElement) {
        waitFor("Control must stay inside the app window: \(control.identifier)") {
            control.exists && control.frame.width > 0 && control.frame.height > 0 &&
            self.app.frame.insetBy(dx: -2, dy: -2).contains(control.frame)
        }
    }
    private func screenshot(_ label: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = deviceName + "-" + label
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
