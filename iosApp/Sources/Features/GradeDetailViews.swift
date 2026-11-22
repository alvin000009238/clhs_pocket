import Charts
import PocketShared
import SwiftUI

// MARK: - Subject detail

struct SubjectDetailView: View {
    let examValue: String
    let name: String

    var body: some View {
        ScrollView {
            if let detail = PocketGrades.shared.subjectDetail(examValue: examValue, subjectName: name) {
                VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                    PocketColumns {
                        SubjectScoreCard(detail: detail)
                        if detail.trend.count > 1 { SubjectTrendCard(detail: detail) }
                    } trailing: {
                        if !detail.standards.isEmpty { StandardsCard(detail: detail) }
                        if !detail.distribution.isEmpty { DistributionCard(detail: detail) }
                    }
                }
                .pocketPage()
            } else {
                ContentUnavailableView("找不到這個科目", systemImage: "questionmark.circle")
            }
        }
        .background(PocketColor.background.ignoresSafeArea())
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct SubjectScoreCard: View {
    let detail: PocketSubjectDetail

    var body: some View {
        PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
            VStack(alignment: .leading, spacing: 0) {
                Text(detail.examTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(detail.scoreText)
                        .font(PocketFont.display(56, relativeTo: .largeTitle))
                        .monospacedDigit()
                        .foregroundStyle(detail.isFlunk ? PocketColor.negative : PocketColor.onPrimaryContainer)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    if !detail.levelText.isEmpty {
                        PocketChip(text: detail.levelText, foreground: PocketColor.onPrimary, background: PocketColor.primary)
                    }
                    if !detail.statusText.isEmpty {
                        PocketChip(text: detail.statusText, foreground: PocketColor.negative, background: PocketColor.background.opacity(0.7))
                    }
                }
                .padding(.top, 2)
                if !detail.changeText.isEmpty {
                    Text(detail.changeText)
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                }
                Grid(horizontalSpacing: 10, verticalSpacing: 10) {
                    GridRow {
                        stat("班平均", detail.classAverageText, detail.diffText.isEmpty ? "" : "差距 \(detail.diffText)")
                        stat("標準差", detail.deviationText.isEmpty ? "--" : detail.deviationText, "分數離散程度")
                    }
                    GridRow {
                        stat("班級排名", detail.classRankText, "")
                        stat("年級排名", detail.yearRankText, "")
                    }
                }
                .padding(.top, 20)
            }
        }
        .accessibilityIdentifier("subject-score-card")
    }

    private func stat(_ title: String, _ value: String, _ note: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption.weight(.medium))
                .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
            Text(value)
                .font(PocketFont.display(22, relativeTo: .title2))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(PocketColor.onPrimaryContainer)
            if !note.isEmpty {
                Text(note)
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(PocketColor.background.opacity(0.55), in: .rect(cornerRadius: PocketMetrics.innerRadius, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct SubjectTrendCard: View {
    let detail: PocketSubjectDetail

    var body: some View {
        let values = (detail.trend + detail.classTrend).map(\.average)
        let low = max(0, (values.min() ?? 0) - 6)
        let high = min(104, (values.max() ?? 100) + 8)
        PocketCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("本學期走勢")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    Spacer()
                    legend("我的分數", PocketColor.primary)
                    legend("班平均", PocketColor.outline)
                }
                Chart {
                    ForEach(detail.classTrend, id: \.examName) { point in
                        LineMark(x: .value("考試", point.examName), y: .value("分數", point.average), series: .value("線", "班平均"))
                            .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, dash: [4, 4]))
                            .foregroundStyle(PocketColor.outline)
                    }
                    ForEach(detail.trend, id: \.examName) { point in
                        LineMark(x: .value("考試", point.examName), y: .value("分數", point.average), series: .value("線", "我"))
                            .interpolationMethod(.monotone)
                            .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round))
                            .foregroundStyle(PocketColor.primary)
                        PointMark(x: .value("考試", point.examName), y: .value("分數", point.average))
                            .symbolSize(80)
                            .foregroundStyle(PocketColor.primary)
                            .annotation(position: .top, spacing: 6) {
                                Text(point.averageText)
                                    .font(.caption.weight(.semibold))
                                    .monospacedDigit()
                                    .foregroundStyle(PocketColor.onSurface)
                            }
                    }
                }
                .chartYScale(domain: low...high)
                .chartYAxis(.hidden)
                .chartXAxis {
                    AxisMarks { _ in
                        AxisValueLabel().font(.caption).foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                }
                .frame(height: 150)
                .dynamicTypeSize(...DynamicTypeSize.xxLarge)
                .accessibilityLabel("本學期走勢：" + detail.trend.map { "\($0.examName) \($0.averageText) 分" }.joined(separator: "，"))
            }
        }
    }

    private func legend(_ text: String, _ color: Color) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text).font(.caption).foregroundStyle(PocketColor.onSurfaceVariant)
        }
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .fixedSize()
        .accessibilityHidden(true)
    }
}

