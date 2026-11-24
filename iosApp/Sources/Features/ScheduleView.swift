import PocketShared
import Photos
import SwiftUI

struct ScheduleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.colorScheme) private var colorScheme
    @State private var showsSemester = false
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var loadingClass = false
    @State private var photoMessage: String?
    @State private var weekOffset = 0
    @State private var selectedCell: PocketScheduleCell?
    @State private var shareImage: Image?
    @State private var overrideError: String?
    /// The term and class choice is open (重新選擇); a first-time account sees it regardless.
    @State private var choosing = false
    @State private var draftYear = ""
    @State private var draftClass = ""
    @State private var draftSemester = false
    @State private var loadingOptions = false
    @State private var optionsError: String?
    @State private var showsWeekPicker = false
    @State private var showsCustomSubjects = false
    @State private var confirmsRestoreAll = false
    @State private var editingOverride: PocketSubjectOverride?
    @State private var loadingWeeks = false
    @State private var weeksError: String?
    /// Bumped by 查詢課表 and 重試 so the same choice is fetched again.
    @State private var reloadToken = 0

    /// Empty for the student's own timetable; kept by `AppModel` across launches.
    private var classValue: String { model.scheduleClass }
    /// Empty for the newest term; kept by `AppModel` across launches.
    private var yearValue: String { model.scheduleYear }
    /// The term the screen shows: the chosen one, else the newest the school lists.
    private var shownYear: String { yearValue.isEmpty ? PocketScheduleStore.shared.years().first?.id ?? "" : yearValue }

    var body: some View {
        Group {
            if model.needsScheduleChoice || choosing {
                choiceView
            } else {
                scheduleContent
            }
        }
        .navigationTitle(PocketTab.schedule.title)
        // The timetable is the page: an inline title leaves room for all eight periods.
        .navigationBarTitleDisplayMode(.inline)
    }

    private var currentSchedule: PocketSchedule {
        PocketScheduleStore.shared.chosenSchedule(
            scope: showsSemester ? .semester : .currentWeek, clock: model.clock, yearValue: yearValue,
            classValue: classValue, weekOffset: Int32(weekOffset), overrides: model.scheduleOverrides
        )
    }

    /// Week offsets the school lists for the shown term; empty while the list is not loaded.
    private var weekOffsets: [Int] {
        (PocketScheduleStore.shared.weeks(yearValue: shownYear) ?? []).compactMap {
            PocketScheduleStore.shared.weekOffset(clock: model.clock, startDate: $0.id).map(\.intValue)
        }
    }

    private var scheduleContent: some View {
        let store = PocketScheduleStore.shared
        let schedule = currentSchedule
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("課表範圍", selection: $showsSemester) {
                    Text("本週").tag(false)
                    Text("學期").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 360)
                .accessibilityIdentifier("schedule-scope")

                header(schedule)

                if schedule.isAvailable {
                    // One continuous panel, as Android: lessons share the panel colour and hairlines
                    // separate days and periods, so the subject accent and changes stand out.
                    grid(schedule)
                        .padding(.vertical, 8)
                        .padding(.horizontal, sizeClass == .regular ? 8 : 4)
                        .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.innerRadius + 4, style: .continuous))
                } else {
                    PocketPendingView(
                        isLoading: model.live?.isLoadingPrivate ?? false || loadingClass,
                        title: weekOffset == 0 ? "課表尚未載入" : "這一週的課表尚未載入",
                        message: model.live?.lastError,
                        retry: {
                            if weekOffset == 0 { reloadToken += 1 } else { Task { await loadWeek() } }
                        }
                    )
                }

                Text("點選課程可查看教師與教室，也可以自訂顯示名稱。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.horizontal, 4)
            }
            .pocketPage()
        }
        .refreshable {
            // The shared refresh loads the chosen term and class; other weeks are fetched here.
            await model.refreshLiveData(force: true)
            await loadWeek()
        }
        .task(id: "\(yearValue)|\(classValue)|\(reloadToken)") {
            // Each switch cancels the previous load; only the current one may clear the flag.
            loadingClass = false
            guard let live = model.account else { return }
            // Opening the tab again does not refetch a timetable that is already here.
            if reloadToken == 0, currentSchedule.isAvailable { return }
            loadingClass = true
            await live.loadSchedule(classValue: classValue, yearValue: yearValue)
            if !Task.isCancelled { loadingClass = false }
        }
        // Rasterising the whole table is expensive, so it happens once per content change, off the
        // body pass, and only for a schedule that actually has lessons.
        .task(id: "\(schedule.hashValue)|\(colorScheme)") {
            guard schedule.isAvailable else { shareImage = nil; return }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            shareImage = exportImage(schedule)
        }
        .task(id: "\(yearValue)|\(classValue)|\(weekOffset)|\(showsSemester)") { await loadWeek() }
        .sensoryFeedback(.selection, trigger: weekOffset)
        .sensoryFeedback(.selection, trigger: showsSemester)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("重新選擇", systemImage: "slider.horizontal.3") { openChoice() }
                        .accessibilityIdentifier("schedule-rechoose")
                    if !showsSemester {
                        Button("選擇週次", systemImage: "calendar") { showsWeekPicker = true }
                            .accessibilityIdentifier("schedule-pick-week")
                    }
                    Button("自訂科目", systemImage: "pencil.and.list.clipboard") { showsCustomSubjects = true }
                        .accessibilityIdentifier("schedule-custom-subjects")
                    Picker("班級", selection: Binding(
                        get: { model.scheduleClass },
                        set: { model.chooseSchedule(yearValue: model.scheduleYear, classValue: $0) }
                    )) {
                        // An empty value is the student's own timetable; every school class keeps its own code.
                        Text("我的課表").tag("")
                        ForEach(store.classes()) { item in
                            Text(item.title).tag(item.id)
                        }
                    }
                    if schedule.isAvailable, let image = shareImage {
                        ShareLink(
                            item: image,
                            preview: SharePreview("\(schedule.className) \(schedule.scopeLabel)", image: image)
                        ) {
                            Label("分享課表圖片", systemImage: "square.and.arrow.up")
                        }
                    }
                    if schedule.isAvailable {
                        Button("儲存到照片", systemImage: "square.and.arrow.down") {
                            Task { await saveToPhotos(schedule) }
                        }
                        .accessibilityIdentifier("schedule-save-photo")
                    }
                } label: {
                    Label("班級與分享", systemImage: "ellipsis")
                }
                .accessibilityIdentifier("schedule-menu")
            }
        }
        .onChange(of: model.live?.lastError) { _, error in
            // The lesson sheet closes before the account save finishes, so a failure is shown here.
            if let error, error.hasPrefix("自訂科目名稱未能儲存") { overrideError = error }
        }
        .alert("自訂科目名稱", isPresented: Binding(get: { overrideError != nil }, set: { if !$0 { overrideError = nil } })) {
            Button("好", role: .cancel) {}
        } message: {
            Text(overrideError ?? "")
        }
        .alert("課表圖片", isPresented: Binding(get: { photoMessage != nil }, set: { if !$0 { photoMessage = nil } })) {
            Button("好") { photoMessage = nil }
        } message: {
            Text(photoMessage ?? "")
        }
        .sheet(item: $selectedCell) { cell in
            ScheduleCellSheet(cell: cell, days: schedule.days)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showsWeekPicker) {
            weekPicker
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showsCustomSubjects) {
            customSubjects
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    // MARK: Custom subjects

    /// Android's 自訂科目 page: every override in one place, each restorable, or all at once.
    private var customSubjects: some View {
        let store = PocketScheduleStore.shared
        let overrides = store.overrides(overrides: model.scheduleOverrides)
        return NavigationStack {
            Group {
                if overrides.isEmpty {
                    ContentUnavailableView(
                        "尚未自訂科目", systemImage: "pencil.and.list.clipboard",
                        description: Text("點選課表中的課程，即可自訂顯示的科目、教師與教室。")
                    )
                } else {
                    List {
                        Section {
                            ForEach(overrides, id: \.original) { item in
                                HStack(alignment: .center, spacing: 12) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(item.subject.isEmpty ? item.original : "\(item.original) → \(item.subject)")
                                            .font(.body.weight(.semibold))
                                            .foregroundStyle(PocketColor.onSurface)
                                        let extra = [
                                            item.teacher.isEmpty ? nil : "教師：\(item.teacher)",
                                            item.classroom.isEmpty ? nil : "教室：\(item.classroom)",
                                        ].compactMap { $0 }
                                        if !extra.isEmpty {
                                            Text(extra.joined(separator: "・"))
                                                .font(.subheadline)
                                                .foregroundStyle(PocketColor.onSurfaceVariant)
                                        }
                                    }
                                    .accessibilityElement(children: .combine)
                                    Spacer(minLength: 8)
                                    Button("編輯") { editingOverride = item }
                                        .buttonStyle(.bordered)
                                        .accessibilityLabel("編輯\(item.original)")
                                        .accessibilityIdentifier("custom-subject-edit-\(item.original)")
                                    Button("還原") {
                                        model.scheduleOverrides = store.clearOverride(overrides: model.scheduleOverrides, original: item.original)
                                    }
                                    .buttonStyle(.bordered)
                                    .accessibilityLabel("還原\(item.original)")
                                    .accessibilityIdentifier("custom-subject-restore-\(item.original)")
                                }
                                .frame(minHeight: 44)
                            }
                        } footer: {
                            Text("還原後會恢復顯示校方提供的名稱、教師與教室。")
                        }
                    }
                }
            }
            .navigationTitle("自訂科目")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("關閉") { showsCustomSubjects = false }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("全部還原") { confirmsRestoreAll = true }
                        .disabled(overrides.isEmpty)
                        .accessibilityIdentifier("custom-subjects-restore-all")
                }
            }
            .sheet(item: Binding(
                get: { editingOverride.map(EditedOverride.init) },
                set: { editingOverride = $0?.value }
            )) { edited in
                CustomSubjectEditor(item: edited.value) { subject, teacher, classroom in
                    model.scheduleOverrides = store.setOverride(
                        overrides: model.scheduleOverrides, original: edited.value.original,
                        subject: subject, teacher: teacher, classroom: classroom
                    )
                }
            }
            .confirmationDialog("全部還原？", isPresented: $confirmsRestoreAll, titleVisibility: .visible) {
                Button("全部還原", role: .destructive) { model.scheduleOverrides = "[]" }
                Button("取消", role: .cancel) {}
            } message: {
                Text("所有科目將恢復顯示校方提供的名稱、教師與教室。")
            }
        }
    }

    // MARK: Term and class choice

    private func openChoice() {
        draftYear = shownYear
        draftClass = classValue
        draftSemester = showsSemester
        optionsError = nil
        choosing = true
    }

    /// Android's selection card: term, class and timetable type, then 查詢課表.
    private var choiceView: some View {
        let store = PocketScheduleStore.shared
        let years = store.years()
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("選擇課表範圍")
                        .font(PocketFont.sectionTitle)
                        .foregroundStyle(PocketColor.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    Text("選擇學期、班級與顯示類型以載入排課資料")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
                if years.isEmpty {
                    PocketPendingView(
                        isLoading: loadingOptions,
                        title: "無法載入學期與班級",
                        message: optionsError,
                        retry: { Task { await loadOptions(yearValue: draftYear) } }
                    )
                } else {
                    choiceField("學期") {
                        Picker("學期", selection: $draftYear) {
                            ForEach(years, id: \.id) { Text($0.title).tag($0.id) }
                        }
                        .accessibilityIdentifier("schedule-choice-year")
                    }
                    choiceField("班級") {
                        Picker("班級", selection: $draftClass) {
                            Text("我的課表").tag("")
                            ForEach(store.classes(), id: \.id) { Text($0.title).tag($0.id) }
                        }
                        .disabled(loadingOptions)
                        .accessibilityIdentifier("schedule-choice-class")
                    }
                    choiceField("課表類型") {
                        Picker("課表類型", selection: $draftSemester) {
                            Text("學期課表").tag(true)
                            Text("週課表").tag(false)
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("schedule-choice-scope")
                    }
                    if loadingOptions {
                        ProgressView("正在載入班級…")
                            .font(.footnote)
                    } else if let optionsError {
                        Label(optionsError, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    Button {
                        confirmChoice()
                    } label: {
                        Text("查詢課表")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 32)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(draftYear.isEmpty || loadingOptions)
                    .accessibilityIdentifier("schedule-choice-confirm")
                    if choosing, !model.needsScheduleChoice {
                        Button("取消") { choosing = false }
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .accessibilityIdentifier("schedule-choice-cancel")
                    }
                }
            }
            .padding(20)
            .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))
            .frame(maxWidth: 560)
            .pocketPage()
        }
        .accessibilityIdentifier("schedule-choice")
        .task {
            if draftYear.isEmpty { draftYear = shownYear }
            await loadOptions(yearValue: draftYear)
            if draftYear.isEmpty { draftYear = PocketScheduleStore.shared.years().first?.id ?? "" }
        }
        .onChange(of: draftYear) { old, new in
            guard !old.isEmpty, old != new else { return }
            Task { await loadOptions(yearValue: new) }
        }
    }

    private func choiceField<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PocketColor.onSurfaceVariant)
                .accessibilityHidden(true)
            content()
                .labelsHidden()
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Loads the terms and the classes of `yearValue`; the demo has them already.
    private func loadOptions(yearValue: String) async {
        guard let live = model.account else { return }
        loadingOptions = true
        optionsError = await live.loadScheduleOptions(yearValue: yearValue)
        loadingOptions = false
        // A class of the previous term is not offered by this one.
        let classes = PocketScheduleStore.shared.classes().map(\.id)
        if !draftClass.isEmpty, !classes.contains(draftClass) { draftClass = "" }
    }

    private func confirmChoice() {
        model.chooseSchedule(yearValue: draftYear, classValue: draftClass)
        showsSemester = draftSemester
        weekOffset = 0
        choosing = false
        reloadToken += 1
    }

    // MARK: Week picker

    private var weekPicker: some View {
        let weeks = PocketScheduleStore.shared.weeks(yearValue: shownYear)
        return NavigationStack {
            Group {
                if let weeks, !weeks.isEmpty {
                    ScrollViewReader { proxy in
                        List(weeks, id: \.id) { week in
                            let offset = PocketScheduleStore.shared.weekOffset(clock: model.clock, startDate: week.id)?.intValue
                            Button {
                                if let offset { weekOffset = offset }
                                showsWeekPicker = false
                            } label: {
                                HStack(spacing: 12) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(week.title).font(.body.weight(.semibold))
                                        Text(week.detail).font(.subheadline).monospacedDigit()
                                            .foregroundStyle(PocketColor.onSurfaceVariant)
                                    }
                                    Spacer(minLength: 8)
                                    if offset == 0 {
                                        Text("本週").font(.caption.weight(.semibold)).foregroundStyle(PocketColor.primary)
                                    }
                                    if offset == weekOffset {
                                        Image(systemName: "checkmark").foregroundStyle(PocketColor.primary)
                                            .accessibilityHidden(true)
                                    }
                                }
                                .contentShape(.rect)
                            }
                            .buttonStyle(.plain)
                            .disabled(offset == nil)
                            .accessibilityAddTraits(offset == weekOffset ? .isSelected : [])
                            .accessibilityIdentifier("schedule-week-\(week.id)")
                            .id(week.id)
                        }
                        .onAppear {
                            let selected = weeks.first {
                                PocketScheduleStore.shared.weekOffset(clock: model.clock, startDate: $0.id)?.intValue == weekOffset
                            }
                            if let selected { proxy.scrollTo(selected.id, anchor: .center) }
                        }
                    }
                } else if weeks != nil {
                    ContentUnavailableView("學校尚未提供可選週次", systemImage: "calendar")
                } else {
                    PocketPendingView(isLoading: loadingWeeks, title: "無法取得週次", message: weeksError) {
                        Task { await loadWeeks() }
                    }
                }
            }
            .navigationTitle("選擇週次")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("關閉") { showsWeekPicker = false }
                }
            }
            .task { if weeks == nil { await loadWeeks() } }
        }
    }

    private func loadWeeks() async {
        guard let live = model.account, !shownYear.isEmpty else {
            weeksError = "無法取得週次，請重試"
            return
        }
        loadingWeeks = true
        weeksError = await live.loadScheduleWeeks(yearValue: shownYear)
        loadingWeeks = false
    }

    /// Renders the grid off screen so the shared image does not depend on scroll position.
    @MainActor
    private func exportImage(_ schedule: PocketSchedule) -> Image? {
        exportUIImage(schedule).map { Image(uiImage: $0) }
    }

    /// Add-only access: the app can write the image but never read the library.
    private func saveToPhotos(_ schedule: PocketSchedule) async {
        guard let image = exportUIImage(schedule) else {
            photoMessage = "無法產生課表圖片。"
            return
        }
        guard let data = image.pngData() else {
            photoMessage = "無法產生課表圖片。"
            return
        }
        switch await SchedulePhotoSaver.save(png: data) {
        case .saved: photoMessage = "已儲存到照片。"
        case .denied: photoMessage = "尚未允許加入照片。請到「設定」›「壢中 Pocket」›「照片」開啟後再試一次。"
        case .failed: photoMessage = "儲存失敗，請再試一次。"
        }
    }

    @MainActor
    private func exportUIImage(_ schedule: PocketSchedule) -> UIImage? {
        let content = VStack(alignment: .leading, spacing: 12) {
            Text("\(schedule.className) · \(schedule.scopeLabel)")
                .font(PocketFont.sectionTitle)
                .foregroundStyle(PocketColor.onSurface)
            Text(showsSemester ? schedule.termText : "\(schedule.rangeText) · \(schedule.weekText)")
                .font(.subheadline)
                .foregroundStyle(PocketColor.onSurfaceVariant)
            ScheduleGrid(schedule: schedule) { _ in }
        }
        .padding(20)
        .frame(width: 640)
        .background(PocketColor.background)
        .environment(\.colorScheme, colorScheme)
        .environment(\.horizontalSizeClass, .regular)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        return renderer.uiImage
    }

    /// Other weeks are fetched on demand, as Android does, and kept while the app runs.
    private func loadWeek() async {
        guard weekOffset != 0, !showsSemester, let live = model.account else { return }
        loadingClass = true
        await live.loadScheduleWeek(classValue: classValue, weekOffset: weekOffset, yearValue: yearValue)
        loadingClass = false
    }

    /// With accessibility text five columns cannot hold a subject name, so the grid keeps a
    /// readable column width and scrolls sideways instead.
    @ViewBuilder
    private func grid(_ schedule: PocketSchedule) -> some View {
        if typeSize.isAccessibilitySize, sizeClass != .regular {
            ScrollView(.horizontal) {
                ScheduleGrid(schedule: schedule) { selectedCell = $0 }
                    .frame(width: 44 + CGFloat(schedule.days.count) * 112)
            }
            .scrollIndicators(.visible)
            .accessibilityIdentifier("schedule-grid-scroll")
        } else {
            ScheduleGrid(schedule: schedule) { selectedCell = $0 }
        }
    }

    private func header(_ schedule: PocketSchedule) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 12) {
                Group {
                    if showsSemester {
                        Text(schedule.termText)
                    } else if typeSize.isAccessibilitySize {
                        // Range and week on their own lines; one string wraps into an orphan separator.
                        VStack(alignment: .leading, spacing: 2) {
                            Text(schedule.rangeText)
                            Text(schedule.weekText)
                                .font(.headline)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                        .accessibilityElement(children: .combine)
                    } else {
                        Text("\(schedule.rangeText) · \(schedule.weekText)")
                    }
                }
                    .font(PocketFont.sectionTitle)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onSurface)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .accessibilityIdentifier("schedule-range")
                    // Tapping the week returns to the current one, as Android's date range does.
                    .onTapGesture { if !showsSemester, weekOffset != 0 { weekOffset = 0 } }
                    .accessibilityActions {
                        if !showsSemester {
                            Button("回到本週") { weekOffset = 0 }
                        }
                    }
                Spacer(minLength: 8)
                if !showsSemester {
                    HStack(spacing: 6) {
                        Button("上一週", systemImage: "chevron.left") { weekOffset -= 1 }
                            .disabled(weekOffsets.min().map { weekOffset <= $0 } ?? false)
                            .accessibilityIdentifier("schedule-previous-week")
                        Button("下一週", systemImage: "chevron.right") { weekOffset += 1 }
                            .disabled(weekOffsets.max().map { weekOffset >= $0 } ?? false)
                            .accessibilityIdentifier("schedule-next-week")
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { subtitle(schedule) }
                VStack(alignment: .leading, spacing: 6) { subtitle(schedule) }
            }
        }
        .padding(.horizontal, 4)
    }

    @ViewBuilder
    private func subtitle(_ schedule: PocketSchedule) -> some View {
                Text(showsSemester ? "\(schedule.className) · 固定課表" : [schedule.className, schedule.termText].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                if schedule.changeCount > 0 {
                    PocketChip(
                        text: "\(schedule.changeCount) 項異動",
                        systemImage: "arrow.left.arrow.right",
                        foreground: PocketColor.onTertiaryContainer,
                        background: PocketColor.tertiaryContainer
                    )
                }
                if !showsSemester, weekOffset != 0 {
                    Button("回到本週") { weekOffset = 0 }
                        .font(.subheadline.weight(.semibold))
                        .frame(minHeight: 32)
                        .accessibilityIdentifier("schedule-this-week")
                }
    }
}

