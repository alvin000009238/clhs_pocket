package com.clhs.score.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonShapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clhs.score.data.ExamSelection
import com.clhs.score.data.ExamSummary
import com.clhs.score.data.GradeAnalysis
import com.clhs.score.data.GradeChangeSet
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.GradeReminderText
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeTrend
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.SubjectScore
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.parseYearTerm
import com.clhs.score.data.shortenSubjectName
import com.clhs.score.notifications.canPostNotifications
import com.clhs.score.notifications.hasPostNotificationsPermission
import com.clhs.score.notifications.openAppNotificationSettings
import com.clhs.score.notifications.shouldShowPostNotificationsRationale
import com.clhs.score.reminders.BatteryOptimizationHelper
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.GradesUiState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal enum class GradesAdaptiveLayout {
    SingleColumn,
    TwoColumn,
    ListDetail,
}

internal fun gradesAdaptiveLayoutForWidth(width: Dp): GradesAdaptiveLayout = when {
    width < 600.dp -> GradesAdaptiveLayout.SingleColumn
    width < 840.dp -> GradesAdaptiveLayout.TwoColumn
    else -> GradesAdaptiveLayout.ListDetail
}

private enum class GradesDestination(val label: String) {
    Overview("摘要"),
    Subjects("科目"),
    Analysis("分析"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GradesScreen(
    state: GradesUiState,
    snackbarHost: @Composable () -> Unit,
    onSelectYear: (String) -> Unit,
    onSelectExam: (String) -> Unit,
    onReload: () -> Unit,
    onToggleSubject: (String) -> Unit,
    onStartGradeReminder: () -> Unit,
    onStopGradeReminder: () -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onGradeReminderPrerequisiteFailed: (String) -> Unit,
    onDismissGradeReminderChanges: () -> Unit,
    onOpenPersonal: () -> Unit,
    showUpdateBadge: Boolean = false,
    onOpenScoreSimulator: () -> Unit,
    onOpenSubjectTrend: () -> Unit,
    onExportGrades: (List<ExamSelection>) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStartGradeReminder by rememberUpdatedState(onStartGradeReminder)
    val currentOnSetNotificationsEnabled by rememberUpdatedState(onSetNotificationsEnabled)
    val currentOnGradeReminderPrerequisiteFailed by rememberUpdatedState(onGradeReminderPrerequisiteFailed)
    val waitingForNotificationGrant = rememberSaveable { mutableStateOf(false) }
    val waitingForBatteryOptimizationGrant = rememberSaveable { mutableStateOf(false) }
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }
    var showGradeReminderDetails by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var isNotificationPermissionGranted by remember { mutableStateOf(context.hasPostNotificationsPermission()) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }

    val pagerState = rememberPagerState(
        initialPage = GradesDestination.Overview.ordinal,
        pageCount = { GradesDestination.entries.size },
    )
    val pagerScope = rememberCoroutineScope()
    val isRefreshing = state.isLoadingStructure || state.isLoadingGrades
    val pullToRefreshState = rememberPullToRefreshState()
    val overviewScrollState = rememberScrollState()
    val subjectsScrollState = rememberScrollState()
    val analysisScrollState = rememberScrollState()
    fun refreshReminderPrerequisites() {
        isNotificationPermissionGranted = context.hasPostNotificationsPermission()
        isBatteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
    }

    fun requestBatteryOptimizationOrStart() {
        refreshReminderPrerequisites()
        if (isBatteryOptimizationIgnored) {
            currentOnStartGradeReminder()
            return
        }
        val activity = context.findActivity()
        if (activity == null) {
            currentOnGradeReminderPrerequisiteFailed("需要開啟電池最佳化設定，才能準時提醒你。")
            return
        }
        waitingForBatteryOptimizationGrant.value = true
        if (!BatteryOptimizationHelper.openBatteryOptimizationRequest(activity)) {
            waitingForBatteryOptimizationGrant.value = false
            currentOnGradeReminderPrerequisiteFailed("需要開啟電池最佳化設定，才能準時提醒你。")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        isNotificationPermissionGranted = granted
        if (granted) {
            currentOnSetNotificationsEnabled(true)
            requestBatteryOptimizationOrStart()
        } else {
            notificationPermissionDenied = true
            currentOnGradeReminderPrerequisiteFailed("未取得通知權限，可再次嘗試啟用")
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver observer@{ _, event ->
            if (event != Lifecycle.Event.ON_RESUME) {
                return@observer
            }
            val notificationGranted = context.hasPostNotificationsPermission()
            val canPostNotifications = context.canPostNotifications()
            val batteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            isNotificationPermissionGranted = notificationGranted
            isBatteryOptimizationIgnored = batteryOptimizationIgnored
            if (waitingForNotificationGrant.value) {
                waitingForNotificationGrant.value = false
                if (canPostNotifications) {
                    currentOnSetNotificationsEnabled(true)
                    requestBatteryOptimizationOrStart()
                } else {
                    currentOnGradeReminderPrerequisiteFailed("未取得通知權限，無法啟用段考更新提醒")
                }
                return@observer
            }
            if (waitingForBatteryOptimizationGrant.value) {
                waitingForBatteryOptimizationGrant.value = false
                if (batteryOptimizationIgnored) {
                    currentOnStartGradeReminder()
                } else {
                    currentOnGradeReminderPrerequisiteFailed("需要開啟電池最佳化設定，才能準時提醒你。")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun beginGradeReminderEnablement() {
        if (context.canPostNotifications()) {
            currentOnSetNotificationsEnabled(true)
            requestBatteryOptimizationOrStart()
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPostNotificationsPermission() &&
            (
                !notificationPermissionDenied ||
                    context.shouldShowPostNotificationsRationale()
            )
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            waitingForNotificationGrant.value = true
            if (!context.openAppNotificationSettings()) {
                waitingForNotificationGrant.value = false
                currentOnGradeReminderPrerequisiteFailed("無法開啟通知設定，請手動到系統設定開啟")
            }
        }
    }
    if (showGradeReminderDetails) {
        GradeReminderDetailsDialog(
            reminderState = state.gradeReminderState,
            studentNo = state.studentNo,
            selectedYearValue = state.selectedYearValue,
            selectedExamValue = state.selectedExamValue,
            isStarting = state.isStartingGradeReminder,
            isNotificationPermissionGranted = isNotificationPermissionGranted,
            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
            onStart = {
                showGradeReminderDetails = false
                beginGradeReminderEnablement()
            },
            onStop = {
                showGradeReminderDetails = false
                onStopGradeReminder()
            },
            onDismiss = { showGradeReminderDetails = false },
        )
    }
    if (showExportDialog) {
        ExportDialog(
            structure = state.structure,
            onConfirm = {
                showExportDialog = false
                onExportGrades(it)
            },
            onDismiss = { showExportDialog = false },
        )
    }
    state.gradeReminderChangeSet?.let { changeSet ->
        GradeReminderChangeDialog(
            changeSet = changeSet,
            onDismiss = onDismissGradeReminderChanges,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                RootTopAppBar(
                    title = "成績",
                    actions = {
                        GradeSelectionPill(
                            state = state,
                            onSelectYear = onSelectYear,
                            onSelectExam = onSelectExam,
                        )
                        Box {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                OutlinedRoundedSymbol(
                                    icon = "more_vert",
                                    contentDescription = "更多選項",
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                offset = DpOffset(x = 0.dp, y = 8.dp),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("段考更新提醒") },
                                    onClick = {
                                        showMoreMenu = false
                                        showGradeReminderDetails = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("匯出成績") },
                                    enabled = !state.isExporting,
                                    onClick = {
                                        showMoreMenu = false
                                        showExportDialog = true
                                    },
                                )
                            }
                        }
                        AccountIconButton(onClick = onOpenPersonal, showUpdateBadge = showUpdateBadge)
                    },
                )
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    GradesDestination.entries.forEach { destination ->
                        Tab(
                            selected = pagerState.currentPage == destination.ordinal,
                            onClick = {
                                pagerScope.launch {
                                    pagerState.animateScrollToPage(destination.ordinal)
                                }
                            },
                            text = {
                                Text(
                                    text = destination.label,
                                    fontWeight = if (pagerState.currentPage == destination.ordinal) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Medium
                                    },
                                )
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = snackbarHost,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onReload,
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            indicator = {},
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val report = state.report
                val analysis = state.analysis
                when {
                    report == null && (state.isLoadingStructure || state.isLoadingGrades) -> GradesTabPage(
                        scrollState = overviewScrollState,
                    ) { OverviewSkeleton() }

                    report == null -> GradesTabPage(
                        scrollState = overviewScrollState,
                    ) {
                        EmptyPanel(
                            message = when {
                                state.structure.isEmpty() -> "尚未取得可查詢考試"
                                state.structure
                                    .firstOrNull { it.value == state.selectedYearValue }
                                    ?.exams
                                    ?.isEmpty() == true -> "此學期尚無可查詢的考試"
                                else -> "請選擇考試"
                            },
                            onReload = onReload,
                        )
                    }

                    analysis == null -> GradesTabPage(
                        scrollState = overviewScrollState,
                    ) { OverviewSkeleton() }

                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = GradesDestination.entries.lastIndex,
                    ) { destination ->
                        val tabScrollState = when (destination) {
                            GradesDestination.Overview.ordinal -> overviewScrollState
                            GradesDestination.Subjects.ordinal -> subjectsScrollState
                            else -> analysisScrollState
                        }
                        GradesTabPage(
                            scrollState = tabScrollState,
                        ) {
                            when (destination) {
                                GradesDestination.Overview.ordinal -> OverviewTab(
                                    report = report,
                                    analysis = analysis,
                                    isLoadingComparison = state.isLoadingComparison,
                                    comparisonError = state.comparisonError,
                                    isLoadingTrend = state.isLoadingTrend,
                                    trendError = state.trendError,
                                    trend = state.trend,
                                )

                                GradesDestination.Subjects.ordinal -> SubjectsTab(
                                    analyses = analysis.subjects,
                                    expandedSubjectKeys = state.expandedSubjectKeys,
                                    onToggleSubject = onToggleSubject,
                                )

                                GradesDestination.Analysis.ordinal -> AdvancedTab(
                                    report = report,
                                    analysis = analysis,
                                    isLoadingTrend = state.isLoadingTrend,
                                    trendError = state.trendError,
                                    trend = state.trend,
                                    onOpenScoreSimulator = onOpenScoreSimulator,
                                    onOpenSubjectTrend = onOpenSubjectTrend,
                                )
                            }
                        }
                    }
                }

                PullToRefreshDefaults.LoadingIndicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(2f),
                )
            }
        }
    }
}

@Composable
private fun GradesTabPage(
    scrollState: ScrollState,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 1200.dp)
                .fillMaxWidth()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GradeSelectionPill(
    state: GradesUiState,
    onSelectYear: (String) -> Unit,
    onSelectExam: (String) -> Unit,
) {
    val selectedYear = state.structure.find { it.value == state.selectedYearValue }
    val selectedYearLabel = selectedYear?.let(::compactYearTermLabel)
    val selectedExamLabel = selectedYear?.exams
        ?.firstOrNull { it.value == state.selectedExamValue }
        ?.text
    val enabled = state.structure.isNotEmpty() && !state.isLoadingStructure
    var yearMenuExpanded by remember { mutableStateOf(false) }
    var examMenuExpanded by remember { mutableStateOf(false) }
    val buttonContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
    val buttonContentColor = MaterialTheme.colorScheme.onSurface
    val buttonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = buttonContainerColor,
        contentColor = buttonContentColor,
        disabledContainerColor = buttonContainerColor.copy(alpha = 0.38f),
        disabledContentColor = buttonContentColor.copy(alpha = 0.42f),
    )
    val leadingButtonShapes = SplitButtonDefaults.leadingButtonShapesFor(48.dp)
    val examButtonShapes = SplitButtonDefaults.trailingButtonShapesFor(48.dp)
    val yearButtonShapes = SplitButtonShapes(
        shape = leadingButtonShapes.shape,
        pressedShape = leadingButtonShapes.pressedShape,
        checkedShape = examButtonShapes.checkedShape,
    )

    Box {
        SplitButtonLayout(
            leadingButton = {
                Box {
                    SplitButtonDefaults.TonalTrailingButton(
                        modifier = Modifier.height(48.dp),
                        checked = yearMenuExpanded,
                        enabled = enabled,
                        shapes = yearButtonShapes,
                        colors = buttonColors,
                        contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(48.dp),
                        onCheckedChange = { checked ->
                            yearMenuExpanded = checked
                            if (checked) {
                                examMenuExpanded = false
                            }
                        },
                    ) {
                        Text(
                            text = selectedYearLabel ?: if (state.isLoadingStructure) "載入中" else "選擇學年度",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = yearMenuExpanded,
                        onDismissRequest = { yearMenuExpanded = false },
                        offset = DpOffset(x = 0.dp, y = 8.dp),
                    ) {
                        state.structure.forEach { year ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = compactYearTermLabel(year),
                                        fontWeight = if (year.value == state.selectedYearValue) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                trailingIcon = {
                                    if (year.value == state.selectedYearValue) {
                                        FilledRoundedSymbol(icon = "check", contentDescription = null)
                                    }
                                },
                                onClick = {
                                    yearMenuExpanded = false
                                    if (year.value != state.selectedYearValue) {
                                        onSelectYear(year.value)
                                    }
                                },
                            )
                        }
                    }
                }
            },
            trailingButton = {
                Box {
                    SplitButtonDefaults.TonalTrailingButton(
                        modifier = Modifier.height(48.dp),
                        checked = examMenuExpanded,
                        enabled = enabled,
                        shapes = examButtonShapes,
                        colors = buttonColors,
                        onCheckedChange = { checked ->
                            examMenuExpanded = checked
                            if (checked) {
                                yearMenuExpanded = false
                            }
                        },
                    ) {
                        Text(
                            text = selectedExamLabel ?: if (state.isLoadingStructure) "載入中" else "選擇考試",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = examMenuExpanded,
                        onDismissRequest = { examMenuExpanded = false },
                        offset = DpOffset(x = 0.dp, y = 8.dp),
                    ) {
                        if (selectedYear == null || selectedYear.exams.isEmpty()) {
                            DropdownMenuItem(
                                enabled = false,
                                text = { Text("此學期沒有考試資料") },
                                onClick = {},
                            )
                        } else {
                            selectedYear.exams.forEach { exam ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = exam.text,
                                            fontWeight = if (exam.value == state.selectedExamValue) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
                                    trailingIcon = {
                                        if (exam.value == state.selectedExamValue) {
                                            FilledRoundedSymbol(icon = "check", contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        examMenuExpanded = false
                                        onSelectExam(exam.value)
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

private fun compactYearTermLabel(yearTerm: YearTermOption): String {
    val (year, term) = parseYearTerm(yearTerm.value, defaultYear = "", defaultTerm = "")
    val termLabel = when (term) {
        "1" -> "上"
        "2" -> "下"
        else -> term
    }
    return when {
        year.isNotBlank() && termLabel.isNotBlank() -> "$year-$termLabel"
        year.isNotBlank() -> year
        else -> yearTerm.text
    }
}

@Composable
private fun OverviewTab(
    report: GradeReport,
    analysis: GradeAnalysis,
    isLoadingComparison: Boolean,
    comparisonError: String?,
    isLoadingTrend: Boolean,
    trendError: String?,
    trend: GradeTrend?,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HeroCard(report = report, analysis = analysis)
        SubjectSnapshotCard(analysis = analysis)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HistorySummaryCard(
                analysis = analysis,
                isLoadingComparison = isLoadingComparison,
                comparisonError = comparisonError,
                isLoadingTrend = isLoadingTrend,
                trendError = trendError,
                trend = trend,
            )
            report.studentInfo.updatedAt.takeIf(String::isNotBlank)?.let { updatedAt ->
                Text(
                    text = updatedAt,
                    modifier = Modifier.padding(end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GradeReminderDetailsDialog(
    reminderState: GradeReminderState,
    studentNo: String,
    selectedYearValue: String?,
    selectedExamValue: String?,
    isStarting: Boolean,
    isNotificationPermissionGranted: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val isActiveForSelection = reminderState.isActiveFor(
        studentNo = studentNo,
        yearValue = selectedYearValue,
        examValue = selectedExamValue,
        nowMillis = now,
    )
    val lastCheckedText = reminderState.lastCheckedAtMillis?.let { "上次檢查時間 ${formatReminderTime(it)}" }
        ?: "尚未檢查"
    val otherExamName = reminderState.examName.ifBlank { "段考" }
    val primaryActionLabel = when {
        isActiveForSelection -> "停止"
        isStarting -> "啟用中..."
        !isNotificationPermissionGranted -> "開啟通知設定"
        isBatteryOptimizationIgnored -> "開始"
        else -> "開啟電池設定"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("段考更新提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "新成績、排名、五標等會在有變動時通知你。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    isActiveForSelection -> {
                        InlineStatus(message = "正在監控這次考試，每 15 分鐘會檢查一次更新")
                        Text(
                            text = "${formatRemaining(reminderState.expiresAtMillis, now)}後自動停止 · $lastCheckedText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    reminderState.isActive(now) -> {
                        InlineStatus(message = "目前正在監控其他考試")
                        Text(
                            text = otherExamName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    !isNotificationPermissionGranted && !isBatteryOptimizationIgnored -> {
                        InlineStatus(message = "需要開啟通知和電池最佳化設定")
                        Text(
                            text = "通知用來提醒你；電池最佳化設定能讓 app 在背景檢查更新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    !isNotificationPermissionGranted -> {
                        InlineStatus(message = "需要開啟通知")
                        Text(
                            text = "允許 app 傳送通知，成績資訊更新時才能提醒你。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    !isBatteryOptimizationIgnored -> {
                        InlineStatus(message = "需要開啟電池最佳化設定")
                        Text(
                            text = "允許 app 不受電池最佳化限制，才能在背景檢查更新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        InlineStatus(message = "點擊開始以繼續")
                    }
                }
            }
        },
        confirmButton = {
            if (isActiveForSelection) {
                TextButton(
                    enabled = !isStarting,
                    onClick = onStop,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(primaryActionLabel)
                }
            } else {
                TextButton(
                    enabled = !isStarting,
                    onClick = onStart,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(primaryActionLabel)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("關閉")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GradeReminderChangeDialog(
    changeSet: GradeChangeSet,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("成績資訊已更新") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = changeSet.examName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                GradeReminderText.detailLines(changeSet).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("知道了")
            }
        },
    )
}

@Composable
private fun HeroCard(
    report: GradeReport,
    analysis: GradeAnalysis,
) {
    val student = report.studentInfo
    val summary = report.examSummary
    val animatedAverage by animateFloatAsState(
        targetValue = analysis.weightedAverage.toFloat(),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "weightedAverage",
    )
    val totalScore = summary?.totalScoreDisplay?.toDoubleOrNull()?.let { "%.0f".format(it) }
        ?: summary?.totalScoreDisplay?.takeIf { it.isNotBlank() }
        ?: "--"
    val rankLine = heroRankLine(summary, student)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "加權平均",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "%.1f".format(animatedAverage),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                        )
                        val deltaText = heroAverageDeltaTextShort(analysis)
                        if (deltaText != null) {
                            val deltaColor = diffColor(analysis.comparison?.averageDelta ?: 0.0)
                            Surface(
                                shape = CircleShape,
                                color = deltaColor.copy(alpha = 0.15f),
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                Text(
                                    text = deltaText,
                                    color = deltaColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = "總分 $totalScore",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = rankLine,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            )
            HeroChipFlow(analysis = analysis)
        }
    }
}

@Composable
private fun HeroChip(
    text: String,
    containerColor: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HeroChipFlow(analysis: GradeAnalysis) {
    val classPercent = analysis.classPercentile?.topPercent
    val categoryPercent = analysis.categoryPercentile?.topPercent
    val chipContainerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
    val chipContentColor = MaterialTheme.colorScheme.onPrimaryContainer

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (classPercent != null) {
            HeroChip(
                text = "班級前 $classPercent%",
                containerColor = chipContainerColor,
                contentColor = chipContentColor,
            )
        }
        if (categoryPercent != null) {
            HeroChip(
                text = "類組前 $categoryPercent%",
                containerColor = chipContainerColor,
                contentColor = chipContentColor,
            )
        }
    }
}

private fun heroRankLine(summary: ExamSummary?, student: StudentInfo): String {
    val classRank = formatRank(summary?.classRank, summary?.classCount, student.showClassRankCount)
    val categoryRank = formatRank(summary?.categoryRank, summary?.categoryRankCount, student.showCategoryRankCount)
    return "班排 ${if (student.showClassRank) classRank else "--"} ・ 類排 ${if (student.showCategoryRank) categoryRank else "--"}"
}

private fun heroAverageDeltaTextShort(analysis: GradeAnalysis): String? {
    val delta = analysis.comparison?.averageDelta ?: return null
    return when {
        delta > 0.05 -> "↑ +${"%.1f".format(delta)}"
        delta < -0.05 -> "↓ ${"%.1f".format(delta)}"
        else -> "→ 持平"
    }
}

@Composable
private fun HistorySummaryCard(
    analysis: GradeAnalysis,
    isLoadingComparison: Boolean,
    comparisonError: String?,
    isLoadingTrend: Boolean,
    trendError: String?,
    trend: GradeTrend?,
) {
    val hasPartialHistory = comparisonError == "部分資料無法載入" ||
        trendError == "部分資料無法載入"
    val hasComparisonStatus = isLoadingComparison || analysis.comparison != null ||
        (comparisonError != null && !hasPartialHistory)
    val hasTrendStatus = isLoadingTrend || (trend != null && trend.points.size >= 2) ||
        (trendError != null && !hasPartialHistory)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "近期變化",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                isLoadingComparison -> InlineStatus("正在載入上一考比較...")
                analysis.comparison != null -> InlineStatus("${analysis.comparison.previousExamName}：${analysis.comparison.summaryText}")
                comparisonError != null && !hasPartialHistory -> InlineStatus(comparisonError)
            }
            when {
                isLoadingTrend -> InlineStatus("正在載入歷次趨勢...")
                trend != null && trend.points.size >= 2 -> InlineStatus("近 ${trend.points.size} 次平均：${trend.averageLine}")
                trendError != null && !hasPartialHistory -> InlineStatus(trendError)
            }
            if (hasPartialHistory) {
                InlineStatus("部分資料無法載入")
            } else if (!hasComparisonStatus && !hasTrendStatus) {
                InlineStatus("目前沒有足夠的歷次資料")
            }
        }
    }
}

@Composable
private fun SubjectSnapshotCard(analysis: GradeAnalysis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SubjectHighlightSection(
                title = "表現亮點",
                subjects = analysis.strengths,
                color = ScoreTheme.semanticColors.positive,
                emptyText = "尚無明顯高於平均的科目",
            )
            SubjectHighlightSection(
                title = "值得留意",
                subjects = analysis.weaknesses,
                color = ScoreTheme.semanticColors.warning,
                emptyText = "目前沒有明顯需要留意的科目",
            )
        }
    }
}

@Composable
private fun SubjectHighlightSection(
    title: String,
    subjects: List<SubjectScore>,
    color: Color,
    emptyText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        if (subjects.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column {
                subjects.forEachIndexed { index, subject ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(40.dp)
                                .background(color, MaterialTheme.shapes.extraSmall),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = shortenSubjectName(subject.subjectName),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = diffSentence(subject.diffValue),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = color,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            text = subjectPercentLabel(subject.classRank, subject.classRankCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index != subjects.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineStatus(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatReminderTime(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))

private fun formatRemaining(expiresAtMillis: Long, nowMillis: Long): String {
    val remainingMillis = (expiresAtMillis - nowMillis).coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis) % 60
    return when {
        hours > 0 -> "${hours}小時${minutes}分"
        minutes > 0 -> "${minutes}分"
        else -> "不到 1 分鐘"
    }
}

@Composable
private fun OverviewSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(4) {
            SkeletonBlock(height = if (it == 1) 190.dp else 96.dp)
        }
    }
}

@Composable
private fun SkeletonBlock(height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
    )
}

@Composable
private fun EmptyPanel(message: String, onReload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                OutlinedRoundedSymbol(icon = "refresh", size = 40.dp, contentDescription = null)
            }
        }
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onReload,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text("重新整理")
        }
    }
}

private fun diffSentence(diff: Double): String = when {
    diff > 0.05 -> "高於平均 ${"%.1f".format(abs(diff))}"
    diff < -0.05 -> "低於平均 ${"%.1f".format(abs(diff))}"
    else -> "接近班級平均"
}

@Composable
private fun diffColor(diff: Double): Color = when {
    diff > 0.05 -> ScoreTheme.semanticColors.positive
    diff < -0.05 -> ScoreTheme.semanticColors.negative
    else -> ScoreTheme.semanticColors.neutral
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
