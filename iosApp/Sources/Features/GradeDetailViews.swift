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
                .accessibilityLabel("本學期走勢：" + detail.trend.map { "\($0.examName) \($0.averageText) 分" }.joined(separator: "，"))
            }
        }
    }

    private func legend(_ text: String, _ color: Color) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text).font(.caption).foregroundStyle(PocketColor.onSurfaceVariant)
        }
        .accessibilityHidden(true)
    }
}

private struct StandardsCard: View {
    let detail: PocketSubjectDetail

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
                        Circle()
                            .fill(PocketColor.primary)
                            .stroke(PocketColor.surfaceLow, lineWidth: 3)
                            .frame(width: 18, height: 18)
                            .offset(x: min(max(proxy.size.width * detail.score / 100 - 9, 0), proxy.size.width - 18))
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
                            if detail.score >= mark.value, index == detail.standards.firstIndex(where: { detail.score >= $0.value }) {
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
    @State private var simulator: PocketSimulator
    @State private var revision = 0
    @State private var showingCredits = false

    init(examValue: String) {
        self.examValue = examValue
        _simulator = State(initialValue: PocketGrades.shared.simulator(examValue: examValue))
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
            CreditsSheet(simulator: simulator, revision: $revision)
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
                    if state.deltaSign != 0 {
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
                .accessibilityIdentifier("simulator-credits")
            }
        }
    }
}

private struct SimulatorRow: View {
    let row: PocketSimulatorRow
    let index: Int
    let edit: ((PocketSimulator) -> Void) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(row.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(PocketColor.onSurface)
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

    var body: some View {
        let state = simulator.state()
        NavigationStack {
            Form {
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
                                simulator.setCredits(name: row.name, value: Int32(value))
                                revision += 1
                            }
                        ), in: 1...4) {
                            HStack {
                                Text(row.name)
                                Spacer()
                                Text("\(row.credits)").monospacedDigit().foregroundStyle(PocketColor.onSurfaceVariant)
                            }
                        }
                        .accessibilityValue("\(row.credits) 學分")
                        .accessibilityIdentifier("credit-stepper-\(index)")
                    }
                }
                Section {
                    Button("依校方總分推算學分", systemImage: "wand.and.stars") {
                        simulator.inferCredits()
                        revision += 1
                    }
                    .accessibilityIdentifier("credits-infer")
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
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("credits-done")
                }
            }
        }
    }
}