private struct ScheduleGrid: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.displayScale) private var displayScale
    @ScaledMetric(relativeTo: .caption) private var compactRowHeight: CGFloat = 58
    let schedule: PocketSchedule
    let onSelect: (PocketScheduleCell) -> Void

    private let gap: CGFloat = 0
    private let lunchGap: CGFloat = 18
    private let lastMorningPeriod = 4

    private var rowHeight: CGFloat { sizeClass == .regular ? compactRowHeight + 30 : compactRowHeight }
    private var timeColumnWidth: CGFloat { sizeClass == .regular ? 56 : 30 }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: gap) {
                Color.clear.frame(width: timeColumnWidth, height: 1)
                ForEach(schedule.days, id: \.dayOfWeek) { day in
                    VStack(spacing: 1) {
                        Text(day.label)
                            .font(.subheadline.weight(.semibold))
                        if schedule.scope == .currentWeek {
                            Text(day.dateText)
                                .font(.caption2)
                                .monospacedDigit()
                                .lineLimit(1)
                                .minimumScaleFactor(0.5)
                                .dynamicTypeSize(...DynamicTypeSize.xLarge)
                        }
                    }
                    .foregroundStyle(day.isToday ? PocketColor.onPrimary : PocketColor.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 5)
                    .background(day.isToday ? PocketColor.primary : .clear, in: .rect(cornerRadius: 12, style: .continuous))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("週\(day.label) \(day.dateText)\(day.isToday ? "，今天" : "")")
                }
            }

            HStack(alignment: .top, spacing: gap) {
                VStack(spacing: 0) {
                    ForEach(Array(schedule.periods.enumerated()), id: \.element.number) { index, period in
                        VStack(spacing: 1) {
                            Text("\(period.number)")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(PocketColor.onSurface)
                            if !period.start.isEmpty {
                                VStack(spacing: 0) {
                                    Text(period.start)
                                    if !period.end.isEmpty { Text(period.end) }
                                }
                                .font(.system(size: 9))
                                .monospacedDigit()
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                                .fixedSize()
                            }
                        }
                        .frame(width: timeColumnWidth, height: rowHeight)
                        .padding(.bottom, bottomGap(afterRow: index))
                        .accessibilityHidden(true)
                    }
                }
                ForEach(schedule.days, id: \.dayOfWeek) { day in
                    VStack(spacing: 0) {
                        ForEach(slots(day: day.dayOfWeek)) { slot in
                            Group {
                                if let cell = slot.cell {
                                    Button {
                                        onSelect(cell)
                                    } label: {
                                        ScheduleCellView(cell: cell)
                                            .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.plain)
                                } else {
                                    Color.clear.accessibilityHidden(true)
                                }
                            }
                            .frame(height: height(rows: slot.rows))
                            .overlay(alignment: .bottom) {
                                if slot.rows.upperBound < schedule.periods.count, slot.rows.upperBound - 1 != lunchRow {
                                    hairline.frame(height: 1 / displayScale)
                                }
                            }
                            .padding(.bottom, bottomGap(afterRow: slot.rows.upperBound - 1))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .overlay(alignment: .leading) { hairline.frame(width: 1 / displayScale) }
                }
            }
            .overlay(alignment: .top) {
                if let lunchRow {
                    HStack(spacing: 8) {
                        line
                        Text("午休")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                        line
                    }
                    .frame(height: lunchGap)
                    .offset(y: offset(ofRow: lunchRow + 1) - lunchGap)
                    .accessibilityHidden(true)
                }
            }
        }
    }

    private struct Slot: Identifiable {
        let id: String
        let rows: Range<Int>
        let cell: PocketScheduleCell?
    }

    /// One slot per row, by period number: a lesson spans its rows and an empty period keeps its
    /// place, so a free period never pulls later lessons into the wrong row.
    private func slots(day: Int32) -> [Slot] {
        let rowOf = Dictionary(schedule.periods.enumerated().map { ($1.number, $0) }, uniquingKeysWith: { first, _ in first })
        var starts: [Int: PocketScheduleCell] = [:]
        for cell in schedule.cells where cell.dayOfWeek == day {
            if let row = rowOf[cell.period], starts[row] == nil { starts[row] = cell }
        }
        var result: [Slot] = []
        var row = 0
        while row < schedule.periods.count {
            if let cell = starts[row] {
                let end = min(row + max(Int(cell.span), 1), schedule.periods.count)
                result.append(Slot(id: "cell-\(cell.id)", rows: row..<end, cell: cell))
                row = end
            } else {
                result.append(Slot(id: "empty-\(day)-\(schedule.periods[row].number)", rows: row..<(row + 1), cell: nil))
                row += 1
            }
        }
        return result
    }

    private var lunchRow: Int? {
        guard let index = schedule.periods.firstIndex(where: { $0.number == Int32(lastMorningPeriod) }),
              index < schedule.periods.count - 1 else { return nil }
        return index
    }

    private func height(rows: Range<Int>) -> CGFloat {
        var total = CGFloat(rows.count) * rowHeight
        for row in rows.dropLast() { total += bottomGap(afterRow: row) }
        return total
    }

    private func offset(ofRow target: Int) -> CGFloat {
        (0..<target).reduce(0) { $0 + rowHeight + bottomGap(afterRow: $1) }
    }

    private var line: some View {
        Rectangle().fill(PocketColor.outline.opacity(0.8)).frame(height: 1)
    }

    private var hairline: some View {
        Rectangle().fill(PocketColor.outline.opacity(0.45)).accessibilityHidden(true)
    }

    private func bottomGap(afterRow row: Int) -> CGFloat {
        if row >= schedule.periods.count - 1 { return 0 }
        return row == lunchRow ? lunchGap : gap
    }
}

