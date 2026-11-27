import PocketShared
import SwiftUI

struct CampusView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let announcements = PocketCampusStore.shared.announcements(
            clock: model.clock, category: "全部", query: "", filter: .all,
            unreadIds: model.unreadAnnouncementIds, bookmarkedIds: model.bookmarkedAnnouncementIds
        )
        let unread = Int(PocketCampusStore.shared.unreadCount(clock: model.clock, unreadIds: model.unreadAnnouncementIds))
        let unreadText = unread > 99 ? "99+" : "\(unread)"
        let pinned = announcements.first(where: \.isPinned)
        let failed = model.usesLiveData && announcements.isEmpty && model.live?.lastError != nil
        ScrollView {
            VStack(spacing: PocketMetrics.cardSpacing) {
                PinnedAnnouncementCard(
                    announcement: pinned,
                    isLoading: model.usesLiveData && announcements.isEmpty && !failed,
                    isFailed: failed
                )
                Grid(horizontalSpacing: PocketMetrics.cardSpacing, verticalSpacing: PocketMetrics.cardSpacing) {
                    GridRow {
                        CampusShortcut(
                            title: "公告",
                            detail: unread > 0 ? "\(unreadText) 則未讀" : "學校最新消息",
                            systemImage: "megaphone",
                            route: .announcements,
                            showsDot: unread > 0
                        )
                        CampusShortcut(title: "行事曆", detail: "\(model.clock.year) 年 \(model.clock.month) 月", systemImage: "calendar", route: .calendar)
                    }
                    GridRow {
                        CampusShortcut(title: "出缺勤", detail: "請假與缺曠", systemImage: "person", route: .attendance)
                        CampusShortcut(title: "獎懲", detail: "功過紀錄", systemImage: "hand.thumbsup", route: .merits)
                    }
                }
            }
            .frame(maxWidth: 720)
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

/// Android's `PinnedAnnouncementCard`: the pinned announcement, or a state line that opens the list.
private struct PinnedAnnouncementCard: View {
    let announcement: PocketAnnouncement?
    let isLoading: Bool
    let isFailed: Bool

    var body: some View {
        NavigationLink(value: announcement.map { PocketRoute.announcement($0.id) } ?? PocketRoute.announcements) {
            VStack(alignment: .leading, spacing: 12) {
                if let announcement {
                    HStack(spacing: 12) {
                        Text("置頂公告")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(PocketColor.tertiaryContainer)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 4)
                            .background(PocketColor.tertiary, in: .capsule)
                        Spacer(minLength: 8)
                        Text(announcement.dateText)
                            .font(.subheadline.weight(.medium))
                            .monospacedDigit()
                    }
                }
                Text(announcement?.title ?? (isLoading ? "正在取得置頂公告" : isFailed ? "公告暫時無法更新" : "目前沒有置頂公告"))
                    .font(PocketFont.display(24, relativeTo: .title2))
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Spacer(minLength: 0)
                HStack(alignment: .firstTextBaseline) {
                    Text(announcement.map { [$0.unit, $0.category].filter { !$0.isEmpty }.joined(separator: " · ") }
                        .flatMap { $0.isEmpty ? nil : $0 } ?? "查看全部學校公告")
                        .font(.subheadline)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    Image(systemName: "arrow.right")
                        .font(.body.weight(.semibold))
                        .accessibilityHidden(true)
                }
            }
            .foregroundStyle(PocketColor.onTertiaryContainer)
            .padding(24)
            .frame(maxWidth: .infinity, minHeight: 176, alignment: .topLeading)
            .background(PocketColor.tertiaryContainer, in: .rect(cornerRadius: 32, style: .continuous))
            .contentShape(.rect(cornerRadius: 32, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("campus-pinned")
    }
}

/// One of the hub's equal-height tiles: icon top-left, with a dot on its corner for unread items, then the
/// title and subtitle at the bottom. The dot is decorative; the subtitle already says how many are unread.
private struct CampusShortcut: View {
    let title: String
    let detail: String
    let systemImage: String
    let route: PocketRoute
    var showsDot = false
    @ScaledMetric(relativeTo: .title3) private var iconSize: CGFloat = 56
    @ScaledMetric(relativeTo: .title3) private var dotSize: CGFloat = 12

    var body: some View {
        NavigationLink(value: route) {
            VStack(alignment: .leading, spacing: 12) {
                PocketIconTile(systemImage: systemImage, size: iconSize)
                    .overlay(alignment: .topTrailing) {
                        if showsDot {
                            Circle()
                                .fill(PocketColor.negative)
                                .frame(width: dotSize, height: dotSize)
                                .offset(x: dotSize * 0.25, y: -dotSize * 0.25)
                                .accessibilityHidden(true)
                        }
                    }
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(PocketFont.display(22, relativeTo: .title3))
                        .foregroundStyle(PocketColor.onSurface)
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 128, maxHeight: .infinity, alignment: .topLeading)
            .background(PocketColor.surface, in: .rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))
            .contentShape(.rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))
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
