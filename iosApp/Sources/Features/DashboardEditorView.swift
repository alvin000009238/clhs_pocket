import PocketShared
import SwiftUI

/// Add, remove, reorder and resize home widgets. Every change goes through the
/// shared `PocketDashboard`, which owns validation and packing.
struct DashboardEditorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let widgets = model.widgets(columns: 4)
        NavigationStack {
            List {
                Section {
                    ForEach(Array(widgets.enumerated()), id: \.element.id) { index, widget in
                        row(widget, index: index, count: widgets.count)
                    }
                    .onMove { source, destination in
                        guard let from = source.first else { return }
                        let target = destination > from ? destination - 1 : destination
                        model.editDashboard { $0.move(layout: $1, id: widgets[from].id, index: Int32(target)) }
                    }
                    .onDelete { offsets in
                        for index in offsets {
                            model.editDashboard { $0.remove(layout: $1, id: widgets[index].id) }
                        }
                    }
                } header: {
                    Text("首頁上的小工具")
                } footer: {
                    Text("長按拖曳可以調整順序，向左滑可移除。")
                }

                Section("新增小工具") {
                    ForEach(PocketDashboard.shared.widgetTypes(layout: model.dashboardLayout)) { type in
                        Button {
                            model.editDashboard { $0.add(layout: $1, type: type.type, clock: model.clock) }
                        } label: {
                            HStack(spacing: 12) {
                                PocketIconTile(systemImage: type.symbol, size: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(type.title).font(.body.weight(.medium)).foregroundStyle(PocketColor.onSurface)
                                    Text(type.canAdd ? type.detail : "已在首頁上")
                                        .font(.footnote)
                                        .foregroundStyle(PocketColor.onSurfaceVariant)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: type.canAdd ? "plus.circle.fill" : "checkmark.circle")
                                    .font(.title3)
                                    .foregroundStyle(type.canAdd ? PocketColor.primary : PocketColor.outline)
                                    .accessibilityHidden(true)
                            }
                        }
                        .disabled(!type.canAdd)
                        .accessibilityIdentifier("add-widget-\(type.type)")
                        .accessibilityHint(type.canAdd ? "加入首頁" : "")
                    }
                }

                Section {
                    Button("還原預設配置", role: .destructive) {
                        model.dashboardLayout = PocketDashboard.shared.defaultLayout()
                    }
                    .accessibilityIdentifier("dashboard-reset")
                }
            }
            .scrollContentBackground(.hidden)
            .background(PocketColor.background.ignoresSafeArea())
            .navigationTitle("編輯首頁")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("dashboard-done")
                }
            }
        }
    }

    private func row(_ widget: PocketWidget, index: Int, count: Int) -> some View {
        NavigationLink {
            WidgetSettingsView(widgetID: widget.id)
        } label: {
            HStack(spacing: 12) {
                PocketIconTile(systemImage: widget.symbol, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(widget.title).font(.body.weight(.medium)).foregroundStyle(PocketColor.onSurface)
                    Text("\(widget.summary) · \(widget.sizes.first { $0.isSelected }?.label ?? "")")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .lineLimit(2)
                }
            }
        }
        .accessibilityIdentifier("dashboard-row-\(index)")
        .accessibilityActions {
            if index > 0 {
                Button("上移") { model.editDashboard { $0.move(layout: $1, id: widget.id, index: Int32(index - 1)) } }
            }
            if index < count - 1 {
                Button("下移") { model.editDashboard { $0.move(layout: $1, id: widget.id, index: Int32(index + 1)) } }
            }
            Button("移除") { model.editDashboard { $0.remove(layout: $1, id: widget.id) } }
        }
    }
}

