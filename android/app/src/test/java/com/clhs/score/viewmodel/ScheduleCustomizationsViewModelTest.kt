package com.clhs.score.viewmodel

import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ScheduleYearTermOption
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleCustomizationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun restoreOnePersistsTheRemainingOverrides() = runTest(dispatcher) {
        val chinese = ScheduleSubjectOverride("國文", "國語文")
        val math = ScheduleSubjectOverride("數學", customClassroom = "201")
        val repository = CustomizationsRepository(listOf(chinese, math))
        val viewModel = ScheduleCustomizationsViewModel(repository)
        runCurrent()

        viewModel.removeOverride("國文")
        runCurrent()

        assertEquals(listOf(math), repository.savedOverrides)
        assertEquals(listOf(math), viewModel.uiState.value.overrides)
    }

    @Test
    fun saveFailureKeepsOriginalOverridesAndUsesFixedMessage() = runTest(dispatcher) {
        val original = ScheduleSubjectOverride("國文", "國語文")
        val repository = CustomizationsRepository(listOf(original)).apply {
            saveFailure = IOException("private location")
        }
        val viewModel = ScheduleCustomizationsViewModel(repository)
        runCurrent()

        viewModel.replaceOverrides(emptyList())
        runCurrent()

        assertEquals(listOf(original), viewModel.uiState.value.overrides)
        assertEquals(ScheduleViewModel.SUBJECT_OVERRIDE_SAVE_ERROR, viewModel.uiState.value.noticeMessage)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    private class CustomizationsRepository(initial: List<ScheduleSubjectOverride>) : ScheduleRepository {
        private val overrides = MutableStateFlow(initial)
        var savedOverrides = initial
        var saveFailure: IOException? = null

        override fun subjectOverridesFlow() = overrides

        override suspend fun saveSubjectOverrides(overrides: List<ScheduleSubjectOverride>) {
            saveFailure?.let { throw it }
            savedOverrides = overrides
            this.overrides.value = overrides
        }

        override suspend fun getLatestSchedule() =
            ScheduleReport("114_2", "230", ScheduleScope.SEMESTER, items = emptyList())

        override suspend fun getScheduleYears(): List<ScheduleYearTermOption> = emptyList()
        @Suppress("UNUSED_PARAMETER")
        override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> = emptyList()
        @Suppress("UNUSED_PARAMETER")
        override suspend fun fetchSchedule(
            yearValue: String,
            year: String,
            term: String,
            classNo: String,
            scope: ScheduleScope,
            targetDate: LocalDate,
        ) = error("Not used")
    }
}
