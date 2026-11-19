import XCTest

/// Walks the app against the shared demo data and records a screenshot of
/// every screen for visual review.
@MainActor
final class PocketShellUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(onboarded: Bool, session: String) {
        app.launchArguments = [
            "-resetLocalState", "YES",
            "-hasCompletedOnboarding", onboarded ? "YES" : "NO",
            "-session", session,
            "-appearance", "system",
            "-demoClock", "2026-11-18T10:35",
            "-dataMode", "demo",
        ]
        // Optional Dynamic Type pass, e.g. TEST_RUNNER_CONTENT_SIZE=UICTContentSizeCategoryAccessibilityXL.
        if let size = ProcessInfo.processInfo.environment["CONTENT_SIZE"], !size.isEmpty {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", size]
        }
        app.launch()
    }

    func testGuestFirstShellAndFourIndependentStacks() {
        launch(onboarded: false, session: "guest")
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
        app.buttons["campus-公告"].tap()
        XCTAssertTrue(app.navigationBars["公告"].waitForExistence(timeout: 5))
        capture("16-campus-announcements")
        tab("成績").tap()
        XCTAssertEqual(app.buttons["exam-picker"].label, pickedExam)
        tab("校園").tap()
        XCTAssertTrue(app.navigationBars["公告"].waitForExistence(timeout: 5))
        app.navigationBars["公告"].buttons.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["近期行事"].waitForExistence(timeout: 5))
        app.buttons["campus-行事曆"].tap()
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

    func testHomeDashboardCanBeEdited() {
        launch(onboarded: true, session: "demo")
        let edit = app.buttons["home-edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
        reveal(edit)
        edit.tap()
        XCTAssertTrue(app.buttons["dashboard-done"].waitForExistence(timeout: 5))
        capture("20-dashboard-editor")

        // Add a YouBike widget, then configure the weather widget.
        let add = app.buttons["add-widget-youbike"]
        reveal(add)
        add.tap()
        capture("21-dashboard-add")
        for _ in 0..<3 where !app.buttons["dashboard-row-1"].isHittable { app.swipeDown() }
        app.buttons["dashboard-row-1"].tap()
        XCTAssertTrue(app.segmentedControls["widget-size"].waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "choice-")).element(boundBy: 1).tap()
        capture("22-widget-settings-weather")
        app.navigationBars.buttons.firstMatch.tap()

        // Turn the first countdown into a custom one.
        app.buttons["dashboard-row-2"].tap()
        XCTAssertTrue(app.segmentedControls["countdown-mode"].waitForExistence(timeout: 5))
        app.segmentedControls["countdown-mode"].buttons["自訂日期"].tap()
        let title = app.textFields["countdown-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText("畢業旅行\n")
        reveal(app.buttons["countdown-apply"])
        app.buttons["countdown-apply"].tap()
        capture("23-widget-settings-countdown")
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["dashboard-done"].tap()

        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "畢業旅行")).firstMatch.waitForExistence(timeout: 5))
        capture("24-home-customised")
        app.swipeUp()
        capture("25-home-customised-lower")
    }

    func testGradeDetailAndSimulator() {
        launch(onboarded: true, session: "demo")
        tab("成績").tap()
        XCTAssertTrue(app.buttons["grades-simulator"].waitForExistence(timeout: 10))
        let subject = app.buttons["subject-0"]
        reveal(subject)
        subject.tap()
        XCTAssertTrue(app.staticTexts["五標"].waitForExistence(timeout: 5))
        capture("30-subject-detail")
        app.swipeUp()
        capture("31-subject-detail-lower")
        app.navigationBars.buttons.firstMatch.tap()

        let simulator = app.buttons["grades-simulator"]
        for _ in 0..<4 where !simulator.isHittable { app.swipeDown() }
        simulator.tap()
        XCTAssertTrue(app.staticTexts["模擬加權平均"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["simulator-reset"].isEnabled)
        capture("32-simulator")
        let slider = app.sliders["simulator-slider-0"]
        reveal(slider)
        slider.adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(app.buttons["simulator-reset"].isEnabled)
        capture("33-simulator-adjusted")

        let credits = app.buttons["simulator-credits"]
        for _ in 0..<4 where !credits.isHittable { app.swipeDown() }
        credits.tap()
        XCTAssertTrue(app.buttons["credits-done"].waitForExistence(timeout: 5))
        capture("34-credits")
        let infer = app.buttons["credits-infer"]
        reveal(infer)
        infer.tap()
        app.swipeUp()
        let candidate = app.buttons["credits-candidate-0"]
        XCTAssertTrue(candidate.waitForExistence(timeout: 10))
        reveal(candidate)
        capture("35-credits-inferred")
        app.buttons["credits-done"].tap()
    }

    func testScheduleOverrideAndWeekSwitching() {
        launch(onboarded: true, session: "demo")
        tab("課表").tap()
        let current = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "上課中")).firstMatch
        XCTAssertTrue(current.waitForExistence(timeout: 10))
        current.tap()
        let edit = app.buttons["override-edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        reveal(edit)
        edit.tap()
        let field = app.textFields["override-subject"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("A\n")
        capture("40-schedule-override-edit")
        reveal(app.buttons["override-save"])
        app.buttons["override-save"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "數學A")).firstMatch.waitForExistence(timeout: 5))
        capture("41-schedule-override")

        app.buttons["schedule-next-week"].tap()
        XCTAssertTrue(app.staticTexts["schedule-range"].label.contains("11/23"))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "上課中")).firstMatch.exists)
        capture("42-schedule-next-week")
        app.buttons["schedule-this-week"].tap()

        app.buttons["schedule-menu"].tap()
        XCTAssertTrue(app.buttons["分享課表圖片"].waitForExistence(timeout: 5))
        capture("43-schedule-menu")
    }

    func testCampusAnnouncementsCalendarAndRecords() {
        launch(onboarded: true, session: "demo")
        tab("校園").tap()
        XCTAssertTrue(app.buttons["campus-公告"].waitForExistence(timeout: 10))
        app.buttons["campus-公告"].tap()
        XCTAssertTrue(app.segmentedControls["announcement-filter"].waitForExistence(timeout: 5))
        capture("50-announcements")
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "category-")).element(boundBy: 1).tap()
        capture("51-announcements-category")
        app.buttons["category-全部"].tap()

        // Opening an announcement marks it read; bookmarking it fills the 收藏 filter.
        let unreadBefore = app.segmentedControls["announcement-filter"].buttons.element(boundBy: 1).label
        app.buttons["announcement-45055"].tap()
        XCTAssertTrue(app.buttons["announcement-bookmark"].waitForExistence(timeout: 5))
        app.buttons["announcement-bookmark"].tap()
        capture("52-announcement-detail")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertNotEqual(app.segmentedControls["announcement-filter"].buttons.element(boundBy: 1).label, unreadBefore)
        app.segmentedControls["announcement-filter"].buttons["收藏"].tap()
        XCTAssertTrue(app.buttons["announcement-45055"].waitForExistence(timeout: 5))
        capture("53-announcements-bookmarked")
        app.segmentedControls["announcement-filter"].buttons["全部"].tap()

        tab("校園").tap()
        XCTAssertTrue(app.buttons["campus-行事曆"].waitForExistence(timeout: 5))
        app.buttons["campus-行事曆"].tap()
        XCTAssertTrue(app.staticTexts["calendar-title"].waitForExistence(timeout: 5))
        capture("55-calendar-month")
        app.buttons["day-2026-11-26"].tap()
        XCTAssertTrue(app.staticTexts["當日行程"].waitForExistence(timeout: 5))
        capture("56-calendar-day")
        app.buttons["calendar-next"].tap()
        XCTAssertEqual(app.staticTexts["calendar-title"].label, "2026 年 12 月")
        app.segmentedControls["calendar-mode"].buttons["列表"].tap()
        capture("57-calendar-list")

        tab("校園").tap()
        app.buttons["campus-出缺勤"].tap()
        XCTAssertTrue(app.buttons["records-semester"].waitForExistence(timeout: 5))
        capture("58-attendance")
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["campus-獎懲"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "銷過")).firstMatch.waitForExistence(timeout: 5))
        capture("59-merits")

        // Search last: an active search replaces the tab bar on iPad.
        tab("校園").tap()
        app.buttons["campus-公告"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("獎助")
        XCTAssertTrue(app.buttons["announcement-45040"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["announcement-45055"].exists)
        capture("54-announcements-search")
    }

    func testSettingsScreensAndDeleteData() {
        launch(onboarded: true, session: "demo")
        XCTAssertTrue(app.buttons["personal-button"].firstMatch.waitForExistence(timeout: 10))
        app.buttons["personal-button"].firstMatch.tap()
        XCTAssertTrue(app.buttons["settings-通知"].waitForExistence(timeout: 5))
        capture("60-personal")
        app.swipeUp()
        capture("61-personal-lower")

        open("settings-通知")
        toggle("notifications-master")
        capture("62-settings-notifications")
        app.navigationBars.buttons.firstMatch.tap()

        open("settings-背景暫停時段")
        toggle("pause-enabled")
        XCTAssertTrue(app.staticTexts["開始"].waitForExistence(timeout: 5))
        capture("63-settings-pause")
        app.navigationBars.buttons.firstMatch.tap()

        open("settings-天氣來源")
        app.buttons["weather-source-cwa"].tap()
        capture("64-settings-weather")
        app.navigationBars.buttons.firstMatch.tap()

        open("settings-App 鎖定")
        toggle("app-lock-toggle")
        capture("65-settings-app-lock")
        app.navigationBars.buttons.firstMatch.tap()

        open("settings-關於")
        capture("66-settings-about")
        app.navigationBars.buttons.firstMatch.tap()

        open("settings-資料管理")
        capture("67-settings-data")
        app.buttons["data-delete"].tap()
        XCTAssertTrue(app.buttons["data-delete-confirm"].firstMatch.waitForExistence(timeout: 5))
        capture("68-settings-delete-confirm")
        app.buttons["data-delete-confirm"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["data-message"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        for _ in 0..<3 where !app.staticTexts["訪客模式"].exists { app.swipeDown() }
        XCTAssertTrue(app.staticTexts["訪客模式"].waitForExistence(timeout: 5))
        let login = app.buttons["personal-login"]
        reveal(login)
        login.tap()
        XCTAssertTrue(app.buttons["login-fill-demo"].waitForExistence(timeout: 5))
        app.buttons["login-fill-demo"].tap()
        app.buttons["login-submit"].tap()
        XCTAssertTrue(app.staticTexts["示範帳號 DEMO-000"].waitForExistence(timeout: 5))
        capture("69-personal-signed-in-again")
    }

    private func open(_ identifier: String) {
        let row = app.buttons[identifier]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        reveal(row)
        row.tap()
    }

    /// Scrolls until the element can be tapped.
    private func reveal(_ element: XCUIElement) {
        let screen = app.windows.firstMatch.frame
        for _ in 0..<8 {
            guard element.exists, element.isHittable else { app.swipeUp(); continue }
            // Hittable rows can still sit under the navigation bar or the floating tab bar.
            if element.frame.midY < screen.minY + 120 {
                app.swipeDown(velocity: .slow)
            } else if element.frame.midY > screen.maxY - 110 {
                app.swipeUp(velocity: .slow)
            } else {
                return
            }
        }
    }

    private func toggle(_ identifier: String) {
        let control = app.switches[identifier]
        XCTAssertTrue(control.waitForExistence(timeout: 5))
        let before = control.value as? String
        control.tap()
        if control.value as? String == before {
            control.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        }
        XCTAssertNotEqual(control.value as? String, before)
    }

    private func tab(_ title: String) -> XCUIElement {
        // The iOS 26 tab bar minimises while scrolled down; scroll back to restore it.
        for _ in 0..<4 where !app.tabBars.buttons[title].exists { app.swipeDown() }
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
