package com.clhs.score.ui.schedule

import com.clhs.score.ui.navigation.navigationContentPadding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.FilledRoundedSymbol
import com.clhs.score.ui.hapticToggle
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.SubpageLayout
import com.clhs.score.ui.theme.PocketMotion

@Composable
internal fun CourseNotificationSettingsScreen(
    allowed: Boolean,
    onBack: () -> Unit,
    onOpenScheduleReliability: () -> Unit,
) {
    val control = rememberCourseNotificationControl(allowed)
    SubpageLayout(title = "當前課程通知", onBack = onBack) {
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            contentPadding = navigationContentPadding(PaddingValues(horizontal = 16.dp, vertical = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CourseNotificationSwitchCard(
                    control = control,
                    allowed = allowed,
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = control.remindBeforeClass,
                            enabled = control.ready && !control.busy,
                            role = Role.Switch,
                            onValueChange = hapticToggle(control.setRemindBeforeClass),
                        ).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("上課前 5 分鐘提醒", style = MaterialTheme.typography.titleMedium)
                            Text("提前顯示下一節課資訊。", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = control.remindBeforeClass, onCheckedChange = null,
                            enabled = control.ready && !control.busy)
                    }
                }
            }
            item {
                Card(
                    onClick = onOpenScheduleReliability,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedRoundedSymbol(
                            icon = "alarm",
                            tint = MaterialTheme.colorScheme.primary,
                            contentDescription = null,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("排程可靠性", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "管理精確鬧鐘與電池最佳化設定。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedRoundedSymbol(
                            icon = "keyboard_arrow_right",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = null,
                        )
                    }
                }
            }
            item {
                Text(
                    "功能介紹",
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item { CourseNotificationFeaturesCard() }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun CourseNotificationSwitchCard(
    control: CourseNotificationControl,
    allowed: Boolean,
) {
    val status = when {
        !allowed -> "載入有效的實際課表後即可啟用。"
        !control.ready -> "正在讀取通知設定…"
        control.enabled && control.blocked -> "目前未取得系統通知權限。"
        control.enabled && control.pausedToday -> "今日已關閉，明日自動恢復。"
        control.enabled && !control.available -> "等待有效課表後開始顯示。"
        control.enabled -> "依照有效課表，在課程切換時更新通知。"
        control.blocked -> "啟用本功能前，請先允許 APP 傳送通知。"
        !control.available -> "取得有效週課表或學期課表後即可啟用。"
        else -> "已取得有效課表，可以啟用通知。"
    }
    val statusSummaryMotion = PocketMotion.stateChange<Float>()
    val contentColor = if (control.enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (control.enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = control.enabled,
                    enabled = control.canToggle,
                    role = Role.Switch,
                    onValueChange = hapticToggle(control.toggle),
                )
                .semantics {
                    stateDescription = when {
                        !control.ready -> "設定狀態未知"
                        control.busy -> "正在更新"
                        control.enabled -> "已開啟"
                        else -> "已關閉"
                    }
                }
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (control.enabled) {
                FilledRoundedSymbol(
                    "notifications_active",
                    size = 32.dp,
                    tint = contentColor,
                )
            } else {
                OutlinedRoundedSymbol(
                    "notifications",
                    size = 32.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    when {
                        !control.ready -> "當前課程通知"
                        control.enabled -> "通知已開啟"
                        else -> "通知已暫停"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                AnimatedContent(
                    targetState = status,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = status
                    },
                    transitionSpec = {
                        (fadeIn(statusSummaryMotion) togetherWith
                            fadeOut(statusSummaryMotion)).using(null)
                    },
                    label = "當前課程通知狀態",
                ) { summary ->
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (control.enabled) {
                            contentColor.copy(alpha = 0.76f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Switch(
                checked = control.enabled,
                onCheckedChange = null,
                enabled = control.canToggle,
            )
        }
    }
    if (control.blocked || control.denied) {
        TextButton(onClick = control.openNotificationSettings) {
            Text("開啟通知權限設定")
        }
    }
}

@Composable
private fun CourseNotificationFeaturesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CourseNotificationFeature(
                icon = "notifications_active",
                title = "顯示本節課資訊",
                description = "包含節次、科目、上課時間與教室（若課表有提供）。",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            CourseNotificationFeature(
                icon = "calendar_today",
                title = "依課表自動更新",
                description = "使用已載入的有效課表，在課程切換時更新通知。",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            CourseNotificationFeature(
                icon = "info",
                title = "自由掌控通知",
                description = "點選通知中的「暫停一日」，即可暫停當天的課程通知，隔日自動恢復。如要永久停用，請關閉此頁面的開關。",
            )
        }
    }
}

@Composable
private fun CourseNotificationFeature(
    icon: String,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedRoundedSymbol(
            icon = icon,
            tint = MaterialTheme.colorScheme.primary,
            contentDescription = null,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
