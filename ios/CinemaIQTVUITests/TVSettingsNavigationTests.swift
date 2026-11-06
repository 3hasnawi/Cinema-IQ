import XCTest

/// Focused remote regressions for the reference settings arrangement. No test creates an account or sends email.
final class TVSettingsNavigationTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-tv.initialTab", "settings", "-tv.testSource", "ext:demo",
                               "-tv.testSubtitleLanguage", "ar", "-tv.testSubtitleSize", "20"]
        app.launch()
        XCTAssertTrue(button("tv.settings.source").waitForExistence(timeout: 15))
    }

    func testReferenceArrangementAndMenuReturnsToNavigation() {
        let source = button("tv.settings.source")
        let logo = app.descendants(matching: .any).matching(identifier: "tv.settings.logo").firstMatch
        XCTAssertTrue(logo.exists)
        XCTAssertLessThan(logo.frame.maxX, source.frame.minX, "The app logo stays to the left of the Arabic settings rows")
        XCTAssertGreaterThan(source.frame.width, logo.frame.width)
        XCTAssertTrue(button("tv.settings.providers").exists)
        XCTAssertTrue(button("tv.settings.account").exists)
        XCTAssertTrue(button("tv.settings.library").exists)
        XCTAssertTrue(button("tv.settings.subtitles").exists)
        XCTAssertTrue(button("tv.settings.about").exists)
        focus(source)
        shot("settings-reference-layout")
        remote.press(.menu)
        assertFocus(button("tv.navigation.settings"))
        XCTAssertTrue(source.exists)
        remote.press(.down)
        assertFocus(source)
    }

    func testSourcePopupCancelAndSelectionRestoreFocus() {
        let source = button("tv.settings.source")
        focus(source)
        for _ in 0..<2 {
            remote.press(.select)
            let selected = option("عروض مفتوحة (تجريبي)")
            XCTAssertTrue(selected.waitForExistence(timeout: 5))
            assertFocus(selected)
            remote.press(.menu)
            assertFocus(source)
        }
        remote.press(.select)
        let vodu = option("فودو")
        XCTAssertTrue(vodu.waitForExistence(timeout: 5))
        focus(vodu)
        remote.press(.select)
        assertFocus(source)
        XCTAssertTrue(source.label.contains("فودو"))
        shot("settings-source-changed")
    }

    func testSubtitlePopupAndBackPreserveSettings() {
        let subtitles = button("tv.settings.subtitles")
        focus(subtitles)
        remote.press(.select)
        let language = button("tv.settings.subtitle.language")
        XCTAssertTrue(language.waitForExistence(timeout: 5))
        focus(language)
        remote.press(.select)
        let arabic = option("العربية")
        XCTAssertTrue(arabic.waitForExistence(timeout: 5))
        assertFocus(arabic)
        remote.press(.menu)
        assertFocus(language)

        let size = button("tv.settings.subtitle.size")
        focus(size)
        remote.press(.select)
        let large = option("كبير")
        XCTAssertTrue(large.waitForExistence(timeout: 5))
        focus(large)
        remote.press(.select)
        assertFocus(size)
        XCTAssertTrue(size.label.contains("كبير"))
        shot("settings-subtitle-preview")

        remote.press(.menu)
        XCTAssertTrue(subtitles.waitForExistence(timeout: 5))
        assertFocus(subtitles)
        remote.press(.select)
        XCTAssertTrue(size.waitForExistence(timeout: 5))
        XCTAssertTrue(size.label.contains("كبير"), "The selected subtitle preference survives back and re-entry")
        remote.press(.menu)
        assertFocus(subtitles)
    }

    func testAccountModesRequireCredentialsWithoutSendingRequests() throws {
        let account = button("tv.settings.account")
        focus(account)
        remote.press(.select)
        let email = app.textFields["tv.settings.account.email"]
        guard email.waitForExistence(timeout: 5) else {
            throw XCTSkip("The account is already signed in or this build has no account configuration; no account is changed")
        }
        let submit = button("tv.settings.account.submit")
        let reset = button("tv.settings.account.resetPassword")
        let toggle = button("tv.settings.account.toggleMode")
        XCTAssertFalse(submit.isEnabled)
        XCTAssertFalse(reset.isEnabled)
        focus(toggle)
        remote.press(.select)
        XCTAssertTrue(submit.label.contains("إنشاء حساب"))
        XCTAssertFalse(submit.isEnabled)
        XCTAssertFalse(reset.isEnabled)
        shot("settings-account-create-mode")
        remote.press(.select)
        XCTAssertTrue(submit.label.contains("تسجيل الدخول"))
        XCTAssertFalse(submit.isEnabled)
        remote.press(.menu)
        assertFocus(account)
    }

    private func button(_ id: String) -> XCUIElement { app.buttons[id] }

    private func option(_ title: String) -> XCUIElement {
        // The selected row may include the checkmark's accessibility label after the title.
        app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", title, title + ",")).firstMatch
    }

    private func focus(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: 5), file: file, line: line)
        for _ in 0..<12 where !element.hasFocus {
            let current = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let goUp = current.exists && current.frame.midY > element.frame.midY
            remote.press(goUp ? .up : .down)
            Thread.sleep(forTimeInterval: 0.25)
        }
        assertFocus(element, file: file, line: line)
    }

    private func assertFocus(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 5), .completed, file: file, line: line)
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