private struct ScheduleCellView: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    let cell: PocketScheduleCell

    var body: some View {
        let color = PocketColor.subject(light: cell.colorLight, dark: cell.colorDark)
        let isCurrent = cell.status == .current
        let isCancelled = cell.change == .cancelled
        let isChanged = cell.change != .none
        let dimmed = cell.status == .ended || isCancelled
        // Android's week grid: lessons share the panel, a short subject-colour mark sits above the
        // name, changes use the tertiary container and the current lesson gets an outline.
        VStack(spacing: 4) {
            Capsule()
                .fill(color)
                .frame(width: 18, height: 3)
                .opacity(dimmed ? 0.45 : 1)
            Text(cell.subject)
                .font(sizeClass == .regular ? .callout.weight(.semibold) : .footnote.weight(.semibold))
                .strikethrough(isCancelled)
                .lineLimit(2)
                .minimumScaleFactor(0.75)
                .multilineTextAlignment(.center)
                .foregroundStyle(isChanged ? PocketColor.onTertiaryContainer : PocketColor.onSurface)
                // The grid scrolls sideways at accessibility sizes; past this the row height cannot keep up.
                .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            if sizeClass == .regular || cell.span > 1 {
                Text(cell.classroom)
                    .font(.caption2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
        }
        .opacity(dimmed ? 0.55 : 1)
        .padding(.horizontal, 3)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            if isChanged {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(PocketColor.tertiaryContainer)
                    .padding(2)
            } else if isCurrent {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(PocketColor.primaryContainer.opacity(0.6))
                    .padding(2)
            }
        }
        .overlay {
            if isCurrent {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(PocketColor.primary, lineWidth: 2)
                    .padding(2)
            }
        }
        .contentShape(.rect)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isButton)
    }

    private var accessibilityText: String {
        var parts = ["第 \(cell.period) 節", cell.subject, cell.classroom]
        if isCurrentText { parts.append("上課中") }
        if !cell.changeNote.isEmpty { parts.append(cell.changeNote) }
        return parts.joined(separator: "，")
    }

    private var isCurrentText: Bool { cell.status == .current }
}

