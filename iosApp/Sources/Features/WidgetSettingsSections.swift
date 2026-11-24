import PocketShared
import SwiftUI

/// Android waits this long after the last keystroke before searching.
private let searchDelay: Duration = .milliseconds(350)

/// One selectable result row; the selected one carries a checkmark and the selected trait.
private struct ChoiceRow: View {
    let title: String
    let detail: String
    let selected: Bool
    let identifier: String
    let pick: () -> Void

    var body: some View {
        Button(action: pick) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(PocketColor.onSurface)
                    if !detail.isEmpty {
                        Text(detail).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 0)
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(PocketColor.primary).accessibilityHidden(true)
                }
            }
            .contentShape(.rect)
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(identifier)
    }
}

private struct SearchField: View {
    let prompt: String
    @Binding var text: String
    let identifier: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(PocketColor.onSurfaceVariant).accessibilityHidden(true)
            TextField(prompt, text: $text)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityLabel(prompt)
                .accessibilityIdentifier(identifier)
            if !text.isEmpty {
                Button("清除搜尋", systemImage: "xmark.circle.fill") { text = "" }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
        }
    }
}

private struct SelectedLine: View {
    let choice: PocketChoice?

    var body: some View {
        if let choice {
            VStack(alignment: .leading, spacing: 2) {
                Text("已選擇：\(choice.title)").font(.body.weight(.medium)).foregroundStyle(PocketColor.onSurface)
                if !choice.detail.isEmpty {
                    Text(choice.detail).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("widget-selection")
        }
    }
}

/// Weather: any Taiwan township from the offline catalogue, and the widget's own data source.
struct WeatherWidgetSettings: View {
    @Environment(AppModel.self) private var model
    let widget: PocketWidget
    @State private var query = ""
    @State private var results: [PocketChoice] = []
    @State private var searching = false
    @State private var showingKey = false
    @State private var hasKey: Bool?

    var body: some View {
        Section {
            SelectedLine(choice: widget.selection)
            SearchField(prompt: "搜尋鄉鎮市區，例如：中壢", text: $query, identifier: "weather-search")
            if searching {
                ProgressView().frame(maxWidth: .infinity)
            } else if !query.trimmingCharacters(in: .whitespaces).isEmpty && results.isEmpty {
                Text("找不到符合的結果").foregroundStyle(PocketColor.onSurfaceVariant).accessibilityIdentifier("search-empty")
            }
            ForEach(results) { item in
                ChoiceRow(title: item.title, detail: item.detail, selected: item.id == widget.selectionId, identifier: "choice-\(item.id)") {
                    model.editDashboard { $0.setWeather(layout: $1, id: widget.id, locationId: item.id, source: widget.weatherSource) }
                    query = ""
                }
            }
        } header: {
            Text("地點")
        } footer: {
            Text("可搜尋臺灣各縣市的鄉鎮市區。")
        }
        .task(id: query) {
            let input = query.trimmingCharacters(in: .whitespaces)
            guard !input.isEmpty else {
                results = []
                searching = false
                return
            }
            searching = true
            defer { searching = false }
            do { try await Task.sleep(for: searchDelay) } catch { return }
            results = model.searchWeatherLocations(input)
        }

        Section {
            sourceRow("OPEN_METEO", title: "Open-Meteo", detail: "免設定，全球天氣模型", enabled: true)
            sourceRow("CWA", title: "中央氣象署", detail: "臺灣鄉鎮預報，需要授權碼", enabled: widget.supportsCwa) {
                Button("設定中央氣象署授權碼", systemImage: "key") { showingKey = true }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .foregroundStyle(PocketColor.primary)
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityIdentifier("weather-cwa-key")
            }
            if widget.weatherSource == "CWA" && hasKey == false {
                Text("尚未設定授權碼，請點右側按鈕設定。")
                    .font(.footnote).foregroundStyle(PocketColor.negative)
                    .accessibilityIdentifier("weather-cwa-missing")
            }
        } header: {
            Text("資料來源")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if !widget.supportsCwa { Text("這個地點沒有中央氣象署的鄉鎮預報。") }
                Text(widget.weatherSource == "CWA"
                     ? "資料來源／交通部中央氣象署；依政府資料開放授權條款提供。"
                     : "資料來源／Open-Meteo.com；依 CC BY 4.0 提供。")
            }
        }
        .task(id: showingKey) { if !showingKey { hasKey = await model.hasCwaKey() } }
        .sheet(isPresented: $showingKey) {
            NavigationStack {
                Form { CwaKeySection() }
                    .scrollContentBackground(.hidden)
                    .background(PocketColor.background.ignoresSafeArea())
                    .navigationTitle("中央氣象署授權碼")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) { Button("完成") { showingKey = false } }
                    }
            }
        }
    }

    private func sourceRow(
        _ id: String, title: String, detail: String, enabled: Bool,
        @ViewBuilder trailing: () -> some View = { EmptyView() }
    ) -> some View {
        HStack(spacing: 8) {
            ChoiceRow(title: title, detail: detail, selected: widget.weatherSource == id, identifier: "weather-source-\(id)") {
                model.editDashboard { $0.setWeather(layout: $1, id: widget.id, locationId: widget.selectionId, source: id) }
            }
            .buttonStyle(.borderless)
            .disabled(!enabled)
            trailing()
        }
    }
}

