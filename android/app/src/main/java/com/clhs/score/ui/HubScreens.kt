@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui

import com.clhs.score.ui.navigation.navigationContentPadding

import com.clhs.score.ui.theme.PocketVisual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clhs.score.data.FakeData
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.data.announcementBadgeText

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CampusScreen(
    dynamicColor: Boolean = false,
    state: OverviewState,
    unreadAnnouncementCount: Int = 0,
    onOpenPersonal: () -> Unit,
    showUpdateBadge: Boolean = false,
    onOpenAnnouncements: () -> Unit,
    onOpenAnnouncement: (SchoolAnnouncement) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenAttendance: () -> Unit,
    onOpenMerit: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSchoolWebsite: (() -> Unit)?,
) {
    val rootScroll = rememberScrollState()
    com.clhs.score.ui.navigation.RegisterRootScroll(rootScroll)
    RootFloatingScaffold(
        dynamicColor = dynamicColor,
        actions = {
            IconButton(onClick = hapticClick(onOpenSearch)) {
                OutlinedRoundedSymbol("search", tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "搜尋公告與行事曆")
            }
            AccountIconButton(onClick = onOpenPersonal, showUpdateBadge = showUpdateBadge,
                animateShape = false)
        },
    ) { padding, header ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rootScroll)
                .navigationContentPadding()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            header()
            Column(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().rootBodyMotion(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val unreadText = announcementBadgeText(unreadAnnouncementCount)
                val pinned = state.announcements.firstOrNull(SchoolAnnouncement::isPinned)
                PinnedAnnouncementCard(
                    announcement = pinned,
                    isLoading = state.announcementsLoading,
                    isFailed = state.announcementsFailed,
                    onClick = if (pinned == null) onOpenAnnouncements else { { onOpenAnnouncement(pinned) } },
                )
                CampusTileRow {
                    val hasUnread = unreadAnnouncementCount > 0
                    CampusTile(
                        title = "公告",
                        description = if (hasUnread) "$unreadText 則未讀" else "學校最新消息",
                        icon = "campaign",
                        onClick = onOpenAnnouncements,
                        hasUnread = hasUnread,
                    )
                    CampusTile(
                        title = "行事曆",
                        description = "${state.now.year} 年 ${state.now.monthValue} 月",
                        icon = "calendar_today",
                        onClick = onOpenCalendar,
                    )
                }
                CampusTileRow {
                    CampusTile(
                        title = "出缺勤",
                        description = "請假與缺曠",
                        icon = "person",
                        onClick = onOpenAttendance,
                    )
                    CampusTile(
                        title = "獎懲",
                        description = "功過紀錄",
                        icon = "thumbs_up_down",
                        onClick = onOpenMerit,
                    )
                }
                if (onOpenSchoolWebsite != null) {
                    CampusModuleCard(
                        title = "欣河智慧校園平台",
                        description = "前往校務系統",
                        icon = "local_library",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        onClick = onOpenSchoolWebsite,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PinnedAnnouncementCard(
    announcement: SchoolAnnouncement?,
    isLoading: Boolean,
    isFailed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 176.dp),
        shape = PocketVisual.heroShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (announcement != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ) {
                        Text(
                            "置頂公告",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (announcement.date.isNotBlank()) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(announcement.date, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text(
                text = when {
                    announcement != null -> announcement.title
                    isLoading -> "正在取得置頂公告"
                    isFailed -> "公告暫時無法更新"
                    else -> "目前沒有置頂公告"
                },
                style = MaterialTheme.typography.headlineSmallEmphasized.copy(lineBreak = LineBreak.Heading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = announcement
                        ?.let { listOf(it.unit.ifBlank { it.issuer }, it.category).filter(String::isNotBlank).joinToString(" · ") }
                        ?.ifBlank { null }
                        ?: "查看全部學校公告",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedRoundedSymbol("arrow_forward", size = 24.dp, contentDescription = null)
            }
        }
    }
}

@Composable
private fun CampusTileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CampusTile(
    title: String,
    description: String,
    icon: String,
    onClick: () -> Unit,
    hasUnread: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 128.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                PocketOptionIcon(icon)
                if (hasUnread) {
                    Badge(modifier = Modifier.align(Alignment.TopEnd).size(8.dp))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CampusModuleCard(
    title: String,
    description: String,
    icon: String,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .heightIn(min = 104.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PocketOptionIcon(icon)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedRoundedSymbol("arrow_forward", size = 24.dp, contentDescription = null)
        }
    }
}

@Preview(name = "校園 · Compact", widthDp = 390, heightDp = 844)
@Preview(name = "校園 · Dark", widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "校園 · Expanded", widthDp = 1100, heightDp = 900)
@Preview(name = "校園 · Large Text", widthDp = 320, heightDp = 1000, fontScale = 1.5f)
@Composable
private fun CampusScreenPreview() {
    com.clhs.score.ui.theme.ScoreTheme {
        CampusScreen(
            state = OverviewState(isInitialLoading = false, announcements = FakeData.announcementPage.announcements),
            unreadAnnouncementCount = 3,
            onOpenPersonal = {},
            onOpenAnnouncements = {},
            onOpenAnnouncement = {},
            onOpenCalendar = {},
            onOpenAttendance = {},
            onOpenMerit = {},
            onOpenSearch = {},
            onOpenSchoolWebsite = {},
        )
    }
}