private struct StandardsCard: View {
    let detail: PocketSubjectDetail
    /// Absent or unpublished subjects have no position among the standards.
    private var hasScore: Bool { Double(detail.scoreText) != nil }

    var body: some View {
        PocketCard {
            VStack(alignment: .leading, spacing: 14) {
                Text("五標")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .accessibilityAddTraits(.isHeader)
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(PocketColor.surfaceHigh).frame(height: 8)
                        ForEach(detail.standards, id: \.label) { mark in
                            RoundedRectangle(cornerRadius: 1)
                                .fill(PocketColor.outline)
                                .frame(width: 2, height: 14)
                                .offset(x: proxy.size.width * mark.value / 100 - 1)
                        }
                        if hasScore {
                        Circle()
                            .fill(PocketColor.primary)
                            .stroke(PocketColor.surfaceLow, lineWidth: 3)
                            .frame(width: 18, height: 18)
                            .offset(x: min(max(proxy.size.width * detail.score / 100 - 9, 0), proxy.size.width - 18))
                        }
                    }
                    .frame(maxHeight: .infinity)
                }
                .frame(height: 20)
                .accessibilityHidden(true)
                VStack(spacing: 0) {
                    ForEach(Array(detail.standards.enumerated()), id: \.element.label) { index, mark in
                        if index > 0 { PocketRowDivider() }
                        HStack {
                            Text(mark.label).font(.subheadline).foregroundStyle(PocketColor.onSurface)
                            Spacer()
                            if hasScore, detail.score >= mark.value, index == detail.standards.firstIndex(where: { detail.score >= $0.value }) {
                                PocketChip(text: "你在這裡", foreground: PocketColor.onPrimaryContainer, background: PocketColor.primaryContainer)
                            }
                            Text(mark.valueText)
                                .font(.subheadline.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(PocketColor.onSurface)
                                .frame(minWidth: 44, alignment: .trailing)
                        }
                        .frame(minHeight: 40)
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
    }
}

private struct DistributionCard: View {
    let detail: PocketSubjectDetail

    var body: some View {
        PocketCard {
            VStack(alignment: .leading, spacing: 14) {
                Text("班級分數分布")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .accessibilityAddTraits(.isHeader)
                Chart(detail.distribution, id: \.label) { bar in
                    BarMark(x: .value("級距", bar.label), y: .value("人數", bar.count), width: .ratio(0.62))
                        .cornerRadius(6)
                        .foregroundStyle(bar.isMine ? PocketColor.primary : PocketColor.primaryContainer)
                        .annotation(position: .top, spacing: 4) {
                            Text("\(bar.count)")
                                .font(.caption.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(bar.isMine ? PocketColor.primary : PocketColor.onSurfaceVariant)
                        }
                }
                .chartYAxis(.hidden)
                .chartXAxis {
                    AxisMarks { _ in
                        AxisValueLabel().font(.caption2).foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                }
                .frame(height: 150)
                .dynamicTypeSize(...DynamicTypeSize.xxLarge)
                .accessibilityLabel("班級分數分布：" + detail.distribution.map { "\($0.label) 分 \($0.count) 人" }.joined(separator: "，"))
                if !detail.distributionSummary.isEmpty {
                    Text(detail.distributionSummary)
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
            }
        }
    }
}

// MARK: - Simulator

struct GradeSimulatorView: View {
    let examValue: String

    var body: some View {
        if let simulator = PocketGrades.shared.simulator(examValue: examValue) {
            GradeSimulatorContent(simulator: simulator, examValue: examValue)
        } else {
            ContentUnavailableView("成績尚未載入", systemImage: "chart.bar", description: Text("載入這次考試的成績後才能試算。"))
                .navigationTitle("成績模擬器")
                .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct GradeSimulatorContent: View {
    @Environment(AppModel.self) private var model
    @State private var simulator: PocketSimulator
    @State private var revision = 0
    @State private var showingCredits = false
    let examValue: String

    init(simulator: PocketSimulator, examValue: String) {
        _simulator = State(initialValue: simulator)
        self.examValue = examValue
    }

    private func edit(_ change: (PocketSimulator) -> Void) {
        change(simulator)
        revision += 1
    }

    var body: some View {
        let _ = revision
        let state = simulator.state()
        ScrollView {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                PocketColumns {
                    result(state)
                    creditCheck(state)
                } trailing: {
                    PocketSectionHeader(title: "調整各科分數") {
                        Text("共 \(state.totalCredits) 學分")
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                    PocketCard(padding: 8) {
                        VStack(spacing: 0) {
                            ForEach(Array(state.rows.enumerated()), id: \.element.name) { index, row in
                                if index > 0 { PocketRowDivider().padding(.leading, 12) }
                                SimulatorRow(row: row, index: index, edit: edit)
                            }
                        }
                    }
                }
            }
            .pocketPage()
        }
        .background(PocketColor.background.ignoresSafeArea())
        .navigationTitle("成績模擬器")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("重設") { edit { $0.reset() } }
                    .disabled(!state.hasChanges)
                    .accessibilityIdentifier("simulator-reset")
            }
        }
        .sheet(isPresented: $showingCredits) {
            CreditsSheet(simulator: simulator, revision: $revision, examValue: examValue)
        }
        .task(id: examValue) {
            // Credits the student confirmed for this exam and account earlier.
            // `account` is nil in the demo: it never reads or writes the stored account's credits.
            guard let live = model.account, let json = await live.simulatorCredits(examValue: examValue) else { return }
            if simulator.restoreCredits(json: json) { revision += 1 }
        }
    }

    private func result(_ state: PocketSimulation) -> some View {
        PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
            VStack(alignment: .leading, spacing: 0) {
                Text("模擬加權平均")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(state.averageText)
                        .font(PocketFont.display(56, relativeTo: .largeTitle))
                        .monospacedDigit()
                        .foregroundStyle(PocketColor.onPrimaryContainer)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .contentTransition(.numericText())
                        .accessibilityIdentifier("simulator-average")
                    if state.deltaSign != 0, state.averageText != "--" {
                        Text(state.deltaText)
                            .font(.subheadline.weight(.bold))
                            .monospacedDigit()
                            .foregroundStyle(state.deltaSign > 0 ? PocketColor.positive : PocketColor.negative)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(PocketColor.background.opacity(0.7), in: .capsule)
                            .accessibilityLabel("與原始成績相差 \(state.deltaText)")
                    }
                }
                .padding(.top, 2)
                Text("加權總分 \(state.totalText) · 成績單平均 \(state.officialAverageText)")
                    .font(.subheadline)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                Text("* 非官方試算，結果不會改變成績單")
                    .font(.caption)
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.75))
                    .padding(.top, 4)
                    .accessibilityIdentifier("simulator-unofficial")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func creditCheck(_ state: PocketSimulation) -> some View {
        let ok = state.check == .matches
        return PocketCard {
            VStack(alignment: .leading, spacing: 10) {
                Label(state.checkTitle, systemImage: ok ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(ok ? PocketColor.positive : PocketColor.warning)
                Text(state.checkDetail)
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    showingCredits = true
                } label: {
                    Label("調整學分", systemImage: "number")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(PocketColor.surfaceHigh, in: .rect(cornerRadius: 14, style: .continuous))
                }
                .overlay(alignment: .topTrailing) {
                    if state.creditsPending {
                        Circle().fill(PocketColor.negative).frame(width: 10, height: 10).padding(8)
                            .accessibilityIdentifier("simulator-credits-pending")
                    }
                }
                .accessibilityIdentifier("simulator-credits")
                .accessibilityValue(state.creditsPending ? "學分尚未儲存" : "")
            }
        }
    }
}

private struct SimulatorRow: View {
    let row: PocketSimulatorRow
    let index: Int
    let edit: ((PocketSimulator) -> Void) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // At accessibility sizes the name gets its own line; beside the chip and score it
            // would break after every character.
            if typeSize.isAccessibilitySize {
                Text(row.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(PocketColor.onSurface)
            }
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if !typeSize.isAccessibilitySize {
                    Text(row.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(PocketColor.onSurface)
                }
                PocketChip(text: "\(row.credits) 學分")
                Spacer(minLength: 4)
                if row.isAdjusted {
                    Text(row.officialScoreText)
                        .font(.footnote)
                        .monospacedDigit()
                        .strikethrough()
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .accessibilityLabel("原始 \(row.officialScoreText) 分")
                }
                Text(row.scoreText)
                    .font(PocketFont.display(22, relativeTo: .title2))
                    .monospacedDigit()
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(row.isAdjusted ? PocketColor.primary : PocketColor.onSurface)
            }
            if row.canSimulate {
                HStack(spacing: 12) {
                    Slider(
                        value: Binding(get: { row.score }, set: { value in edit { $0.setScore(name: row.name, value: value) } }),
                        in: 0...100, step: 1
                    )
                    .disabled(!row.isIncluded)
                    .accessibilityLabel("\(row.name)模擬分數")
                    .accessibilityValue("\(row.scoreText) 分")
                    .accessibilityIdentifier("simulator-slider-\(index)")
                    Toggle("計入平均", isOn: Binding(
                        get: { row.isIncluded },
                        set: { value in edit { $0.setIncluded(name: row.name, isIncluded: value) } }
                    ))
                    .labelsHidden()
                    .accessibilityLabel("\(row.name)計入平均")
                    .accessibilityIdentifier("simulator-include-\(index)")
                }
            } else {
                Text("沒有可用分數，不列入模擬。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .opacity(row.isIncluded || !row.canSimulate ? 1 : 0.55)
    }
}

private struct CreditsSheet: View {
    @Environment(\.dismiss) private var dismiss
    let simulator: PocketSimulator
    @Binding var revision: Int
    let examValue: String
    @Environment(AppModel.self) private var model
    @State private var inferring: Task<Void, Never>?
    @State private var saving = false
    @State private var saveMessage: String?

    /// Stores the credits for this exam and account; only a stored set counts as confirmed.
    private func saveCredits(_ live: LiveDataController) {
        guard let json = simulator.creditsJson() else { return }
        saving = true
        Task {
            do {
                try await live.saveSimulatorCredits(examValue: examValue, json: json)
                // Only the snapshot written counts as saved; edits made meanwhile stay pending.
                simulator.markCreditsSaved(json: json)
                saveMessage = nil
            } catch {
                saveMessage = "學分未能儲存：\(PocketError(error).message)"
            }
            saving = false
            revision += 1
        }
    }

    /// The search can take a while on a long report card, so it runs off the main actor; only
    /// applying the finished result touches the simulator, and a cancelled search is dropped.
    private func startInference() {
        let search = CreditSearch(simulator: simulator)
        inferring = Task {
            let result = await Task.detached(priority: .userInitiated) { search.run() }.value
            guard !Task.isCancelled else { return }
            simulator.applyInference(result: result.value)
            revision += 1
            inferring = nil
        }
    }

    private func cancelInference() {
        inferring?.cancel()
        inferring = nil
    }

    var body: some View {
        let _ = revision
        let state = simulator.state()
        NavigationStack {
            Form {
                Section {
                    LabeledContent("學分來源") {
                        HStack(spacing: 6) {
                            if state.creditsPending {
                                Circle().fill(PocketColor.negative).frame(width: 8, height: 8).accessibilityHidden(true)
                            }
                            Text(state.creditSourceText)
                        }
                    }
                    .accessibilityIdentifier("credits-source")
                    if let live = model.account, live.session != nil {
                        Button(saving ? "儲存中…" : "儲存學分", systemImage: "square.and.arrow.down") { saveCredits(live) }
                            .disabled(!state.creditsPending || saving)
                            .accessibilityIdentifier("credits-save")
                    }
                    if let saveMessage {
                        Text(saveMessage).font(.footnote).foregroundStyle(PocketColor.negative)
                    }
                }
                Section {
                    Label(state.checkTitle, systemImage: state.check == .matches ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(state.check == .matches ? PocketColor.positive : PocketColor.warning)
                        .accessibilityIdentifier("credits-status")
                } footer: {
                    Text("學分只用於這次考試的試算，不會改動校方成績單。每科為 1 到 4 學分。")
                }
                Section("各科學分") {
                    ForEach(Array(state.rows.enumerated()), id: \.element.name) { index, row in
                        Stepper(value: Binding(
                            get: { Int(row.credits) },
                            set: { value in
                                // A manual edit supersedes a search still running for the old credits.
                                cancelInference()
                                simulator.setCredits(name: row.name, value: Int32(value))
                                revision += 1
                            }
                        ), in: 1...4) {
                            ViewThatFits(in: .horizontal) {
                                HStack {
                                    Text(row.name).lineLimit(1)
                                    Spacer()
                                    Text("\(row.credits)").monospacedDigit().foregroundStyle(PocketColor.onSurfaceVariant)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(row.name)
                                    Text("\(row.credits) 學分").monospacedDigit().foregroundStyle(PocketColor.onSurfaceVariant)
                                }
                            }
                        }
                        .accessibilityValue("\(row.credits) 學分")
                        .accessibilityIdentifier("credit-stepper-\(index)")
                    }
                }
                Section {
                    if inferring != nil {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("正在推算學分…").foregroundStyle(PocketColor.onSurfaceVariant)
                            Spacer()
                            Button("取消") { cancelInference() }
                                .accessibilityIdentifier("credits-infer-cancel")
                        }
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("credits-inferring")
                    } else {
                        Button("依校方總分推算學分", systemImage: "wand.and.stars") { startInference() }
                            .accessibilityIdentifier("credits-infer")
                    }
                    if !state.inferenceText.isEmpty {
                        Text(state.inferenceText).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                    ForEach(state.candidates, id: \.index) { candidate in
                        Button {
                            simulator.applyCandidate(index: candidate.index)
                            revision += 1
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("共 \(candidate.totalCredits) 學分").font(.subheadline.weight(.semibold))
                                Text(candidate.summary).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                            }
                        }
                        .accessibilityIdentifier("credits-candidate-\(candidate.index)")
                    }
                } header: {
                    Text("自動推算")
                } footer: {
                    Text("推算結果只代表在 1 到 4 學分範圍內能重現成績單總分，不是校方公布的學分。")
                }
            }
            .scrollContentBackground(.hidden)
            .background(PocketColor.background.ignoresSafeArea())
            .navigationTitle("學分")
            .navigationBarTitleDisplayMode(.inline)
            .onDisappear { cancelInference() }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("credits-done")
                }
            }
        }
    }
}

/// Carries the simulator to the search thread. `computeInference` reads only the immutable report,
/// so the editable state is never touched off the main actor.
private struct CreditSearch: @unchecked Sendable {
    let simulator: PocketSimulator

    struct Result: @unchecked Sendable { let value: PocketCreditInference }

    func run() -> Result { Result(value: simulator.computeInference()) }
}

// MARK: Subject trend

/// Android's 成績折線圖: several terms and subjects on one line chart.
struct SubjectTrendView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// Nil until the student narrows it: every term, as Android starts.
    @State private var yearValues: [String]?
    @State private var subjectKeys: [String] = []
    @State private var highlighted: String?
    @State private var selectedExam: String?
    @State private var loading = false
    @State private var showsYears = false
    @State private var showsSubjects = false

    private var trends: PocketSubjectTrends { PocketSubjectTrends.shared }
    private var chosenYears: [String] { yearValues ?? trends.terms().map(\.id) }

    var body: some View {
        let trend = trends.trend(yearValues: chosenYears, subjectKeys: subjectKeys)
        ScrollView {
            // One column on every width: the chart gets the whole width, the choices sit below it.
            VStack(spacing: PocketMetrics.cardSpacing) {
                chartCard(trend)
                controls(trend)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, PocketMetrics.screenPadding)
            .padding(.top, 8)
            .padding(.bottom, 32)
        }
        .background(PocketColor.background)
        .navigationTitle("成績折線圖")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: chosenYears) {
            guard let live = model.account else { return }
            loading = true
            await live.loadReports(examValues: trends.examKeys(yearValues: chosenYears))
            if !Task.isCancelled { loading = false }
        }
        .sheet(isPresented: $showsYears) {
            TrendChoiceSheet(
                title: "選擇學期", message: "選擇要納入折線圖比較的學期", confirm: "套用",
                options: trends.terms().map { ($0.id, $0.title) }, selection: chosenYears, offersAll: true
            ) { yearValues = $0 }
        }
        .sheet(isPresented: $showsSubjects) {
            TrendChoiceSheet(
                title: "調整對比科目", message: "選擇要顯示的科目", confirm: "確認",
                options: trend.availableSubjects.map { ($0, $0) }, selection: subjectKeys, offersAll: false
            ) { keys in
                subjectKeys = keys
                if let highlighted, !trends.trend(yearValues: chosenYears, subjectKeys: keys).legend.contains(where: { $0.baseName == highlighted }) {
                    self.highlighted = nil
                }
            }
        }
    }

    @ViewBuilder
    private func chartCard(_ trend: PocketSubjectTrend) -> some View {
        PocketCard {
            VStack(alignment: .leading, spacing: 14) {
                if !trend.emptyTitle.isEmpty || trend.exams.isEmpty {
                    if loading {
                        ProgressView("正在載入成績…")
                            .frame(maxWidth: .infinity, minHeight: 160)
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(trend.emptyTitle.isEmpty ? "暫時沒有可繪製的成績資料" : trend.emptyTitle)
                                .font(PocketFont.sectionTitle)
                                .foregroundStyle(PocketColor.onSurface)
                            Text(trend.emptyMessage.isEmpty ? "請調整學期或科目後再試一次。" : trend.emptyMessage)
                                .font(.subheadline)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                            Button(trend.emptyAction.isEmpty ? "選擇學期" : trend.emptyAction) {
                                if trend.emptyAction == "選擇科目" { showsSubjects = true } else { showsYears = true }
                            }
                            .buttonStyle(.borderedProminent)
                            .padding(.top, 4)
                            .accessibilityIdentifier("trend-empty-action")
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                } else {
                    chart(trend)
                    legend(trend)
                }
            }
        }
    }

    private func chart(_ trend: PocketSubjectTrend) -> some View {
        let labels = trend.exams.map(\.label)
        // Up to six exams fit an iPhone (twelve an iPad); more scroll sideways, starting at the newest.
        let visible = sizeClass == .regular ? min(labels.count, 12) : min(labels.count, 6)
        let scrolls = labels.count > visible
        return VStack(alignment: .leading, spacing: 8) {
        if scrolls {
            Label("左右滑動查看其他 \(labels.count - visible) 次考試", systemImage: "arrow.left.and.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(PocketColor.primary)
                .accessibilityIdentifier("trend-scroll-hint")
        }
        Chart {
            ForEach(Array(trend.segments.enumerated()), id: \.offset) { index, segment in
                let tint = TrendPalette.color(Int(segment.colorIndex)).opacity(opacity(segment.baseName))
                ForEach([(segment.fromIndex, segment.fromScore), (segment.toIndex, segment.toScore)], id: \.0) { point in
                    LineMark(
                        x: .value("考試", labels[Int(point.0)]),
                        y: .value("分數", point.1),
                        series: .value("線段", "\(index)")
                    )
                    .foregroundStyle(segment.isDashed ? tint.opacity(0.6) : tint)
                    .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round, dash: segment.isDashed ? [6, 5] : []))
                }
            }
            ForEach(trend.series, id: \.key) { line in
                ForEach(line.values, id: \.examIndex) { value in
                    PointMark(x: .value("考試", labels[Int(value.examIndex)]), y: .value("分數", value.score))
                        .foregroundStyle(TrendPalette.color(Int(line.colorIndex)).opacity(opacity(line.baseName)))
                        .symbolSize(highlighted == line.baseName ? 90 : 50)
                }
            }
            if let selectedExam, let exam = trend.exams.first(where: { $0.label == selectedExam }) {
                RuleMark(x: .value("考試", selectedExam))
                    .foregroundStyle(PocketColor.onSurfaceVariant.opacity(0.3))
                    .annotation(position: .top, spacing: 4, overflowResolution: .init(x: .fit(to: .chart), y: .fit(to: .chart))) {
                        tooltip(trend, exam: exam)
                    }
            }
        }
        .chartXScale(domain: labels)
        .chartYScale(domain: trend.minScore...trend.maxScore)
        // A tap keeps the callout open, as Android's point tooltip; tapping the same exam closes it.
        .chartGesture { proxy in
            SpatialTapGesture().onEnded { value in
                let exam = proxy.value(atX: value.location.x, as: String.self)
                selectedExam = exam == selectedExam ? nil : exam
            }
        }
        .chartScrollableAxes(scrolls ? .horizontal : [])
        .chartXVisibleDomain(length: max(visible, 1))
        .chartScrollPosition(initialX: labels.last ?? "")
        .chartXAxis {
            AxisMarks(values: labels) { value in
                AxisGridLine()
                AxisValueLabel(centered: true, collisionResolution: .disabled) {
                    if let label = value.as(String.self), let exam = trend.exams.first(where: { $0.label == label }) {
                        VStack(spacing: 0) {
                            Text(exam.termLabel)
                            Text(sizeClass == .regular ? exam.examName : Self.shortExamName(exam.examName))
                        }
                        .lineLimit(1)
                        .font(.caption2)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine()
                AxisValueLabel().font(.caption2)
            }
        }
        .frame(height: sizeClass == .regular ? 520 : 320)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .accessibilityLabel("成績折線圖")
        .accessibilityValue(trend.description_)
        .accessibilityChartDescriptor(SubjectTrendChartDescriptor(trend: trend))
        .accessibilityIdentifier("subject-trend-chart")
        if let exam = trend.exams.first(where: { $0.label == selectedExam }) {
            Text(selectionSummary(trend, exam: exam))
                .font(.footnote.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(PocketColor.onSurface)
                .accessibilityIdentifier("trend-selection")
        }
        }
    }

    /// 第一次段考 → 一段, 期末考 → 期末, so six exams fit side by side on an iPhone.
    private static func shortExamName(_ name: String) -> String {
        let ordinals = ["第一次": "一", "第二次": "二", "第三次": "三"]
        for (long, short) in ordinals where name.hasPrefix(long) {
            return short + (name.contains("段考") ? "段" : String(name.dropFirst(long.count).prefix(1)))
        }
        if name.hasSuffix("考"), name.count == 3 { return String(name.prefix(2)) }
        return String(name.prefix(3))
    }

    private func selectionSummary(_ trend: PocketSubjectTrend, exam: PocketTrendExam) -> String {
        let values = trend.series.compactMap { line in
            line.values.first { $0.examIndex == exam.index }.map { "\(line.label) \($0.scoreText)" }
        }
        return "\(exam.label)：\(values.isEmpty ? "沒有成績" : values.joined(separator: "、"))"
    }

    private func tooltip(_ trend: PocketSubjectTrend, exam: PocketTrendExam) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(exam.label).font(.caption.weight(.semibold))
            ForEach(trend.series, id: \.key) { line in
                if let value = line.values.first(where: { $0.examIndex == exam.index }) {
                    HStack(spacing: 4) {
                        Circle().fill(TrendPalette.color(Int(line.colorIndex))).frame(width: 7, height: 7)
                        Text("\(line.label) \(value.scoreText)").monospacedDigit()
                    }
                    .font(.caption)
                }
            }
        }
        .padding(8)
        .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(PocketColor.outline.opacity(0.3)))
    }

    private func opacity(_ baseName: String) -> Double {
        highlighted == nil || highlighted == baseName ? 1 : 0.2
    }

    private func legend(_ trend: PocketSubjectTrend) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), alignment: .leading)], alignment: .leading, spacing: 8) {
            ForEach(trend.legend, id: \.baseName) { item in
                let isSelected = highlighted == item.baseName
                Button {
                    highlighted = isSelected ? nil : item.baseName
                } label: {
                    HStack(spacing: 6) {
                        Circle().fill(TrendPalette.color(Int(item.colorIndex))).frame(width: 10, height: 10)
                        Text(item.label)
                            .font(.subheadline.weight(isSelected ? .semibold : .regular))
                            .foregroundStyle(PocketColor.onSurface)
                    }
                    .padding(.horizontal, 10)
                    .frame(minHeight: 36)
                    .background(isSelected ? PocketColor.primary.opacity(0.14) : .clear, in: .capsule)
                    .contentShape(.capsule)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("突顯\(item.label)")
                .accessibilityAddTraits(isSelected ? .isSelected : [])
                .accessibilityIdentifier("trend-legend-\(item.baseName)")
            }
        }
    }

