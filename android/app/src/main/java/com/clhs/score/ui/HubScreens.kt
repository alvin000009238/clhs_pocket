package com.clhs.score.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.data.announcementBadgeText

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CampusScreen(
    state: OverviewState,
    unreadAnnouncementCount: Int = 0,
    onOpenPersonal: () -> Unit,
    showUpdateBadge: Boolean = false,
    onOpenAnnouncements: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSchoolWebsite: () -> Unit,
) {
    Scaffold(
        topBar = {
            RootTopAppBar(
                title = "校園",
                actions = {
                    AccountIconButton(onClick = onOpenPersonal, showUpdateBadge = showUpdateBadge)
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val announcement = state.items.firstOrNull { it.kind == OverviewItemKind.Announcement }
            val calendarEvent = state.items.firstOrNull { it.kind == OverviewItemKind.CalendarEvent }
            AnnouncementHeroCard(
                announcement = announcement,
                isLoading = state.isInitialLoading,
                unreadAnnouncementCount = unreadAnnouncementCount,
                onClick = onOpenAnnouncements,
            )
            CampusModuleCard(
                title = "行事曆",
                description = when {
                    calendarEvent != null -> "${calendarEvent.label} · ${calendarEvent.title}"
                    state.isInitialLoading -> "正在取得最近行程"
                    else -> "近期沒有校園行程"
                },
                icon = "calendar_today",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                onClick = onOpenCalendar,
            )
            CampusModuleCard(
                title = "欣河智慧校園平台",
                description = "前往校務系統",
                icon = "local_library",
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                onClick = onOpenSchoolWebsite,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AnnouncementHeroCard(
    announcement: OverviewItem?,
    isLoading: Boolean,
    unreadAnnouncementCount: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .heightIn(min = 176.dp),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        OutlinedRoundedSymbol("campaign", size = 28.dp, contentDescription = null)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (unreadAnnouncementCount > 0) {
                    Badge {
                        Text(
                            announcementBadgeText(unreadAnnouncementCount),
                            modifier = Modifier.semantics {
                                contentDescription = "${announcementBadgeText(unreadAnnouncementCount)} 則未讀公告"
                            },
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                }
                OutlinedRoundedSymbol("arrow_forward", size = 24.dp, contentDescription = null)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "學校公告",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        announcement != null -> announcement.title
                        isLoading -> "正在取得最新與置頂消息"
                        else -> "查看學校最新與置頂消息"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                announcement?.supportingText?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CampusModuleCard(
    title: String,
    description: String,
    icon: String,
    containerColor: androidx.compose.ui.graphics.Color,
    iconContainerColor: androidx.compose.ui.graphics.Color,
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
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = iconContainerColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    OutlinedRoundedSymbol(icon, size = 24.dp, contentDescription = null)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
