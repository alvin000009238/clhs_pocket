package com.clhs.score.ui.schedule


import androidx.compose.foundation.layout.Spacer

import com.clhs.score.ui.navigation.navigationContentPadding

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import com.clhs.score.ui.navigation.LocalNavigationClearance
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.clhs.score.data.ScheduleDisplayMode
import java.time.LocalTime
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.OutlinedTextField
import com.clhs.score.ui.RootFloatingScaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleChange
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.SCHOOL_COURSE_ZONE
import com.clhs.score.data.applySubjectOverrides
import com.clhs.score.data.getSubjectColors
import com.clhs.score.data.refreshAt
import com.clhs.score.ui.hapticClick
import com.clhs.score.ui.PocketBackButton
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.FilledRoundedSymbol
import com.clhs.score.ui.AccountIconButton
import com.clhs.score.viewmodel.ScheduleUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScheduleScreen(
    uiState: ScheduleUiState,
    dynamicColor: Boolean = false,
    notificationMenu: @Composable (() -> Unit) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenPersonal: () -> Unit = {},
    showUpdateBadge: Boolean = false,
    onOpenSubjectCustomizations: () -> Unit = {},
    onRefresh: () -> Unit,
    onYearSelected: (String) -> Unit,
    onClassSelected: (String) -> Unit,
    onScopeSelected: (ScheduleScope) -> Unit,
    onDisplayModeSelected: (ScheduleDisplayMode) -> Unit = {},
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    onCurrentWeek: () -> Unit = {},
    onChooseWeek: () -> Unit = {},
    onDismissWeekPicker: () -> Unit = {},
    onWeekSelected: (com.clhs.score.data.ScheduleWeekOption) -> Unit = {},
    onEnsureCurrentWeek: () -> Unit = {},
    onConfirmSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onNoticeShown: () -> Unit,
    onSaveSubjectOverride: (ScheduleSubjectOverride, () -> Unit) -> Unit = { _, onSaved -> onSaved() },
) {
    if (uiState.isWeekPickerOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissWeekPicker,
            title = { Text("選擇週次") },
            text = {
                Column {
                    when {
                        uiState.isLoadingWeeks -> LoadingIndicator()
                        uiState.weekPickerError != null -> {
                            Text(uiState.weekPickerError)
                            androidx.compose.material3.TextButton(onClick = onChooseWeek) { Text("重試") }
                        }
                        uiState.weekOptions.isEmpty() -> Text("學校尚未提供可選週次")
                        else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                            items(uiState.weekOptions.size) { index ->
                                val option = uiState.weekOptions[index]
                                androidx.compose.material3.TextButton(
                                    onClick = { onWeekSelected(option) }, modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("${option.text}  ${option.startDate}–${option.endDate}")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismissWeekPicker) { Text("關閉") } },
        )
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showMoreMenu by remember { mutableStateOf(false) }
    val captureLayer = rememberGraphicsLayer()
    val snackbarHostState = remember { SnackbarHostState() }
    var scheduleNow by remember(uiState.report) { mutableStateOf(LocalDateTime.now(SCHOOL_COURSE_ZONE)) }
    val ensureCurrentWeek by rememberUpdatedState(onEnsureCurrentWeek)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, uiState.report) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = LocalDateTime.now(SCHOOL_COURSE_ZONE)
                scheduleNow = now
                ensureCurrentWeek()
                val nextMinute = now.withSecond(0).withNano(0).plusMinutes(1)
                val nextRefresh = uiState.report?.refreshAt()?.takeIf { it.isAfter(now) }
                val nextUpdate = nextRefresh?.takeIf { it.isBefore(nextMinute) } ?: nextMinute
                delay(Duration.between(now, nextUpdate).toMillis().coerceAtLeast(1).milliseconds)
            }
        }
    }
    val isRefreshingWeekReport =
        uiState.report?.scope == ScheduleScope.CURRENT_WEEK && uiState.isLoading
    val reloadWarning = uiState.refreshFailed
    val reloadDescription = when {
        isRefreshingWeekReport -> "正在重新載入目前顯示的週課表"
        uiState.refreshFailed -> "更新失敗，顯示已儲存課表；點擊重新載入"
        else -> "更新目前顯示的週課表"
    }

    LaunchedEffect(uiState.noticeMessage) {
        uiState.noticeMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onNoticeShown()
        }
    }

    RootFloatingScaffold(
        dynamicColor = dynamicColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            if (onBack != null) {
                PocketBackButton(onBack)
            }

            if (uiState.report?.scope == ScheduleScope.CURRENT_WEEK) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(reloadDescription) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !uiState.isLoading,
                        shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (reloadWarning) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                            contentColor = if (reloadWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        when {
                            isRefreshingWeekReport -> LoadingIndicator(
                                modifier = Modifier.size(24.dp).semantics {
                                    contentDescription = reloadDescription
                                },
                                color = MaterialTheme.colorScheme.primary,
                            )
                            reloadWarning -> FilledRoundedSymbol(
                                icon = "refresh",
                                size = 28.dp,
                                contentDescription = reloadDescription,
                            )
                            else -> OutlinedRoundedSymbol(
                                icon = "refresh",
                                contentDescription = "更新目前顯示的週課表",
                            )
                        }
                    }
                }
            }
                    IconButton(
                        onClick = { showMoreMenu = true },
                        shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape),
                    ) {
                        OutlinedRoundedSymbol(icon = "more_vert", tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "更多選項")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        offset = DpOffset(x = 0.dp, y = 10.dp),
                    ) {
                        notificationMenu { showMoreMenu = false }
                        if (!uiState.report?.items.isNullOrEmpty()) {
                            DropdownMenuItem(
                                text = { Text("重新選擇") },
                                onClick = {
                                    onClearSelection()
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自訂科目") },
                                onClick = {
                                    showMoreMenu = false
                                    onOpenSubjectCustomizations()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("另存為圖片") },
                                onClick = {
                                    captureLayer.takeIf { it.size.width > 0 && it.size.height > 0 }?.let { layer ->
                                        coroutineScope.launch {
                                            saveBitmapToGallery(context, layer)
                                                .onSuccess {
                                                    Toast.makeText(context, "課表已儲存至相簿", Toast.LENGTH_SHORT).show()
                                                }
                                                .onFailure { error ->
                                                    Toast.makeText(
                                                        context,
                                                        "儲存失敗: ${error.message ?: "未知錯誤"}",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                        }
                                    } ?: run {
                                        Toast.makeText(context, "無法擷取課表", Toast.LENGTH_SHORT).show()
                                    }
                                    showMoreMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("新增 Widget") },
                                onClick = {
                                        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
                                        val componentName = android.content.ComponentName(context, com.clhs.score.widget.ScheduleWidgetReceiver::class.java)
                                        if (appWidgetManager.isRequestPinAppWidgetSupported) {
                                            appWidgetManager.requestPinAppWidget(componentName, null, null)
                                        } else {
                                            Toast.makeText(context, "您的裝置不支援新增桌面小工具", Toast.LENGTH_SHORT).show()
                                        }
                                    showMoreMenu = false
                                }
                            )
                        }
                    }
            AccountIconButton(onClick = onOpenPersonal, showUpdateBadge = showUpdateBadge,
                animateShape = false)
        },
    ) { padding, _ ->
        val header: @Composable () -> Unit = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stackedHeader = maxWidth < 156.dp + 160.dp * LocalDensity.current.fontScale
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .padding(top = if (stackedHeader) 56.dp else 0.dp, end = if (stackedHeader) 0.dp else 156.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.report != null) {
                        ScheduleScopePicker(
                            scope = uiState.report.scope,
                            classNo = uiState.report.classNo,
                            enabled = !uiState.isLoading,
                            onSelected = onScopeSelected,
                        )
                    } else {
                        Text(
                            text = "課表查詢",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 1100.dp)
                .fillMaxWidth()
        ) {
            if (!uiState.isInitialLoading && (uiState.report == null || uiState.report.items.isEmpty())) {
                Box(Modifier.align(Alignment.TopCenter).padding(padding).padding(horizontal = 16.dp)) { header() }
            }
            if (uiState.isInitialLoading) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Box(Modifier.padding(horizontal = 16.dp)) { header() }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            } else if (uiState.report == null) {
                // Selection Screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationContentPadding()
                        .padding(padding)
                        .padding(top = 64.dp, start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(bottom = 12.dp),
                            ) {
                                Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                                    OutlinedRoundedSymbol(
                                        icon = "calendar_view_week",
                                        size = 28.dp,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Text(
                                text = "選擇課表範圍",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "選擇學期、班級與顯示類型以載入排課資料",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                            )

                            var yearExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = yearExpanded,
                                onExpandedChange = { yearExpanded = !yearExpanded },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            ) {
                                OutlinedTextField(
                                    value = uiState.availableYears.find { it.value == uiState.selectedYearValue }?.text ?: "選擇學期",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("學期") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearExpanded) },
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    singleLine = true,
                                )
                                DropdownMenu(
                                    offset = DpOffset(0.dp, 6.dp),
                                    expanded = yearExpanded,
                                    onDismissRequest = { yearExpanded = false },
                                    modifier = Modifier.exposedDropdownSize(),
                                ) {
                                    uiState.availableYears.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.text) },
                                            onClick = {
                                                onYearSelected(option.value)
                                                yearExpanded = false
                                            },
                                        )
                                    }
                                }
                            }

                            var classExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = classExpanded,
                                onExpandedChange = { classExpanded = !classExpanded },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            ) {
                                OutlinedTextField(
                                    value = uiState.availableClasses.find { it.value == uiState.selectedClassValue }?.text ?: "選擇班級",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("班級") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = classExpanded) },
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    singleLine = true,
                                )
                                DropdownMenu(
                                    offset = DpOffset(0.dp, 6.dp),
                                    expanded = classExpanded,
                                    onDismissRequest = { classExpanded = false },
                                    modifier = Modifier.exposedDropdownSize(),
                                ) {
                                    if (uiState.availableClasses.isEmpty()) {
                                        DropdownMenuItem(text = { Text("無可用班級") }, onClick = { classExpanded = false })
                                    }
                                    uiState.availableClasses.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.text) },
                                            onClick = {
                                                onClassSelected(option.value)
                                                classExpanded = false
                                            },
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "課表類型",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            )
                            val scopeOptions = listOf(
                                "學期課表" to ScheduleScope.SEMESTER,
                                "週課表" to ScheduleScope.CURRENT_WEEK,
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                            ) {
                                scopeOptions.forEachIndexed { index, (label, scope) ->
                                    SegmentedButton(
                                        selected = uiState.selectedScope == scope,
                                        onClick = hapticClick { onScopeSelected(scope) },
                                        shape = SegmentedButtonDefaults.itemShape(index, scopeOptions.size),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }

                            Button(
                                onClick = onConfirmSelection,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                enabled = !uiState.isLoading && uiState.selectedYearValue != null,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                if (uiState.isLoading) {
                                    LoadingIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text("查詢課表", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            if (uiState.isError) {
                                Text(
                                    text = uiState.errorMessage,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 16.dp),
                                    textAlign = TextAlign.Center,
                                )
                                Button(
                                    onClick = onRefresh,
                                    modifier = Modifier.padding(top = 12.dp),
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text("重新整理")
                                }
                            }
                        }
                    }
                }
            } else {
                uiState.report.let { report ->
                    Surface(modifier = Modifier.fillMaxSize().drawWithContent {
                        captureLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(captureLayer)
                    }) {
                        ScheduleGrid(
                            automaticWeek = uiState.weekTargetDate == null,
                            items = report.items,
                            contentPadding = padding,
                            header = {
                                header()
                                ScheduleControls(
                                    report = report,
                                    displayMode = uiState.displayMode,
                                    isLoading = uiState.isLoading,
                                    onDisplayModeSelected = onDisplayModeSelected,
                                    onPreviousWeek = onPreviousWeek,
                                    onNextWeek = onNextWeek,
                                    onCurrentWeek = onCurrentWeek,
                                    onChooseWeek = onChooseWeek,
                                )
                            },
                            report = report,
                            displayMode = uiState.displayMode,
                            now = scheduleNow,
                            changes = report.changes.orEmpty(),
                            subjectOverrides = report.subjectOverrides,
                            currentDayOfWeek = scheduleCurrentDayOfWeek(report, scheduleNow.toLocalDate()),
                            isSavingSubjectOverride = uiState.isSavingSubjectOverride,
                            onSaveSubjectOverride = onSaveSubjectOverride,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

internal data class ScheduleGridCell(
    val period: Int,
    val periodCount: Int,
    val item: ScheduleItem?,
    val change: ScheduleChange?,
)

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五")

internal fun scheduleCurrentDayOfWeek(report: ScheduleReport, today: LocalDate): Int? {
    val dayOfWeek = today.dayOfWeek.value
    return dayOfWeek.takeIf { it in 1..5 && report.isValidOn(today) }
}

internal fun scheduleCellContentDescription(
    dayOfWeek: Int,
    cell: ScheduleGridCell,
): String = buildList {
    add(WEEKDAY_LABELS.getOrNull(dayOfWeek - 1)?.let { "週$it" } ?: "星期 $dayOfWeek")
    add(
        if (cell.periodCount == 1) {
            "第 ${cell.period} 節"
        } else {
            "第 ${cell.period} 至 ${cell.period + cell.periodCount - 1} 節"
        },
    )
    val changeType = cell.change?.type
    when (changeType) {
        ScheduleChangeType.ADDED -> add("新增課程")
        ScheduleChangeType.MODIFIED -> add("調課")
        ScheduleChangeType.REMOVED -> add("停課")
        null -> Unit
    }
    (cell.item ?: cell.change?.semesterItem)?.let { item ->
        add(if (changeType == ScheduleChangeType.REMOVED) "原課程${item.subjectName}" else item.subjectName)
        if (item.teacherName.isNotBlank()) add("教師${item.teacherName}")
        if (item.classroom.isNotBlank()) add("教室${item.classroom}")
    }
}.joinToString("，")

internal fun scheduleSinglePeriodCells(cells: List<ScheduleGridCell>): List<ScheduleGridCell> =
    cells.flatMap { cell ->
        (cell.period until cell.period + cell.periodCount).map { period ->
            cell.copy(period = period, periodCount = 1, item = cell.item?.copy(period = period))
        }
    }

internal fun scheduleGridPeriods(
    items: List<ScheduleItem>,
    changes: List<ScheduleChange>,
): List<Int> {
    val lastPeriod = maxOf(7, items.maxOfOrNull { it.period } ?: 0, changes.maxOfOrNull { it.period } ?: 0)
    return ((1..PERIOD_TIMES.size) + items.map { it.period } + changes.map { it.period })
        .filter { it in 1..lastPeriod }
        .distinct()
        .sorted()
}

internal fun scheduleGridCells(
    items: List<ScheduleItem>,
    changes: List<ScheduleChange>,
    dayOfWeek: Int,
    periods: Iterable<Int> = scheduleGridPeriods(items, changes),
): List<ScheduleGridCell> {
    val displayedPeriods = periods.filter { it > 0 }.distinct().sorted()
    if (displayedPeriods.isEmpty()) return emptyList()

    val itemsByPeriod = items
        .filter { it.dayOfWeek == dayOfWeek }
        .associateBy { it.period }
    val changesByPeriod = changes
        .filter { it.dayOfWeek == dayOfWeek }
        .associateBy { it.period }

    return buildList {
        var index = 0
        while (index < displayedPeriods.size) {
            val period = displayedPeriods[index]
            val change = changesByPeriod[period]
            val item = change?.weekItem ?: itemsByPeriod[period]
            var periodCount = 1
            if (item != null && change == null) {
                while (
                    displayedPeriods.getOrNull(index + periodCount) == period + periodCount &&
                    changesByPeriod[period + periodCount] == null &&
                    item.isSameDisplayedCourse(itemsByPeriod[period + periodCount])
                ) {
                    periodCount++
                }
            }
            add(ScheduleGridCell(period, periodCount, item, change))
            index += periodCount
        }
    }
}

private fun ScheduleItem.isSameDisplayedCourse(other: ScheduleItem?): Boolean =
    other != null &&
        subjectName == other.subjectName &&
        teacherName == other.teacherName &&
        classroom == other.classroom

@Composable
private fun ScheduleScopePicker(
    scope: ScheduleScope,
    classNo: String,
    enabled: Boolean,
    onSelected: (ScheduleScope) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (scope == ScheduleScope.CURRENT_WEEK) "週課表" else "學期課表",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedRoundedSymbol(
                        icon = "keyboard_arrow_down",
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "選擇課表類型",
                    )
                }
                Text(
                    text = "$classNo 班",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = 0.dp, y = 6.dp),
        ) {
            ScheduleScope.entries.forEach { option ->
                val isSelected = scope == option
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (option == ScheduleScope.CURRENT_WEEK) "週課表" else "學期課表",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                text = if (option == ScheduleScope.CURRENT_WEEK) "顯示當週調課、加課與停課異動" else "顯示學期固定排課基準",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (isSelected) {
                            OutlinedRoundedSymbol("check", tint = MaterialTheme.colorScheme.primary, contentDescription = null)
                        }
                    },
                    onClick = hapticClick { expanded = false; onSelected(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScheduleControls(
    report: ScheduleReport,
    displayMode: ScheduleDisplayMode,
    isLoading: Boolean,
    onDisplayModeSelected: (ScheduleDisplayMode) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onChooseWeek: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val dateStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)
        // Both scopes reserve the same navigation geometry, including full-width dates.
        val dateSize = textMeasurer.measure("12/30–12/31", style = dateStyle, softWrap = false).size
        val weekSize = textMeasurer.measure("第 99 週", style = MaterialTheme.typography.labelMedium, softWrap = false).size
        val requiredWidth = with(density) { (dateSize.width + weekSize.width).toDp() } + 182.dp
        val narrow = maxWidth < requiredWidth
        val navigationHeight = with(density) {
            if (narrow) maxOf(48.dp, dateSize.height.toDp()) + maxOf(48.dp, weekSize.height.toDp())
            else maxOf(48.dp, dateSize.height.toDp(), weekSize.height.toDp())
        }
        val weekNavigation: @Composable (Modifier) -> Unit = { modifier ->
            if (report.scope == ScheduleScope.CURRENT_WEEK) {
                Surface(
                    modifier = modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = hapticClick(onPreviousWeek), enabled = !isLoading) {
                            OutlinedRoundedSymbol("arrow_back", size = 20.dp, contentDescription = "上一週")
                        }
                        ScheduleWeekTargetButton(
                            onClick = hapticClick(onCurrentWeek),
                            onLongClick = hapticClick(onChooseWeek),
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = listOfNotNull(scheduleReportDateLabel(report), report.weekNo?.takeIf { it.isNotBlank() }?.let { "第 $it 週" }, "點擊回到本週").joinToString("，")
                            },
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = scheduleReportDateLabel(report),
                                    modifier = Modifier.alignByBaseline(),
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                                report.weekNo?.takeIf { it.isNotBlank() }?.let { week ->
                                    Text(
                                        text = "第 $week 週",
                                        modifier = Modifier.alignByBaseline(),
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = hapticClick(onNextWeek), enabled = !isLoading) {
                            OutlinedRoundedSymbol("arrow_forward", size = 20.dp, contentDescription = "下一週")
                        }
                    }
                }
            } else {
                Spacer(modifier.height(navigationHeight))
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (narrow && report.scope == ScheduleScope.CURRENT_WEEK) {
                    Column(Modifier.weight(1f)) {
                        ScheduleWeekTargetButton(
                            onClick = hapticClick(onCurrentWeek), enabled = !isLoading,
                            onLongClick = hapticClick(onChooseWeek),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(scheduleReportDateLabel(report), maxLines = 1, softWrap = false,
                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onClick = hapticClick(onPreviousWeek), enabled = !isLoading) {
                                OutlinedRoundedSymbol("arrow_back", size = 20.dp, contentDescription = "上一週")
                            }
                            report.weekNo?.takeIf { it.isNotBlank() }?.let {
                                Text("第 $it 週", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                            }
                            IconButton(onClick = hapticClick(onNextWeek), enabled = !isLoading) {
                                OutlinedRoundedSymbol("arrow_forward", size = 20.dp, contentDescription = "下一週")
                            }
                        }
                    }
                } else weekNavigation(Modifier.weight(1f))
                androidx.compose.material3.FilledTonalIconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = hapticClick {
                        onDisplayModeSelected(if (displayMode == ScheduleDisplayMode.DAY) ScheduleDisplayMode.WEEK else ScheduleDisplayMode.DAY)
                    },
                ) {
                    OutlinedRoundedSymbol(
                        if (displayMode == ScheduleDisplayMode.DAY) "calendar_view_week" else "calendar_today",
                        contentDescription = if (displayMode == ScheduleDisplayMode.DAY) "目前單日顯示，切換為整週顯示" else "目前整週顯示，切換為單日顯示",
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleWeekTargetButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.extraLarge)
            .combinedClickable(enabled = enabled, role = Role.Button,
                onClickLabel = "回到目前週", onLongClickLabel = "選擇週次",
                onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

internal fun scheduleDayDate(report: ScheduleReport, dayOfWeek: Int): LocalDate? {
    if (report.scope != ScheduleScope.CURRENT_WEEK || dayOfWeek !in 1..7) return null
    val start = runCatching { LocalDate.parse(report.weekStartDate) }.getOrNull() ?: return null
    val end = runCatching { LocalDate.parse(report.weekEndDate) }.getOrNull() ?: return null
    if (end < start) return null
    return start.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek))).takeIf { it in start..end }
}

internal fun scheduleInitialDay(report: ScheduleReport?, days: List<Int>, today: LocalDate): Int {
    val futureWeek = report?.scope == ScheduleScope.CURRENT_WEEK &&
        report.weekStartDate?.let { runCatching { LocalDate.parse(it) > today }.getOrDefault(false) } == true
    return if (futureWeek) days.first() else today.dayOfWeek.value.takeIf { it in days } ?: days.first()
}

internal enum class ScheduleTemporalStatus(val label: String) {
    CURRENT("上課中"), UPCOMING("未開始"), ENDED("已結束"),
}

internal fun scheduleCellTemporalStatus(
    report: ScheduleReport?,
    dayOfWeek: Int,
    cell: ScheduleGridCell,
    now: LocalDateTime,
): ScheduleTemporalStatus? {
    if (report == null || scheduleDayDate(report, dayOfWeek) != now.toLocalDate() ||
        cell.item == null || cell.change?.type == ScheduleChangeType.REMOVED) return null
    val times = (cell.period until cell.period + cell.periodCount).map { period ->
        val time = PERIOD_TIMES.getOrNull(period - 1) ?: return null
        LocalTime.parse(time.start) to LocalTime.parse(time.end)
    }
    val currentTime = now.toLocalTime()
    return when {
        times.any { (start, end) -> !currentTime.isBefore(start) && currentTime.isBefore(end) } -> ScheduleTemporalStatus.CURRENT
        currentTime.isBefore(times.last().second) -> ScheduleTemporalStatus.UPCOMING
        else -> ScheduleTemporalStatus.ENDED
    }
}

internal fun schedulePeriodLabel(cell: ScheduleGridCell): String =
    if (cell.periodCount == 1) "第 ${cell.period} 節" else "第 ${cell.period} 至 ${cell.period + cell.periodCount - 1} 節"

internal fun scheduleCellTimeLabel(cell: ScheduleGridCell): String? {
    val start = PERIOD_TIMES.getOrNull(cell.period - 1)?.start ?: return null
    val end = PERIOD_TIMES.getOrNull(cell.period + cell.periodCount - 2)?.end ?: return null
    return "$start–$end"
}

private fun scheduleReportDateLabel(report: ScheduleReport): String {
    val start = runCatching { LocalDate.parse(report.weekStartDate) }.getOrNull()
    val end = runCatching { LocalDate.parse(report.weekEndDate) }.getOrNull()
    return if (start != null && end != null && end >= start) {
        "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
    } else "週課表日期未提供"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleGrid(
    items: List<ScheduleItem>,
    modifier: Modifier = Modifier,
    changes: List<ScheduleChange> = emptyList(),
    subjectOverrides: List<ScheduleSubjectOverride> = emptyList(),
    currentDayOfWeek: Int? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(),
    header: @Composable () -> Unit = {},
    isSavingSubjectOverride: Boolean = false,
    onSaveSubjectOverride: (ScheduleSubjectOverride, () -> Unit) -> Unit = { _, onSaved -> onSaved() },
    report: ScheduleReport? = null,
    displayMode: ScheduleDisplayMode = ScheduleDisplayMode.WEEK,
    automaticWeek: Boolean = true,
    now: LocalDateTime = LocalDateTime.now(SCHOOL_COURSE_ZONE),
) {
    val scheduleColorScheme = MaterialTheme.colorScheme
    val displayedItems = remember(items, subjectOverrides) { items.applySubjectOverrides(subjectOverrides) }
    val displayedChanges = remember(changes, subjectOverrides) { changes.applySubjectOverrides(subjectOverrides) }
    val periods = remember(displayedItems, displayedChanges) { scheduleGridPeriods(displayedItems, displayedChanges) }
    val days = remember(items, changes) {
        (1..5).toList() + (6..7).filter { day -> items.any { it.dayOfWeek == day } || changes.any { it.dayOfWeek == day } }
    }
    val cellsByDay = remember(displayedItems, displayedChanges, periods, days) {
        days.associateWith { day -> scheduleGridCells(displayedItems, displayedChanges, day, periods) }
    }
    val isDarkTheme = scheduleColorScheme.background.luminance() < 0.5f
    val subjectColors = remember(displayedItems, displayedChanges, isDarkTheme) {
        getSubjectColors(displayedItems.map { it.subjectName } + displayedChanges.mapNotNull { it.weekItem?.subjectName }, isDarkTheme)
    }
    val rootScroll = rememberScrollState()
    com.clhs.score.ui.navigation.RegisterRootScroll(rootScroll)
    var selectedDay by rememberSaveable { mutableIntStateOf(scheduleInitialDay(report, days, now.toLocalDate())) }
    LaunchedEffect(days) { if (selectedDay !in days) selectedDay = days.first() }
    var selectionScope by rememberSaveable { mutableStateOf(report?.scope?.name) }
    var selectionWeek by rememberSaveable { mutableStateOf(report?.weekStartDate) }
    var selectionAutomatic by rememberSaveable { mutableStateOf(automaticWeek) }
    LaunchedEffect(report?.scope, report?.weekStartDate, automaticWeek) {
        val changed = selectionScope != report?.scope?.name ||
            selectionWeek != report?.weekStartDate || selectionAutomatic != automaticWeek
        if (changed && automaticWeek && report?.scope == ScheduleScope.CURRENT_WEEK) {
            selectedDay = scheduleInitialDay(report, days, now.toLocalDate())
        }
        selectionScope = report?.scope?.name
        selectionWeek = report?.weekStartDate
        selectionAutomatic = automaticWeek
    }
    var selectedCell by remember(items, changes) { mutableStateOf<ScheduleGridCell?>(null) }
    var selectedChange by remember(items, changes) { mutableStateOf<ScheduleChange?>(null) }
    val onCellClick: (ScheduleGridCell) -> Unit = { cell ->
        if (cell.change != null) selectedChange = cell.change else selectedCell = cell
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compactHeight = windowHeight > 0.dp && windowHeight < 480.dp
    val timeColumnWidth = 40.dp * fontScale.coerceAtLeast(1f)
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = scheduleWeekTitleStyle()
    val navigationClearance = LocalNavigationClearance.current
    BoxWithConstraints(modifier) {
        val horizontalDayLabels = compactHeight && maxWidth >= 500.dp * fontScale
        val dayTabWidth = maxOf(52.dp * fontScale.coerceAtLeast(1f), (maxWidth - 24.dp - 8.dp * (days.size - 1)) / days.size)
        val availableHeight = (maxHeight - contentPadding.calculateTopPadding() -
            contentPadding.calculateBottomPadding() - navigationClearance - 16.dp).coerceAtLeast(0.dp)
        ScheduleMeasuredColumn(
            availableHeight = availableHeight,
            modifier = Modifier.verticalScroll(rootScroll).navigationContentPadding().padding(contentPadding).padding(bottom = 16.dp),
            header = {
                Column(Modifier.padding(horizontal = 8.dp)) { header() }
                Spacer(Modifier.height(6.dp))
            },
        ) { boardAvailableHeight ->
            Column {
                if (displayMode == ScheduleDisplayMode.DAY) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (day in days) {
                            val date = report?.let { scheduleDayDate(it, day) }
                            val isToday = date == now.toLocalDate()
                            val isSelected = selectedDay == day
                            val hasDayChanges = displayedChanges.any { it.dayOfWeek == day }
                            Surface(
                                modifier = Modifier
                                    .width(dayTabWidth)
                                    .clip(MaterialTheme.shapes.large)
                                    .selectable(isSelected, role = Role.Tab, onClick = hapticClick { selectedDay = day })
                                    .semantics {
                                        contentDescription = "週${dayName(day)}${date?.let { "，${it.monthValue} 月 ${it.dayOfMonth} 日" }.orEmpty()}${if (isToday) "，今天" else ""}"
                                    },
                                shape = MaterialTheme.shapes.large,
                                color = if (isSelected) scheduleColorScheme.primaryContainer else scheduleColorScheme.surfaceContainerLow,
                                contentColor = if (isSelected) scheduleColorScheme.onPrimaryContainer else scheduleColorScheme.onSurface,
                                border = if (isToday) BorderStroke(1.5.dp, scheduleColorScheme.primary) else null,
                            ) {
                                val weekdayHeight = textMeasurer.measure("一", MaterialTheme.typography.labelLarge).size.height
                                val dateHeight = textMeasurer.measure("31", MaterialTheme.typography.titleSmall).size.height
                                val dayTabHeight = with(density) {
                                    if (horizontalDayLabels) maxOf(48.dp, maxOf(weekdayHeight, dateHeight).toDp() + 16.dp)
                                    else maxOf(60.dp, (weekdayHeight + dateHeight).toDp() + 26.dp)
                                }
                                val dayContent: @Composable (Modifier) -> Unit = { textModifier ->
                                    Text(
                                        text = dayName(day),
                                        modifier = textModifier,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) scheduleColorScheme.onPrimaryContainer else scheduleColorScheme.onSurfaceVariant,
                                    )
                                    if (date != null) Text(
                                        text = date.dayOfMonth.toString(),
                                        modifier = textModifier,
                                        minLines = 1,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (date != null) Box(
                                        Modifier
                                            .padding(top = 1.dp)
                                            .size(5.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(
                                                when {
                                                    !hasDayChanges -> Color.Transparent
                                                    isSelected -> scheduleColorScheme.onPrimaryContainer
                                                    else -> scheduleColorScheme.tertiary
                                                },
                                            ),
                                    )
                                }
                                if (horizontalDayLabels) {
                                    Row(
                                        Modifier.heightIn(min = dayTabHeight).padding(horizontal = 4.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                    ) { dayContent(Modifier.alignByBaseline()) }
                                } else {
                                    Column(
                                        Modifier.heightIn(min = dayTabHeight).padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                    ) { dayContent(Modifier) }
                                }
                            }
                        }
                    }
                    val dailyCells = remember(cellsByDay, selectedDay) {
                        scheduleSinglePeriodCells(cellsByDay[selectedDay].orEmpty())
                    }
                        .filter { it.item != null || it.change != null }
                    Column(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = 840.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (dailyCells.isEmpty()) {
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                color = scheduleColorScheme.surfaceContainerLow,
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.large,
                                        color = scheduleColorScheme.secondaryContainer,
                                        contentColor = scheduleColorScheme.onSecondaryContainer,
                                    ) {
                                        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                                            OutlinedRoundedSymbol(
                                                icon = "calendar_today",
                                                size = 24.dp,
                                                tint = scheduleColorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                    Text(
                                        text = "當日無課程",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "可選擇其他日期查看課表",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = scheduleColorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (dailyCells.isNotEmpty()) {
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                color = scheduleColorScheme.surfaceContainerLow,
                            ) {
                                Column(Modifier.padding(6.dp)) {
                                    dailyCells.forEachIndexed { index, cell ->
                                        ScheduleDayCard(
                                            cell = cell,
                                            subjectColors = subjectColors,
                                            temporalStatus = scheduleCellTemporalStatus(report, selectedDay, cell, now),
                                            onClick = { onCellClick(cell) },
                                        )
                                        if (index < dailyCells.lastIndex) {
                                            val nextCell = dailyCells[index + 1]
                                            val crossesLunchBreak = (cell.period + cell.periodCount - 1) <= 4 && nextCell.period >= 5
                                            if (crossesLunchBreak) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.weight(1f),
                                                        color = scheduleColorScheme.outlineVariant.copy(alpha = 0.45f),
                                                    )
                                                    Text(
                                                        text = "午休 12:00–13:00",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                                        color = scheduleColorScheme.onSurfaceVariant,
                                                    )
                                                    HorizontalDivider(
                                                        modifier = Modifier.weight(1f),
                                                        color = scheduleColorScheme.outlineVariant.copy(alpha = 0.45f),
                                                    )
                                                }
                                            } else if (cell.change == null && nextCell.change == null) {
                                                HorizontalDivider(
                                                    Modifier.padding(horizontal = 16.dp),
                                                    color = scheduleColorScheme.outlineVariant.copy(alpha = 0.45f),
                                                )
                                            } else {
                                                Spacer(Modifier.height(4.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (items.isEmpty() && changes.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = scheduleColorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("查無課表資料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "可切換週次或課表範圍",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheduleColorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val minimumDayWidth = 48.dp * (fontScale / 1.15f).coerceAtLeast(1f)
                        val gridWidth = maxOf(maxWidth, timeColumnWidth + minimumDayWidth * days.size)
                        val titleWidthPx = with(density) {
                            ((gridWidth - timeColumnWidth) / days.size - 8.dp).roundToPx().coerceAtLeast(1)
                        }
                        val badgeStyle = scheduleWeekBadgeStyle()
                        val timeLabelStyle = MaterialTheme.typography.labelLarge
                        val timeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, fontFeatureSettings = "tnum")
                        val minimumTimeHeight = with(density) {
                            (textMeasurer.measure("8", timeLabelStyle).size.height +
                                textMeasurer.measure("16:05\n16:55", timeStyle).size.height).toDp() + 8.dp
                        }
                        // Match the tile's text constraints and padding before layout; no size feedback loop.
                        val rowHeight = remember(cellsByDay, titleWidthPx, titleStyle, badgeStyle, density, textMeasurer, minimumTimeHeight) {
                            val height = cellsByDay.values.flatten().maxOfOrNull { cell ->
                                val item = cell.item ?: cell.change?.semesterItem
                                val title = textMeasurer.measure(
                                    text = item?.subjectName.orEmpty(), style = titleStyle,
                                    maxLines = if (cell.periodCount > 1) 5 else 3,
                                    constraints = Constraints(maxWidth = titleWidthPx),
                                ).size.height
                                val room = if (cell.periodCount > 1 && item?.classroom?.let(::hasScheduleDetail) == true) {
                                    textMeasurer.measure(item.classroom, badgeStyle, maxLines = 1,
                                        constraints = Constraints(maxWidth = titleWidthPx)).size.height
                                } else 0
                                with(density) { (title + room).toDp() + 17.dp + (if (room > 0) 2.dp else 0.dp) } / cell.periodCount
                            } ?: 0.dp
                            maxOf(56.dp, height, minimumTimeHeight)
                        }
                        ScheduleWeekBoard(
                            days = days, periods = periods, cellsByDay = cellsByDay, report = report,
                            now = now, currentDayOfWeek = currentDayOfWeek, subjectColors = subjectColors,
                            minimumRowHeight = rowHeight, availableHeight = boardAvailableHeight, timeColumnWidth = timeColumnWidth,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).width(gridWidth),
                            onCellClick = onCellClick,
                        )
                    }
                }
            }
        }
    }
    selectedCell?.let { cell ->
        val displayedItem = cell.item ?: return@let
        val originalItems = items.filter { it.dayOfWeek == displayedItem.dayOfWeek && it.period in cell.period until cell.period + cell.periodCount }
        val original = originalItems.firstOrNull() ?: displayedItem
        ScheduleDetailSheet(
            item = original,
            originalItems = originalItems.ifEmpty { listOf(original) },
            existingOverride = subjectOverrides.firstOrNull { it.originalSubjectName == original.subjectName },
            isSaving = isSavingSubjectOverride,
            onSave = onSaveSubjectOverride,
            onDismiss = { selectedCell = null },
        )
    }
    selectedChange?.let { change ->
        ScheduleChangeDetailSheet(
            change = change,
            onDismiss = { selectedChange = null },
        )
    }
}

// Measure both slots in one pass; never feed drawn sizes back into composition.
@Composable
private fun ScheduleMeasuredColumn(
    availableHeight: Dp,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (Dp) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val heading = subcompose("header") { Column { header() } }.single().measure(loose)
        val remaining = (availableHeight - heading.height.toDp()).coerceAtLeast(0.dp)
        val body = subcompose("body") { Column { content(remaining) } }.single().measure(loose)
        layout(maxOf(heading.width, body.width), heading.height + body.height) {
            heading.placeRelative(0, 0)
            body.placeRelative(0, heading.height)
        }
    }
}

internal fun scheduleFittedRowHeight(availableHeight: Dp, minimumHeight: Dp, periodCount: Int): Dp =
    if (periodCount <= 0) minimumHeight
    else maxOf(minimumHeight, minOf(80.dp, availableHeight / periodCount))

@Composable
private fun ScheduleDayCard(
    cell: ScheduleGridCell,
    subjectColors: Map<String, Long>,
    temporalStatus: ScheduleTemporalStatus?,
    onClick: () -> Unit,
) {
    val item = cell.item ?: cell.change?.semesterItem
    val colors = MaterialTheme.colorScheme
    val isRemoved = cell.change?.type == ScheduleChangeType.REMOVED
    val isEnded = temporalStatus == ScheduleTemporalStatus.ENDED
    val isCurrent = temporalStatus == ScheduleTemporalStatus.CURRENT
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = scheduleCellContentDescription(item?.dayOfWeek ?: cell.change?.dayOfWeek ?: 1, cell) +
                temporalStatus?.let { "，${it.label}" }.orEmpty()
        },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = when {
                cell.change != null -> colors.tertiaryContainer
                isCurrent -> colors.primaryContainer
                isEnded -> colors.surfaceContainerLowest
                else -> colors.surfaceContainerLow
            },
            contentColor = when {
                cell.change != null -> colors.onTertiaryContainer
                isCurrent -> colors.onPrimaryContainer
                isEnded -> colors.onSurfaceVariant
                else -> colors.onSurface
            },
        ),
        border = if (isCurrent) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.width(if (LocalDensity.current.fontScale > 1.5f) 72.dp else 60.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = schedulePeriodLabel(cell),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        cell.change != null -> colors.onTertiaryContainer
                        isCurrent -> colors.primary
                        isEnded -> colors.onSurfaceVariant
                        else -> colors.onSurface
                    },
                )
                scheduleCellTimeLabel(cell)?.let { timeRange ->
                    Text(
                        text = timeRange.replace("–", "\n"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                            lineHeight = 14.sp,
                        ),
                        color = when {
                            cell.change != null -> colors.onTertiaryContainer.copy(alpha = 0.8f)
                            isCurrent -> colors.onPrimaryContainer.copy(alpha = 0.85f)
                            else -> colors.onSurfaceVariant
                        },
                    )
                }
            }
            Box(
                Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(subjectColors[item?.subjectName]?.let { Color(it) } ?: colors.tertiary),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item?.subjectName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (isRemoved) TextDecoration.LineThrough else null,
                )
                val details = listOfNotNull(
                    item?.teacherName?.takeIf(::hasScheduleDetail),
                    item?.classroom?.takeIf(::hasScheduleDetail),
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            cell.change != null -> colors.onTertiaryContainer.copy(alpha = 0.85f)
                            isCurrent -> colors.onPrimaryContainer.copy(alpha = 0.85f)
                            else -> colors.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun scheduleWeekTitleStyle() = MaterialTheme.typography.labelMedium.copy(
    fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
    lineBreak = LineBreak.Heading, textAlign = TextAlign.Center,
)

@Composable
private fun scheduleWeekBadgeStyle() = MaterialTheme.typography.labelSmall.copy(
    fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
)

@Composable
private fun ScheduleWeekBoard(
    days: List<Int>,
    periods: List<Int>,
    cellsByDay: Map<Int, List<ScheduleGridCell>>,
    report: ScheduleReport?,
    now: LocalDateTime,
    currentDayOfWeek: Int?,
    subjectColors: Map<String, Long>,
    minimumRowHeight: Dp,
    availableHeight: Dp,
    timeColumnWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onCellClick: (ScheduleGridCell) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val divider = colors.outlineVariant.copy(alpha = 0.3f)
    val headerTextMeasurer = rememberTextMeasurer()
    val headerHeight = with(LocalDensity.current) {
        (headerTextMeasurer.measure("一", MaterialTheme.typography.labelLarge).size.height +
            headerTextMeasurer.measure("31", MaterialTheme.typography.labelSmall).size.height).toDp() + 16.dp
    }
    val today = currentDayOfWeek?.takeIf {
        report?.scope == ScheduleScope.CURRENT_WEEK && scheduleDayDate(report, it) == now.toLocalDate()
    }
    ScheduleMeasuredColumn(
        availableHeight = availableHeight,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerLow),
        header = {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(timeColumnWidth), contentAlignment = Alignment.Center) {
                    Text("節次", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                days.forEach { day ->
                    val date = report?.let { scheduleDayDate(it, day) }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = headerHeight)
                            .background(if (day == today) colors.primary.copy(alpha = 0.05f) else Color.Transparent)
                            .padding(vertical = 8.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "週${dayName(day)}" + date?.let { "，${it.year} 年 ${it.monthValue} 月 ${it.dayOfMonth} 日" }.orEmpty() +
                                    if (day == today) "，今天" else ""
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(dayName(day), style = MaterialTheme.typography.labelLarge,
                            color = if (day == today) colors.primary else colors.onSurface)
                        if (date != null) Text(date.dayOfMonth.toString(), minLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day == today) colors.primary else colors.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = divider)
        },
    ) { rowsAvailableHeight ->
        val rowHeight = scheduleFittedRowHeight(rowsAvailableHeight, minimumRowHeight, periods.size)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(timeColumnWidth)) {
                periods.forEach { period ->
                    Column(
                        Modifier.fillMaxWidth().height(rowHeight).drawWithContent {
                            drawContent()
                            drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height),
                                if (period == 4) 1.dp.toPx() else 0.5.dp.toPx())
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(period.toString(), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                        Text(PERIOD_TIMES.getOrNull(period - 1)?.multiLine.orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, fontFeatureSettings = "tnum"),
                            maxLines = 2, softWrap = false, textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
                    }
                }
            }
            days.forEach { day ->
                Column(Modifier.weight(1f).background(if (day == today) colors.primary.copy(alpha = 0.05f) else Color.Transparent)) {
                    cellsByDay.getValue(day).forEach { cell ->
                        Box(Modifier.fillMaxWidth().height(rowHeight * cell.periodCount).drawWithContent {
                            drawContent()
                            drawLine(divider, Offset(0f, 0f), Offset(0f, size.height), 0.5.dp.toPx())
                            // Only boundaries between cells are drawn, including across lunch.
                            drawLine(divider.copy(alpha = if (cell.period + cell.periodCount == 5) 0.65f else 0.3f),
                                Offset(0f, size.height), Offset(size.width, size.height),
                                if (cell.period + cell.periodCount == 5) 1.dp.toPx() else 0.5.dp.toPx())
                        }) {
                            if (cell.item != null || cell.change != null) {
                                ScheduleWeekTile(day, cell, subjectColors,
                                    scheduleCellTemporalStatus(report, day, cell, now), { onCellClick(cell) },
                                    bottomEndRadius = if (day == days.last() && cell.period + cell.periodCount - 1 == periods.last()) 16.dp else 0.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleWeekTile(
    day: Int,
    cell: ScheduleGridCell,
    subjectColors: Map<String, Long>,
    temporalStatus: ScheduleTemporalStatus?,
    onClick: () -> Unit,
    bottomEndRadius: Dp = 0.dp,
) {
    val colors = MaterialTheme.colorScheme
    val item = cell.item ?: cell.change?.semesterItem
    val isRemoved = cell.change?.type == ScheduleChangeType.REMOVED
    val isEnded = temporalStatus == ScheduleTemporalStatus.ENDED
    val isCurrent = temporalStatus == ScheduleTemporalStatus.CURRENT
    val contentColor = if (cell.change != null) colors.onTertiaryContainer else colors.onSurface
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxSize().semantics(mergeDescendants = true) {
            contentDescription = scheduleCellContentDescription(day, cell) + temporalStatus?.let { "，${it.label}" }.orEmpty()
        },
        shape = RoundedCornerShape(bottomEnd = bottomEndRadius),
        color = if (cell.change != null) colors.tertiaryContainer else Color.Transparent,
        contentColor = contentColor.copy(alpha = if (isEnded) 0.65f else 1f),
        border = if (isCurrent) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(18.dp, 3.dp).clip(RoundedCornerShape(2.dp))
                .background(Color(subjectColors[item?.subjectName] ?: 0L).copy(alpha = if (isEnded) 0.45f else 1f)))
            Text(
                text = item?.subjectName.orEmpty(), style = scheduleWeekTitleStyle(),
                textDecoration = if (isRemoved) TextDecoration.LineThrough else null,
                maxLines = if (cell.periodCount > 1) 5 else 3, overflow = TextOverflow.Ellipsis,
            )
            if (cell.periodCount > 1) item?.classroom?.takeIf(::hasScheduleDetail)?.let { room ->
                Text(room, style = scheduleWeekBadgeStyle(), color = contentColor.copy(alpha = 0.65f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
private fun ScheduleDetailSheet(
    item: ScheduleItem,
    originalItems: List<ScheduleItem>,
    existingOverride: ScheduleSubjectOverride?,
    isSaving: Boolean,
    onSave: (ScheduleSubjectOverride, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var isEditing by remember(item.subjectName, existingOverride) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        ScheduleSubjectOverrideEditor(
            originalSubjectName = item.subjectName,
            originalItems = originalItems,
            existingOverride = existingOverride,
            isEditing = isEditing,
            isSaving = isSaving,
            onEdit = { isEditing = true },
            onCancel = { isEditing = false },
            onSave = { override -> onSave(override) { isEditing = false } },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
private fun ScheduleChangeDetailSheet(
    change: ScheduleChange,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        ScheduleChangeDetailContent(change)
    }
}

@Composable
internal fun ScheduleChangeDetailContent(change: ScheduleChange) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(
                    text = scheduleChangeTitle(change.type),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

        }

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScheduleComparisonCard(
                title = "學期課表",
                item = change.semesterItem,
                emptyText = "無排課",
                isHighlighted = false,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ScheduleComparisonCard(
                title = "本週課表",
                item = change.weekItem,
                emptyText = "無",
                isHighlighted = true,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

internal fun scheduleChangeTitle(type: ScheduleChangeType): String = when (type) {
    ScheduleChangeType.ADDED -> "加課"
    ScheduleChangeType.MODIFIED -> "調課"
    ScheduleChangeType.REMOVED -> "停課"
}

@Composable
private fun ScheduleComparisonCard(
    title: String,
    item: ScheduleItem?,
    emptyText: String,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) colors.tertiaryContainer.copy(alpha = 0.55f) else colors.surfaceContainer,
            contentColor = if (isHighlighted) colors.onTertiaryContainer else colors.onSurface,
        ),
        border = if (isHighlighted) BorderStroke(1.dp, colors.tertiary.copy(alpha = 0.5f)) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (isHighlighted) colors.tertiary else colors.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = item?.subjectName ?: emptyText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            item?.teacherName?.takeIf(::hasScheduleDetail)?.let { teacher ->
                Text(
                    text = "教師：$teacher",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isHighlighted) colors.onTertiaryContainer.copy(alpha = 0.85f) else colors.onSurfaceVariant,
                )
            }
            item?.classroom?.takeIf(::hasScheduleDetail)?.let { classroom ->
                Text(
                    text = "教室：$classroom",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isHighlighted) colors.onTertiaryContainer.copy(alpha = 0.85f) else colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun dayName(dayOfWeek: Int): String =
    listOf("", "一", "二", "三", "四", "五", "六", "日").getOrElse(dayOfWeek) { dayOfWeek.toString() }

private fun hasScheduleDetail(value: String): Boolean = value.isNotBlank() && value != "null"
