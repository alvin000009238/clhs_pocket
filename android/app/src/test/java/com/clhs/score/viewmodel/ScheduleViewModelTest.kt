package com.clhs.score.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleDisplayMode
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.FakeScheduleRepository
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 7, 23)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun staleClassListDoesNotOverrideNewerYearSelection() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()

        repository.yearsDeferred.complete(
            listOf(
                ScheduleYearTermOption(text = "114 學年度 第 1 學期", value = "114_1"),
                ScheduleYearTermOption(text = "113 學年度 第 2 學期", value = "113_2"),
            ),
        )
        runCurrent()

        viewModel.selectYear("113_2")
        runCurrent()

        repository.completeClasses(
            year = "114",
            term = "1",
            classes = listOf(ScheduleClassOption(text = "一年甲班", value = "101")),
        )
        runCurrent()

        assertEquals("113_2", viewModel.uiState.value.selectedYearValue)
        assertEquals(emptyList<ScheduleClassOption>(), viewModel.uiState.value.availableClasses)

        repository.completeClasses(
            year = "113",
            term = "2",
            classes = listOf(ScheduleClassOption(text = "二年乙班", value = "202")),
        )
        runCurrent()

        assertEquals("113_2", viewModel.uiState.value.selectedYearValue)
        assertEquals(listOf(ScheduleClassOption(text = "二年乙班", value = "202")), viewModel.uiState.value.availableClasses)
        assertEquals("202", viewModel.uiState.value.selectedClassValue)
    }

    @Test
    fun malformedYearDoesNotRequestScheduleClasses() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()

        repository.yearsDeferred.complete(
            listOf(ScheduleYearTermOption(text = "格式錯誤", value = "bad")),
        )
        runCurrent()

        assertEquals(emptyList<Pair<String, String>>(), repository.classRequests)
        assertEquals("學期資料格式錯誤", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun fakeScheduleCanSwitchSemesterAndLoadClasses() = runTest(dispatcher) {
        val viewModel = ScheduleViewModel(FakeScheduleRepository(), nowProvider = { today.atTime(12, 0) })
        runCurrent()

        viewModel.clearSelection()
        runCurrent()
        viewModel.selectYear(FakeScheduleData.years.last().value)
        runCurrent()

        assertFalse(viewModel.uiState.value.isError)
        assertEquals(FakeScheduleData.classes, viewModel.uiState.value.availableClasses)
        assertEquals(FakeScheduleData.classes.first().value, viewModel.uiState.value.selectedClassValue)
    }

    @Test
    fun refreshRetriesFailedPersonalScheduleQuery() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()

        repository.yearsDeferred.complete(
            listOf(ScheduleYearTermOption(text = "114 學年度 第 1 學期", value = "114_1")),
        )
        runCurrent()
        repository.completeClasses(year = "114", term = "1", classes = emptyList())
        runCurrent()

        repository.enqueueSchedule(Result.failure(IllegalStateException("暫時無法載入課表")))
        viewModel.confirmSelection()
        runCurrent()
        assertEquals(true, viewModel.uiState.value.isError)

        val report = ScheduleReport("114_1", "", ScheduleScope.SEMESTER, items = emptyList())
        repository.enqueueSchedule(Result.success(report))
        viewModel.refresh()
        runCurrent()

        assertEquals(report, viewModel.uiState.value.report)
        assertFalse(viewModel.uiState.value.isError)
        assertEquals(listOf("", ""), repository.scheduleClassNos)
    }

    @Test
    fun currentWeekFallbackShowsNoticeAndUsesSemesterResult() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.yearsDeferred.complete(listOf(ScheduleYearTermOption("114 學年度 第 1 學期", "114_1")))
        runCurrent()
        repository.completeClasses("114", "1", emptyList())
        runCurrent()
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        repository.enqueueSchedule(
            Result.success(ScheduleReport("114_1", "", ScheduleScope.SEMESTER, items = emptyList())),
        )

        viewModel.confirmSelection()
        runCurrent()

        assertEquals(ScheduleScope.CURRENT_WEEK, repository.scheduleScopes.single())
        assertEquals(ScheduleScope.SEMESTER, viewModel.uiState.value.report?.scope)
        assertEquals(ScheduleViewModel.CURRENT_WEEK_FALLBACK_NOTICE, viewModel.uiState.value.noticeMessage)
        viewModel.consumeNotice()
        assertNull(viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun currentWeekComparisonFailureShowsNoticeAndKeepsWeekResult() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.yearsDeferred.complete(listOf(ScheduleYearTermOption("114 學年度 第 1 學期", "114_1")))
        runCurrent()
        repository.completeClasses("114", "1", emptyList())
        runCurrent()
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        val report = ScheduleReport(
            "114_1",
            "",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = today.toString(),
            weekEndDate = today.toString(),
            items = emptyList(),
            changes = null,
        )
        repository.enqueueSchedule(Result.success(report))

        viewModel.confirmSelection()
        runCurrent()

        assertEquals(report, viewModel.uiState.value.report)
        assertEquals(ScheduleScope.CURRENT_WEEK, viewModel.uiState.value.selectedScope)
        assertEquals(ScheduleViewModel.CURRENT_WEEK_COMPARISON_NOTICE, viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun initialExpiredWeekImmediatelyLoadsNextWeek() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-25T12:00")
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        repository.yearsDeferred.complete(listOf(ScheduleYearTermOption("115 學年度 第 4 學期", "115_4")))
        runCurrent()
        repository.completeClasses("115", "4", listOf(ScheduleClassOption("二年三十班", "230")))
        runCurrent()
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        val week4 = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(dayOfWeek = 5, period = 4, subjectName = "國文")),
        )
        val week5 = week4.copy(
            weekNo = "5",
            weekStartDate = "2026-07-26",
            weekEndDate = "2026-08-01",
        )
        repository.enqueueSchedule(Result.success(week4))

        repository.enqueueSchedule(Result.success(week5))
        viewModel.confirmSelection()
        runCurrent()

        assertEquals(week5, viewModel.uiState.value.report)
        assertEquals(listOf(true, true), repository.overviewUpdates)
        assertEquals(
            listOf(LocalDate.parse("2026-07-25"), LocalDate.parse("2026-07-26")),
            repository.scheduleTargetDates,
        )
    }

    @Test
    fun changingScopeCancelsInFlightScheduleRequest() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.yearsDeferred.complete(listOf(ScheduleYearTermOption("114 學年度 第 1 學期", "114_1")))
        runCurrent()
        repository.completeClasses("114", "1", emptyList())
        runCurrent()
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        val pending = repository.enqueuePendingSchedule()

        viewModel.confirmSelection()
        runCurrent()
        viewModel.selectScope(ScheduleScope.SEMESTER)
        pending.complete(
            Result.success(
                ScheduleReport("114_1", "", ScheduleScope.CURRENT_WEEK, items = emptyList()),
            ),
        )
        runCurrent()

        assertNull(viewModel.uiState.value.report)
        assertEquals(ScheduleScope.SEMESTER, viewModel.uiState.value.selectedScope)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun cancelledCurrentWeekRequestDoesNotBecomeFallbackOrError() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.yearsDeferred.complete(listOf(ScheduleYearTermOption("114 學年度 第 1 學期", "114_1")))
        runCurrent()
        repository.completeClasses("114", "1", emptyList())
        runCurrent()
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        repository.enqueueSchedule(Result.failure(CancellationException("cancelled")))

        viewModel.confirmSelection()
        runCurrent()

        assertNull(viewModel.uiState.value.report)
        assertNull(viewModel.uiState.value.noticeMessage)
        assertFalse(viewModel.uiState.value.isError)
    }

    @Test
    fun currentWeekCacheBeforeRefreshBoundaryIsShownWithoutRequest() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-23T12:00")
        val cached = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(dayOfWeek = 5, period = 8, subjectName = "國文")),
        )
        val repository = ControllableScheduleRepository(cached)

        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()

        assertEquals(cached, viewModel.uiState.value.report)
        assertEquals(emptyList<ScheduleScope>(), repository.scheduleScopes)
    }

    @Test
    fun reopeningFromManuallyBrowsedFutureWeekResolvesCurrentDate() = runTest(dispatcher) {
        val future = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-26", weekEndDate = "2026-08-01", items = emptyList(),
        )
        val current = future.copy(weekStartDate = "2026-07-19", weekEndDate = "2026-07-25")
        val repository = ControllableScheduleRepository(future)
        repository.enqueueSchedule(Result.success(current))

        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()

        assertEquals(current, viewModel.uiState.value.report)
        assertEquals(listOf(today), repository.scheduleTargetDates)
        assertNull(viewModel.uiState.value.weekTargetDate)
    }

    @Test
    fun initialWeekWithRemainingSaturdayClassDoesNotAdvance() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-25T12:00")
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        repository.yearsDeferred.complete(listOf(ScheduleYearTermOption("115 學年度 第 4 學期", "115_4")))
        runCurrent()
        repository.completeClasses("115", "4", emptyList())
        runCurrent()
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        val report = ScheduleReport(
            "115_4",
            "",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(dayOfWeek = 6, period = 8, subjectName = "國文")),
        )
        repository.enqueueSchedule(Result.success(report))

        viewModel.confirmSelection()
        runCurrent()

        assertEquals(report, viewModel.uiState.value.report)
        assertEquals(listOf(now.toLocalDate()), repository.scheduleTargetDates)
    }

    @Test
    fun subjectOverridesUpdateLoadedReportAndPersistEdits() = runTest(dispatcher) {
        val report = ScheduleReport(
            "114_1",
            "230",
            ScheduleScope.SEMESTER,
            items = listOf(ScheduleItem(1, 1, "彈性學習時間")),
        )
        val repository = ControllableScheduleRepository(latestSchedule = report)
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        val override = ScheduleSubjectOverride("彈性學習時間", customSubjectName = "彈性課")
        var saved = false

        viewModel.saveSubjectOverride(override) { saved = true }
        runCurrent()

        assertEquals(true, saved)
        assertEquals(listOf(override), repository.savedOverrides)
        assertEquals(listOf(override), viewModel.uiState.value.report?.subjectOverrides)
        assertFalse(viewModel.uiState.value.isSavingSubjectOverride)
    }

    @Test
    fun subjectOverrideSaveFailureKeepsEditorOpenAndShowsFixedMessage() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository(
            latestSchedule = ScheduleReport("114_1", "230", ScheduleScope.SEMESTER, items = emptyList()),
        ).apply { subjectOverrideSaveFailure = IOException("secret path") }
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        var saved = false

        viewModel.saveSubjectOverride(ScheduleSubjectOverride("國文", "國語文")) { saved = true }
        runCurrent()

        assertFalse(saved)
        assertEquals("無法儲存自訂科目，請稍後再試", viewModel.uiState.value.noticeMessage)
        assertFalse(viewModel.uiState.value.isSavingSubjectOverride)
    }

    @Test
    fun currentWeekCachePastRefreshBoundaryIsRefreshed() = runTest(dispatcher) {
        val yesterday = today.minusDays(1)
        val cached = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = yesterday.minusDays(6).toString(),
            weekEndDate = yesterday.toString(),
            items = emptyList(),
        )
        val refreshed = cached.copy(
            weekNo = "5",
            weekStartDate = today.toString(),
            weekEndDate = today.plusDays(6).toString(),
        )
        val repository = ControllableScheduleRepository(cached).apply {
            enqueueSchedule(Result.success(refreshed))
        }

        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()

        assertEquals(refreshed, viewModel.uiState.value.report)
        assertEquals(listOf("230"), repository.scheduleClassNos)
        assertEquals(listOf(ScheduleScope.CURRENT_WEEK), repository.scheduleScopes)
        assertEquals(listOf(today), repository.scheduleTargetDates)
    }

    @Test
    fun lastClassEndRefreshTargetsNextWeekBeforeEndDate() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-24T12:00")
        val cached = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = listOf(
                ScheduleItem(
                    dayOfWeek = 5,
                    period = 4,
                    subjectName = "國文",
                ),
            ),
        )
        val refreshed = cached.copy(
            weekNo = "5",
            weekStartDate = "2026-07-26",
            weekEndDate = "2026-08-01",
        )
        val repository = ControllableScheduleRepository(cached).apply {
            enqueueSchedule(Result.success(refreshed))
        }

        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()

        assertEquals(refreshed, viewModel.uiState.value.report)
        assertEquals(listOf(LocalDate.parse("2026-07-26")), repository.scheduleTargetDates)
        assertEquals(listOf(true), repository.forcedRefreshes)
    }

    @Test
    fun failedAutomaticRefreshKeepsExpiredCacheAndRetriesOriginalQuery() = runTest(dispatcher) {
        val yesterday = today.minusDays(1)
        val cached = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = yesterday.minusDays(6).toString(),
            weekEndDate = yesterday.toString(),
            items = emptyList(),
        )
        val refreshed = cached.copy(
            weekNo = "5",
            weekStartDate = today.toString(),
            weekEndDate = today.plusDays(6).toString(),
        )
        val repository = ControllableScheduleRepository(cached).apply {
            enqueueSchedule(Result.failure(IllegalStateException("offline")))
        }
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()

        assertEquals(cached, viewModel.uiState.value.report)
        assertEquals("無法更新，暫時顯示已儲存的課表", viewModel.uiState.value.noticeMessage)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isError)

        repository.enqueueSchedule(Result.success(refreshed))
        viewModel.refresh()
        runCurrent()

        assertEquals(refreshed, viewModel.uiState.value.report)
        assertEquals(listOf("230", "230"), repository.scheduleClassNos)
        assertEquals(
            listOf(ScheduleScope.CURRENT_WEEK, ScheduleScope.CURRENT_WEEK),
            repository.scheduleScopes,
        )
        assertEquals(listOf(today, today), repository.scheduleTargetDates)
    }

    @Test
    fun loadedScopeSwitchesImmediatelyAndReusesBothReports() = runTest(dispatcher) {
        val semester = ScheduleReport("114_1", "230", ScheduleScope.SEMESTER, items = emptyList())
        val week = semester.copy(
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
        )
        val repository = ControllableScheduleRepository(semester)
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.enqueueSchedule(Result.success(week))

        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        runCurrent()
        assertEquals(week, viewModel.uiState.value.report)
        viewModel.selectScope(ScheduleScope.SEMESTER)
        runCurrent()
        assertEquals(semester, viewModel.uiState.value.report)
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        runCurrent()

        assertEquals(week, viewModel.uiState.value.report)
        assertEquals(listOf(ScheduleScope.CURRENT_WEEK), repository.scheduleScopes)
    }

    @Test
    fun historicalWeekStaysSelectedAndRefreshRetriesItsDate() = runTest(dispatcher) {
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25", items = emptyList(),
        )
        val previous = week.copy(weekStartDate = "2026-07-12", weekEndDate = "2026-07-18")
        val repository = ControllableScheduleRepository(week)
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.enqueueSchedule(Result.success(previous))

        viewModel.previousWeek()
        runCurrent()
        assertEquals(previous, viewModel.uiState.value.report)
        assertEquals(LocalDate.parse("2026-07-12"), viewModel.uiState.value.weekTargetDate)
        repository.enqueueSchedule(Result.failure(IOException("offline")))
        viewModel.refresh()
        runCurrent()
        assertEquals(previous, viewModel.uiState.value.report)
        assertEquals(true, viewModel.uiState.value.refreshFailed)
        repository.enqueueSchedule(Result.success(previous))
        viewModel.refresh()
        runCurrent()

        assertEquals(List(3) { LocalDate.parse("2026-07-12") }, repository.scheduleTargetDates)
        assertEquals(listOf(false, false, false), repository.overviewUpdates)
        assertFalse(viewModel.uiState.value.refreshFailed)
        viewModel.nextWeek()
        runCurrent()
        assertEquals(week, viewModel.uiState.value.report)
        assertEquals(3, repository.scheduleTargetDates.size)
        viewModel.currentWeek()
        runCurrent()
        assertNull(viewModel.uiState.value.weekTargetDate)
        assertEquals(week, viewModel.uiState.value.report)
        assertEquals(3, repository.scheduleTargetDates.size)
    }

    @Test
    fun returningToCurrentWeekAfterLastLessonUsesNextWeek() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-07-24T11:00")
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 4, "國文")),
        )
        val previous = week.copy(weekStartDate = "2026-07-12", weekEndDate = "2026-07-18")
        val next = week.copy(weekStartDate = "2026-07-26", weekEndDate = "2026-08-01")
        val repository = ControllableScheduleRepository(week)
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        repository.enqueueSchedule(Result.success(previous))
        viewModel.previousWeek()
        runCurrent()
        now = now.withHour(12)
        repository.enqueueSchedule(Result.success(next))

        viewModel.currentWeek()
        runCurrent()

        assertEquals(next, viewModel.uiState.value.report)
        assertNull(viewModel.uiState.value.weekTargetDate)
        assertEquals(listOf(LocalDate.parse("2026-07-12"), LocalDate.parse("2026-07-26")), repository.scheduleTargetDates)
    }

    @Test
    fun returningToCurrentWeekRevalidatesPreloadedNextWeekOnce() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-07-24T11:00")
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 4, "國文")),
        )
        val previous = week.copy(weekStartDate = "2026-07-12", weekEndDate = "2026-07-18")
        val next = week.copy(weekStartDate = "2026-07-26", weekEndDate = "2026-08-01")
        val repository = ControllableScheduleRepository(week)
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        repository.enqueueSchedule(Result.success(previous))
        viewModel.previousWeek()
        runCurrent()
        repository.enqueueSchedule(Result.success(next))
        viewModel.currentWeek()
        runCurrent()
        viewModel.nextWeek()
        runCurrent()
        now = now.withHour(12)
        repository.enqueueSchedule(Result.success(next))

        viewModel.currentWeek()
        runCurrent()

        assertEquals(3, repository.scheduleTargetDates.size)
        assertEquals(true, repository.forcedRefreshes.last())
        viewModel.currentWeek()
        runCurrent()
        assertEquals(3, repository.scheduleTargetDates.size)
        assertEquals(next, viewModel.uiState.value.report)
        assertNull(viewModel.uiState.value.weekTargetDate)
        assertEquals(LocalDate.parse("2026-07-26"), repository.scheduleTargetDates.last())
    }

    @Test
    fun automaticBoundaryRefreshDoesNotRepeatFailureOrAdvanceManualWeek() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-07-24T11:00")
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 4, "國文")),
        )
        val previous = week.copy(weekStartDate = "2026-07-12", weekEndDate = "2026-07-18")
        val repository = ControllableScheduleRepository(week)
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        viewModel.ensureCurrentWeek()
        runCurrent()
        assertEquals(emptyList<LocalDate>(), repository.scheduleTargetDates)
        now = now.withHour(12)
        repository.enqueueSchedule(Result.failure(IOException("offline")))
        viewModel.ensureCurrentWeek()
        runCurrent()
        assertEquals(true, viewModel.uiState.value.refreshFailed)
        viewModel.ensureCurrentWeek()
        runCurrent()
        assertEquals(listOf(LocalDate.parse("2026-07-26")), repository.scheduleTargetDates)
        assertEquals(listOf(true), repository.forcedRefreshes)
        repository.enqueueSchedule(Result.success(previous))
        viewModel.previousWeek()
        runCurrent()

        viewModel.ensureCurrentWeek()
        runCurrent()

        assertEquals(previous, viewModel.uiState.value.report)
        assertEquals(listOf(LocalDate.parse("2026-07-26"), LocalDate.parse("2026-07-12")), repository.scheduleTargetDates)
    }

    @Test fun unpublishedNextWeekKeepsCurrentWeekWithoutWarningOrRepeatedAutomaticRequests() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-07-24T11:00")
        val week = ScheduleReport("114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25", items = listOf(ScheduleItem(5, 4, "國文")))
        val repository = ControllableScheduleRepository(week)
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        now = now.withHour(12)
        repository.enqueueSchedule(Result.failure(com.clhs.score.data.ScheduleFetchException(
            com.clhs.score.data.ScheduleFetchResult.Unpublished(LocalDate.parse("2026-07-26")))))
        viewModel.ensureCurrentWeek()
        runCurrent()
        assertEquals(week, viewModel.uiState.value.report)
        assertFalse(viewModel.uiState.value.refreshFailed)
        assertEquals(com.clhs.score.data.ScheduleAvailability.UNPUBLISHED, viewModel.uiState.value.availability)
        viewModel.ensureCurrentWeek()
        runCurrent()
        assertEquals(listOf(LocalDate.parse("2026-07-26")), repository.scheduleTargetDates)
        assertEquals(listOf(true), repository.forcedRefreshes)
    }

    @Test
    fun initialAutomaticNextWeekFailureRetainsFetchedWeekAndRetryTarget() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-24T12:00")
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 4, "國文")),
        )
        val next = week.copy(weekStartDate = "2026-07-26", weekEndDate = "2026-08-01")
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        repository.yearsDeferred.complete(listOf(ScheduleYearTermOption("114 學年度 第 1 學期", "114_1")))
        runCurrent()
        repository.completeClasses("114", "1", listOf(ScheduleClassOption("二年三十班", "230")))
        runCurrent()
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        repository.enqueueSchedule(Result.success(week))
        repository.enqueueSchedule(Result.failure(IOException("offline")))
        viewModel.confirmSelection()
        runCurrent()
        assertEquals(week, viewModel.uiState.value.report)
        assertFalse(viewModel.uiState.value.isError)
        assertEquals(true, viewModel.uiState.value.refreshFailed)
        repository.enqueueSchedule(Result.success(next))

        viewModel.refresh()
        runCurrent()

        assertEquals(next, viewModel.uiState.value.report)
        assertEquals(listOf(now.toLocalDate(), LocalDate.parse("2026-07-26"), LocalDate.parse("2026-07-26")), repository.scheduleTargetDates)
    }

    @Test
    fun changingSelectionClearsFailureFromPreviousQuery() = runTest(dispatcher) {
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25", items = emptyList(),
        )
        val repository = ControllableScheduleRepository(week)
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.enqueueSchedule(Result.failure(IOException("offline")))
        viewModel.refresh()
        runCurrent()
        assertEquals(true, viewModel.uiState.value.refreshFailed)

        viewModel.selectClass("231")

        assertFalse(viewModel.uiState.value.refreshFailed)
        assertNull(viewModel.uiState.value.noticeMessage)
        repository.enqueueSchedule(Result.failure(IOException("offline")))
        viewModel.refresh()
        runCurrent()
        assertEquals(true, viewModel.uiState.value.refreshFailed)

        viewModel.selectYear("113_2")
        runCurrent()

        assertFalse(viewModel.uiState.value.refreshFailed)
        assertNull(viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun switchingToCachedScopeClearsRefreshFailureAndNotice() = runTest(dispatcher) {
        val semester = ScheduleReport("114_1", "230", ScheduleScope.SEMESTER, items = emptyList())
        val week = semester.copy(
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
        )
        val repository = ControllableScheduleRepository(semester)
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.enqueueSchedule(Result.success(week))
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        runCurrent()
        repository.enqueueSchedule(Result.failure(IOException("offline")))
        viewModel.refresh()
        runCurrent()
        assertEquals(true, viewModel.uiState.value.refreshFailed)

        viewModel.selectScope(ScheduleScope.SEMESTER)

        assertEquals(semester, viewModel.uiState.value.report)
        assertFalse(viewModel.uiState.value.refreshFailed)
        assertNull(viewModel.uiState.value.noticeMessage)
        assertEquals(2, repository.scheduleTargetDates.size)
    }

    @Test
    fun automaticWeekAdvancesAtExactLastLessonEnd() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-07-24T11:59:59")
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 4, "國文")),
        )
        val next = week.copy(weekStartDate = "2026-07-26", weekEndDate = "2026-08-01")
        val repository = ControllableScheduleRepository(week)
        val viewModel = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        viewModel.ensureCurrentWeek()
        runCurrent()
        assertEquals(week, viewModel.uiState.value.report)
        assertEquals(emptyList<LocalDate>(), repository.scheduleTargetDates)
        now = now.plusSeconds(1)
        repository.enqueueSchedule(Result.success(next))

        viewModel.ensureCurrentWeek()
        runCurrent()
        viewModel.ensureCurrentWeek()
        runCurrent()

        assertEquals(next, viewModel.uiState.value.report)
        assertNull(viewModel.uiState.value.weekTargetDate)
        assertFalse(viewModel.uiState.value.refreshFailed)
        assertEquals(listOf(LocalDate.parse("2026-07-26")), repository.scheduleTargetDates)
        assertEquals(listOf(true), repository.forcedRefreshes)
    }

    @Test
    fun cachedReportsUseLatestSubjectOverridesAfterScopeSwitch() = runTest(dispatcher) {
        val semester = ScheduleReport("114_1", "230", ScheduleScope.SEMESTER, items = emptyList())
        val repository = ControllableScheduleRepository(semester)
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        repository.enqueueSchedule(Result.success(semester.copy(
            scope = ScheduleScope.CURRENT_WEEK, weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
        )))
        viewModel.selectScope(ScheduleScope.CURRENT_WEEK)
        runCurrent()
        val override = ScheduleSubjectOverride("彈性學習時間", "彈性課")
        viewModel.saveSubjectOverride(override)
        runCurrent()

        viewModel.selectScope(ScheduleScope.SEMESTER)
        runCurrent()

        assertEquals(listOf(override), viewModel.uiState.value.report?.subjectOverrides)
    }

    @Test
    fun displayPreferenceLoadsAndFailedWriteKeepsSavedChoice() = runTest(dispatcher) {
        val preferences = MutableStateFlow<Preferences>(mutablePreferencesOf(
            stringPreferencesKey("schedule_display_mode") to ScheduleDisplayMode.DAY.name,
        ))
        var failWrite = true
        val store = object : DataStore<Preferences> {
            override val data = preferences
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrite) throw IOException("unavailable")
                return transform(preferences.value).also { preferences.value = it }
            }
        }
        val repository = ControllableScheduleRepository(
            ScheduleReport("114_1", "230", ScheduleScope.SEMESTER, items = emptyList()),
        )
        val viewModel = ScheduleViewModel(
            repository, nowProvider = { today.atTime(12, 0) }, settingsRepository = SettingsRepository(store),
        )
        runCurrent()
        assertEquals(ScheduleDisplayMode.DAY, viewModel.uiState.value.displayMode)

        viewModel.setDisplayMode(ScheduleDisplayMode.WEEK)
        runCurrent()
        assertEquals(ScheduleDisplayMode.DAY, viewModel.uiState.value.displayMode)
        assertEquals(ScheduleViewModel.DISPLAY_MODE_SAVE_ERROR, viewModel.uiState.value.noticeMessage)
        failWrite = false
        viewModel.setDisplayMode(ScheduleDisplayMode.WEEK)
        runCurrent()
        assertEquals(ScheduleDisplayMode.WEEK, viewModel.uiState.value.displayMode)
        viewModel.setDisplayMode(ScheduleDisplayMode.DAY)
        viewModel.setDisplayMode(ScheduleDisplayMode.WEEK)
        runCurrent()
        assertEquals(ScheduleDisplayMode.WEEK, viewModel.uiState.value.displayMode)
    }

    @Test
    fun schoolWeekPickerReusesOptionsAndSelectionStaysManual() = runTest(dispatcher) {
        val report = ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-20", weekEndDate = "2026-07-26", items = listOf(ScheduleItem(5, 8, "數學")))
        val repository = ControllableScheduleRepository(report)
        val option = com.clhs.score.data.ScheduleWeekOption("第 2 週", "2", "2026-07-13", "2026-07-19")
        repository.weeks = listOf(option)
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        viewModel.openWeekPicker()
        runCurrent()
        assertEquals(listOf(option), viewModel.uiState.value.weekOptions)
        viewModel.dismissWeekPicker()
        viewModel.openWeekPicker()
        runCurrent()
        assertEquals(1, repository.weekRequests)
        repository.enqueueSchedule(Result.success(report.copy(weekStartDate = option.startDate, weekEndDate = option.endDate)))
        viewModel.selectWeek(option)
        runCurrent()
        assertEquals(LocalDate.parse(option.startDate), viewModel.uiState.value.weekTargetDate)
        assertFalse(viewModel.uiState.value.isWeekPickerOpen)
        viewModel.ensureCurrentWeek()
        runCurrent()
        assertEquals(listOf(LocalDate.parse(option.startDate)), repository.scheduleTargetDates)
    }

    @Test
    fun schoolWeekPickerFailureCanRetryWithoutDiscardingSchedule() = runTest(dispatcher) {
        val report = ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-20", weekEndDate = "2026-07-26", items = listOf(ScheduleItem(5, 8, "數學")))
        val repository = ControllableScheduleRepository(report)
        repository.weeksFailure = IOException("offline")
        val viewModel = ScheduleViewModel(repository, nowProvider = { today.atTime(12, 0) })
        runCurrent()
        viewModel.openWeekPicker()
        runCurrent()
        assertEquals(report, viewModel.uiState.value.report)
        assertEquals("無法取得週次，請重試", viewModel.uiState.value.weekPickerError)
        repository.weeksFailure = null
        viewModel.openWeekPicker()
        runCurrent()
        assertNull(viewModel.uiState.value.weekPickerError)
        assertEquals(2, repository.weekRequests)
        viewModel.selectScope(ScheduleScope.SEMESTER)
        assertFalse(viewModel.uiState.value.isWeekPickerOpen)
    }

    @Test
    fun reopeningThenBrowsingNextWeekAndReturningReusesAutomaticWeek() = runTest(dispatcher) {
        val automatic = ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22",
            items = listOf(ScheduleItem(1, 1, "Math")))
        val following = automatic.copy(weekStartDate = "2026-11-23", weekEndDate = "2026-11-29")
        val repository = ControllableScheduleRepository(automatic, overviewSchedule = com.clhs.score.data.CachedScheduleReport(automatic, Long.MAX_VALUE))
        val vm = ScheduleViewModel(repository, nowProvider = { LocalDateTime.parse("2026-11-14T12:00:00") })
        runCurrent()
        repository.enqueueSchedule(Result.success(following))
        vm.nextWeek()
        runCurrent()
        vm.currentWeek()
        runCurrent()
        assertEquals(automatic, vm.uiState.value.report)
        assertNull(vm.uiState.value.weekTargetDate)
        assertEquals(listOf(LocalDate.parse("2026-11-23")), repository.scheduleTargetDates)
        val semester = automatic.copy(scope = ScheduleScope.SEMESTER, weekStartDate = null, weekEndDate = null)
        repository.enqueueSchedule(Result.success(semester))
        vm.selectScope(ScheduleScope.SEMESTER)
        runCurrent()
        vm.selectScope(ScheduleScope.CURRENT_WEEK)
        runCurrent()
        assertEquals(automatic, vm.uiState.value.report)
        assertEquals(2, repository.scheduleTargetDates.size)
    }

    @Test
    fun tappingCurrentWeekWithOnlyUpcomingWeekLoadedDoesNotFetchCalendarWeek() = runTest(dispatcher) {
        val next = ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22",
            items = listOf(ScheduleItem(1, 1, "Math")))
        val repository = ControllableScheduleRepository(next, overviewSchedule = com.clhs.score.data.CachedScheduleReport(next, Long.MAX_VALUE))
        val vm = ScheduleViewModel(repository, nowProvider = { LocalDateTime.parse("2026-11-14T12:00:00") })
        runCurrent()
        repeat(3) { vm.currentWeek(); runCurrent() }
        assertEquals(next, vm.uiState.value.report)
        assertEquals(emptyList<LocalDate>(), repository.scheduleTargetDates)
    }

    @Test
    fun reopeningWithFutureWeekCacheDoesNotRequestSchoolBeforeWeekStarts() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-11-14T12:00:00")
        val next = ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22",
            items = listOf(ScheduleItem(1, 1, "Math")))
        val repository = ControllableScheduleRepository(next, 0L, com.clhs.score.data.CachedScheduleReport(next, 0L))
        val vm = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        vm.ensureCurrentWeek()
        runCurrent()
        assertEquals(next, vm.uiState.value.report)
        assertEquals(emptyList<LocalDate>(), repository.scheduleTargetDates)
        assertFalse(vm.uiState.value.refreshFailed)
    }

    @Test
    fun enteringWeekRevalidatesPreloadedSnapshotButDoesNotPollDuringWeek() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-11-09T08:00:00")
        val old = ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-08", weekEndDate = "2026-11-14",
            items = listOf(ScheduleItem(5, 8, "原課程")))
        val updated = old.copy(items = listOf(ScheduleItem(5, 8, "新課程")))
        val repository = ControllableScheduleRepository(old, 0L)
        repository.enqueueSchedule(Result.success(updated))
        val vm = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()


        runCurrent()
        assertEquals(updated, vm.uiState.value.report)
        repeat(3) { vm.ensureCurrentWeek(); runCurrent() }
        assertEquals(1, repository.scheduleTargetDates.size)
    }

    @Test
    fun overviewThisWeekAndBrowserNextWeekStartupPreservesAutoAndCurrentWeekUsesCache() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-11-11T12:00:00")
        val thisWeek = ScheduleReport(
            "115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-09", weekEndDate = "2026-11-15",
            items = listOf(ScheduleItem(5, 8, "國文")),
        )
        val nextWeek = thisWeek.copy(
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22",
            items = listOf(ScheduleItem(1, 1, "數學")),
        )
        val overviewSnapshot = com.clhs.score.data.CachedScheduleReport(
            thisWeek,
            LocalDateTime.parse("2026-11-09T08:00:00").atZone(com.clhs.score.data.SCHOOL_COURSE_ZONE).toInstant().toEpochMilli(),
        )
        val repository = ControllableScheduleRepository(
            latestSchedule = nextWeek,
            fetchedAtMillis = now.atZone(com.clhs.score.data.SCHOOL_COURSE_ZONE).toInstant().toEpochMilli(),
            overviewSchedule = overviewSnapshot,
        )

        val vm = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()

        assertEquals(nextWeek, vm.uiState.value.report)
        assertEquals(LocalDate.parse("2026-11-16"), vm.uiState.value.weekTargetDate)
        assertEquals(emptyList<LocalDate>(), repository.scheduleTargetDates)

        vm.currentWeek()
        runCurrent()

        assertEquals(thisWeek, vm.uiState.value.report)
        assertNull(vm.uiState.value.weekTargetDate)
        assertEquals(emptyList<LocalDate>(), repository.scheduleTargetDates)
        assertEquals(listOf(thisWeek to true), repository.selectedCachedReports)
    }

    @Test
    fun preloadedNextWeekCrossingMondayConfirmsOnceOnEnsureAndManualReturnWithoutDailyPolling() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-11-14T12:00:00")
        val preloaded = ScheduleReport(
            "115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22",
            items = listOf(ScheduleItem(5, 8, "原課表")),
        )
        val confirmed = preloaded.copy(items = listOf(ScheduleItem(5, 8, "週一確認課表")))
        val preloadTime = now.atZone(com.clhs.score.data.SCHOOL_COURSE_ZONE).toInstant().toEpochMilli()

        // Part A: ensureCurrentWeek after crossing into Monday confirms once, no daily polling
        val repoEnsure = ControllableScheduleRepository(preloaded, fetchedAtMillis = preloadTime, overviewSchedule = com.clhs.score.data.CachedScheduleReport(preloaded, preloadTime))
        val vmEnsure = ScheduleViewModel(repoEnsure, nowProvider = { now })
        runCurrent()
        assertEquals(emptyList<LocalDate>(), repoEnsure.scheduleTargetDates)

        now = LocalDateTime.parse("2026-11-16T08:00:00")
        repoEnsure.enqueueSchedule(Result.success(confirmed))
        vmEnsure.ensureCurrentWeek()
        runCurrent()

        assertEquals(confirmed, vmEnsure.uiState.value.report)
        assertEquals(1, repoEnsure.scheduleTargetDates.size)
        assertEquals(listOf(true), repoEnsure.forcedRefreshes)
        assertEquals(listOf(true), repoEnsure.overviewUpdates)

        now = LocalDateTime.parse("2026-11-17T08:00:00")
        vmEnsure.ensureCurrentWeek()
        runCurrent()
        now = LocalDateTime.parse("2026-11-18T08:00:00")
        vmEnsure.ensureCurrentWeek()
        runCurrent()
        assertEquals(1, repoEnsure.scheduleTargetDates.size)

        // Part B: manual browse before Monday, then returning via currentWeek after Monday confirms once
        now = LocalDateTime.parse("2026-11-14T12:00:00")
        val following = preloaded.copy(weekStartDate = "2026-11-23", weekEndDate = "2026-11-29")
        val repoManual = ControllableScheduleRepository(preloaded, fetchedAtMillis = preloadTime, overviewSchedule = com.clhs.score.data.CachedScheduleReport(preloaded, preloadTime))
        val vmManual = ScheduleViewModel(repoManual, nowProvider = { now })
        runCurrent()
        repoManual.enqueueSchedule(Result.success(following))
        vmManual.nextWeek()
        runCurrent()

        now = LocalDateTime.parse("2026-11-16T08:00:00")
        repoManual.enqueueSchedule(Result.success(confirmed))
        vmManual.currentWeek()
        runCurrent()

        assertEquals(confirmed, vmManual.uiState.value.report)
        assertNull(vmManual.uiState.value.weekTargetDate)
        assertEquals(listOf(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-16")), repoManual.scheduleTargetDates)
        assertEquals(true, repoManual.forcedRefreshes.last())

        now = LocalDateTime.parse("2026-11-17T08:00:00")
        vmManual.currentWeek()
        runCurrent()
        assertEquals(2, repoManual.scheduleTargetDates.size)
    }

    @Test
    fun automaticFailureSkipsRetryAfterBrowsingOtherWeekButExplicitRefreshRetries() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-11-17T08:00:00")
        val preloaded = ScheduleReport(
            "115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22",
            items = listOf(ScheduleItem(5, 8, "原課表")),
        )
        val following = preloaded.copy(weekStartDate = "2026-11-23", weekEndDate = "2026-11-29")
        val confirmed = preloaded.copy(items = listOf(ScheduleItem(5, 8, "重試成功")))
        val preloadTime = LocalDateTime.parse("2026-11-14T12:00:00")
            .atZone(com.clhs.score.data.SCHOOL_COURSE_ZONE).toInstant().toEpochMilli()
        val repository = ControllableScheduleRepository(preloaded, fetchedAtMillis = preloadTime, overviewSchedule = com.clhs.score.data.CachedScheduleReport(preloaded, preloadTime))
        repository.enqueueSchedule(Result.failure(IOException("offline")))

        val vm = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()
        assertEquals(true, vm.uiState.value.refreshFailed)
        assertEquals(listOf(LocalDate.parse("2026-11-16")), repository.scheduleTargetDates)

        repository.enqueueSchedule(Result.success(following))
        vm.nextWeek()
        runCurrent()
        assertEquals(following, vm.uiState.value.report)

        now = LocalDateTime.parse("2026-11-18T08:00:00")
        vm.currentWeek()
        runCurrent()
        vm.ensureCurrentWeek()
        runCurrent()
        assertEquals(preloaded, vm.uiState.value.report)
        assertNull(vm.uiState.value.weekTargetDate)
        assertEquals(true, vm.uiState.value.refreshFailed)
        assertEquals(com.clhs.score.data.ScheduleAvailability.FAILED, vm.uiState.value.availability)
        assertEquals(listOf(LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-23")), repository.scheduleTargetDates)

        repository.enqueueSchedule(Result.success(confirmed))
        vm.refresh()
        runCurrent()
        assertEquals(confirmed, vm.uiState.value.report)
        assertFalse(vm.uiState.value.refreshFailed)
        assertEquals(3, repository.scheduleTargetDates.size)
    }

    @Test
    fun failedScopeSelectionCanBeSelectedAgainAndManualRetryRemainsManual() = runTest(dispatcher) {
        val week = ScheduleReport("114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 8, "Math")))
        val repo = ControllableScheduleRepository(week)
        val vm = ScheduleViewModel(repo, nowProvider = { LocalDateTime.parse("2026-07-23T12:00:00") })
        runCurrent()
        repo.enqueueSchedule(Result.failure(IOException("offline")))
        vm.selectScope(ScheduleScope.SEMESTER)
        runCurrent()
        assertEquals(ScheduleScope.CURRENT_WEEK, vm.uiState.value.selectedScope)
        val semester = week.copy(scope = ScheduleScope.SEMESTER)
        repo.enqueueSchedule(Result.success(semester))
        vm.selectScope(ScheduleScope.SEMESTER)
        runCurrent()
        assertEquals(semester, vm.uiState.value.report)
        vm.selectScope(ScheduleScope.CURRENT_WEEK)
        runCurrent()
        repo.enqueueSchedule(Result.failure(IOException("offline")))
        vm.nextWeek()
        runCurrent()
        val next = week.copy(weekStartDate = "2026-07-26", weekEndDate = "2026-08-01")
        repo.enqueueSchedule(Result.success(next))
        vm.refresh()
        runCurrent()
        assertEquals(LocalDate.parse("2026-07-26"), vm.uiState.value.weekTargetDate)
        vm.currentWeek()
        runCurrent()
        assertEquals(week, vm.uiState.value.report)
        assertNull(vm.uiState.value.weekTargetDate)
    }

    @Test
    fun failedManualBrowseRestoresDisplayedWeekTargetForSubsequentNavigation() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-23T12:00:00")
        val week1 = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 8, "國文")),
        )
        val week0 = week1.copy(weekStartDate = "2026-07-12", weekEndDate = "2026-07-18")
        val repository = ControllableScheduleRepository(week1)
        val vm = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()

        repository.enqueueSchedule(Result.failure(IOException("offline")))
        vm.nextWeek()
        runCurrent()

        assertEquals(week1, vm.uiState.value.report)
        assertNull(vm.uiState.value.weekTargetDate)
        assertEquals(true, vm.uiState.value.refreshFailed)

        repository.enqueueSchedule(Result.success(week0))
        vm.previousWeek()
        runCurrent()

        assertEquals(week0, vm.uiState.value.report)
        assertEquals(LocalDate.parse("2026-07-12"), vm.uiState.value.weekTargetDate)
        assertEquals(listOf(LocalDate.parse("2026-07-26"), LocalDate.parse("2026-07-12")), repository.scheduleTargetDates)
    }

    @Test
    fun automaticSnapshotRemainsFallbackEvenAfterLoadedCacheEviction() = runTest(dispatcher) {
        var now = LocalDateTime.parse("2026-07-23T12:00:00")
        val autoWeek = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 8, "自動本週")),
        )
        val repository = ControllableScheduleRepository(autoWeek)
        val vm = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()

        // Browse 17 distinct future weeks to evict autoWeek from loadedSnapshots (capacity 16)
        var lastManual = autoWeek
        repeat(17) { step ->
            val start = LocalDate.parse("2026-07-26").plusWeeks(step.toLong())
            lastManual = autoWeek.copy(
                weekStartDate = start.toString(),
                weekEndDate = start.plusDays(6).toString(),
                items = listOf(ScheduleItem(1, 1, "手動第${step}週")),
            )
            repository.enqueueSchedule(Result.success(lastManual))
            vm.nextWeek()
            runCurrent()
        }
        assertEquals(lastManual, vm.uiState.value.report)

        now = LocalDateTime.parse("2026-07-24T17:00:00")
        repository.enqueueSchedule(Result.failure(IOException("offline")))
        vm.currentWeek()
        runCurrent()

        assertEquals(autoWeek, vm.uiState.value.report)
        assertNull(vm.uiState.value.weekTargetDate)
        assertEquals(true, vm.uiState.value.refreshFailed)
    }

    @Test
    fun clearYearAndClassResetAvailabilityToAvailable() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-23T12:00:00")
        val week = ScheduleReport(
            "114_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19", weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(5, 8, "國文")),
        )
        val repository = ControllableScheduleRepository(week)
        val vm = ScheduleViewModel(repository, nowProvider = { now })
        runCurrent()

        repository.enqueueSchedule(
            Result.failure(
                com.clhs.score.data.ScheduleFetchException(
                    com.clhs.score.data.ScheduleFetchResult.Unpublished(LocalDate.parse("2026-07-26"))
                )
            )
        )
        vm.nextWeek()
        runCurrent()
        assertEquals(com.clhs.score.data.ScheduleAvailability.UNPUBLISHED, vm.uiState.value.availability)

        vm.selectClass("231")
        assertEquals(com.clhs.score.data.ScheduleAvailability.AVAILABLE, vm.uiState.value.availability)

        repository.enqueueSchedule(Result.failure(IOException("offline")))
        vm.confirmSelection()
        runCurrent()
        assertEquals(com.clhs.score.data.ScheduleAvailability.FAILED, vm.uiState.value.availability)

        vm.selectYear("114_2")
        assertEquals(com.clhs.score.data.ScheduleAvailability.AVAILABLE, vm.uiState.value.availability)

        vm.clearSelection()
        assertEquals(com.clhs.score.data.ScheduleAvailability.AVAILABLE, vm.uiState.value.availability)
    }

    private class ControllableScheduleRepository(
        private val latestSchedule: ScheduleReport? = null,
        val fetchedAtMillis: Long = Long.MAX_VALUE,
        private val overviewSchedule: com.clhs.score.data.CachedScheduleReport? = null,
    ) : ScheduleRepository {
        val selectedCachedReports = mutableListOf<Pair<ScheduleReport, Boolean>>()
        override suspend fun selectCachedReport(report: ScheduleReport, automatic: Boolean) {
            selectedCachedReports += report to automatic
        }
        override suspend fun getOverviewScheduleSnapshot(): com.clhs.score.data.CachedScheduleReport? = overviewSchedule
        var weeks: List<com.clhs.score.data.ScheduleWeekOption> = emptyList()
        var weeksFailure: Exception? = null
        var weekRequests = 0
        override suspend fun getScheduleWeeks(year: String, term: String): List<com.clhs.score.data.ScheduleWeekOption> {
            weekRequests++
            weeksFailure?.let { throw it }
            return weeks
        }
        val yearsDeferred = CompletableDeferred<List<ScheduleYearTermOption>>()
        val classRequests = mutableListOf<Pair<String, String>>()
        val scheduleClassNos = mutableListOf<String>()
        val scheduleScopes = mutableListOf<ScheduleScope>()
        val scheduleTargetDates = mutableListOf<LocalDate>()
        val overviewUpdates = mutableListOf<Boolean>()
        val forcedRefreshes = mutableListOf<Boolean>()
        private val classes = mutableMapOf<Pair<String, String>, CompletableDeferred<List<ScheduleClassOption>>>()
        private val scheduleResults = ArrayDeque<CompletableDeferred<Result<ScheduleReport>>>()
        private val subjectOverrides = MutableStateFlow<List<ScheduleSubjectOverride>>(emptyList())
        var savedOverrides: List<ScheduleSubjectOverride> = emptyList()
        var subjectOverrideSaveFailure: Throwable? = null

        override suspend fun getScheduleYears(): List<ScheduleYearTermOption> = yearsDeferred.await()

        override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> {
            classRequests += year to term
            return classes.getOrPut(year to term) { CompletableDeferred() }.await()
        }

        override suspend fun fetchSchedule(
            yearValue: String,
            year: String,
            term: String,
            classNo: String,
            scope: ScheduleScope,
            targetDate: LocalDate,
            expectedSession: com.clhs.score.data.AuthenticatedSession?,
            updateOverviewSnapshot: Boolean,
            forceRefresh: Boolean,
        updateBrowseSelection: Boolean,
        ): ScheduleReport {
            scheduleClassNos += classNo
            scheduleScopes += scope
            scheduleTargetDates += targetDate
            overviewUpdates += updateOverviewSnapshot
            forcedRefreshes += forceRefresh
            return scheduleResults.removeFirst().await().getOrThrow()
        }

        override suspend fun getLatestScheduleSnapshot() = latestSchedule?.let { com.clhs.score.data.CachedScheduleReport(it, fetchedAtMillis) }
        override suspend fun getLatestSchedule(): ScheduleReport? = latestSchedule

        override fun subjectOverridesFlow() = subjectOverrides

        override suspend fun saveSubjectOverrides(overrides: List<ScheduleSubjectOverride>) {
            subjectOverrideSaveFailure?.let { throw it }
            savedOverrides = overrides
            subjectOverrides.value = overrides
        }

        fun completeClasses(year: String, term: String, classes: List<ScheduleClassOption>) {
            this.classes.getOrPut(year to term) { CompletableDeferred() }.complete(classes)
        }

        fun enqueueSchedule(result: Result<ScheduleReport>) {
            scheduleResults.addLast(CompletableDeferred(result))
        }

        fun enqueuePendingSchedule(): CompletableDeferred<Result<ScheduleReport>> {
            return CompletableDeferred<Result<ScheduleReport>>().also(scheduleResults::addLast)
        }
    }
}
