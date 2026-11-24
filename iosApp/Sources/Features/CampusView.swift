import PocketShared
import SwiftUI

struct CampusView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let campus = model.dataSource.campus(clock: model.clock)
        let announcements = PocketCampusStore.shared.announcements(
            clock: model.clock, category: "全部", query: "", filter: .all,
            unreadIds: model.unreadAnnouncementIds, bookmarkedIds: model.bookmarkedAnnouncementIds
        )
        let unread = Int(PocketCampusStore.shared.unreadCount(clock: model.clock, unreadIds: model.unreadAnnouncementIds))
        let featured = announcements.first
        ScrollView {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                PocketColumns {
                    if let featured {
                        FeaturedAnnouncementCard(announcement: featured)
                    }
                    HStack(spacing: PocketMetrics.cardSpacing) {
                        CampusShortcut(
                            title: "公告",
                            detail: unread > 0 ? "\(unread) 則未讀" : "已全部讀完",
                            systemImage: "megaphone.fill",
                            route: .announcements,
                            badge: unread
                        )
                        CampusShortcut(
                            title: "行事曆",
                            detail: campus.calendarHeadline,
                            systemImage: "calendar",
                            route: .calendar
                        )
                    }
                    HStack(spacing: PocketMetrics.cardSpacing) {
                        CampusShortcut(title: "出缺勤", detail: model.isSignedIn ? "請假與缺曠" : "需要登入", systemImage: "person.badge.clock.fill", route: .attendance)
                        CampusShortcut(title: "獎懲", detail: model.isSignedIn ? "功過紀錄" : "需要登入", systemImage: "rosette", route: .merits)
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
                            AnnouncementRows(announcements: Array(announcements.dropFirst().prefix(5)))
                        }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        PocketSectionHeader(title: "學校連結") { EmptyView() }
                        PocketCard(padding: 8) {
                            CampusLinkRow(title: "學校網站", detail: "龍潭高中官方網站", systemImage: "globe",
                                          url: PocketAnnouncementFeed.shared.SCHOOL_WEBSITE_URL)
                            PocketRowDivider().padding(.leading, 60)
                            CampusLinkRow(title: "Google 行事曆", detail: "查看完整行事曆", systemImage: "calendar.badge.clock",
                                          url: PocketAnnouncementFeed.shared.SCHOOL_CALENDAR_URL)
                        }
                    }
                }
            }
            .pocketPage()
        }
        .navigationTitle(PocketTab.campus.title)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: PocketRoute.campusSearch) {
                    Label("搜尋公告與行事曆", systemImage: "magnifyingglass")
                }
                .accessibilityIdentifier("campus-search")
            }
        }
    }
}

private struct CampusLinkRow: View {
    @Environment(\.openURL) private var openURL
    let title: String
    let detail: String
    let systemImage: String
    let url: String

    var body: some View {
        Button {
            if let url = URL(string: url) { openURL(url) }
        } label: {
            HStack(spacing: 14) {
                PocketIconTile(systemImage: systemImage)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.medium)).foregroundStyle(PocketColor.onSurface)
                    Text(detail).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.right").font(.footnote.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("在瀏覽器開啟")
        .accessibilityIdentifier("campus-link-\(title)")
    }
}

private struct FeaturedAnnouncementCard: View {
    let announcement: PocketAnnouncement

    var body: some View {
        NavigationLink(value: PocketRoute.announcement(announcement.id)) {
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
    var badge = 0

    var body: some View {
        NavigationLink(value: route) {
            PocketCard(padding: 16) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top) {
                        PocketIconTile(systemImage: systemImage)
                        Spacer(minLength: 8)
                        if badge > 0 {
                            Text(badge, format: .number)
                                .font(.caption.weight(.bold))
                                .monospacedDigit()
                                .foregroundStyle(.white)
                                .padding(.horizontal, 7)
                                .frame(minWidth: 24, minHeight: 24)
                                .background(PocketColor.negative, in: .capsule)
                                .accessibilityHidden(true)
                        } else {
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                                .accessibilityHidden(true)
                        }
                    }
                    Text(title)
                        .lineLimit(1)
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
        .accessibilityIdentifier("campus-\(title)")
    }
}

struct AnnouncementRows: View {
    let announcements: [PocketAnnouncement]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(announcements.enumerated()), id: \.element.id) { index, item in
                if index > 0 { PocketRowDivider().padding(.leading, 12) }
                NavigationLink(value: PocketRoute.announcement(item.id)) {
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
                    if item.isBookmarked {
                        Image(systemName: "bookmark.fill")
                            .font(.footnote)
                            .foregroundStyle(PocketColor.primary)
                            .padding(.top, 4)
                            .accessibilityLabel("已收藏")
                    }
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
                .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityValue(item.isUnread ? "未讀" : "")
                .accessibilityIdentifier("announcement-\(item.id)")
            }
        }
    }
}

struct EventRows: View {
    let events: [PocketEventRow]
    /// When set, each row opens the event.
    var onSelect: ((String) -> Void)? = nil

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
                    .dynamicTypeSize(...DynamicTypeSize.xxLarge)
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
                .contentShape(.rect)
                .accessibilityElement(children: .combine)
                .modifier(EventRowTap(id: event.id, onSelect: onSelect))
            }
        }
    }
}

private struct EventRowTap: ViewModifier {
    let id: String
    let onSelect: ((String) -> Void)?

    func body(content: Content) -> some View {
        if let onSelect {
            Button { onSelect(id) } label: { content }
                .buttonStyle(.plain)
                .accessibilityIdentifier("event-\(id)")
        } else {
            content
        }
    }
}
