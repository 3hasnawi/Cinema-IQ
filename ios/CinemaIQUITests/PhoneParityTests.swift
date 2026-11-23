import XCTest
import UIKit

/// Touch/keyboard regressions over the local demo catalog. No account action is submitted.
final class PhoneParityTests: XCTestCase {
    private let app = XCUIApplication()
    private var tablet: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    private var deviceName: String { tablet ? "ipad" : "iphone" }

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["-ios.contentSource.v1", "ext:demo", "--cinema-ui-testing-player"]
        KeyboardTestSupport.installIntroductionMonitor(on: self)
        addUIInterruptionMonitor(withDescription: "System permission") { alert in
            for title in ["Allow", "OK", "السماح", "موافق"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
        launchAndWaitForHome()
    }

    override func tearDown() {
        shot("last-state")
        let tree = app.debugDescription
        SimulatorEvidence.hierarchy(tree, test: name)
        let hierarchy = XCTAttachment(string: tree)
        hierarchy.name = deviceName + "-phone-parity-hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
        app.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testAccountKeyboardNextAndBothDoneActionsStayDismissedAfterTabReturn() {
        tab("الإعدادات")
        let email = element("ios.account.email")
        let password = element("ios.account.password")
        let login = element("ios.account.login")
        revealSetting(email)
        XCTAssertTrue(password.exists)
        XCTAssertFalse(login.isEnabled)
        let address = "keyboard@example.invalid"
        email.tap()
        dismissKeyboardIntroductionIfPresent()
        email.typeText(address)
        dismissKeyboardIntroductionIfPresent()
        pressKeyboardButton(["Next", "التالي"])
        // Sending keys through the app cannot implicitly focus the password field.
        // Login becoming enabled and the unchanged email prove that native Next moved focus.
        dismissKeyboardIntroductionIfPresent()
        waitFor("Next must finish switching to the secure field's native Done keyboard") {
            self.keyboardButton(["Done", "تم"]) != nil
        }
        app.typeText("preview-only")
        dismissKeyboardIntroductionIfPresent()
        waitFor("Next must move to the password field") {
            (email.value as? String) == address && login.isEnabled
        }
        shot("account-next-reached-password")
        pressKeyboardButton(["Done", "تم"])
        waitForKeyboardDismissal()
        XCTAssertTrue(login.isEnabled, "Keyboard Done must dismiss without submitting/clearing credentials")
        shot("account-native-done-dismissed")

        revealSetting(password)
        password.tap()
        dismissKeyboardIntroductionIfPresent()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        let done = app.buttons["ios.account.keyboard.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        XCTAssertTrue(done.isHittable)
        done.tap()
        waitForKeyboardDismissal()
        shot("account-toolbar-done-dismissed")

        // Remove the synthetic password before leaving the form. Otherwise iCloud Keychain can
        // offer to save it over the next screen, including over a subsequent test's app launch.
        revealSetting(password)
        password.tap()
        app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "preview-only".count))
        waitFor("The unsubmitted test credential should be removed") { !login.isEnabled }
        pressKeyboardButton(["Done", "تم"])
        waitForKeyboardDismissal()

        tab("الرئيسية")
        XCTAssertTrue(element("ios.home.hero").waitForExistence(timeout: 20))
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        tab("الإعدادات")
        XCTAssertTrue(element("ios.settings").waitForExistence(timeout: 10))
        waitForKeyboardDismissal()
        shot("account-tab-return-does-not-reopen-keyboard")
    }

    func testSearchSubmitDismissesKeyboardAndDetailReturnPreservesQuery() {
        tab("البحث")
        let search = activateSearch()
        search.tap()
        dismissKeyboardIntroductionIfPresent()
        search.typeText("Big")
        dismissKeyboardIntroductionIfPresent()
        pressKeyboardButton(["Search", "بحث", "البحث"])
        waitForKeyboardDismissal()
        let result = firstResult()
        waitFor("Submitted demo result must be reachable") { result.isHittable }
        shot("search-submit-results-without-keyboard")
        result.tap()
        XCTAssertTrue(element("ios.detail.title").waitForExistence(timeout: 20))
        XCTAssertTrue(element("ios.detail.play").exists)
        returnFromDetailsToSearch()
        waitFor("Returning from detail must restore results without a keyboard") {
            result.exists && result.isHittable && !self.app.keyboards.firstMatch.exists
        }
        let restored = app.searchFields.firstMatch
        if restored.exists {
            XCTAssertEqual(restored.value as? String, "Big")
        } else {
            // iPad can collapse the native search field into a toolbar item after navigation.
            // The keyboard was already verified dismissed; reveal only to inspect the retained query.
            let revealed = activateSearch()
            XCTAssertEqual(revealed.value as? String, "Big")
            if app.keyboards.firstMatch.exists {
                pressKeyboardButton(["Search", "بحث", "البحث"])
                waitForKeyboardDismissal()
            }
        }
        shot("search-query-and-results-preserved-after-back")
    }

    func testQualityChoicePersistsAcrossProcessRelaunch() {
        tab("الإعدادات")
        chooseQuality("تلقائي")
        chooseQuality("720p")
        shot("preferred-quality-selected-720p")
        app.terminate()
        // No quality launch argument overrides the stored value on either launch.
        launchAndWaitForHome()
        tab("الإعدادات")
        let quality = element("ios.settings.quality")
        revealSetting(quality)
        waitFor("Quality preference must survive process relaunch") { self.qualityDisplays("720p") }
        shot("preferred-quality-persisted-after-relaunch")
        // Leave later cases at the production default using the same public control.
        chooseQuality("تلقائي")
    }

    private func launchAndWaitForHome() {
        app.launch()
        XCTAssertTrue(element("ios.home.hero").waitForExistence(timeout: 30))
    }

    private func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    private func tab(_ title: String) {
        CinemaTabNavigation.open(title, in: app)
    }

    private func revealSetting(_ target: XCUIElement) {
        XCTAssertTrue(element("ios.settings").waitForExistence(timeout: 15))
        for _ in 0..<8 {
            if target.exists && target.isHittable { return }
            element("ios.settings").swipeUp()
        }
        XCTAssertTrue(target.exists && target.isHittable, "Setting must be reachable: \(target.identifier)")
    }

    private func keyboardButton(_ titles: [String]) -> XCUIElement? {
        let labels = titles.flatMap { [$0, $0.lowercased()] }
        let predicate = NSPredicate(format: "label IN %@", labels)
        let keyboard = app.keyboards.firstMatch
        let choices = keyboard.buttons.matching(predicate).allElementsBoundByIndex +
            keyboard.keys.matching(predicate).allElementsBoundByIndex
        return choices.first {
            $0.identifier != "ios.account.keyboard.done" && $0.exists && $0.isHittable
        }
    }

    private func pressKeyboardButton(_ titles: [String]) {
        dismissKeyboardIntroductionIfPresent()
        waitFor("Native keyboard action must be reachable: \(titles.joined(separator: "/"))") {
            self.keyboardButton(titles) != nil
        }
        keyboardButton(titles)?.tap()
    }

    private func waitForKeyboardDismissal() {
        waitFor("Keyboard must dismiss and stay outside the content") { !self.app.keyboards.firstMatch.exists }
    }

    private func dismissKeyboardIntroductionIfPresent() {
        KeyboardTestSupport.dismissIntroductionIfPresent(in: app)
    }

    private func activateSearch() -> XCUIElement {
        let field = app.searchFields.firstMatch
        if !field.exists {
            func activationButton() -> XCUIElement? {
                app.navigationBars.buttons.matching(
                    NSPredicate(format: "label IN %@", ["Search", "بحث", "البحث"]))
                    .allElementsBoundByIndex.first { $0.exists && $0.isHittable }
            }
            waitFor("Native search control must be reachable") { activationButton() != nil }
            activationButton()?.tap()
        }
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        return field
    }

    private func firstResult() -> XCUIElement {
        let card = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "ios.poster.grid-")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        return card
    }

