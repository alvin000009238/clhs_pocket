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

    private func launch(onboarded: Bool, session: String, dataMode: String = "demo", lockAuth: String = "succeed", extra: [String] = []) {
        app.launchArguments = [
            "-resetLocalState", "YES",
            "-hasCompletedOnboarding", onboarded ? "YES" : "NO",
            "-session", session,
            // TEST_RUNNER_APPEARANCE=dark reruns the flows for the dark-mode screenshots.
            "-appearance", ProcessInfo.processInfo.environment["APPEARANCE"] ?? "system",
            "-demoClock", "2026-11-18T10:35",
            "-dataMode", dataMode,
            "-lockAuth", lockAuth,
            // A Live Activity outlives the app and makes the system relaunch it, which races the
            // unit-test host that the same scheme starts afterwards.
            "-platform.liveActivity", "NO",
        ] + extra
        // Optional Dynamic Type pass, e.g. TEST_RUNNER_CONTENT_SIZE=UICTContentSizeCategoryAccessibilityXL.
        if let size = ProcessInfo.processInfo.environment["CONTENT_SIZE"], !size.isEmpty {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", size]
        }
        // Xcode can pre-launch the app in the background with only the scheme's arguments;
        // end that process so this launch really starts with the arguments above.
        app.terminate()
        app.launch()
    }

    /// iPad only: the same screen on the wider landscape grid, then back to portrait.
    private func captureLandscapeOnPad(_ name: String) {
        guard UIDevice.current.userInterfaceIdiom == .pad else { return }
        let window = app.windows.firstMatch
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(waitUntil { window.frame.width > window.frame.height }, "the window never rotated to landscape")
        capture(name)
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(waitUntil { window.frame.height > window.frame.width }, "the window never rotated back to portrait")
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
        captureLandscapeOnPad("02-home-guest-landscape")

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
        captureLandscapeOnPad("08-home-landscape")
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
        let confirm = app.buttons["sign-out-confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "signing out must ask for confirmation first")
        XCTAssertTrue(app.staticTexts["示範帳號 DEMO-000"].exists, "the account is still signed in until confirmed")
        capture("18b-sign-out-confirm")
        confirm.tap()
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

    func testHomeWidgetsReorderByDragging() {
        launch(onboarded: true, session: "demo")
        let widgets = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "widget-"))
        // A freshly booted simulator can take well over ten seconds to bring the app up.
        XCTAssertTrue(widgets.firstMatch.waitForExistence(timeout: 40))
        func order() -> [String] {
            widgets.allElementsBoundByIndex
                .sorted { ($0.frame.minY, $0.frame.minX) < ($1.frame.minY, $1.frame.minX) }
                .map(\.identifier)
        }
        let before = order()
        XCTAssertGreaterThanOrEqual(before.count, 3)
        // Drag between two different widgets whose centres are clear of the top bar and the floating
        // tab bar on any phone size: a widget peeking out behind the tab bar counts as hittable, but a
        // drop there lands on the bar and reorders nothing. Identifiers repeat (two countdowns, nested
        // elements), so each is used once.
        let screen = app.windows.firstMatch.frame
        let clear = (screen.minY + 150)...(screen.maxY - 170)
        var seen = Set<String>()
        let reachable = widgets.allElementsBoundByIndex
            .sorted { ($0.frame.minY, $0.frame.minX) < ($1.frame.minY, $1.frame.minX) }
            .filter { clear.contains($0.frame.midY) && $0.isHittable && seen.insert($0.identifier).inserted }
        XCTAssertGreaterThanOrEqual(reachable.count, 2)
        let moved = reachable[reachable.count - 2].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let target = reachable[reachable.count - 1].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        // On a freshly booted simulator the first drag has been seen to change nothing. A person
        // would simply drag again; the flow does the same, keeps a screenshot of the failed attempt,
        // and still requires that a drag ends up changing the order.
        for attempt in 1...3 where order() == before {
            if attempt > 1 { capture("26-drag-attempt-\(attempt - 1)-unchanged") }
            _ = app.wait(for: .runningForeground, timeout: 5)
            moved.press(forDuration: 1.5, thenDragTo: target, withVelocity: .slow, thenHoldForDuration: 1.0)
            // The drop animates; on a slow machine the new layout can take a few seconds to settle.
            _ = waitUntil(timeout: 10) { order() != before }
        }
        let after = order()
        XCTAssertEqual(Set(after), Set(before))
        XCTAssertNotEqual(after, before, "dragging a widget onto another must change the order")
        capture("26-home-reordered")
    }

    /// Xcode's accessibility audit on the four root screens: every element needs a description
    /// VoiceOver can read, and controls need a usable hit region.
    func testRootScreensPassTheAccessibilityAudit() throws {
        launch(onboarded: true, session: "demo")
        XCTAssertTrue(app.buttons["home-edit"].waitForExistence(timeout: 10))
        var issues: [String] = []
        for (index, tab) in ["首頁", "課表", "成績", "校園"].enumerated() {
            if index > 0 { self.tab(tab).tap() }
            let selected = self.tab(tab)
            XCTAssertTrue(waitUntil { selected.isSelected }, "\(tab) tab never became selected")
            try app.performAccessibilityAudit(for: [.sufficientElementDescription, .hitRegion]) { issue in
                let element = issue.element
                issues.append("\(tab): \(issue.compactDescription) [\(element?.identifier ?? "") | \(element?.label ?? "") | \(element?.elementType.rawValue ?? 0) | \(element?.frame.debugDescription ?? "")]")
                return true
            }
        }
        if let directory = ProcessInfo.processInfo.environment["SHOT_DIR"] {
            try? FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
            try? issues.joined(separator: "\n").write(toFile: directory + "/accessibility-audit.txt", atomically: true, encoding: .utf8)
        }
        XCTAssertTrue(issues.isEmpty, issues.joined(separator: "\n"))
    }

    func testScheduleImageIsSavedToPhotos() {
        launch(onboarded: true, session: "demo", extra: ["-initialTab", "schedule"])
        let menu = app.buttons["schedule-menu"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        menu.tap()
        let save = app.buttons["schedule-save-photo"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        save.tap()
        // First use asks for add-only access to Photos.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow", "允許", "OK", "好"] {
            let button = springboard.alerts.buttons[label]
            if button.waitForExistence(timeout: label == "Allow" ? 5 : 1) {
                button.tap()
                break
            }
        }
        XCTAssertTrue(app.alerts.staticTexts["已儲存到照片。"].waitForExistence(timeout: 10))
        capture("44-schedule-saved-to-photos")
        app.alerts.buttons["好"].tap()
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
        // Keep the button clear of the top bar and the floating tab bar before tapping it.
        reveal(simulator)
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

        open("settings-安靜時段")
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
        app.buttons["about-licenses"].tap()
        let huninn = app.buttons["license-Huninn-OFL"]
        XCTAssertTrue(huninn.waitForExistence(timeout: 5))
        capture("66b-settings-licenses")
        huninn.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "SIL Open Font License")).firstMatch.waitForExistence(timeout: 5),
                      "the bundled licence text must load")
        capture("66c-settings-license-huninn")
        app.navigationBars.buttons.firstMatch.tap()
        app.navigationBars.buttons.firstMatch.tap()
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
        // The iOS 26 tab bar minimises while scrolled down and then shows only the current tab,
        // as a button whose value is "Collapsed". Scrolling back up does not always restore it,
        // so expand it the way a user does: tap the collapsed bar (this keeps the scroll position
        // and does not reselect the tab). Only a real tab-bar button is ever returned.
        guard app.tabBars.firstMatch.waitForExistence(timeout: 2) else {
            // On iPad the sidebar-adaptable tabs sit in a top bar or sidebar, not in a tab bar.
            return app.buttons[title].firstMatch
        }
        let button = app.tabBars.buttons[title]
        for _ in 0..<4 where !button.waitForExistence(timeout: 2) {
            let collapsed = app.tabBars.buttons.matching(NSPredicate(format: "value == %@", "Collapsed")).firstMatch
            if collapsed.exists { collapsed.tap() } else { app.swipeDown() }
        }
        return button
    }

    func testAppLockCoversTheAppUntilUnlocked() {
        launch(onboarded: true, session: "demo", lockAuth: "manual", extra: ["-appLockEnabled", "YES"])
        let unlock = app.buttons["lock-unlock"]
        XCTAssertTrue(unlock.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["home-edit"].isHittable)
        capture("80-lock-screen")
        // `-lockAuth manual` cancels every prompt during the first seconds after launch, so keep
        // asking until a prompt succeeds and the lock screen goes away.
        XCTAssertTrue(waitUntil(timeout: 20) {
            if unlock.exists, unlock.isHittable { unlock.tap() }
            return !unlock.exists
        }, "the lock screen never unlocked")
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5) || app.buttons["home-edit"].waitForExistence(timeout: 5))
        XCTAssertFalse(unlock.exists)
        capture("81-unlocked-home")
    }

    /// The personal sheet is a separate presentation; the lock must cover it too.
    func testLockCoversAnOpenSheetAfterReturningFromTheBackground() {
        // `relock`: the launch prompt succeeds, later prompts are cancelled.
        launch(onboarded: true, session: "demo", lockAuth: "relock", extra: ["-appLockEnabled", "YES"])
        let personal = app.buttons["personal-button"].firstMatch
        XCTAssertTrue(personal.waitForExistence(timeout: 10))
        personal.tap()
        let name = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "DEMO-000")).firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 5))

        // `relock` still lets prompts succeed during launch, so return from the background until
        // the app actually locks instead of guessing when that window ends.
        let unlock = app.buttons["lock-unlock"]
        XCTAssertTrue(waitUntil(timeout: 30) {
            XCUIDevice.shared.press(.home)
            _ = self.app.wait(for: .runningBackground, timeout: 5)
            self.app.activate()
            _ = self.app.wait(for: .runningForeground, timeout: 5)
            return unlock.waitForExistence(timeout: 3)
        }, "returning from the background never locked the app")
        XCTAssertFalse(name.exists, "personal data is still exposed to accessibility while locked")
        XCTAssertFalse(app.buttons["sign-out"].exists)
        XCTAssertFalse(app.navigationBars["個人"].exists)
        capture("84-lock-over-personal-sheet")
    }

    /// Production mode as a guest: the public widgets are fed by the live services. What they
    /// show depends on the network, so this only records the screens.
    func testProductionGuestHomeShowsPublicWidgets() throws {
        try requireLiveNetwork()
        launch(onboarded: true, session: "guest", dataMode: "live")
        let edit = app.buttons["home-edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
        let loading = app.activityIndicators.firstMatch
        _ = waitUntil(timeout: 15) { !loading.exists }
        capture("85-live-guest-home")
        reveal(edit)
        edit.tap()
        let add = app.buttons["add-widget-youbike"]
        XCTAssertTrue(app.buttons["dashboard-done"].waitForExistence(timeout: 5))
        if add.exists || { app.swipeUp(); return add.exists }() {
            reveal(add)
            add.tap()
        }
        for _ in 0..<3 where !app.buttons["dashboard-done"].isHittable { app.swipeDown() }
        app.buttons["dashboard-done"].tap()
        let bike = app.descendants(matching: .any)["widget-youbike"]
        for _ in 0..<5 where !bike.exists { app.swipeUp() }
        XCTAssertTrue(bike.waitForExistence(timeout: 5))
        _ = waitUntil(timeout: 15) { !bike.activityIndicators.firstMatch.exists }
        capture("86-live-guest-youbike")
    }

    /// Production mode: a guest sees real public data and the school's own sign-in page.
    /// No credentials are entered; the page content depends on the network.
    func testProductionGuestReachesTheSchoolLoginScreen() throws {
        try requireLiveNetwork()
        launch(onboarded: true, session: "guest", dataMode: "live", extra: ["-initialTab", "grades"])
        let login = app.buttons["guest-gate-demo"]
        XCTAssertTrue(login.waitForExistence(timeout: 10))
        capture("82-live-guest-gate")
        login.tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 20) || app.buttons["login-retry"].waitForExistence(timeout: 5))
        // Give the school's page time to render; the screenshot is taken either way.
        let loading = app.staticTexts["正在載入…"]
        _ = waitUntil(timeout: 10) { !loading.exists }
        capture("83-live-school-login")
    }

    /// Flows that talk to the school, weather and YouBike servers are opt-in, so a CI gate never
    /// depends on someone else's service: run them with `TEST_RUNNER_LIVE_NETWORK=1`.
    private func requireLiveNetwork() throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["LIVE_NETWORK"] == "1",
            "Live-network flow; set TEST_RUNNER_LIVE_NETWORK=1 to run it"
        )
    }

    /// Polls a condition until it holds or the timeout passes; returns whether it held.
    @discardableResult
    private func waitUntil(timeout: TimeInterval = 10, _ condition: @escaping () -> Bool) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    private func capture(_ name: String) {
        // Queries wait for the app to go idle, so transitions have settled before the frame is taken.
        _ = app.wait(for: .runningForeground, timeout: 5)
        _ = app.windows.firstMatch.exists
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
