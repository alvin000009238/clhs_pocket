package com.clhs.score.ui.schedule

import com.clhs.score.ui.rootBodyMotion

import androidx.compose.foundation.layout.Spacer

import com.clhs.score.ui.navigation.navigationContentPadding

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.clhs.score.data.shouldRefreshAt
import com.clhs.score.ui.hapticClick
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.FilledRoundedSymbol
import com.clhs.score.ui.AccountIconButton
import com.clhs.score.viewmodel.ScheduleUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

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
    onConfirmSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onNoticeShown: () -> Unit,
    onSaveSubjectOverride: (ScheduleSubjectOverride, () -> Unit) -> Unit = { _, onSaved -> onSaved() },
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showMoreMenu by remember { mutableStateOf(false) }
    val captureLayer = rememberGraphicsLayer()
    val snackbarHostState = remember { SnackbarHostState() }
    var scheduleNow by remember(uiState.report) { mutableStateOf(LocalDateTime.now(SCHOOL_COURSE_ZONE)) }
    LaunchedEffect(uiState.report) {
        val report = uiState.report ?: return@LaunchedEffect
        val refreshAt = report.refreshAt() ?: return@LaunchedEffect
        if (scheduleNow.isBefore(refreshAt)) {
            delay(Duration.between(scheduleNow, refreshAt).toMillis().milliseconds)
            scheduleNow = LocalDateTime.now(SCHOOL_COURSE_ZONE)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now(SCHOOL_COURSE_ZONE)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1).milliseconds)
            scheduleNow = LocalDateTime.now(SCHOOL_COURSE_ZONE)
        }
    }
    val isRefreshingWeekReport =
        uiState.report?.scope == ScheduleScope.CURRENT_WEEK && uiState.isLoading
    val isWeekReportExpired = uiState.report?.shouldRefreshAt(scheduleNow) == true
    val reloadWarning = isWeekReportExpired || uiState.refreshFailed
    val reloadDescription = when {
        isRefreshingWeekReport -> "正在重新載入本週課表"
        uiState.refreshFailed -> "更新失敗，顯示已儲存課表；點擊重新載入"
        isWeekReportExpired -> "週課表已過期，點擊重新載入"
        else -> "強制重新載入本週課表"
    }
    val bodyMotion = Modifier.rootBodyMotion()

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
                IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape)) {
                    OutlinedRoundedSymbol("arrow_back", contentDescription = "返回")
                }
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
                                contentDescription = "強制重新載入本週課表",
                            )
                        }
                    }
                }
            }
                    IconButton(
                        onClick = { showMoreMenu = true },
                        shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape),
                    ) {
                        OutlinedRoundedSymbol(icon = "more_vert", contentDescription = "更多選項")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        offset = DpOffset(x = 0.dp, y = 8.dp),
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
    ) { padding, header ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 1200.dp)
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
                        .padding(padding)
                        .padding(top = 64.dp)
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    var yearExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = yearExpanded,
                        onExpandedChange = { yearExpanded = !yearExpanded },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.availableYears.find { it.value == uiState.selectedYearValue }?.text ?: "選擇學期",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            singleLine = true
                        )
                        DropdownMenu(
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
                                    }
                                )
                            }
                        }
                    }

                    var classExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = classExpanded,
                        onExpandedChange = { classExpanded = !classExpanded },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.availableClasses.find { it.value == uiState.selectedClassValue }?.text ?: "選擇班級",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = classExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            singleLine = true
                        )
                        DropdownMenu(
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
                                    }
                                )
                            }
                        }
                    }

                    Text(
                        text = "課表類型",
                        style = MaterialTheme.typography.labelLarge,
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
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isLoading && uiState.selectedYearValue != null,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        if (uiState.isLoading) {
                            LoadingIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("查詢課表")
                        }
                    }

                    if (uiState.isError) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 16.dp),
                            textAlign = TextAlign.Center
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
            } else if (uiState.report.items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(top = 64.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("查無課表資料", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "請重新選擇學期或班級",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = onClearSelection,
                        modifier = Modifier.padding(top = 16.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text("重新選擇")
                    }
                }
            } else {
                // Grid Screen
                uiState.report.let { report ->
                    Surface(modifier = Modifier.fillMaxSize().drawWithContent {
                        captureLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(captureLayer)
                    }) {
                        ScheduleGrid(
                            items = report.items,
                            contentPadding = padding,
                            header = header,
                            bodyMotion = bodyMotion,
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

private suspend fun saveBitmapToGallery(context: Context, layer: GraphicsLayer): Result<String> =
    try {
        val bitmap = withContext(Dispatchers.Main) {
            if (layer.size.width <= 0 || layer.size.height <= 0) {
                throw IOException("課表尚未完成排版")
            }
            layer.toImageBitmap().asAndroidBitmap()
        }
        try {
            val filename = "Schedule_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Schedules")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("無法建立圖片檔")
                try {
                    val output: OutputStream = resolver.openOutputStream(imageUri)
                        ?: throw IOException("無法開啟圖片檔")
                    output.use {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                            throw IOException("圖片壓縮失敗")
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                } catch (error: Exception) {
                    resolver.delete(imageUri, null, null)
                    throw error
                }
            }
            Result.success(filename)
        } finally {
            bitmap.recycle()
        }
    } catch (error: Exception) {
        Result.failure(error)
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

internal fun scheduleGridPeriods(
    items: List<ScheduleItem>,
    changes: List<ScheduleChange>,
): List<Int> =
    ((1..PERIOD_TIMES.size) + items.map { it.period } + changes.map { it.period })
        .filter { it > 0 }
        .distinct()
        .sorted()

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
    bodyMotion: Modifier = Modifier,
    isSavingSubjectOverride: Boolean = false,
    onSaveSubjectOverride: (ScheduleSubjectOverride, () -> Unit) -> Unit = { _, onSaved -> onSaved() },
) {
    val scheduleColorScheme = MaterialTheme.colorScheme
    val displayedItems = remember(items, subjectOverrides) {
        items.applySubjectOverrides(subjectOverrides)
    }
    val displayedChanges = remember(changes, subjectOverrides) {
        changes.applySubjectOverrides(subjectOverrides)
    }
    val days = listOf("時間") + WEEKDAY_LABELS
    val periods = remember(displayedItems, displayedChanges) {
        scheduleGridPeriods(displayedItems, displayedChanges)
    }
    val cellsByDay = remember(displayedItems, displayedChanges, periods) {
        (1..5).associateWith { day -> scheduleGridCells(displayedItems, displayedChanges, day, periods) }
    }
    val originalItemsBySlot = remember(items) { items.associateBy { it.dayOfWeek to it.period } }
    val isDarkTheme = scheduleColorScheme.background.luminance() < 0.5f
    val subjectColors = remember(displayedItems, displayedChanges, isDarkTheme) {
        getSubjectColors(
            displayedItems.map { it.subjectName } +
                displayedChanges.mapNotNull { it.weekItem?.subjectName },
            isDarkTheme = isDarkTheme,
        )
    }
    val rootScroll = rememberScrollState()
    com.clhs.score.ui.navigation.RegisterRootScroll(rootScroll)
    var selectedItem by remember { mutableStateOf<ScheduleItem?>(null) }
    var selectedChange by remember { mutableStateOf<ScheduleChange?>(null) }

    Column(modifier = modifier.verticalScroll(rootScroll).navigationContentPadding()
        .padding(contentPadding)) {
        Box(Modifier.padding(horizontal = 8.dp)) { header() }
        Spacer(Modifier.height(8.dp))
        Column(bodyMotion) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                days.forEachIndexed { dayOfWeek, day ->
                    val isCurrentDay = dayOfWeek in 1..5 && dayOfWeek == currentDayOfWeek
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .semantics(mergeDescendants = true) {
                                if (isCurrentDay) contentDescription = "$day，今天"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = day,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (isCurrentDay) scheduleColorScheme.primary else Color.Transparent,
                                )
                                .padding(horizontal = 12.dp),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isCurrentDay) {
                                scheduleColorScheme.onPrimary
                            } else {
                                Color.Unspecified
                            },
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    periods.forEach { period ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .padding(bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(text = period.toString(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                text = PERIOD_TIMES.getOrNull(period - 1)?.multiLine ?: "",
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true
                            )
                        }
                    }
                }

                for (day in 1..5) {
                    Column(modifier = Modifier.weight(1f)) {
                        cellsByDay.getValue(day).forEach { cell ->
                            val change = cell.change
                            val item = cell.item
                            Box(
                                modifier = Modifier
                                    .height(80.dp * cell.periodCount)
                                    .padding(horizontal = 2.dp)
                                    .padding(bottom = 8.dp),
                            ) {
                                if (change?.type == ScheduleChangeType.REMOVED) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = scheduleCellContentDescription(day, cell)
                                            }
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(
                                                role = Role.Button,
                                                onClickLabel = "查看課程詳情",
                                                onClick = { selectedChange = change },
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        ),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Text(
                                                text = "停課",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                } else if (item != null) {
                                    val tileColors = scheduleTileColors(
                                        subjectColors.getValue(item.subjectName),
                                        if (change == null) {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        } else {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        },
                                        if (change == null) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        },
                                    )
                                    val shortName = item.subjectName.split("-")[0]
                                    Card(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = scheduleCellContentDescription(day, cell)
                                            }
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(
                                                role = Role.Button,
                                                onClickLabel = "查看課程詳情",
                                                onClick = {
                                                    if (change != null) {
                                                        selectedChange = change
                                                    } else {
                                                        selectedItem = originalItemsBySlot[item.dayOfWeek to item.period]
                                                            ?: item
                                                    }
                                                },
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = tileColors.container),
                                    ) {
                                        Row(modifier = Modifier.fillMaxSize()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .width(4.dp)
                                                    .background(tileColors.accent),
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .padding(4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                            ) {
                                                Text(
                                                    text = shortName,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 2,
                                                    color = tileColors.content,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        val override = subjectOverrides.firstOrNull { it.originalSubjectName == item.subjectName }
        ScheduleDetailSheet(
            item = item,
            existingOverride = override,
            isSaving = isSavingSubjectOverride,
            onSave = onSaveSubjectOverride,
            onDismiss = { selectedItem = null },
        )
    }
    selectedChange?.let { change ->
        ScheduleChangeDetailSheet(
            change = change,
            onDismiss = { selectedChange = null },
        )
    }
}

private data class ScheduleTileColors(
    val container: Color,
    val content: Color,
    val accent: Color,
)

private fun scheduleTileColors(
    subjectColor: Long,
    containerColor: Color,
    contentColor: Color,
): ScheduleTileColors = ScheduleTileColors(
    container = containerColor,
    content = contentColor,
    accent = Color(subjectColor),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
private fun ScheduleDetailSheet(
    item: ScheduleItem,
    existingOverride: ScheduleSubjectOverride?,
    isSaving: Boolean,
    onSave: (ScheduleSubjectOverride, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var isEditing by remember(item.subjectName, existingOverride) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        ScheduleSubjectOverrideEditor(
            originalSubjectName = item.subjectName,
            originalItems = listOf(item),
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = scheduleChangeTitle(change.type),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "週${dayName(change.dayOfWeek)} · 第 ${change.period} 節",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScheduleComparisonCard(
                    title = "學期課表",
                    item = change.semesterItem,
                    emptyText = "無排課",
                    modifier = Modifier.weight(1f),
                )
                ScheduleComparisonCard(
                    title = "本週課表",
                    item = change.weekItem,
                    emptyText = "無",
                    modifier = Modifier.weight(1f),
                )
            }

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
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = item?.subjectName ?: emptyText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            item?.teacherName?.takeIf(::hasScheduleDetail)?.let { teacher ->
                Text(
                    text = "教師：$teacher",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item?.classroom?.takeIf(::hasScheduleDetail)?.let { classroom ->
                Text(
                    text = "教室：$classroom",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun dayName(dayOfWeek: Int): String =
    listOf("", "一", "二", "三", "四", "五", "六", "日").getOrElse(dayOfWeek) { dayOfWeek.toString() }

private fun hasScheduleDetail(value: String): Boolean = value.isNotBlank() && value != "null"
