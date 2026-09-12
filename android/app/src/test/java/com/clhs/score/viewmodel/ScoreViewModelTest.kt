package com.clhs.score.viewmodel

import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.ExamOption
import com.clhs.score.data.FakeData
import com.clhs.score.data.FakeGradeRepository
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.YearTermOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreViewModelTest {
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
    fun sessionRestoreRemainsPendingUntilRepositoryReturns() = runTest(dispatcher) {
        val restoreDeferred = CompletableDeferred<AuthenticatedSession?>()
        val viewModel = ScoreViewModel(ControllableGradeRepository(restoreDeferred))
        runCurrent()

        assertEquals(AuthState.Restoring, viewModel.authState.value)

        restoreDeferred.complete(null)
        runCurrent()

        assertEquals(AuthState.Guest, viewModel.authState.value)
    }

    @Test
    fun explicitLoginCancelsPendingStartupRestore() = runTest(dispatcher) {
        val restore = CompletableDeferred<AuthenticatedSession?>()
        val viewModel = ScoreViewModel(ControllableGradeRepository(restore))
        runCurrent()
        assertEquals(AuthState.Restoring, viewModel.authState.value)
        viewModel.loginWithWebViewCookies("DEMO-000", "fake=cookie")
        assertEquals(AuthState.Authenticating, viewModel.authState.value)
        runCurrent()
        assertTrue(viewModel.authState.value is AuthState.Authenticated)
        restore.complete(null)
        runCurrent()
        assertTrue(viewModel.authState.value is AuthState.Authenticated)
    }

    @Test
    fun fakeDataModeStartsAuthenticatedWithoutLogin() = runTest(dispatcher) {
        val viewModel = ScoreViewModel(
            repository = FakeGradeRepository(),
            demoMode = true,
        )

        runCurrent()

        assertTrue(viewModel.authState.value is AuthState.Authenticated)
        assertEquals("DEMO-000", viewModel.gradesState.value.studentNo)
    }

    @Test
    fun sessionRestoreLoadsStudentInfoOnlyWhenRequested() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)

        runCurrent()

        assertTrue(repository.studentInfoRequests.isEmpty())
        viewModel.refreshStudentInfo()
        runCurrent()
        repository.completeLatestStudentInfo()
        runCurrent()

        assertEquals("範例學生", viewModel.gradesState.value.studentInfo?.studentName)
        assertEquals(null, viewModel.gradesState.value.report)
    }

    @Test
    fun sessionRestoreUsesCachedStudentInfoWithoutNetworkRequest() = runTest(dispatcher) {
        val cachedStudentInfo = FakeData.latestReport().studentInfo
        val repository = ControllableGradeRepository(cachedStudentInfo = cachedStudentInfo)
        val viewModel = ScoreViewModel(repository)

        runCurrent()

        assertEquals(cachedStudentInfo, viewModel.gradesState.value.studentInfo)
        assertTrue(repository.studentInfoRequests.isEmpty())
    }

    @Test
    fun sessionRestorePublishesLoginBeforeCachedStudentInfoReturns() = runTest(dispatcher) {
        val cachedStudentInfo = FakeData.latestReport().studentInfo
        val cachedInfoDeferred = CompletableDeferred<StudentInfo?>()
        val repository = ControllableGradeRepository(
            cachedStudentInfoDeferred = cachedInfoDeferred,
        )
        val viewModel = ScoreViewModel(repository)

        runCurrent()

        assertTrue(viewModel.authState.value is AuthState.Authenticated)
        assertEquals(null, viewModel.gradesState.value.studentInfo)

        cachedInfoDeferred.complete(cachedStudentInfo)
        runCurrent()

        assertEquals(cachedStudentInfo, viewModel.gradesState.value.studentInfo)
    }

    @Test
    fun repeatedStudentInfoRefreshCancelsThePreviousRequest() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        viewModel.refreshStudentInfo()
        runCurrent()
        viewModel.refreshStudentInfo()
        runCurrent()

        assertEquals(2, repository.studentInfoRequests.size)
        assertEquals(1, repository.cancelledStudentInfoRequests)
        repository.completeLatestStudentInfo()
        runCurrent()
        assertEquals("範例學生", viewModel.gradesState.value.studentInfo?.studentName)
    }

    @Test
    fun logoutCancelsStudentInfoRefreshBeforeClearingSession() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        viewModel.refreshStudentInfo()
        runCurrent()
        viewModel.logout()
        runCurrent()

        assertEquals(1, repository.cancelledStudentInfoRequests)
        assertFalse(repository.logoutObservedActiveRequest)
        assertEquals(AuthState.Guest, viewModel.authState.value)
        assertEquals(null, viewModel.gradesState.value.studentInfo)
    }

    @Test
    fun initialLoadSkipsLatestYearWithoutExams() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 2 學期",
                    value = "114_2",
                    exams = listOf(ExamOption("期末考", "114_2_E3")),
                ),
                YearTermOption(
                    text = "115 學年度 第 1 學期",
                    value = "115_1",
                    exams = emptyList(),
                ),
            ),
        )
        runCurrent()

        assertEquals("114_2", viewModel.gradesState.value.selectedYearValue)
        assertEquals("114_2_E3", viewModel.gradesState.value.selectedExamValue)
        assertEquals(listOf(Triple("114_2", "114_2_E3", false)), repository.fetchCalls)
    }

    @Test
    fun initialLoadKeepsLatestYearWhenEveryYearIsEmpty() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption("114 學年度 第 2 學期", "114_2"),
                YearTermOption("115 學年度 第 1 學期", "115_1"),
            ),
        )
        runCurrent()

        assertEquals("115_1", viewModel.gradesState.value.selectedYearValue)
        assertEquals(null, viewModel.gradesState.value.selectedExamValue)
        assertTrue(repository.fetchCalls.isEmpty())
    }

    @Test
    fun selectingAnotherExamInvalidatesStaleFetchResult() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(
                        ExamOption("第一次段考", "E1"),
                        ExamOption("期末考", "E2"),
                    ),
                ),
            ),
        )
        runCurrent()

        viewModel.selectExam("E1")
        runCurrent()
        viewModel.selectExam("E2")
        runCurrent()

        repository.completeFetch("E1", FakeData.previousReport())
        runCurrent()
        assertEquals("E2", viewModel.gradesState.value.selectedExamValue)
        assertEquals(null, viewModel.gradesState.value.report)

        repository.completeFetch("E2", FakeData.latestReport())
        runCurrent()
        assertEquals("期末考", viewModel.gradesState.value.report?.examSummary?.examName)
    }

    @Test
    fun selectingExamKeepsCurrentReportUntilFetchCompletes() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.selectExam("114_1_E2")
        runCurrent()

        assertEquals("114_1_E4", viewModel.gradesState.value.selectedExamValue)
        assertEquals("期末考", viewModel.gradesState.value.report?.examSummary?.examName)
        assertEquals(true, viewModel.gradesState.value.isLoadingGrades)

        repository.completeFetch("114_1_E2", FakeData.previousReport())
        runCurrent()

        assertEquals("114_1_E2", viewModel.gradesState.value.selectedExamValue)
        assertEquals("第二次段考", viewModel.gradesState.value.report?.examSummary?.examName)
        assertEquals(false, viewModel.gradesState.value.isLoadingGrades)
    }

    @Test
    fun refreshingStructureKeepsSelectedExam() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.selectExam("114_1_E2")
        runCurrent()
        repository.completeFetch("114_1_E2", FakeData.previousReport())
        runCurrent()

        viewModel.reloadStructure()
        runCurrent()

        assertEquals("114_1", viewModel.gradesState.value.selectedYearValue)
        assertEquals("114_1_E2", viewModel.gradesState.value.selectedExamValue)
        assertEquals("第二次段考", viewModel.gradesState.value.report?.examSummary?.examName)
    }

    @Test
    fun refreshingWhileExamLoadsDoesNotCancelSelection() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.selectExam("114_1_E2")
        runCurrent()
        viewModel.reloadStructure()
        runCurrent()
        repository.completeFetch("114_1_E2", FakeData.previousReport())
        runCurrent()

        assertEquals("114_1_E2", viewModel.gradesState.value.selectedExamValue)
        assertEquals("第二次段考", viewModel.gradesState.value.report?.examSummary?.examName)
    }

    @Test
    fun refreshingOnlyForcesSelectedExam() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()
        repository.completeFetch("114_1_E1", FakeData.reportFor("114_1", "114_1_E1"))
        repository.completeFetch("114_1_E2", FakeData.previousReport())
        repository.completeFetch("113_2_E3", FakeData.reportFor("113_2", "113_2_E3"))
        runCurrent()

        repository.fetchCalls.clear()
        viewModel.reloadStructure()
        runCurrent()

        val selectedCalls = repository.fetchCalls.filter { it.second == "114_1_E4" }
        val historicalCalls = repository.fetchCalls.filterNot { it.second == "114_1_E4" }
        assertEquals(listOf(true), selectedCalls.map { it.third })
        assertFalse(historicalCalls.isEmpty())
        assertEquals(setOf(false), historicalCalls.map { it.third }.toSet())
    }

    @Test
    fun selectingFirstExamStillBuildsSameTermTrend() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(
                        ExamOption("第一次段考", "114_1_E1"),
                        ExamOption("第二次段考", "114_1_E2"),
                        ExamOption("期末考", "114_1_E4"),
                    ),
                ),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.selectExam("114_1_E1")
        runCurrent()
        repository.completeFetch("114_1_E1", FakeData.reportFor("114_1", "114_1_E1"))
        runCurrent()
        repository.completeFetch("114_1_E2", FakeData.previousReport())
        runCurrent()

        assertEquals(null, viewModel.gradesState.value.trendError)
        assertEquals(
            listOf("第一次段考", "第二次段考", "期末考"),
            viewModel.gradesState.value.trend?.points?.map { it.examName },
        )
    }

    @Test
    fun historicalGradesKeepSuccessfulReportsWhenOneRequestFails() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()
        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(
                        ExamOption("第一次段考", "114_1_E1"),
                        ExamOption("第二次段考", "114_1_E2"),
                        ExamOption("期末考", "114_1_E4"),
                    ),
                ),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.selectExam("114_1_E1")
        runCurrent()
        repository.completeFetch("114_1_E1", FakeData.reportFor("114_1", "114_1_E1"))
        runCurrent()
        repository.failFetch("114_1_E2")
        runCurrent()

        assertEquals(
            listOf("第一次段考", "期末考"),
            viewModel.gradesState.value.trend?.points?.map { it.examName },
        )
        assertEquals("部分資料無法載入", viewModel.gradesState.value.trendError)
    }

    @Test
    fun subjectTrendReportsPartialAndTotalFailuresWithoutRawErrors() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()
        repository.structureDeferred.complete(
            listOf(
                YearTermOption("113 學年度 第 2 學期", "113_2", listOf(ExamOption("第一次段考", "113_2_E1"))),
                YearTermOption("114 學年度 第 1 學期", "114_1", listOf(ExamOption("期末考", "114_1_E4"))),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.initSubjectTrend()
        runCurrent()
        repository.failFetch("113_2_E1")
        runCurrent()
        assertEquals(listOf("期末考"), viewModel.subjectTrendState.value.reports.mapNotNull { it.examSummary?.examName })
        assertEquals("部分資料無法載入", viewModel.subjectTrendState.value.errorMessage)

        val totalFailureRepository = ControllableGradeRepository()
        val totalFailureViewModel = ScoreViewModel(totalFailureRepository)
        runCurrent()
        totalFailureRepository.structureDeferred.complete(
            listOf(YearTermOption("113 學年度 第 2 學期", "113_2", listOf(ExamOption("第一次段考", "113_2_E1")))),
        )
        runCurrent()
        totalFailureRepository.failFetch("113_2_E1")
        runCurrent()
        totalFailureViewModel.initSubjectTrend()
        runCurrent()
        assertEquals(emptyList<GradeReport>(), totalFailureViewModel.subjectTrendState.value.reports)
        assertEquals("載入折線圖資料失敗", totalFailureViewModel.subjectTrendState.value.errorMessage)
    }

    @Test
    fun logoutProvidesActiveSessionToRepository() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)

        repository.structureDeferred.complete(emptyList())
        runCurrent()

        viewModel.logout()
        runCurrent()

        assertEquals("DEMO-000", repository.loggedOutSession?.studentNo)
    }

    @Test
    fun selectingYearWithoutExamsClearsCurrentReport() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(ExamOption("期末考", "114_1_E4")),
                ),
                YearTermOption(
                    text = "113 學年度 第 2 學期",
                    value = "113_2",
                    exams = emptyList(),
                ),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        assertEquals("期末考", viewModel.gradesState.value.report?.examSummary?.examName)

        viewModel.selectYear("113_2")
        runCurrent()

        assertEquals("113_2", viewModel.gradesState.value.selectedYearValue)
        assertEquals(null, viewModel.gradesState.value.selectedExamValue)
        assertEquals(null, viewModel.gradesState.value.report)
        assertEquals(false, viewModel.gradesState.value.isLoadingGrades)
    }

    @Test
    fun selectingYearWithoutExamsCancelsPendingGradeRequest() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()
        repository.structureDeferred.complete(
            listOf(
                YearTermOption("114 上", "114_1", listOf(ExamOption("期末考", "114_1_E4"))),
                YearTermOption("113 下", "113_2", emptyList()),
            ),
        )
        runCurrent()

        viewModel.selectYear("113_2")
        runCurrent()

        assertEquals(listOf("114_1" to "114_1_E4"), repository.cancelledFetches)
        assertFalse(viewModel.gradesState.value.isLoadingGrades)
        assertEquals(null, viewModel.gradesState.value.report)
    }

    @Test
    fun selectingYearWithExamsKeepsCurrentReportUntilFetchCompletes() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        assertEquals(114, viewModel.gradesState.value.report?.examSummary?.year)

        viewModel.selectYear("113_2")
        runCurrent()

        assertEquals("114_1", viewModel.gradesState.value.selectedYearValue)
        assertEquals("114_1_E4", viewModel.gradesState.value.selectedExamValue)
        assertEquals(114, viewModel.gradesState.value.report?.examSummary?.year)
        assertEquals(true, viewModel.gradesState.value.isLoadingGrades)

        repository.completeFetch("113_2_E3", FakeData.reportFor("113_2", "113_2_E3"))
        runCurrent()

        assertEquals("113_2", viewModel.gradesState.value.selectedYearValue)
        assertEquals("113_2_E3", viewModel.gradesState.value.selectedExamValue)
        assertEquals(113, viewModel.gradesState.value.report?.examSummary?.year)
        assertEquals(false, viewModel.gradesState.value.isLoadingGrades)
    }

    @Test
    fun subjectTrendIgnoresStaleFetchResultAfterYearSelectionChanges() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "113 學年度 第 2 學期",
                    value = "113_2",
                    exams = listOf(ExamOption("第一次段考", "113_2_E1")),
                ),
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(ExamOption("期末考", "114_1_E4")),
                ),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.initSubjectTrend()
        runCurrent()
        viewModel.setSubjectTrendYears(setOf("113_2"))
        runCurrent()

        assertTrue(repository.cancelledFetches.any { it.second == "113_2_E1" })

        repository.completeFetch("113_2_E1", FakeData.reportFor("113_2", "113_2_E1"))
        runCurrent()
        assertEquals(listOf("第一次段考"), viewModel.subjectTrendState.value.reports.mapNotNull { it.examSummary?.examName })

        assertEquals(listOf("第一次段考"), viewModel.subjectTrendState.value.reports.mapNotNull { it.examSummary?.examName })
    }

    @Test
    fun subjectTrendYearSelectionLoadsOnceAndSkipsUnchangedSelection() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "113 學年度 第 2 學期",
                    value = "113_2",
                    exams = listOf(ExamOption("第一次段考", "113_2_E1")),
                ),
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(ExamOption("期末考", "114_1_E4")),
                ),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.initSubjectTrend()
        runCurrent()
        val callsBeforeApply = repository.fetchCalls.size

        viewModel.setSubjectTrendYears(setOf("113_2"))
        runCurrent()
        assertEquals(callsBeforeApply + 1, repository.fetchCalls.size)

        viewModel.setSubjectTrendYears(setOf("113_2"))
        runCurrent()
        assertEquals(callsBeforeApply + 1, repository.fetchCalls.size)

        viewModel.setSubjectTrendYears(emptySet())
        runCurrent()
        assertEquals(emptySet<String>(), viewModel.subjectTrendState.value.selectedYearValues)
        assertEquals(emptyList<GradeReport>(), viewModel.subjectTrendState.value.reports)
        assertEquals(callsBeforeApply + 1, repository.fetchCalls.size)
    }

    @Test
    fun subjectTrendSubjectSelectionOnlyUpdatesLocalFilter() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.initSubjectTrend()
        runCurrent()
        val callsBeforeSelection = repository.fetchCalls.size

        viewModel.toggleSubjectTrendSubject("國文")
        viewModel.toggleSubjectTrendSubject("英文")
        runCurrent()

        assertEquals(setOf("國文", "英文"), viewModel.subjectTrendState.value.selectedSubjectKeys)
        assertEquals(callsBeforeSelection, repository.fetchCalls.size)

        viewModel.toggleSubjectTrendSubject("國文")

        assertEquals(setOf("英文"), viewModel.subjectTrendState.value.selectedSubjectKeys)
        assertEquals(callsBeforeSelection, repository.fetchCalls.size)
    }

    @Test
    fun expandedSubjectSelectionIsSingleAndClearsWhenExamChanges() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.toggleSubjectExpanded("國文")
        assertEquals(setOf("國文"), viewModel.gradesState.value.expandedSubjectKeys)

        viewModel.toggleSubjectExpanded("英文")
        assertEquals(setOf("英文"), viewModel.gradesState.value.expandedSubjectKeys)

        viewModel.selectExam("114_1_E2")
        runCurrent()

        assertEquals(emptySet<String>(), viewModel.gradesState.value.expandedSubjectKeys)
    }

    @Test
    fun logoutClearsSubjectTrendState() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(ExamOption("期末考", "114_1_E4")),
                ),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.initSubjectTrend()
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        viewModel.logout()
        runCurrent()

        assertEquals(emptyList<GradeReport>(), viewModel.subjectTrendState.value.reports)
        assertEquals(emptySet<String>(), viewModel.subjectTrendState.value.selectedYearValues)
    }

    @Test
    fun logoutIgnoresStaleStructureResult() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        viewModel.logout()
        repository.structureDeferred.complete(FakeData.structure)
        runCurrent()

        assertEquals(AuthState.Guest, viewModel.authState.value)
        assertEquals(emptyList<YearTermOption>(), viewModel.gradesState.value.structure)
    }

    @Test
    fun logoutWaitsForInFlightSessionRequests() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        viewModel.logout()
        runCurrent()

        assertTrue(repository.structureRequestCancelled)
        assertFalse(repository.logoutObservedActiveRequest)
    }

    @Test
    fun authenticationFailureDowngradesToGuestAndClearsPrivateState() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val viewModel = ScoreViewModel(repository)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(ExamOption("期末考", "114_1_E4")),
                ),
            ),
        )
        runCurrent()
        repository.failAuthenticationFetch("114_1_E4")
        runCurrent()

        assertEquals(AuthState.Guest, viewModel.authState.value)
        assertEquals(null, viewModel.gradesState.value.report)
        assertEquals(emptyList<YearTermOption>(), viewModel.gradesState.value.structure)
        assertTrue(repository.loggedOutSession != null)
    }

    @Test
    fun loginWithWebViewCookiesLogsAnonymousAnalytics() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val analytics = RecordingAnalyticsLogger()
        val viewModel = ScoreViewModel(repository, analyticsLogger = analytics)
        runCurrent()

        viewModel.loginWithWebViewCookies("SENSITIVE-STUDENT", "SESSION_ID=secret")
        runCurrent()

        val startEvent = analytics.events.first { it.name == AnalyticsEvents.LOGIN_START }
        val resultEvent = analytics.events.first { it.name == AnalyticsEvents.LOGIN_RESULT }

        assertEquals(AnalyticsValues.METHOD_WEBVIEW, startEvent.parameters[AnalyticsParams.METHOD])
        assertEquals(AnalyticsValues.RESULT_SUCCESS, resultEvent.parameters[AnalyticsParams.RESULT])
        assertFalse(analytics.containsSensitiveKey())
    }

    @Test
    fun gradeQuerySuccessLogsTriggerAndSubjectBucket() = runTest(dispatcher) {
        val repository = ControllableGradeRepository()
        val analytics = RecordingAnalyticsLogger()
        ScoreViewModel(repository, analyticsLogger = analytics)
        runCurrent()

        repository.structureDeferred.complete(
            listOf(
                YearTermOption(
                    text = "114 學年度 第 1 學期",
                    value = "114_1",
                    exams = listOf(ExamOption("期末考", "114_1_E4")),
                ),
            ),
        )
        runCurrent()
        repository.completeFetch("114_1_E4", FakeData.latestReport())
        runCurrent()

        val gradeEvent = analytics.events.first { it.name == AnalyticsEvents.GRADE_QUERY }
        assertEquals(AnalyticsValues.RESULT_SUCCESS, gradeEvent.parameters[AnalyticsParams.RESULT])
        assertEquals(AnalyticsValues.TRIGGER_INITIAL, gradeEvent.parameters[AnalyticsParams.TRIGGER])
        assertEquals("7_10", gradeEvent.parameters[AnalyticsParams.SUBJECT_COUNT_BUCKET])
        assertFalse(analytics.containsSensitiveKey())
    }

    @Test
    fun logoutRemainsGuestAndReportsAggregatedCleanupFailures() = runTest(dispatcher) {
        val failures = setOf(
            com.clhs.score.data.SessionCleanupFailure.SESSION_DELETE_FAILED,
            com.clhs.score.data.SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED,
            com.clhs.score.data.SessionCleanupFailure.BIOMETRIC_DELETE_FAILED,
        )
        val repository = ControllableGradeRepository(logoutFailure = com.clhs.score.data.SessionCleanupException(failures))
        val viewModel = ScoreViewModel(repository)
        runCurrent()
        assertTrue(viewModel.authState.value is AuthState.Authenticated)
        viewModel.logout()
        assertEquals(AuthState.Guest, viewModel.authState.value)
        assertEquals(null, viewModel.getCurrentSession())
        runCurrent()
        assertEquals(failures, viewModel.cleanupFailures.value)
        assertEquals(AuthState.Guest, viewModel.authState.value)
        assertTrue(repository.structureRequestCancelled)
    }

    private class ControllableGradeRepository(
        private val restoreDeferred: CompletableDeferred<AuthenticatedSession?>? = null,
        private val logoutFailure: Exception? = null,
        private val cachedStudentInfo: StudentInfo? = null,
        private val cachedStudentInfoDeferred: CompletableDeferred<StudentInfo?>? = null,
    ) : GradeRepository {
        val structureDeferred = CompletableDeferred<List<YearTermOption>>()
        val fetchCalls = mutableListOf<Triple<String, String, Boolean>>()
        val studentInfoRequests = mutableListOf<CompletableDeferred<StudentInfo>>()
        var cancelledStudentInfoRequests = 0
        var loggedOutSession: AuthenticatedSession? = null
        var structureRequestCancelled = false
        var logoutObservedActiveRequest = false
        private var activeRequests = 0
        val cancelledFetches = mutableListOf<Pair<String, String>>()
        private val session = AuthenticatedSession("DEMO-000", "token", emptyMap())
        private val fetches = mutableMapOf<Pair<String, String>, CompletableDeferred<GradeReport>>()

        override suspend fun restoreSession(): AuthenticatedSession? = if (restoreDeferred != null) {
            restoreDeferred.await()
        } else {
            session
        }

        override fun activateSession(session: AuthenticatedSession) = Unit

        override suspend fun loadCachedStudentInfo(session: AuthenticatedSession): StudentInfo? =
            cachedStudentInfoDeferred?.await() ?: cachedStudentInfo

        override suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo {
            val request = CompletableDeferred<StudentInfo>()
            studentInfoRequests += request
            activeRequests++
            return try {
                request.await()
            } catch (error: CancellationException) {
                cancelledStudentInfoRequests++
                throw error
            } finally {
                activeRequests--
            }
        }

        override suspend fun loadStructure(
            session: AuthenticatedSession,
            forceRefresh: Boolean,
        ): List<YearTermOption> {
            activeRequests++
            return try {
                structureDeferred.await()
            } catch (error: CancellationException) {
                structureRequestCancelled = true
                throw error
            } finally {
                activeRequests--
            }
        }

        override suspend fun fetchGrades(
            session: AuthenticatedSession,
            yearValue: String,
            examValue: String,
            forceRefresh: Boolean,
        ): GradeReport {
            fetchCalls += Triple(yearValue, examValue, forceRefresh)
            return try {
                fetches.getOrPut(yearValue to examValue) { CompletableDeferred() }.await()
            } catch (error: CancellationException) {
                cancelledFetches += yearValue to examValue
                throw error
            }
        }

        override suspend fun logout(currentSession: AuthenticatedSession?) {
            logoutObservedActiveRequest = activeRequests > 0
            loggedOutSession = currentSession
            logoutFailure?.let { throw it }
        }

        override suspend fun loginWithCookies(
            studentNo: String,
            cookies: Map<String, String>,
        ): AuthenticatedSession = session

        fun completeFetch(examValue: String, report: GradeReport) {
            val matchingKey = fetches.keys.firstOrNull { it.second == examValue }
            val key = matchingKey ?: ("" to examValue)
            fetches.getOrPut(key) { CompletableDeferred() }.complete(report)
        }

        fun completeLatestStudentInfo() {
            studentInfoRequests.last().complete(FakeData.reportFor("114_1", "114_1_E4").studentInfo)
        }

        fun failFetch(examValue: String) {
            val matchingKey = fetches.keys.firstOrNull { it.second == examValue }
            val key = matchingKey ?: ("" to examValue)
            fetches.getOrPut(key) { CompletableDeferred() }.completeExceptionally(IllegalStateException("sensitive upstream error"))
        }

        fun failAuthenticationFetch(examValue: String) {
            val matchingKey = fetches.keys.firstOrNull { it.second == examValue }
            val key = matchingKey ?: ("" to examValue)
            fetches.getOrPut(key) { CompletableDeferred() }
                .completeExceptionally(SchoolAuthenticationException())
        }
    }

    private data class AnalyticsEventRecord(
        val name: String,
        val parameters: Map<String, Any?>,
    )

    private class RecordingAnalyticsLogger : AnalyticsLogger {
        val events = mutableListOf<AnalyticsEventRecord>()

        override fun logEvent(name: String, parameters: Map<String, Any?>) {
            events += AnalyticsEventRecord(name, parameters)
        }

        fun containsSensitiveKey(): Boolean {
            val forbidden = setOf("studentNo", "cookies", "apiToken", "rawResult", "scoreValue", "url")
            return events.any { event -> event.parameters.keys.any { it in forbidden } }
        }
    }
}