/// YouBike: searches the public station list (demo stations in demo mode), like Android's sheet.
struct YouBikeWidgetSettings: View {
    @Environment(AppModel.self) private var model
    let widget: PocketWidget
    @State private var query = ""
    @State private var retry = 0
    @State private var results: [YouBikeStation] = []
    @State private var searching = false
    @State private var failed = false

    var body: some View {
        Section {
            SelectedLine(choice: widget.selection)
            SearchField(prompt: "搜尋站名、行政區或地址", text: $query, identifier: "youbike-search")
            if searching {
                ProgressView().frame(maxWidth: .infinity)
            } else if failed {
                HStack {
                    Text("搜尋失敗，請重試").foregroundStyle(PocketColor.negative)
                    Spacer()
                    Button("重試") { retry += 1 }.buttonStyle(.borderless).accessibilityIdentifier("search-retry")
                }
            } else if !query.trimmingCharacters(in: .whitespaces).isEmpty && results.isEmpty {
                Text("找不到符合的結果").foregroundStyle(PocketColor.onSurfaceVariant).accessibilityIdentifier("search-empty")
            }
            ForEach(results, id: \.id) { station in
                ChoiceRow(
                    title: station.name,
                    detail: [station.district, station.address].filter { !$0.isEmpty }.joined(separator: " · "),
                    selected: station.id == widget.selectionId,
                    identifier: "choice-\(station.id)"
                ) {
                    model.editDashboard { $0.selectYouBikeStation(layout: $1, id: widget.id, station: station) }
                    query = ""
                }
            }
        } header: {
            Text("站點")
        } footer: {
            Text(model.usesLiveData ? "搜尋 YouBike 公開資料中的所有站點。" : "示範資料只包含桃園地區的幾個站點，例如「中壢」或「桃園」。")
        }
        .task(id: "\(query)#\(retry)") {
            let input = query.trimmingCharacters(in: .whitespaces)
            failed = false
            guard !input.isEmpty else {
                results = []
                searching = false
                return
            }
            searching = true
            defer { searching = false }
            do { try await Task.sleep(for: searchDelay) } catch { return }
            do {
                results = try await model.searchYouBikeStations(input)
            } catch {
                guard !Task.isCancelled, PocketError(error).kind != .cancelled else { return }
                results = []
                failed = true
            }
        }
    }
}

/// School-event countdown: the calendar's upcoming events, filtered by a search like Android's.
struct CountdownEventChoices: View {
    @Environment(AppModel.self) private var model
    let widget: PocketWidget
    @State private var query = ""

