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
        // The first test of a run can attach to the process Xcode pre-launched with only the scheme's
        // arguments, which shows onboarding at the default text size. That process ignored this
        // launch, so start a fresh one and require the arguments to take effect this time.
        if onboarded, app.buttons["onboarding-next"].exists {
            XCTContext.runActivity(named: "Relaunch: the running process did not receive the launch arguments") { _ in
                app.terminate()
                app.launch()
            }
            XCTAssertFalse(app.buttons["onboarding-next"].exists, "launch arguments were not applied")
        }
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
        // Any township from the offline catalogue, not just the demo's four places.
        search("weather-search", "台東 池上")
        let township = app.buttons["choice-tw-10014060"]
        XCTAssertTrue(township.waitForExistence(timeout: 5))
        reveal(township)
        township.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已選擇：池上")).firstMatch.waitForExistence(timeout: 5))
        let cwa = app.buttons["weather-source-CWA"]
        reveal(cwa)
        cwa.tap()
        XCTAssertTrue(cwa.isSelected)
        capture("22-widget-settings-weather")
        app.navigationBars.buttons.firstMatch.tap()

        // The YouBike widget searches stations instead of a fixed list.
        let bikeRow = editorRow("YouBike")
        reveal(bikeRow)
        bikeRow.tap()
        search("youbike-search", "中壢")
        let station = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "choice-")).firstMatch
        XCTAssertTrue(station.waitForExistence(timeout: 5))
        let stationName = station.label.components(separatedBy: ",").first ?? ""
        reveal(station)
        station.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已選擇")).firstMatch.waitForExistence(timeout: 5))
        search("youbike-search", "不存在的站")
        XCTAssertTrue(app.staticTexts["search-empty"].waitForExistence(timeout: 5))
        capture("22b-widget-settings-youbike")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(editorRow(String(stationName.prefix(4))).exists, "the editor row summarises the chosen station")
        for _ in 0..<3 where !app.buttons["dashboard-row-2"].isHittable { app.swipeDown() }

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

    func testDashboardListOptionsAndDiscardingADraft() {
        launch(onboarded: true, session: "demo")
        let edit = app.buttons["home-edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 40))
        reveal(edit)
        edit.tap()
        XCTAssertTrue(app.buttons["dashboard-cancel"].waitForExistence(timeout: 5))

        let announcements = editorRow("公告")
        reveal(announcements)
        announcements.tap()
        let bookmarkedUp = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "priority-up-")).element(boundBy: 2)
        reveal(bookmarkedUp)
        let last = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "priority-")).element(boundBy: 2).label
        bookmarkedUp.tap()
        XCTAssertNotEqual(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "priority-")).element(boundBy: 2).label, last)
        capture("26-widget-settings-announcements")
        app.navigationBars.buttons.firstMatch.tap()

        let calendar = editorRow("近期行程")
        reveal(calendar)
        calendar.tap()
        toggle("calendar-hide-long")
        XCTAssertTrue(app.otherElements["calendar-max-days"].waitForExistence(timeout: 5) || app.steppers["calendar-max-days"].exists)
        capture("27-widget-settings-calendar")
        app.navigationBars.buttons.firstMatch.tap()

        // Cancelling asks before throwing away the draft; discarding restores the saved home.
        let bikeRows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "dashboard-row-", "YouBike"))
        let savedBikes = bikeRows.count
        let add = app.buttons["add-widget-youbike"]
        reveal(add)
        add.tap()
        for _ in 0..<3 where !app.buttons["dashboard-cancel"].isHittable { app.swipeDown() }
        XCTAssertTrue(app.buttons["dashboard-undo"].isEnabled)
        app.buttons["dashboard-cancel"].tap()
        let discard = app.buttons["dashboard-discard"].firstMatch
        XCTAssertTrue(discard.waitForExistence(timeout: 5))
        capture("28-dashboard-discard-dialog")
        discard.tap()
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        reveal(edit)
        edit.tap()
        XCTAssertTrue(app.buttons["dashboard-cancel"].waitForExistence(timeout: 5))
        XCTAssertEqual(bikeRows.count, savedBikes, "a discarded draft must not reach the saved home")
        XCTAssertFalse(app.buttons["dashboard-undo"].isEnabled)
    }

    func testHomeWidgetsReorderByDragging() {
        launch(onboarded: true, session: "demo")
        let widgets = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "widget-"))
        // A freshly booted simulator can take well over ten seconds to bring the app up.
        XCTAssertTrue(widgets.firstMatch.waitForExistence(timeout: 40))
        // Widget identifiers in reading order, from one snapshot of the hierarchy. Resolving every match
        // by index re-queries the app once per element, which on a slow simulator overlaps the drop
        // animation and fails when the matches change between reads.
        func snapshotOrder() -> [String]? {
            guard let root = try? app.snapshot() else { return nil }
            var found: [(frame: CGRect, id: String)] = []
            var pending = [root]
            while let node = pending.popLast() {
                if node.identifier.hasPrefix("widget-") { found.append((node.frame, node.identifier)) }
                pending.append(contentsOf: node.children)
            }
            return found
                .sorted { ($0.frame.minY, $0.frame.minX, $0.id) < ($1.frame.minY, $1.frame.minX, $1.id) }
                .map(\.id)
        }
        func order() -> [String] {
            var result: [String]?
            XCTAssertTrue(waitUntil(timeout: 20) { result = snapshotOrder(); return result != nil }, "the home screen can be read")
            return result ?? []
        }
        // Wait for the home screen to settle, so a widget that finishes loading is not taken for a move.
        var before = order()
        for _ in 0..<5 {
            let again = order()
            if again == before { break }
            before = again
        }
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
            _ = waitUntil(timeout: 10) { snapshotOrder().map { $0 != before } ?? false }
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
        // Each root screen is recognised by its own content. The tab's selected trait is not
        // usable for this: on the iPad Air 11-inch the compact top tab bar never reports any tab as
        // selected (it does on the 13-inch), so it would not say which screen is being audited.
        let roots = [("首頁", "home-edit"), ("課表", "schedule-range"), ("成績", "grades-simulator"), ("校園", "campus-公告")]
        for (index, (tab, marker)) in roots.enumerated() {
            if index > 0 { self.tab(tab).tap() }
            XCTAssertTrue(app.descendants(matching: .any)[marker].firstMatch.waitForExistence(timeout: 10), "the \(tab) screen never appeared")
            try app.performAccessibilityAudit(for: [.sufficientElementDescription, .hitRegion]) { issue in
                let element = issue.element
                issues.append("\(tab): \(issue.compactDescription) [\(element?.identifier ?? "") | \(element?.label ?? "") | \(element?.elementType.rawValue ?? 0) | \(element?.frame.debugDescription ?? "")]")
                return true
            }
            capture("R\(index)-root")
            if tab == "成績" {
                for page in 2...3 {
                    app.swipeUp()
                    capture("R\(index)-root-\(page)")
                }
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
        // At accessibility sizes the swipes can leave it hittable but under the navigation bar.
        reveal(credits)
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

    func testGradesAreExportedAsCSV() {
        launch(onboarded: true, session: "demo")
        tab("成績").tap()
        let export = app.buttons["grades-export"]
        XCTAssertTrue(export.waitForExistence(timeout: 10))
        reveal(export)
        export.tap()
        XCTAssertTrue(app.navigationBars["選擇要匯出的考試"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["export-114_1|114_1_E4"].isSelected, "the exam on screen is chosen at first")
        app.buttons["export-114_1|114_1_E1"].tap()
        capture("36-grades-export")
        app.buttons["export-confirm"].tap()
        XCTAssertTrue(app.buttons["export-share"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["export-message"].label, "已匯出 2 次考試。")
        capture("37-grades-exported")
    }

    func testSubjectTrendComparesSubjectsAcrossTerms() {
        launch(onboarded: true, session: "demo")
        tab("成績").tap()
        let entry = app.buttons["grades-subject-trend"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10))
        reveal(entry)
        entry.tap()
        // Every term is compared at first; a subject has to be chosen, as on Android.
        XCTAssertTrue(app.staticTexts["選擇要比較的科目"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["trend-years"].label.contains("已全選（共 2 個學期）"), app.buttons["trend-years"].label)
        capture("50-subject-trend-empty")

        app.buttons["trend-empty-action"].tap()
        XCTAssertTrue(app.buttons["trend-option-數學"].waitForExistence(timeout: 5))
        app.buttons["trend-option-數學"].tap()
        app.buttons["trend-option-國文"].tap()
        capture("51-subject-trend-subjects")
        app.buttons["trend-apply"].tap()

        let chart = app.descendants(matching: .any)["subject-trend-chart"].firstMatch
        XCTAssertTrue(chart.waitForExistence(timeout: 5))
        XCTAssertTrue((chart.value as? String)?.hasPrefix("6 次考試的成績趨勢。") ?? false, chart.debugDescription)
        XCTAssertTrue(app.buttons["trend-legend-數學"].exists)
        capture("52-subject-trend-chart")
        // All six exams fit without scrolling; a tap keeps the selected exam's values on screen.
        XCTAssertFalse(app.descendants(matching: .any)["trend-scroll-hint"].exists)
        chart.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.5)).tap()
        let selection = app.staticTexts["trend-selection"]
        XCTAssertTrue(selection.waitForExistence(timeout: 5))
        XCTAssertTrue(selection.label.contains("國文 "), selection.label)
        capture("52b-subject-trend-selected")
        chart.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil { !selection.exists })
        app.buttons["trend-legend-數學"].tap()
        XCTAssertTrue(app.buttons["trend-legend-數學"].isSelected)
        capture("53-subject-trend-highlight")

        // One term: only its three exams.
        app.buttons["trend-years"].tap()
        XCTAssertTrue(app.buttons["trend-option-113_2"].waitForExistence(timeout: 5))
        app.buttons["trend-option-113_2"].tap()
        app.buttons["trend-apply"].tap()
        XCTAssertTrue(waitUntil { (chart.value as? String)?.hasPrefix("3 次考試") ?? false }, chart.debugDescription)
        XCTAssertTrue(app.buttons["trend-years"].label.contains("已選 1 個學期"))
        captureLandscapeOnPad("54-subject-trend-one-term-landscape")
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

        // 自訂科目 lists every override and restores the school's name.
        app.buttons["schedule-menu"].tap()
        app.buttons["schedule-custom-subjects"].tap()
        let restore = app.buttons["custom-subject-restore-數學"]
        XCTAssertTrue(restore.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["數學 → 數學A"].exists)
        capture("41b-schedule-custom-subjects")
        // Editing from the list, as Android's 自訂 dialog does.
        app.buttons["custom-subject-edit-數學"].tap()
        let name = app.textFields["custom-subject-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        XCTAssertEqual(name.value as? String, "數學A")
        name.tap()
        name.typeText("B")
        capture("41c-schedule-custom-subject-edit")
        app.buttons["custom-subject-save"].tap()
        XCTAssertTrue(app.staticTexts["數學 → 數學AB"].waitForExistence(timeout: 5))
        restore.tap()
        XCTAssertTrue(app.staticTexts["尚未自訂科目"].waitForExistence(timeout: 5))
        app.buttons["關閉"].tap()
        XCTAssertTrue(waitUntil { !self.app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "數學A")).firstMatch.exists })

        app.buttons["schedule-next-week"].tap()
        XCTAssertTrue(app.staticTexts["schedule-range"].label.contains("11/23"))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "上課中")).firstMatch.exists)
        capture("42-schedule-next-week")
        app.buttons["schedule-this-week"].tap()

        app.buttons["schedule-menu"].tap()
        XCTAssertTrue(app.buttons["分享課表圖片"].waitForExistence(timeout: 5))
        capture("43-schedule-menu")

        // The demo can show another class and come back to the student's own timetable.
        // The class title is followed by the term, e.g.「高二 31 班 · 115 學年度 第 1 學期」.
        func classTitle(_ name: String) -> XCUIElement {
            app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        }
        app.buttons["高二 31 班"].tap()
        XCTAssertTrue(classTitle("高二 31 班").waitForExistence(timeout: 5))
        capture("43b-schedule-other-class")
        app.buttons["schedule-menu"].tap()
        app.buttons["我的課表"].tap()
        XCTAssertTrue(classTitle("高二 30 班").waitForExistence(timeout: 5))
        XCTAssertFalse(classTitle("高二 31 班").exists)
    }

    func testScheduleTermClassAndWeekCanBeChosen() {
        launch(onboarded: true, session: "demo")
        tab("課表").tap()
        XCTAssertTrue(app.staticTexts["schedule-range"].waitForExistence(timeout: 10))

        // Any week the school lists, not just two weeks around this one.
        app.buttons["schedule-menu"].tap()
        app.buttons["schedule-pick-week"].tap()
        XCTAssertTrue(app.navigationBars["選擇週次"].waitForExistence(timeout: 5))
        capture("45-schedule-week-picker")
        let week = app.buttons["schedule-week-2026-12-14"]
        for _ in 0..<6 where !week.isHittable { app.swipeUp() }
        week.tap()
        XCTAssertTrue(app.staticTexts["schedule-range"].label.contains("12/14"), app.staticTexts["schedule-range"].label)
        // Week 21 is the last one the school lists.
        XCTAssertFalse(app.buttons["schedule-next-week"].isEnabled)
        app.buttons["schedule-previous-week"].tap()
        XCTAssertTrue(app.staticTexts["schedule-range"].label.contains("12/7"))
        capture("46-schedule-chosen-week")
        app.buttons["schedule-this-week"].tap()

        // 重新選擇: the previous term, another class and the semester timetable.
        app.buttons["schedule-menu"].tap()
        app.buttons["schedule-rechoose"].tap()
        XCTAssertTrue(app.staticTexts["選擇課表範圍"].waitForExistence(timeout: 5))
        capture("47-schedule-choice")
        app.buttons["schedule-choice-year"].tap()
        let older = app.buttons["114學年度第2學期"]
        XCTAssertTrue(older.waitForExistence(timeout: 5))
        older.tap()
        app.buttons["schedule-choice-class"].tap()
        app.buttons["高二 31 班"].tap()
        app.buttons["學期課表"].tap()
        capture("48-schedule-choice-filled")
        app.buttons["schedule-choice-confirm"].tap()
        let range = app.staticTexts["schedule-range"]
        XCTAssertTrue(waitUntil { range.label == "114 學年度 第 2 學期" }, range.label)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "高二 31 班")).firstMatch.exists)
        capture("49-schedule-chosen-term")

        // Cancelling a new choice keeps the timetable that was showing.
        app.buttons["schedule-menu"].tap()
        app.buttons["schedule-rechoose"].tap()
        XCTAssertTrue(app.buttons["schedule-choice-cancel"].waitForExistence(timeout: 5))
        app.buttons["schedule-choice-cancel"].tap()
        XCTAssertTrue(waitUntil { range.label == "114 學年度 第 2 學期" }, range.label)
    }

    func testCampusAnnouncementsCalendarAndRecords() {
        launch(onboarded: true, session: "demo")
        tab("校園").tap()
        XCTAssertTrue(app.buttons["campus-公告"].waitForExistence(timeout: 10))
        app.buttons["campus-公告"].tap()
        XCTAssertTrue(app.segmentedControls["announcement-filter"].waitForExistence(timeout: 5))
        capture("50-announcements")

        // Opening an announcement marks it read; bookmarking it fills the 收藏 filter.
        let unreadBefore = app.segmentedControls["announcement-filter"].buttons.element(boundBy: 1).label
        app.buttons["announcement-45055"].tap()
        XCTAssertTrue(app.buttons["announcement-bookmark"].waitForExistence(timeout: 5))
        app.buttons["announcement-bookmark"].tap()
        capture("52-announcement-detail")
        app.navigationBars.buttons.firstMatch.tap()
        // At accessibility text sizes the taller cards leave the list scrolled past the filter on return.
        let filter = app.segmentedControls["announcement-filter"]
        for _ in 0..<6 where !filter.waitForExistence(timeout: 1) { app.swipeDown() }
        XCTAssertNotEqual(filter.buttons.element(boundBy: 1).label, unreadBefore)
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
        app.buttons["records-categories"].tap()
        XCTAssertTrue(app.navigationBars["全部分類"].waitForExistence(timeout: 5))
        // At accessibility text sizes the half-height sheet shows only the first categories.
        let leave = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "公假")).firstMatch
        for _ in 0..<4 where !leave.exists { app.navigationBars["全部分類"].swipeUp() }
        XCTAssertTrue(leave.exists, "every category the school counts, not only the highlights")
        capture("58b-attendance-categories")
        app.buttons["關閉"].tap()
        let history = app.buttons["records-history"]
        reveal(history)
        history.tap()
        XCTAssertTrue(app.descendants(matching: .any)["records-history-table"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["病假 二上 4"].exists)
        capture("58c-attendance-history")
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["campus-獎懲"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "銷過")).firstMatch.waitForExistence(timeout: 5))
        capture("59-merits")
        // 懲處 shows only the penalty, whose approval details open on request.
        app.segmentedControls["records-filter"].buttons["懲處"].tap()
        XCTAssertTrue(waitUntil { !self.app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "協助校園活動")).firstMatch.exists })
        let approval = app.buttons["record-approval-m0"]
        reveal(approval)
        approval.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "核定日期：115/10/16")).firstMatch.waitForExistence(timeout: 5))
        capture("59b-merits-penalty-approval")

        // Search last: an active search replaces the tab bar on iPad. Go back with the page's own back
        // button: on this scrolled page the minimised tab bar may only expand instead of popping.
        app.navigationBars["獎懲紀錄"].buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["campus-公告"].waitForExistence(timeout: 5))
        app.buttons["campus-公告"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("獎助")
        XCTAssertTrue(app.buttons["announcement-45040"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["announcement-45055"].exists)
        capture("54-announcements-search")
    }

    func testAnnouncementsScrollEveryPageFilterSearchAndRemind() {
        launch(onboarded: true, session: "demo", extra: ["-demoAnnouncementLoadMoreFailure", "YES"])
        tab("校園").tap()
        XCTAssertTrue(app.buttons["campus-公告"].waitForExistence(timeout: 10))
        capture("70-campus-hub")
        app.buttons["campus-公告"].tap()
        XCTAssertTrue(app.buttons["announcement-45055"].waitForExistence(timeout: 10))

        // The second page fails once: the footer offers a retry instead of loading in a loop.
        let retry = app.buttons["announcements-load-more-retry"]
        for _ in 0..<12 where !(retry.exists && retry.isHittable) { app.swipeUp() }
        XCTAssertTrue(retry.waitForExistence(timeout: 5))
        capture("71-announcements-load-more-error")
        retry.tap()
        let end = app.staticTexts["announcements-end"]
        for _ in 0..<30 where !end.exists { app.swipeUp() }
        XCTAssertTrue(end.waitForExistence(timeout: 5), "scrolling reaches the last page")
        capture("72-announcements-end")
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "announcement-4"))

        // A notice from a later page opens and can be bookmarked.
        let late = rows.allElementsBoundByIndex.last { $0.isHittable }
        XCTAssertNotNil(late)
        late?.tap()
        XCTAssertTrue(app.buttons["announcement-bookmark"].waitForExistence(timeout: 5))
        app.buttons["announcement-bookmark"].tap()
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["announcements-top"].waitForExistence(timeout: 5))
        app.buttons["announcements-top"].tap()

        app.buttons["announcements-menu"].tap()
        XCTAssertTrue(app.buttons["announcements-filters"].waitForExistence(timeout: 5))
        capture("73-announcements-menu")
        app.buttons["announcements-filters"].tap()
        XCTAssertTrue(app.buttons["filter-apply"].waitForExistence(timeout: 5))
        app.buttons["filter-unit"].tap()
        XCTAssertTrue(app.buttons["教學組"].waitForExistence(timeout: 5))
        app.buttons["教學組"].tap()
        capture("74-announcement-filters")
        app.buttons["filter-apply"].tap()
        XCTAssertTrue(app.buttons["announcements-clear-filters"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["announcement-45055"].exists)
        capture("75-announcements-filtered")
        app.buttons["announcements-clear-filters"].tap()
        XCTAssertTrue(app.buttons["announcement-45055"].waitForExistence(timeout: 5))

        app.segmentedControls["announcement-filter"].buttons["收藏"].tap()
        XCTAssertGreaterThanOrEqual(rows.count, 1, "the later-page bookmark is listed")
        capture("76-announcements-bookmarks")
        app.segmentedControls["announcement-filter"].buttons["全部"].tap()

        app.buttons["announcements-menu"].tap()
        app.buttons["announcements-reminders"].tap()
        XCTAssertTrue(app.switches["reminder-toggle"].waitForExistence(timeout: 5))
        let unit = app.buttons["reminder-unit-101"]
        // With large Dynamic Type the unit list starts below the fold.
        for _ in 0..<6 where !unit.waitForExistence(timeout: 2) || !unit.isHittable { app.swipeUp() }
        XCTAssertTrue(unit.isHittable)
        unit.tap()
        XCTAssertEqual(unit.value as? String, "已選取")
        let allUnits = app.buttons["reminder-unit--1"]
        for _ in 0..<6 where !allUnits.exists { app.swipeDown() }
        XCTAssertEqual(allUnits.value as? String, "未選取")
        capture("77-announcement-reminders")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.segmentedControls["announcement-filter"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()

        let searchButton = app.buttons["搜尋公告與行事曆"].firstMatch
        XCTAssertTrue(searchButton.waitForExistence(timeout: 5))
        searchButton.tap()
        XCTAssertTrue(app.buttons["quick-期末考"].waitForExistence(timeout: 5))
        capture("78-campus-search")
        app.buttons["quick-期末考"].tap()
        XCTAssertTrue(app.buttons["event-demo-e5"].waitForExistence(timeout: 5))
        capture("79-campus-search-results")
        app.buttons["event-demo-e5"].tap()
        XCTAssertTrue(app.staticTexts["event-title"].waitForExistence(timeout: 5))
        capture("80-calendar-event")
        let addCountdown = app.buttons["event-add-countdown"]
        // At accessibility text sizes the half-height sheet keeps its actions below the fold.
        for _ in 0..<4 where !(addCountdown.exists && addCountdown.isHittable) { app.staticTexts["event-title"].swipeUp() }
        addCountdown.tap()
        XCTAssertTrue(app.buttons["已將「期末考」加入首頁倒數元件"].waitForExistence(timeout: 5))
        app.buttons["完成"].tap()
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["campus-行事曆"].waitForExistence(timeout: 5))
        app.buttons["campus-行事曆"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("段考")
        XCTAssertTrue(app.buttons["event-demo-e3"].waitForExistence(timeout: 5))
        capture("81-calendar-search")
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
        let json = app.buttons["data-json"]
        XCTAssertTrue(json.waitForExistence(timeout: 5))
        json.tap()
        let editor = app.textViews["json-editor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertTrue(NSPredicate(format: "value CONTAINS %@", "clhs-pocket").evaluate(with: editor) || (editor.value as? String)?.contains("clhs-pocket") == true,
                      "the editor starts with the current settings")
        capture("67a-settings-json-editor")
        let apply = app.buttons["json-apply"]
        reveal(apply)
        apply.tap()
        XCTAssertTrue(app.buttons["import-confirm"].waitForExistence(timeout: 5), "valid JSON is previewed before it is applied")
        capture("67b-settings-json-preview")
        app.buttons["import-confirm"].tap()
        XCTAssertTrue(app.staticTexts["json-message"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["json-message"].label, "已套用設定。")
        editor.tap()
        editor.typeText("}}")
        reveal(apply)
        apply.tap()
        let invalid = app.staticTexts["json-message"]
        XCTAssertTrue(invalid.waitForExistence(timeout: 5))
        XCTAssertTrue(NSPredicate(format: "label != %@", "已套用設定。").evaluate(with: invalid), "broken JSON is rejected, not applied")
        XCTAssertFalse(app.buttons["import-confirm"].exists)
        capture("67c-settings-json-invalid")
        let reset = app.buttons["json-reset"]
        reveal(reset)
        reset.tap()
        XCTAssertTrue(invalid.waitForNonExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
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

    func testGeneralSettingsUsageAndDeveloperOptions() {
        launch(onboarded: true, session: "demo")
        XCTAssertTrue(app.buttons["personal-button"].firstMatch.waitForExistence(timeout: 10))
        app.buttons["personal-button"].firstMatch.tap()
        let startOnSchedule = app.switches["start-on-schedule"]
        XCTAssertTrue(startOnSchedule.waitForExistence(timeout: 5))
        reveal(startOnSchedule)
        toggle("start-on-schedule")
        capture("70-personal-start-on-schedule")
        XCTAssertFalse(app.buttons["settings-開發者選項"].exists, "developer options stay hidden until unlocked")
        pickProfilePhoto()

        open("settings-使用統計")
        let opens = app.descendants(matching: .any)["usage-personal_open"]
        XCTAssertTrue(opens.waitForExistence(timeout: 5))
        XCTAssertTrue(opens.label.contains("1 次") || (opens.value as? String)?.contains("1 次") == true, "opening 個人 is counted")
        capture("71-settings-usage")
        app.navigationBars.buttons.firstMatch.tap()

        open("settings-關於")
        XCTAssertTrue(app.descendants(matching: .any)["about-source"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["about-feedback"].exists, "意見回饋 opens the same form as Android")
        let version = app.buttons["about-version"]
        for _ in 0..<10 { version.tap() }
        XCTAssertTrue(app.staticTexts["about-developer-message"].waitForExistence(timeout: 5))
        capture("72-settings-about-developer")
        app.navigationBars.buttons.firstMatch.tap()

        open("settings-開發者選項")
        let cache = app.switches["storage-cache"]
        XCTAssertTrue(cache.waitForExistence(timeout: 5))
        for category in ["gradeCache", "scheduleCache", "session", "webData"] {
            XCTAssertTrue(app.switches["storage-\(category)"].isEnabled, category)
        }
        XCTAssertFalse(app.switches["storage-settings"].isEnabled, "settings are only removed by 刪除資料")
        toggle("storage-gradeCache")
        toggle("storage-scheduleCache")
        toggle("storage-cache")
        capture("73-settings-developer")
        app.buttons["storage-clear"].tap()
        let confirm = app.buttons["storage-clear-confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        XCTAssertTrue(app.staticTexts["storage-freed"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["storage-error"].exists, "every chosen area was cleared")
        capture("73b-settings-developer-cleared")
        XCTAssertTrue(app.buttons["settings-使用統計"].exists || app.switches["storage-cache"].exists, "clearing caches keeps the demo signed in")
        let diagnostics = app.buttons["developer-diagnostics"]
        reveal(diagnostics)
        diagnostics.tap()
        let report = app.staticTexts["developer-report"]
        XCTAssertTrue(report.waitForExistence(timeout: 5))
        XCTAssertFalse(report.label.contains("DEMO-000"), "the diagnostic report never carries the account")
        capture("74-settings-developer-report")
        let disable = app.buttons["developer-disable"]
        reveal(disable)
        disable.tap()
        XCTAssertTrue(app.buttons["settings-使用統計"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["settings-開發者選項"].exists)

        // The preference is read at launch: the app now opens on 課表.
        launch(onboarded: true, session: "demo", extra: ["-startOnSchedule", "YES"])
        XCTAssertTrue(tab("課表").waitForExistence(timeout: 10))
        XCTAssertTrue(waitUntil { self.tab("課表").isSelected })
    }

    private func search(_ identifier: String, _ text: String) {
        let field = app.textFields[identifier]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        reveal(field)
        field.tap()
        if let value = field.value as? String, !value.isEmpty, value != field.placeholderValue {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count))
        }
        // Submitting closes the keyboard, which can cover the results at large text sizes.
        field.typeText(text + "\n")
    }

    private func editorRow(_ text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "dashboard-row-", text)).firstMatch
    }

    private func open(_ identifier: String) {
        let row = app.buttons[identifier]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        reveal(row)
        row.tap()
    }

    /// Picks the first library photo for the avatar, then removes it again.
    private func pickProfilePhoto() {
        let avatar = app.buttons["profile-photo"].firstMatch
        XCTAssertTrue(avatar.waitForExistence(timeout: 5))
        // 個人 is a sheet: swiping down while it is already scrolled to the top dismisses it,
        // so only scroll back up while the avatar is out of reach.
        for _ in 0..<4 where !avatar.isHittable { app.scrollViews.firstMatch.swipeDown(velocity: .slow) }
        XCTAssertTrue(avatar.isHittable, "the avatar is on screen")
        XCTAssertEqual(avatar.label, "設定大頭貼")
        avatar.tap()
        let photo = app.images.matching(identifier: "PXGGridLayout-Info").firstMatch
        // On a fresh simulator the picker first indexes the photo library, which can take over 15 s on CI.
        XCTAssertTrue(photo.waitForExistence(timeout: 40), "the photo picker opens")
        capture("70a-personal-photo-picker")
        // The picker runs out of process, so its cells report as not hittable; tap where the cell is.
        photo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["更換大頭貼"].firstMatch.waitForExistence(timeout: 15), "the picked photo is shown")
        // The new photo can show while the picker is still closing, so wait until the picker is gone.
        XCTAssertTrue(waitUntil(timeout: 15) { !photo.exists && avatar.isHittable }, "the photo picker closes")
        capture("70b-personal-photo")
        // With a photo set, tapping the avatar offers to change or remove it.
        avatar.tap()
        let remove = app.buttons["profile-photo-remove"].firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 5))
        remove.tap()
        XCTAssertTrue(app.buttons["設定大頭貼"].firstMatch.waitForExistence(timeout: 5), "the photo can be removed")
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
            // A slow first launch can also land here before the tab bar appears, so wait for the tab.
            let button = app.buttons[title].firstMatch
            _ = button.waitForExistence(timeout: 10)
            return button
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
