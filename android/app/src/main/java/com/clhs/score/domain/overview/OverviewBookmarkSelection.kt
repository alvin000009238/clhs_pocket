package com.clhs.score.domain.overview

import com.clhs.score.data.AnnouncementBookmark

internal fun OverviewState.importantAnnouncements(
    config: OverviewWidgetConfig.ListItems,
    unreadIds: Set<String>,
    bookmarks: List<AnnouncementBookmark>,
): List<OverviewItem> = importantAnnouncementsFrom(config, unreadIds, bookmarks.map { it.toAnnouncement() })
