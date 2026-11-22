import Charts
import PocketShared
import SwiftUI

struct GradesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var examValue: String?
    @State private var loadingExam = false
    @State private var showsExport = false

    var body: some View {
        let source = model.dataSource
        let report = source.grades(examValue: examValue ?? source.defaultExamValue())
        ScrollView {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                if !source.gradeTerms().isEmpty {
                    examPicker(report: report, terms: source.gradeTerms())
                }
                if !report.isLoaded {
                    PocketPendingView(
                        isLoading: model.live?.isLoadingPrivate ?? false || loadingExam,
                        title: "成績尚未載入", message: model.live?.lastError
                    ) { Task { await reload(report) } }
                } else {
                PocketColumns {
                    VStack(spacing: PocketMetrics.cardSpacing) {
                        GradeSummaryCard(report: report)
                        actions(report: report)
                        if report.trend.count > 1 {
                            GradeTrendCard(report: report)
                        }
                        NavigationLink(value: PocketRoute.subjectTrend) {
                            SubjectTrendEntry()
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("grades-subject-trend")
                        AnalysisCard(analysis: PocketGrades.shared.analysis(examValue: report.examValue, unused: false))
                        if !report.strengths.isEmpty || !report.weaknesses.isEmpty {
                            let layout = typeSize.isAccessibilitySize
                                ? AnyLayout(VStackLayout(spacing: PocketMetrics.cardSpacing))
                                : AnyLayout(HStackLayout(alignment: .top, spacing: PocketMetrics.cardSpacing))
                            layout {
                                InsightCard(title: "表現亮眼", systemImage: "arrow.up.right", tone: PocketColor.positive, items: report.strengths)
                                InsightCard(title: "可以加強", systemImage: "arrow.down.right", tone: PocketColor.negative, items: report.weaknesses)
                            }
                            .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                } trailing: {
                    VStack(alignment: .leading, spacing: 8) {
                        PocketSectionHeader(title: "各科成績") {
                            Text("與班平均比較")
                                .font(.footnote)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                        PocketCard(padding: 8) {
                            VStack(spacing: 0) {
                                ForEach(Array(report.subjects.enumerated()), id: \.element.name) { index, subject in
                                    if index > 0 { PocketRowDivider().padding(.leading, 12) }
                                    NavigationLink(value: PocketRoute.subject(exam: report.examValue, name: subject.name)) {
                                        SubjectGradeRow(subject: subject)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityIdentifier("subject-\(index)")
                                }
                            }
                        }
                        Text("資料更新於 \(report.updatedAtText)")
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                            .padding(.horizontal, 4)
                            .padding(.top, 4)
                    }
                }
                }
            }
            .pocketPage()
        }
        .refreshable { await reload(report) }
        .task(id: report.examValue + (report.isLoaded ? "" : "?")) {
            guard !report.isLoaded, !report.examValue.isEmpty, let live = model.account else { return }
            loadingExam = true
            await live.loadReport(examValue: report.examValue)
            loadingExam = false
        }
        .navigationTitle(PocketTab.grades.title)
        .sheet(isPresented: $showsExport) {
            GradeExportSheet(terms: model.dataSource.gradeTerms(), initial: report.examValue)
        }
    }

    /// The shared refresh only reloads the newest exam, so an older selected exam is fetched again too.
    private func reload(_ report: PocketGradeReport) async {
        await model.refreshLiveData(force: true)
        guard !report.examValue.isEmpty, report.examValue != model.dataSource.defaultExamValue(),
              let live = model.account else { return }
        loadingExam = true
        // Already-loaded reports are skipped unless forced, and the school does correct old grades.
        await live.loadReport(examValue: report.examValue, force: true)
        loadingExam = false
    }

    private func actions(report: PocketGradeReport) -> some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: PocketMetrics.cardSpacing))
            : AnyLayout(HStackLayout(spacing: PocketMetrics.cardSpacing))
        return layout {
            NavigationLink(value: PocketRoute.simulator(exam: report.examValue)) {
                actionLabel("成績模擬器", "slider.horizontal.3")
            }
            .accessibilityIdentifier("grades-simulator")
            ShareLink(item: PocketGrades.shared.shareText(examValue: report.examValue)) {
                actionLabel("分享摘要", "square.and.arrow.up")
            }
            .accessibilityIdentifier("grades-share")
            Button { showsExport = true } label: {
                actionLabel("匯出成績", "tablecells")
            }
            .accessibilityIdentifier("grades-export")
        }
        .buttonStyle(.plain)
    }

    private func actionLabel(_ title: String, _ symbol: String) -> some View {
        Label(title, systemImage: symbol)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(PocketColor.primary)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.innerRadius, style: .continuous))
            .contentShape(.rect)
    }

    private func examPicker(report: PocketGradeReport, terms: [PocketGradeTerm]) -> some View {
        Menu {
            ForEach(terms, id: \.value) { term in
                Section(term.text) {
                    ForEach(term.exams, id: \.value) { exam in
                        Button {
                            examValue = exam.value
                        } label: {
                            if exam.value == report.examValue {
                                Label(exam.text, systemImage: "checkmark")
                            } else {
                                Text(exam.text)
                            }
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(report.termTitle)
                        .font(.caption)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    Text(report.examTitle)
                        .font(PocketFont.cardTitle)
                        .foregroundStyle(PocketColor.onSurface)
                }
                Image(systemName: "chevron.up.chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PocketColor.primary)
            }
            .padding(.horizontal, 18)
            .frame(minHeight: 52)
            .contentShape(.capsule)
            .pocketGlass(in: .capsule, interactive: true)
        }
        .accessibilityLabel("選擇考試，目前為\(report.termTitle)\(report.examTitle)")
        .accessibilityIdentifier("exam-picker")
    }
}

private struct GradeSummaryCard: View {
    let report: PocketGradeReport

    var body: some View {
        PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
            VStack(alignment: .leading, spacing: 0) {
                Text("加權平均")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(report.averageText)
                        .font(PocketFont.display(56, relativeTo: .largeTitle))
                        .foregroundStyle(PocketColor.onPrimaryContainer)
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                        .accessibilityLabel("加權平均 \(report.averageText) 分")
                    if report.deltaSign != 0 {
                        let up = report.deltaSign > 0
                        HStack(spacing: 3) {
                            Image(systemName: up ? "arrow.up" : "arrow.down")
                                .imageScale(.small)
                            Text(report.deltaText.trimmingCharacters(in: CharacterSet(charactersIn: "+-")))
                        }
                        .font(.subheadline.weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(up ? PocketColor.positive : PocketColor.negative)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(PocketColor.background.opacity(0.7), in: .capsule)
                        .accessibilityLabel("較上次\(up ? "進步" : "退步") \(report.deltaText)")
                    }
                }
                .padding(.top, 2)
                Text("總分 \(report.totalText)")
                    .font(.subheadline)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))

                HStack(spacing: 10) {
                    rank("班級排名", report.classRankText, report.classTopText)
                    rank("類組排名", report.categoryRankText, report.categoryTopText)
                }
                .padding(.top, 20)
            }
        }
    }

    private func rank(_ title: String, _ value: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption.weight(.medium))
                .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
            Text(value)
                .font(PocketFont.display(28, relativeTo: .title))
                .monospacedDigit()
                .foregroundStyle(PocketColor.onPrimaryContainer)
            Text(detail)
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PocketColor.background.opacity(0.55), in: .rect(cornerRadius: PocketMetrics.innerRadius, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct GradeTrendCard: View {
    let report: PocketGradeReport

    var body: some View {
        let values = report.trend.map(\.average)
        let low = (values.min() ?? 0) - 3
        let high = (values.max() ?? 100) + 3
        PocketCard {
            VStack(alignment: .leading, spacing: 14) {
                Text("本學期平均走勢")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                Chart(report.trend, id: \.examName) { point in
                    AreaMark(
                        x: .value("考試", point.examName),
                        yStart: .value("基準", low),
                        yEnd: .value("平均", point.average)
                    )
                    .interpolationMethod(.monotone)
                    .foregroundStyle(
                        .linearGradient(
                            colors: [PocketColor.primary.opacity(0.22), PocketColor.primary.opacity(0)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    LineMark(x: .value("考試", point.examName), y: .value("平均", point.average))
                        .interpolationMethod(.monotone)
                        .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round))
                        .foregroundStyle(PocketColor.primary)
                    PointMark(x: .value("考試", point.examName), y: .value("平均", point.average))
                        .symbolSize(point.examName == report.examTitle ? 130 : 60)
                        .foregroundStyle(PocketColor.primary)
                        .annotation(position: .top, spacing: 6) {
                            Text(point.averageText)
                                .font(.caption.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(PocketColor.onSurface)
                        }
                }
                .chartYScale(domain: low...high)
                .chartYAxis(.hidden)
                .chartXAxis {
                    AxisMarks { _ in
                        AxisValueLabel()
                            .font(.caption)
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                }
                .frame(height: 132)
                .dynamicTypeSize(...DynamicTypeSize.xxLarge)
                .accessibilityLabel("本學期平均走勢：" + report.trend.map { "\($0.examName) \($0.averageText)" }.joined(separator: "，"))
            }
        }
    }
}

/// Android's 匯出成績: choose exams, then share them as a CSV file.
private struct GradeExportSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let terms: [PocketGradeTerm]
    @State private var selection: Set<String>
    @State private var file: URL?
    @State private var working = false
    @State private var message: String?

    init(terms: [PocketGradeTerm], initial: String) {
        self.terms = terms
        _selection = State(initialValue: initial.isEmpty ? [] : [initial])
    }

    var body: some View {
        NavigationStack {
            List {
                // Status first, so it is visible at the medium detent however many terms there are.
                Section {
                    Text(message ?? "匯出所選考試的各科分數、班平均、排名與五標，可用試算表開啟。")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .accessibilityIdentifier("export-message")
                    if let file {
                        ShareLink(item: file) {
                            Label("分享 CSV 檔", systemImage: "square.and.arrow.up")
                        }
                        .accessibilityIdentifier("export-share")
                    }
                }
                ForEach(terms, id: \.value) { term in
                    Section {
                        ForEach(term.exams, id: \.value) { exam in
                            let isOn = selection.contains(exam.value)
                            Button {
                                if isOn { selection.remove(exam.value) } else { selection.insert(exam.value) }
                                file = nil
                            } label: {
                                HStack {
                                    Text(exam.text).foregroundStyle(PocketColor.onSurface)
                                    Spacer()
                                    Image(systemName: isOn ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(isOn ? PocketColor.primary : PocketColor.onSurfaceVariant)
                                        .accessibilityHidden(true)
                                }
                                .contentShape(.rect)
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(isOn ? .isSelected : [])
                            .accessibilityIdentifier("export-\(exam.value)")
                        }
                    } header: {
                        HStack {
                            Text(term.text)
                            Spacer()
                            let keys = Set(term.exams.map(\.value))
                            Button(keys.isSubset(of: selection) ? "取消全選" : "全選") {
                                if keys.isSubset(of: selection) { selection.subtract(keys) } else { selection.formUnion(keys) }
                                file = nil
                            }
                            .font(.footnote.weight(.semibold))
                            .textCase(nil)
                        }
                    }
                }
            }
            .navigationTitle("選擇要匯出的考試")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if working {
                        ProgressView()
                    } else {
                        Button("匯出") { Task { await export() } }
                            .disabled(selection.isEmpty || file != nil)
                            .accessibilityIdentifier("export-confirm")
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func export() async {
        working = true
        defer { working = false }
        let keys = terms.flatMap { $0.exams.map(\.value) }.filter(selection.contains)
        if let live = model.account { await live.loadReports(examValues: keys) }
        let missing = PocketGradeExport.shared.missing(examValues: keys)
        guard missing.count < keys.count else {
            message = "所選考試的成績尚未載入，請稍後再試。"
            return
        }
        let format = DateFormatter()
        format.dateFormat = "yyyyMMdd_HHmmss"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("成績_\(format.string(from: .now)).csv")
        do {
            try Data(PocketGradeExport.shared.csv(examValues: keys).utf8).write(to: url, options: [.atomic, .completeFileProtection])
            file = url
            message = missing.isEmpty ? "已匯出 \(keys.count) 次考試。" : "已匯出 \(keys.count - missing.count) 次考試，\(missing.count) 次尚未載入。"
        } catch {
            message = "無法建立匯出檔案，請再試一次。"
        }
    }
}

/// Android's 成績折線圖 entry card.
private struct SubjectTrendEntry: View {
    var body: some View {
        PocketCard {
            HStack(spacing: 12) {
                Image(systemName: "chart.xyaxis.line")
                    .font(.title2)
                    .foregroundStyle(PocketColor.primary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("成績折線圖")
                        .font(.headline)
                        .foregroundStyle(PocketColor.onSurface)
                    Text("以折線圖查看各科目歷次成績走勢。")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

private struct InsightCard: View {
    let title: String
    let systemImage: String
    let tone: Color
    let items: [PocketSubjectInsight]

    var body: some View {
        PocketCard(padding: 16) {
            VStack(alignment: .leading, spacing: 10) {
                Label(title, systemImage: systemImage)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(tone)
                if items.isEmpty {
                    Text("目前沒有明顯落差")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
                ForEach(items, id: \.name) { item in
                    HStack(alignment: .firstTextBaseline) {
                        Text(item.name)
                            .font(PocketFont.cardTitle)
                            .foregroundStyle(PocketColor.onSurface)
                        Spacer(minLength: 4)
                        Text(item.percentText)
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(tone)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(item.name)，與班平均相差 \(item.percentText) 分")
                }
            }
            .frame(maxHeight: .infinity, alignment: .top)
        }
    }
}

private struct SubjectGradeRow: View {
    let subject: PocketSubjectGrade

    private var tone: Color {
        if subject.isFlunk { return PocketColor.negative }
        return subject.diffSign >= 0 ? PocketColor.positive : PocketColor.warning
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(subject.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(PocketColor.onSurface)
                if subject.isFlunk {
                    PocketChip(text: "不及格", foreground: PocketColor.negative, background: PocketColor.negative.opacity(0.12))
                }
                Spacer()
                Text(subject.diffText)
                    .font(.footnote.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(tone)
                Text(subject.scoreText)
                    .font(PocketFont.display(24, relativeTo: .title2))
                    .monospacedDigit()
                    .foregroundStyle(subject.isFlunk ? PocketColor.negative : PocketColor.onSurface)
                    .frame(minWidth: 40, alignment: .trailing)
            }
            if subject.fraction >= 0 {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(PocketColor.surfaceHigh)
                    Capsule()
                        .fill(subject.isFlunk ? PocketColor.negative : PocketColor.primary)
                        .frame(width: max(6, proxy.size.width * subject.fraction))
                    RoundedRectangle(cornerRadius: 1)
                        .fill(PocketColor.onSurface)
                        .frame(width: 2, height: 12)
                        .offset(x: proxy.size.width * subject.averageFraction - 1)
                }
                .frame(height: 6)
                .frame(maxHeight: .infinity)
            }
            .frame(height: 12)
            }
            HStack {
                Text("班平均 \(subject.classAverageText)")
                Spacer()
                Text("班排 \(subject.classRankText)")
            }
            .font(.caption)
            .monospacedDigit()
            .foregroundStyle(PocketColor.onSurfaceVariant)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .contentShape(.rect)
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("查看班級統計與走勢")
        .accessibilityLabel("\(subject.name) \(subject.fraction >= 0 ? "\(subject.scoreText) 分" : subject.scoreText)，班平均 \(subject.classAverageText)，班排 \(subject.classRankText)\(subject.isFlunk ? "，不及格" : "")")
    }
}

private struct AnalysisCard: View {
    let analysis: PocketAnalysis

    var body: some View {
        PocketCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("成績分析", systemImage: "text.magnifyingglass")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                Text(analysis.summary)
                    .font(.body)
                    .foregroundStyle(PocketColor.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                if !analysis.changes.isEmpty {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { chips }
                        VStack(alignment: .leading, spacing: 8) { chips }
                    }
                }
                if !analysis.improved.isEmpty || !analysis.declined.isEmpty {
                    PocketRowDivider()
                    ForEach(analysis.improved + analysis.declined, id: \.name) { item in
                        let up = !item.percentText.hasPrefix("-")
                        HStack(alignment: .firstTextBaseline) {
                            Image(systemName: up ? "arrow.up.right" : "arrow.down.right")
                                .font(.footnote.weight(.bold))
                                .foregroundStyle(up ? PocketColor.positive : PocketColor.negative)
                                .accessibilityHidden(true)
                            Text(item.name).font(.subheadline.weight(.medium)).foregroundStyle(PocketColor.onSurface)
                            Text(item.detail).font(.footnote).monospacedDigit().foregroundStyle(PocketColor.onSurfaceVariant)
                            Spacer(minLength: 4)
                            Text(item.percentText)
                                .font(.subheadline.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(up ? PocketColor.positive : PocketColor.negative)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
        .accessibilityIdentifier("grade-analysis")
    }

    @ViewBuilder private var chips: some View {
        ForEach(analysis.changes, id: \.self) { change in
            PocketChip(text: change, foreground: PocketColor.onPrimaryContainer, background: PocketColor.primaryContainer)
        }
    }
}
