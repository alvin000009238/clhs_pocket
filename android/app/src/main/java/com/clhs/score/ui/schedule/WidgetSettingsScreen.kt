package com.clhs.score.ui.schedule

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.displayItems
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.widget.ScheduleWidgetPreferences
import com.clhs.score.widget.classifyWidgetScheduleItems
import com.clhs.score.widget.selectWidgetSchedule
import com.clhs.score.widget.widgetScheduleDateLabel
import com.clhs.score.widget.widgetScheduleDayLabel
import com.clhs.score.widget.widgetScheduleShortDateLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WidgetSettingsScreen(
    initialPreferences: ScheduleWidgetPreferences,
    previewSize: DpSize,
    onDismiss: () -> Unit,
    onSaveCompleted: suspend (ScheduleWidgetPreferences) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cacheStore = remember { GradeCacheStore(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scheduleReport by cacheStore.widgetScheduleReportFlow().collectAsStateWithLifecycle(
        initialValue = null
    )

    var isSaving by remember { mutableStateOf(false) }
    var showTeacher by rememberSaveable { mutableStateOf(initialPreferences.showTeacher) }
    var showClassroom by rememberSaveable { mutableStateOf(initialPreferences.showClassroom) }
    var showTime by rememberSaveable { mutableStateOf(initialPreferences.showTime) }
    var afterLastClass by rememberSaveable { mutableStateOf(initialPreferences.afterLastClass) }
    val widgetCornerRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dimensionResource(android.R.dimen.system_app_widget_background_radius)
    } else {
        24.dp
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Widget 設定") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        OutlinedRoundedSymbol(icon = "arrow_back", contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        enabled = !isSaving,
                        onClick = {
                            if (!isSaving) {
                                isSaving = true
                                coroutineScope.launch {
                                    try {
                                        onSaveCompleted(
                                            ScheduleWidgetPreferences(
                                                showTeacher = showTeacher,
                                                showClassroom = showClassroom,
                                                showTime = showTime,
                                                afterLastClass = afterLastClass,
                                            ),
                                        )
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        snackbarHostState.showSnackbar("儲存失敗，請再試一次")
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        if (isSaving) {
                            LoadingIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("儲存中")
                        } else {
                            Text("完成")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "即時預覽",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )

                Card(
                modifier = Modifier
                    .width(previewSize.width)
                    .height(previewSize.height)
                    .align(Alignment.CenterHorizontally),
                shape = RoundedCornerShape(widgetCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                ScheduleWidgetPreview(
                    report = scheduleReport,
                    showTeacher = showTeacher,
                    showClassroom = showClassroom,
                    showTime = showTime,
                    afterLastClass = afterLastClass,
                    widgetSize = previewSize,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "顯示內容",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                SettingSwitchRow(
                    title = "任課教師",
                    subtitle = "顯示授課教師；小尺寸會自動隱藏",
                    checked = showTeacher,
                    onCheckedChange = { showTeacher = it },
                    index = 0,
                    count = 3,
                )
                SettingSwitchRow(
                    title = "上課地點",
                    subtitle = "顯示上課地點；小尺寸會自動隱藏",
                    checked = showClassroom,
                    onCheckedChange = { showClassroom = it },
                    index = 1,
                    count = 3,
                )
                SettingSwitchRow(
                    title = "上課時間",
                    subtitle = "顯示每節課的起訖時間",
                    checked = showTime,
                    onCheckedChange = { showTime = it },
                    index = 2,
                    count = 3,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "課表切換時機",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            SettingSwitchRow(
                title = "放學後顯示下一個上課日",
                subtitle = "開啟後，最後一節下課即切換；關閉則於午夜切換",
                checked = afterLastClass,
                onCheckedChange = { afterLastClass = it },
                index = 0,
                count = 1,
            )

        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        supportingContent = {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null)
        },
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = title)
    }
}

@Composable
private fun ScheduleWidgetPreview(
    report: ScheduleReport?,
    showTeacher: Boolean,
    showClassroom: Boolean,
    showTime: Boolean,
    afterLastClass: Boolean,
    widgetSize: DpSize,
    modifier: Modifier = Modifier
) {
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val currentTotalMinutes = now.hour * 60 + now.minute

    val sourceItems = report?.displayItems().orEmpty()
    val validFrom = report
        ?.takeIf { it.scope == ScheduleScope.CURRENT_WEEK }
        ?.weekStartDate
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: today
    val selection = selectWidgetSchedule(sourceItems, today, currentTotalMinutes, afterLastClass, validFrom)
    val isExpiredCurrentWeek =
        report?.scope == ScheduleScope.CURRENT_WEEK && !report.isValidOn(selection.date)
    val sections = classifyWidgetScheduleItems(
        items = selection.items,
        selectionDate = selection.date,
        today = today,
        currentMinutes = currentTotalMinutes,
    )
    val isShort = widgetSize.height < 160.dp
    val isNarrow = widgetSize.width < 220.dp
    val isCompact = widgetSize.height < 260.dp
    val prioritizedItems = if (isShort) sections.prioritized.take(1) else sections.prioritized

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (isShort) 8.dp else 16.dp,
                )
                .padding(top = if (isShort) 8.dp else 12.dp, bottom = if (isShort) 4.dp else 8.dp),
        ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = widgetScheduleDayLabel(selection.date, today),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (isShort || isNarrow) {
                                widgetScheduleShortDateLabel(selection.date)
                            } else {
                                widgetScheduleDateLabel(selection.date)
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        if (!isShort &&
                            !isNarrow &&
                            !isExpiredCurrentWeek &&
                            report?.scope == ScheduleScope.CURRENT_WEEK &&
                            !report.weekNo.isNullOrBlank()
                        ) {
                            Text(
                                text = "第 ${report.weekNo} 週",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (report == null) {
                        Text(
                            "點擊此處查詢課表",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (isExpiredCurrentWeek) {
                        Text(
                            "課表已過期\n點擊此處更新",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (selection.items.isEmpty()) {
                        Text(
                            "今日無排課",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (sections.prioritized.isEmpty() && sections.completed.isNotEmpty()) {
                                item(key = -1) {
                                    Text(
                                        "今日課程已結束",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            bottom = if (isCompact) 0.dp else 8.dp,
                                        ),
                                    )
                                }
                            }
                            items(prioritizedItems, key = { it.period }) { item ->
                                PreviewScheduleItemRow(
                                    item = item,
                                    isCurrent = item == sections.current,
                                    isCompleted = false,
                                    showTeacher = showTeacher && !isShort && !isNarrow,
                                    showClassroom = showClassroom && !isShort && !isNarrow,
                                    showTime = showTime,
                                    isSmall = isShort,
                                )
                            }
                            if (!isCompact && sections.completed.isNotEmpty()) {
                                item(key = -2) {
                                    Text(
                                        text = "已下課",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                    )
                                }
                                items(sections.completed, key = { 100 + it.period }) { item ->
                                    PreviewScheduleItemRow(
                                        item = item,
                                        isCurrent = false,
                                        isCompleted = true,
                                        showTeacher = showTeacher,
                                        showClassroom = showClassroom,
                                        showTime = showTime,
                                        isSmall = false,
                                    )
                                }
                            }
                        }
                    }
        }
    }
}

@Composable
private fun PreviewScheduleItemRow(
    item: ScheduleItem,
    isCurrent: Boolean,
    isCompleted: Boolean,
    showTeacher: Boolean,
    showClassroom: Boolean,
    showTime: Boolean,
    isSmall: Boolean,
) {
    val contentColor = when {
        isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val periodColor = when {
        isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val details = buildList {
        if (showTeacher && item.teacherName.isMeaningfulPreviewText()) add(item.teacherName)
        if (showClassroom && item.classroom.isMeaningfulPreviewText()) add(item.classroom)
    }.joinToString(" • ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (isCurrent) 12.dp else 0.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .padding(horizontal = 8.dp, vertical = if (isSmall) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 10.dp)) {
            Text(
                text = "第 ${item.period} 節",
                fontWeight = FontWeight.Bold,
                color = periodColor,
                fontSize = if (isCurrent) 13.sp else 12.sp,
            )
            if (showTime) {
                PERIOD_TIMES.getOrNull(item.period - 1)?.singleLine?.let { periodTime ->
                    Text(
                        text = periodTime,
                        color = periodColor,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.subjectName,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                fontSize = if (isCurrent) 16.sp else 14.sp,
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun String.isMeaningfulPreviewText(): Boolean = isNotBlank() && this != "null"