    private func returnFromDetailsToSearch() {
        func backButton() -> XCUIElement? {
            app.navigationBars.buttons.matching(
                NSPredicate(format: "label IN %@ OR identifier == 'Back'", ["Back", "رجوع", "البحث", "Search"]))
                .allElementsBoundByIndex.first { $0.exists && $0.isHittable }
        }
        waitFor("The native Back action must be reachable") { backButton() != nil }
        backButton()?.tap()
    }

    private func chooseQuality(_ title: String) {
        let quality = element("ios.settings.quality")
        revealSetting(quality)
        quality.tap()
        func qualityOption() -> XCUIElement? {
            app.buttons.matching(NSPredicate(format: "label == %@", title))
                .allElementsBoundByIndex.first { $0.exists && $0.isHittable }
        }
        waitFor("Quality menu must offer \(title)") { qualityOption() != nil }
        qualityOption()?.tap()
        waitFor("Selected quality must be displayed") { self.qualityDisplays(title) }
    }

    private func qualityDisplays(_ title: String) -> Bool {
        let row = element("ios.settings.quality")
        return row.exists && (row.label.contains(title) || (row.value as? String)?.contains(title) == true)
    }

    private func waitFor(_ message: String, condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 20), .completed, message)
    }

    private func shot(_ label: String) {
        let capture = XCUIScreen.main.screenshot()
        SimulatorEvidence.screenshot(capture, test: name, name: deviceName + "-" + label)
        let attachment = XCTAttachment(screenshot: capture)
        attachment.name = deviceName + "-" + label
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
