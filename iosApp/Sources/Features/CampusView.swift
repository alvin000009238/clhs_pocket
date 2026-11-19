import PocketShared
import SwiftUI

struct CampusView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let campus = model.dataSource.campus(clock: model.clock)
        ScrollView {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                PocketColumns {
                    if let featured = campus.featured {
                        FeaturedAnnouncementCard(announcement: featured)
                    }
                    HStack(spacing: PocketMetrics.cardSpacing) {
                        CampusShortcut(
                            title: "公告",
                            detail: campus.unreadCount > 0 ? "\(campus.unreadCount) 則未讀" : "已全部讀完",
                            systemImage: "megaphone.fill",
                            route: .announcements
                        )
                        CampusShortcut(
                            title: "行事曆",
                            detail: campus.calendarHeadline,
                            systemImage: "calendar",
                            route: .calendar
                        )
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        PocketSectionHeader(title: "近期行事") {
                            PocketMoreLink(value: PocketRoute.calendar)
                        }
                        PocketCard(padding: 8) {
                            EventRows(events: Array(campus.events.prefix(4)))
                        }
                    }
                } trailing: {
                    VStack(alignment: .leading, spacing: 8) {
                        PocketSectionHeader(title: "最新公告") {
                            PocketMoreLink(value: PocketRoute.announcements)
                        }
                        PocketCard(padding: 8) {
                            AnnouncementRows(announcements: Array(campus.announcements.dropFirst().prefix(4)))
                        }
                    }
                }
            }
            .pocketPage()
        }
        .navigationTitle(PocketTab.campus.title)
    }
}

private struct FeaturedAnnouncementCard: View {
    let announcement: PocketAnnouncement

    var body: some View {
        NavigationLink(value: PocketRoute.announcements) {
            PocketCard(fill: PocketColor.tertiaryContainer, padding: 24) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        PocketChip(
                            text: announcement.isPinned ? "置頂公告" : "最新公告",
                            systemImage: announcement.isPinned ? "pin.fill" : nil,
                            foreground: PocketColor.tertiaryContainer,
                            background: PocketColor.onTertiaryContainer
                        )
                        Spacer()
                        Text(announcement.dateText)
                            .font(.footnote)
                            .monospacedDigit()
                    }
                    Text(announcement.title)
                        .font(PocketFont.display(24, relativeTo: .title2))
                        .multilineTextAlignment(.leading)
                        .lineSpacing(2)
                        .padding(.top, 16)
                    HStack {
                        Text("\(announcement.unit) · \(announcement.category)")
                            .font(.subheadline)
                        Spacer()
                        Image(systemName: "arrow.right")
                            .font(.subheadline.weight(.semibold))
                    }
                    .padding(.top, 12)
                }
                .foregroundStyle(PocketColor.onTertiaryContainer)
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

private struct CampusShortcut: View {
    let title: String
    let detail: String
    let systemImage: String
    let route: PocketRoute

    var body: some View {
        NavigationLink(value: route) {
            PocketCard(padding: 16) {
                VStack(alignment: .leading, spacing: 0) {
                    PocketIconTile(systemImage: systemImage)
                    Text(title)
                        .font(PocketFont.cardTitle)
                        .foregroundStyle(PocketColor.onSurface)
                        .padding(.top, 14)
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .padding(.top, 2)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

struct AnnouncementRows: View {
    let announcements: [PocketAnnouncement]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(announcements.enumerated()), id: \.element.id) { index, item in
                if index > 0 { PocketRowDivider().padding(.leading, 12) }
                HStack(alignment: .top, spacing: 10) {
                    Circle()
                        .fill(item.isUnread ? PocketColor.primary : .clear)
                        .frame(width: 8, height: 8)
                        .padding(.top, 7)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.title)
                            .font(.body.weight(item.isUnread ? .semibold : .regular))
                            .foregroundStyle(PocketColor.onSurface)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        Text("\(item.unit) · \(item.shortDateText)")
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                    Spacer(minLength: 8)
                    if item.isPinned {
                        Image(systemName: "pin.fill")
                            .font(.footnote)
                            .foregroundStyle(PocketColor.tertiary)
                            .padding(.top, 4)
                            .accessibilityLabel("置頂")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
                .accessibilityElement(children: .combine)
                .accessibilityValue(item.isUnread ? "未讀" : "")
            }
        }
    }
}

struct EventRows: View {
    let events: [PocketEventRow]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(events.enumerated()), id: \.element.id) { index, event in
                if index > 0 { PocketRowDivider().padding(.leading, 72) }
                HStack(spacing: 14) {
                    VStack(spacing: 0) {
                        Text(event.monthText)
                            .font(.caption2.weight(.semibold))
                        Text(event.dayText)
                            .font(PocketFont.display(20, relativeTo: .title3))
                            .monospacedDigit()
                    }
                    .foregroundStyle(event.isOngoing ? PocketColor.onPrimary : PocketColor.onPrimaryContainer)
                    .frame(width: 46, height: 46)
                    .background(
                        event.isOngoing ? PocketColor.primary : PocketColor.primaryContainer,
                        in: .rect(cornerRadius: 14, style: .continuous)
                    )
                    VStack(alignment: .leading, spacing: 3) {
                        Text(event.title)
                            .font(.body.weight(.medium))
                            .foregroundStyle(PocketColor.onSurface)
                        Text(event.subtitle)
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(event.isOngoing ? PocketColor.primary : PocketColor.onSurfaceVariant)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

struct AnnouncementListView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let campus = model.dataSource.campus(clock: model.clock)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(campus.unreadCount > 0 ? "\(campus.unreadCount) 則未讀 · 共 \(campus.announcements.count) 則" : "共 \(campus.announcements.count) 則")
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.horizontal, 4)
                PocketCard(padding: 8) {
                    AnnouncementRows(announcements: campus.announcements)
                }
            }
            .frame(maxWidth: 720)
            .pocketPage()
        }
        .navigationTitle("公告")
        .navigationBarTitleDisplayMode(.large)
    }
}

struct CalendarListView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let campus = model.dataSource.campus(clock: model.clock)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(campus.calendarHeadline)
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.horizontal, 4)
                PocketCard(padding: 8) {
                    EventRows(events: campus.events)
                }
            }
            .frame(maxWidth: 720)
            .pocketPage()
        }
        .navigationTitle("行事曆")
        .navigationBarTitleDisplayMode(.large)
    }
}