private struct EditedOverride: Identifiable {
    let value: PocketSubjectOverride
    var id: String { value.original }
}

/// Android's 自訂 dialog in 自訂科目: the school's subject keeps its key; blank fields use the school's value.
private struct CustomSubjectEditor: View {
    @Environment(\.dismiss) private var dismiss
    let item: PocketSubjectOverride
    let save: (String, String, String) -> Void
    @State private var subject: String
    @State private var teacher: String
    @State private var classroom: String

    init(item: PocketSubjectOverride, save: @escaping (String, String, String) -> Void) {
        self.item = item
        self.save = save
        _subject = State(initialValue: item.subject.isEmpty ? item.original : item.subject)
        _teacher = State(initialValue: item.teacher)
        _classroom = State(initialValue: item.classroom)
    }

    private static func clamped(_ text: String) -> String {
        String(text.prefix(Int(PocketScheduleStore.shared.maxOverrideLength())))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("科目名稱", text: $subject).accessibilityIdentifier("custom-subject-name")
                    TextField("教師（留空使用原本的）", text: $teacher)
                    TextField("教室（留空使用原本的）", text: $classroom)
                } header: {
                    Text("校方科目：\(item.original)")
                } footer: {
                    Text("套用到這個科目的每一堂課。每欄最多 \(PocketScheduleStore.shared.maxOverrideLength()) 個字。")
                }
            }
            .onChange(of: subject) { _, value in subject = Self.clamped(value) }
            .onChange(of: teacher) { _, value in teacher = Self.clamped(value) }
            .onChange(of: classroom) { _, value in classroom = Self.clamped(value) }
            .navigationTitle("自訂")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("儲存") {
                        save(subject, teacher, classroom)
                        dismiss()
                    }
                    .accessibilityIdentifier("custom-subject-save")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct ScheduleCellSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model
    @State private var editing = false
    @State private var subject = ""
    @State private var teacher = ""
    @State private var classroom = ""
    let cell: PocketScheduleCell
    let days: [PocketScheduleDay]

    var body: some View {
        let color = PocketColor.subject(light: cell.colorLight, dark: cell.colorDark)
        let day = days.first { $0.dayOfWeek == cell.dayOfWeek }
        ScrollView {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Circle().fill(color).frame(width: 10, height: 10)
                        Text("週\(day?.label ?? "") · 第 \(cell.period)\(cell.span > 1 ? "–\(cell.period + cell.span - 1)" : "") 節")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                    Text(cell.subject)
                        .font(PocketFont.display(32, relativeTo: .largeTitle))
                        .foregroundStyle(PocketColor.onSurface)
                }
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                        .background(PocketColor.surfaceHigh, in: .circle)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("關閉")
            }
            if !cell.changeNote.isEmpty {
                PocketChip(
                    text: cell.changeNote,
                    systemImage: "arrow.left.arrow.right",
                    foreground: PocketColor.onTertiaryContainer,
                    background: PocketColor.tertiaryContainer
                )
                .padding(.top, 10)
            }
            VStack(spacing: 0) {
                detail("clock", "時間", cell.timeText)
                PocketRowDivider().padding(.leading, 44)
                detail("mappin.and.ellipse", "教室", cell.classroom)
                if !cell.teacher.isEmpty {
                    PocketRowDivider().padding(.leading, 44)
                    detail("person", "教師", cell.teacher)
                }
            }
            .padding(.horizontal, 4)
            .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 20, style: .continuous))
            .padding(.top, 18)
            overrideSection
        }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 24)
        .frame(maxWidth: .infinity, alignment: .top)
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationBackground(PocketColor.background)
    }

    private static func clamped(_ text: String) -> String {
        String(text.prefix(Int(PocketScheduleStore.shared.maxOverrideLength())))
    }

    @ViewBuilder private var overrideSection: some View {
        if editing {
            VStack(alignment: .leading, spacing: 10) {
                Text("自訂「\(cell.originalSubject)」的顯示")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                Group {
                    TextField("科目名稱", text: $subject).accessibilityIdentifier("override-subject")
                    TextField("教師（留空使用原本的）", text: $teacher)
                    TextField("教室（留空使用原本的）", text: $classroom)
                }
                .textFieldStyle(.roundedBorder)
                .onChange(of: subject) { _, value in subject = Self.clamped(value) }
                .onChange(of: teacher) { _, value in teacher = Self.clamped(value) }
                .onChange(of: classroom) { _, value in classroom = Self.clamped(value) }
                Text("套用到這個科目的每一堂課，\(model.account?.session != nil ? "存在這台裝置並會包含在設定備份中" : "只存在這台裝置")。每欄最多 \(PocketScheduleStore.shared.maxOverrideLength()) 個字。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                HStack(spacing: 12) {
                    Button("儲存") {
                        model.scheduleOverrides = PocketScheduleStore.shared.setOverride(
                            overrides: model.scheduleOverrides, original: cell.originalSubject,
                            subject: subject, teacher: teacher, classroom: classroom
                        )
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("override-save")
                    if cell.isOverridden {
                        Button("還原", role: .destructive) {
                            model.scheduleOverrides = PocketScheduleStore.shared.clearOverride(
                                overrides: model.scheduleOverrides, original: cell.originalSubject
                            )
                            dismiss()
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("override-clear")
                    }
                }
            }
            .padding(.top, 20)
        } else {
            Button {
                let saved = PocketScheduleStore.shared.overrides(overrides: model.scheduleOverrides)
                    .first { $0.original == cell.originalSubject }
                subject = saved.flatMap { $0.subject.isEmpty ? nil : $0.subject } ?? cell.subject
                teacher = saved?.teacher ?? ""
                classroom = saved?.classroom ?? ""
                editing = true
            } label: {
                Label(cell.isOverridden ? "編輯自訂名稱（原「\(cell.originalSubject)」）" : "自訂科目名稱", systemImage: "pencil")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 16, style: .continuous))
            }
            .padding(.top, 14)
            .accessibilityIdentifier("override-edit")
        }
    }

    private func detail(_ icon: String, _ label: String, _ value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(PocketColor.primary)
                .frame(width: 28)
            Text(label)
                .foregroundStyle(PocketColor.onSurfaceVariant)
            Spacer()
            Text(value)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
                .foregroundStyle(PocketColor.onSurface)
        }
        .font(.body)
        .padding(.vertical, 6)
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }
}

/// Photos calls its handlers on its own queues, so nothing here may be tied to the main actor.
private enum SchedulePhotoSaver {
    enum Outcome { case saved, denied, failed }

    nonisolated static func save(png: Data) async -> Outcome {
        guard await PHPhotoLibrary.requestAuthorization(for: .addOnly) == .authorized else { return .denied }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: png, options: nil)
            }
            return .saved
        } catch {
            return .failed
        }
    }
}