/// Size, order and per-kind settings for one widget.
struct WidgetSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let widgetID: String
    var showsDone = false

    @State private var customTitle = ""
    @State private var customDate = Date()
    @State private var customHasTime = false
    @State private var isCustom = false
    @State private var loaded = false

    var body: some View {
        let widgets = model.widgets(columns: 4)
        let index = widgets.firstIndex { $0.id == widgetID }
        Form {
            if let index {
                let widget = widgets[index]
                Section("尺寸") {
                    Picker("尺寸", selection: Binding(
                        get: { widget.sizes.first { $0.isSelected } ?? widget.sizes[0] },
                        set: { size in
                            model.editDashboard { $0.resize(layout: $1, id: widgetID, columns: size.columns, rows: size.rows) }
                        }
                    )) {
                        ForEach(widget.sizes, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("widget-size")
                }

                settings(widget)

                Section("順序") {
                    Button("往前移", systemImage: "arrow.up") {
                        model.editDashboard { $0.move(layout: $1, id: widgetID, index: Int32(index - 1)) }
                    }
                    .disabled(index == 0)
                    .accessibilityIdentifier("widget-move-up")
                    Button("往後移", systemImage: "arrow.down") {
                        model.editDashboard { $0.move(layout: $1, id: widgetID, index: Int32(index + 1)) }
                    }
                    .disabled(index == widgets.count - 1)
                    .accessibilityIdentifier("widget-move-down")
                }

                Section {
                    Button("從首頁移除", systemImage: "minus.circle", role: .destructive) {
                        model.editDashboard { $0.remove(layout: $1, id: widgetID) }
                        dismiss()
                    }
                    .accessibilityIdentifier("widget-remove")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.background.ignoresSafeArea())
        .navigationTitle(index.map { widgets[$0].title } ?? "小工具")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsDone {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
        .onAppear {
            guard !loaded, let index else { return }
            loaded = true
            let widget = widgets[index]
            isCustom = !widget.customTitle.isEmpty
            customTitle = widget.customTitle
            customHasTime = !widget.customTime.isEmpty
            if let date = Self.formatter(customHasTime).date(from: widget.customDate + (customHasTime ? " \(widget.customTime)" : "")) {
                customDate = date
            }
        }
    }

    @ViewBuilder
    private func settings(_ widget: PocketWidget) -> some View {
        switch widget.settings {
        case .weather:
            choices("地點", PocketDashboard.shared.weatherLocations(), selected: widget.selectionId) { id in
                model.editDashboard { $0.setWeatherLocation(layout: $1, id: widgetID, locationId: id) }
            }
        case .youbike:
            choices("站點", PocketDashboard.shared.youBikeStations(), selected: widget.selectionId) { id in
                model.editDashboard { $0.setYouBikeStation(layout: $1, id: widgetID, stationId: id) }
            }
        case .countdown:
            Section("倒數目標") {
                Picker("倒數目標", selection: $isCustom) {
                    Text("學校行程").tag(false)
                    Text("自訂日期").tag(true)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("countdown-mode")
            }
            if isCustom {
                Section {
                    TextField("名稱，例如：學測", text: $customTitle)
                        .accessibilityIdentifier("countdown-title")
                    DatePicker("日期", selection: $customDate, displayedComponents: customHasTime ? [.date, .hourAndMinute] : [.date])
                        // Stored as a Taipei calendar date, so it is picked in Taipei time too.
                        .environment(\.timeZone, TimeZone(identifier: "Asia/Taipei") ?? .current)
                    Toggle("指定時間", isOn: $customHasTime)
                    Button("套用") { applyCustom() }
                        .disabled(customTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                        .accessibilityIdentifier("countdown-apply")
                }
            } else {
                choices("行程", PocketDashboard.shared.countdownEvents(clock: model.clock), selected: widget.selectionId) { id in
                    model.editDashboard { $0.setSchoolEvent(layout: $1, id: widgetID, eventId: id, clock: model.clock) }
                }
            }
        case .list:
            Section {
                Stepper(value: Binding(
                    get: { Int(widget.limit) },
                    set: { limit in model.editDashboard { $0.setListLimit(layout: $1, id: widgetID, limit: Int32(limit)) } }
                ), in: 1...20) {
                    Text("最多顯示 \(widget.limit) 則")
                }
                .accessibilityIdentifier("widget-limit")
            } header: {
                Text("顯示數量")
            } footer: {
                Text("尺寸為「寬」時只顯示 1 則。")
            }
        default:
            EmptyView()
        }
    }

    private func choices(_ title: String, _ items: [PocketChoice], selected: String, pick: @escaping (String) -> Void) -> some View {
        Section(title) {
            ForEach(items) { item in
                Button {
                    pick(item.id)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.title).foregroundStyle(PocketColor.onSurface)
                            if !item.detail.isEmpty {
                                Text(item.detail).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                            }
                        }
                        Spacer()
                        if item.id == selected {
                            Image(systemName: "checkmark").foregroundStyle(PocketColor.primary).accessibilityHidden(true)
                        }
                    }
                }
                .accessibilityAddTraits(item.id == selected ? .isSelected : [])
                .accessibilityIdentifier("choice-\(item.id)")
            }
        }
    }

    private func applyCustom() {
        let date = Self.formatter(false).string(from: customDate)
        let time = customHasTime ? String(Self.formatter(true).string(from: customDate).suffix(5)) : ""
        model.editDashboard {
            $0.setCustomCountdown(layout: $1, id: widgetID, title: customTitle, date: date, time: time)
        }
    }

    private static func formatter(_ withTime: Bool) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Taipei")
        formatter.dateFormat = withTime ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd"
        return formatter
    }
}
