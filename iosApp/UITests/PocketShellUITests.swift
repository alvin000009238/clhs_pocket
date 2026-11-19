import XCTest

/// Walks the Milestone 1 shell end to end against the shared demo data and
/// records a screenshot of every screen for visual review.
@MainActor
final class PocketShellUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = [
            "-hasCompletedOnboarding", "NO",
            "-session", "guest",
            "-appearance", "system",
            "-demoClock", "2026-11-18T10:35",
        ]
        app.launch()
    }

    func testGuestFirstShellAndFourIndependentStacks() {
        XCTAssertTrue(app.staticTexts["把校園生活放進口袋。"].waitForExistence(timeout: 10))
        capture("01-onboarding-welcome")
        app.buttons["onboarding-next"].tap()
        XCTAssertTrue(app.buttons["onboarding-appearance-dark"].waitForExistence(timeout: 5))
        capture("01b-onboarding-appearance")
        app.buttons["onboarding-next"].tap()
        XCTAssertTrue(app.buttons["onboarding-notifications-skip"].waitForExistence(timeout: 5))
        capture("01c-onboarding-notifications")
        app.buttons["onboarding-notifications-skip"].tap()
        XCTAssertTrue(app.buttons["onboarding-guest"].waitForExistence(timeout: 5))
        capture("01d-onboarding-account")

        // The login form rejects unknown credentials and accepts the demo account.
        app.buttons["onboarding-login"].tap()
        XCTAssertTrue(app.buttons["login-submit"].waitForExistence(timeout: 5))
        app.buttons["login-submit"].tap()
        XCTAssertTrue(app.staticTexts["login-error"].waitForExistence(timeout: 5))
        capture("01e-login-error")
        app.buttons["取消"].tap()

        app.buttons["onboarding-guest"].tap()
        XCTAssertTrue(app.staticTexts["登入後顯示今日課程"].waitForExistence(timeout: 5))
        capture("02-home-guest")

        tab("課表").tap()
        XCTAssertTrue(app.staticTexts["登入後查看課表"].waitForExistence(timeout: 5))
        capture("03-schedule-guest-gate")

        app.buttons["guest-gate-demo"].tap()
        XCTAssertTrue(app.staticTexts["3 項異動"].waitForExistence(timeout: 5))
        capture("04-schedule-week")

        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "上課中")).firstMatch.tap()
        XCTAssertTrue(app.buttons["關閉"].waitForExistence(timeout: 5))
        capture("05-schedule-lesson-sheet")
        app.buttons["關閉"].tap()

        app.swipeUp()
        capture("06-schedule-week-lower")
        app.swipeDown()
        app.buttons["學期"].tap()
        XCTAssertFalse(app.staticTexts["3 項異動"].waitForExistence(timeout: 1))
        capture("07-schedule-semester")

        tab("首頁").tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "還有 25 分鐘下課")).firstMatch.waitForExistence(timeout: 5))
        capture("08-home")
        app.swipeUp()
        capture("09-home-lower")

        tab("成績").tap()
        XCTAssertTrue(app.buttons["exam-picker"].waitForExistence(timeout: 5))
        let latestExam = app.buttons["exam-picker"].label
        capture("10-grades")
        app.swipeUp()
        capture("11-grades-lower")
        app.swipeUp()
        capture("12-grades-subjects")
        app.swipeDown()
        app.swipeDown()
        app.buttons["exam-picker"].tap()
        XCTAssertTrue(app.buttons["第一次段考"].firstMatch.waitForExistence(timeout: 5))
        capture("13-grades-exam-menu")
        app.buttons["第一次段考"].firstMatch.tap()
        XCTAssertNotEqual(app.buttons["exam-picker"].label, latestExam)
        let pickedExam = app.buttons["exam-picker"].label

        tab("校園").tap()
        XCTAssertTrue(app.staticTexts["近期行事"].waitForExistence(timeout: 5))
        capture("14-campus")
        app.swipeUp()
        capture("15-campus-lower")
        app.swipeDown()

        // Push inside 校園, leave, and come back: the stack must be preserved.
        app.staticTexts["3 則未讀"].tap()
        XCTAssertTrue(app.navigationBars["公告"].waitForExistence(timeout: 5))
        capture("16-campus-announcements")
        tab("成績").tap()
        XCTAssertEqual(app.buttons["exam-picker"].label, pickedExam)
        tab("校園").tap()
        XCTAssertTrue(app.navigationBars["公告"].waitForExistence(timeout: 5))
        app.navigationBars["公告"].buttons.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["近期行事"].waitForExistence(timeout: 5))
        app.staticTexts["2026 年 11 月"].tap()
        XCTAssertTrue(app.navigationBars["行事曆"].waitForExistence(timeout: 5))
        capture("17-campus-calendar")

        // Reselecting the active tab pops its stack back to the root.
        tab("校園").tap()
        XCTAssertTrue(app.staticTexts["近期行事"].waitForExistence(timeout: 5))
        app.buttons["personal-button"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["示範帳號 DEMO-000"].waitForExistence(timeout: 5))
        capture("18-personal")
        app.buttons["sign-out"].tap()
        XCTAssertTrue(app.staticTexts["訪客模式"].waitForExistence(timeout: 5))
        capture("19-personal-guest")
    }

    private func tab(_ title: String) -> XCUIElement {
        // The iOS 26 tab bar minimises while scrolled down; scroll back to restore it.
        if !app.tabBars.buttons[title].exists { app.swipeDown() }
        let inTabBar = app.tabBars.buttons[title]
        return inTabBar.exists ? inTabBar : app.buttons[title].firstMatch
    }

    private func capture(_ name: String) {
        // Let scroll indicators and transitions settle before the frame is taken.
        Thread.sleep(forTimeInterval: 0.8)
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let directory = ProcessInfo.processInfo.environment["SHOT_DIR"] {
            try? FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: directory).appendingPathComponent("\(name).png"))
        }
    }
}