    private func controls(_ trend: PocketSubjectTrend) -> some View {
        let layout = sizeClass == .regular ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
        return PocketCard(padding: 8) {
            VStack(spacing: 0) {
                layout {
                    controlRow("比較學期", detail: trend.yearsText, symbol: "calendar") { showsYears = true }
                        .accessibilityIdentifier("trend-years")
                    if sizeClass == .regular { Divider().frame(height: 40) } else { PocketRowDivider().padding(.leading, 12) }
                    controlRow("調整對比科目", detail: trend.subjectsText, symbol: "books.vertical") { showsSubjects = true }
                        .accessibilityIdentifier("trend-subjects")
                        .disabled(trend.availableSubjects.isEmpty)
                }
                if !loading, !trend.loadMessage.isEmpty {
                    PocketRowDivider().padding(.leading, 12)
                    Label(trend.loadMessage, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                }
            }
        }
    }

    private func controlRow(_ title: String, detail: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .foregroundStyle(PocketColor.primary)
                    .frame(width: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundStyle(PocketColor.onSurface)
                    Text(detail).font(.subheadline).foregroundStyle(PocketColor.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(12)
            .frame(minHeight: 56)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// Android's subject colours, in the same order.
private enum TrendPalette {
    private static let hex: [UInt32] = [
        0xE91E63, 0x9C27B0, 0x3F51B5, 0x00BCD4, 0x4CAF50, 0xFF9800, 0x795548, 0x607D8B, 0xF44336,
        0x009688, 0xCDDC39, 0xFFC107, 0xFF5722, 0x8BC34A, 0x673AB7, 0x03A9F4, 0xE040FB, 0x00E5FF,
    ]

    static func color(_ index: Int) -> Color {
        let value = hex[((index % hex.count) + hex.count) % hex.count]
        return Color(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }
}

/// Audio Graph: every subject as a data series over the exams.
private struct SubjectTrendChartDescriptor: AXChartDescriptorRepresentable {
    let trend: PocketSubjectTrend

    func makeChartDescriptor() -> AXChartDescriptor {
        let labels = trend.exams.map(\.label)
        let xAxis = AXCategoricalDataAxisDescriptor(title: "考試", categoryOrder: labels)
        let yAxis = AXNumericDataAxisDescriptor(title: "分數", range: trend.minScore...trend.maxScore, gridlinePositions: []) {
            "\(Int($0.rounded())) 分"
        }
        let series = trend.series.map { line in
            AXDataSeriesDescriptor(name: line.label, isContinuous: true, dataPoints: line.values.map {
                AXDataPoint(x: labels[Int($0.examIndex)], y: $0.score)
            })
        }
        return AXChartDescriptor(title: "成績折線圖", summary: trend.description_, xAxis: xAxis, yAxis: yAxis, additionalAxes: [], series: series)
    }
}

/// Multiple choice with 全選 / 清除, applied only by the confirm button, as Android's sheets.
private struct TrendChoiceSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let message: String
    let confirm: String
    let options: [(id: String, title: String)]
    @State var selection: [String]
    let offersAll: Bool
    let apply: ([String]) -> Void

    init(title: String, message: String, confirm: String, options: [(String, String)], selection: [String], offersAll: Bool, apply: @escaping ([String]) -> Void) {
        self.title = title
        self.message = message
        self.confirm = confirm
        self.options = options.map { (id: $0.0, title: $0.1) }
        _selection = State(initialValue: selection)
        self.offersAll = offersAll
        self.apply = apply
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(options, id: \.id) { option in
                        let isOn = selection.contains(option.id)
                        Button {
                            if isOn { selection.removeAll { $0 == option.id } } else { selection.append(option.id) }
                        } label: {
                            HStack {
                                Text(option.title).foregroundStyle(PocketColor.onSurface)
                                Spacer()
                                Image(systemName: isOn ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(isOn ? PocketColor.primary : PocketColor.onSurfaceVariant)
                                    .accessibilityHidden(true)
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(isOn ? .isSelected : [])
                        .accessibilityIdentifier("trend-option-\(option.id)")
                    }
                } footer: {
                    Text(message)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirm) {
                        // Keep the order the options are listed in, so colours follow the list.
                        apply(options.map(\.id).filter(selection.contains))
                        dismiss()
                    }
                    .accessibilityIdentifier("trend-apply")
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    if offersAll {
                        Button("全選") { selection = options.map(\.id) }
                    }
                    Spacer()
                    Button("清除") { selection = [] }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
