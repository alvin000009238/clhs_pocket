import CoreText
import SwiftUI
import WidgetKit

@main
struct PocketWidgetBundle: WidgetBundle {
    init() {
        WidgetFonts.register()
    }

    var body: some Widget {
        ScheduleWidget()
        LessonLiveActivity()
    }
}

/// Extensions do not always honour `UIAppFonts`, so the bundled faces are
/// registered explicitly; a failure only falls back to the system font.
enum WidgetFonts {
    static func register() {
        for name in ["Huninn-Regular", "Outfit-Bold-Subset"] {
            guard let url = Bundle.main.url(forResource: name, withExtension: "ttf") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

struct ScheduleWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: PocketPlatformID.scheduleWidgetKind, provider: ScheduleTimelineProvider()) { entry in
            ScheduleWidgetView(glance: entry.glance)
                .widgetURL(PocketPlatformID.scheduleLink)
        }
        .configurationDisplayName("課表")
        .description("查看現在與接下來的課程。")
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge,
            .accessoryRectangular, .accessoryCircular, .accessoryInline,
        ])
    }
}

struct ScheduleEntry: TimelineEntry {
    let date: Date
    let glance: ScheduleGlance
}

/// Reads the App Group snapshot and nothing else: no network, Keychain or
/// shared defaults. Entries fall on the boundaries the shared resolver chose.
struct ScheduleTimelineProvider: TimelineProvider {
    var store = ScheduleSnapshotStore.shared

    func placeholder(in context: Context) -> ScheduleEntry {
        ScheduleEntry(date: .now, glance: ScheduleSnapshot.sample().glance(at: .now))
    }

    func getSnapshot(in context: Context, completion: @escaping (ScheduleEntry) -> Void) {
        let now = Date()
        let snapshot = context.isPreview ? ScheduleSnapshot.sample(now: now) : store.load()
        completion(ScheduleEntry(date: now, glance: snapshot?.glance(at: now) ?? ScheduleGlance(state: .empty, date: now)))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ScheduleEntry>) -> Void) {
        let now = Date()
        guard let snapshot = store.load() else {
            // The app reloads timelines whenever it publishes or clears the snapshot.
            completion(Timeline(entries: [ScheduleEntry(date: now, glance: ScheduleGlance(state: .empty, date: now))], policy: .never))
            return
        }
        let entries = snapshot.timelineDates(from: now).map { ScheduleEntry(date: $0, glance: snapshot.glance(at: $0)) }
        completion(Timeline(entries: entries, policy: snapshot.timelineContinues(from: now) ? .atEnd : .never))
    }
}
