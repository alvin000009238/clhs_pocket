package com.clhs.score.viewmodel

import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.FakeScheduleRepository
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ScheduleYearTermOption
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
        val viewModel = ScheduleViewModel(repository)
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
        val viewModel = ScheduleViewModel(repository)
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
        val viewModel = ScheduleViewModel(FakeScheduleRepository())
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
        val viewModel = ScheduleViewModel(repository)
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
        val viewModel = ScheduleViewModel(repository)
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
        val viewModel = ScheduleViewModel(repository)
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
            weekStartDate = LocalDate.now().toString(),
            weekEndDate = LocalDate.now().toString(),
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
    fun refreshAfterManualExpiredWeekTargetsNextWeek() = runTest(dispatcher) {
        val now = LocalDateTime.parse("2026-07-24T12:00")
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

        viewModel.confirmSelection()
        runCurrent()
        repository.enqueueSchedule(Result.success(week5))
        viewModel.refresh()
        runCurrent()

        assertEquals(week5, viewModel.uiState.value.report)
        assertEquals(
            listOf(LocalDate.parse("2026-07-24"), LocalDate.parse("2026-07-26")),
            repository.scheduleTargetDates,
        )
    }

    @Test
    fun changingScopeCancelsInFlightScheduleRequest() = runTest(dispatcher) {
        val repository = ControllableScheduleRepository()
        val viewModel = ScheduleViewModel(repository)
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
        val viewModel = ScheduleViewModel(repository)
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
    fun subjectOverridesUpdateLoadedReportAndPersistEdits() = runTest(dispatcher) {
        val report = ScheduleReport(
            "114_1",
            "230",
            ScheduleScope.SEMESTER,
            items = listOf(ScheduleItem(1, 1, "彈性學習時間")),
        )
        val repository = ControllableScheduleRepository(latestSchedule = report)
        val viewModel = ScheduleViewModel(repository)
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
        val viewModel = ScheduleViewModel(repository)
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
        val yesterday = LocalDate.now().minusDays(1)
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
            weekStartDate = LocalDate.now().toString(),
            weekEndDate = LocalDate.now().plusDays(6).toString(),
        )
        val repository = ControllableScheduleRepository(cached).apply {
            enqueueSchedule(Result.success(refreshed))
        }

        val viewModel = ScheduleViewModel(repository)
        runCurrent()

        assertEquals(refreshed, viewModel.uiState.value.report)
        assertEquals(listOf("230"), repository.scheduleClassNos)
        assertEquals(listOf(ScheduleScope.CURRENT_WEEK), repository.scheduleScopes)
        assertEquals(listOf(LocalDate.now()), repository.scheduleTargetDates)
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
    }

    @Test
    fun failedAutomaticRefreshKeepsExpiredCacheAndRetriesOriginalQuery() = runTest(dispatcher) {
        val yesterday = LocalDate.now().minusDays(1)
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
            weekStartDate = LocalDate.now().toString(),
            weekEndDate = LocalDate.now().plusDays(6).toString(),
        )
        val repository = ControllableScheduleRepository(cached).apply {
            enqueueSchedule(Result.failure(IllegalStateException("offline")))
        }
        val viewModel = ScheduleViewModel(repository)
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
        assertEquals(listOf(LocalDate.now(), LocalDate.now()), repository.scheduleTargetDates)
    }

    private class ControllableScheduleRepository(
        private val latestSchedule: ScheduleReport? = null,
    ) : ScheduleRepository {
        val yearsDeferred = CompletableDeferred<List<ScheduleYearTermOption>>()
        val classRequests = mutableListOf<Pair<String, String>>()
        val scheduleClassNos = mutableListOf<String>()
        val scheduleScopes = mutableListOf<ScheduleScope>()
        val scheduleTargetDates = mutableListOf<LocalDate>()
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
        ): ScheduleReport {
            scheduleClassNos += classNo
            scheduleScopes += scope
            scheduleTargetDates += targetDate
            return scheduleResults.removeFirst().await().getOrThrow()
        }

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