    var body: some View {
        let events = PocketDashboard.shared.countdownEvents(clock: model.clock)
        let input = query.trimmingCharacters(in: .whitespaces)
        let shown = input.isEmpty ? events : events.filter { $0.title.localizedCaseInsensitiveContains(input) }
        Section {
            if !widget.selectionId.isEmpty && !events.contains(where: { $0.id == widget.selectionId }) {
                Text("原行程已不在資料中，可保留設定或重新選擇")
                    .font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
            }
            SearchField(prompt: "搜尋行事曆", text: $query, identifier: "countdown-search")
            if shown.isEmpty {
                Text("找不到符合的行程").foregroundStyle(PocketColor.onSurfaceVariant)
            }
            ForEach(shown) { item in
                ChoiceRow(title: item.title, detail: item.detail, selected: item.id == widget.selectionId, identifier: "choice-\(item.id)") {
                    model.editDashboard { $0.setSchoolEvent(layout: $1, id: widget.id, eventId: item.id, clock: model.clock) }
                }
            }
        } header: {
            Text("行程")
        }
    }
}

/// Announcements: which kind of announcement fills the list first.
struct AnnouncementPrioritySettings: View {
    @Environment(AppModel.self) private var model
    let widget: PocketWidget

    var body: some View {
        let order = widget.priorities
        Section {
            ForEach(Array(order.enumerated()), id: \.element.id) { index, item in
                HStack(spacing: 12) {
                    Text("\(index + 1)").font(.body.monospacedDigit().weight(.semibold)).foregroundStyle(PocketColor.primary)
                        .frame(minWidth: 20)
                    Text(item.title).foregroundStyle(PocketColor.onSurface)
                    Spacer(minLength: 0)
                    Button("提高「\(item.title)」優先順序", systemImage: "chevron.up") { move(order, index, by: -1) }
                        .disabled(index == 0)
                        .accessibilityIdentifier("priority-up-\(item.id)")
                    Button("降低「\(item.title)」優先順序", systemImage: "chevron.down") { move(order, index, by: 1) }
                        .disabled(index == order.count - 1)
                        .accessibilityIdentifier("priority-down-\(item.id)")
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("第 \(index + 1) 優先：\(item.title)")
                .accessibilityActions {
                    if index > 0 { Button("提高優先順序") { move(order, index, by: -1) } }
                    if index < order.count - 1 { Button("降低優先順序") { move(order, index, by: 1) } }
                }
                .accessibilityIdentifier("priority-\(item.id)")
            }
        } header: {
            Text("優先順序")
        } footer: {
            Text("越上方越優先；其餘名額依發布時間補滿。")
        }
    }

    private func move(_ order: [PocketChoice], _ index: Int, by offset: Int) {
        var ids = order.map(\.id)
        ids.swapAt(index, index + offset)
        model.editDashboard { $0.setAnnouncementPriority(layout: $1, id: widget.id, order: ids) }
    }
}

/// Calendar: optionally hides events that last longer than a number of days.
struct LongEventSettings: View {
    @Environment(AppModel.self) private var model
    let widget: PocketWidget

    var body: some View {
        let days = Int(widget.maxEventDurationDays)
        Section {
            Toggle("隱藏長行程", isOn: Binding(
                get: { days > 0 },
                set: { on in model.editDashboard { $0.setMaxEventDuration(layout: $1, id: widget.id, days: on ? 7 : 0) } }
            ))
            .accessibilityIdentifier("calendar-hide-long")
            if days > 0 {
                Stepper(value: Binding(
                    get: { days },
                    set: { value in model.editDashboard { $0.setMaxEventDuration(layout: $1, id: widget.id, days: Int32(value)) } }
                ), in: 1...365) {
                    Text("超過 \(days) 天不顯示")
                }
                .accessibilityIdentifier("calendar-max-days")
            }
        } footer: {
            Text("依完整行程長度判斷；相同天數仍顯示。")
        }
    }
}
