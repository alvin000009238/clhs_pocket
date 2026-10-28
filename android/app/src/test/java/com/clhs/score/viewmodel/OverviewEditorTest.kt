package com.clhs.score.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.NetworkSchoolCalendarRepository
import com.clhs.score.data.OverviewLayoutMetadata
import com.clhs.score.data.OverviewLayoutRepository
import com.clhs.score.data.OverviewTestLayoutDao
import com.clhs.score.data.OverviewTestSettingsStore
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.SettingsRepository
import com.clhs.score.domain.overview.OverviewCoordinator
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetSize
import com.clhs.score.domain.overview.OverviewLayoutJson
import kotlinx.serialization.encodeToString
import com.clhs.score.domain.overview.defaultOverviewWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewEditorTest {
    @Test fun calendarCountdownPreservesDraftAndCommitsOnlyOnSave() = runBlocking {
        val initial = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val vm = viewModel(dao)
        awaitLoaded(vm)
        assertFalse(vm.addCalendarCountdown("", "期中考"))
        assertFalse(vm.editor.value.editing)
        vm.beginEditing()
        vm.removeWidget(initial.first().id)
        assertTrue(vm.addCalendarCountdown("exam", "期中考"))
        val added = vm.editor.value.draft!!.first()
        assertEquals("countdown", added.type)
        assertEquals(OverviewWidgetConfig.SchoolEvent("exam", "期中考"), added.config())
        assertFalse(vm.editor.value.draft!!.any { it.id == initial.first().id })
        assertEquals(0, dao.saveAttempts)
        vm.cancelEditing()
        assertEquals(initial, vm.editor.value.committed)
        assertTrue(vm.addCalendarCountdown("exam", "期中考"))
        assertTrue(vm.saveLayout())
        assertEquals(OverviewWidgetConfig.SchoolEvent("exam", "期中考"), dao.rows.value.first().instance().config())
        assertEquals(initial.size + 1, dao.rows.value.size)
    }

    @Test fun preparingLegacyVisualOrderDoesNotSaveOrMakeUnchangedDraftDirty() = runBlocking {
        val initial = legacyResizeWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val handle = SavedStateHandle()
        val vm = viewModel(dao, handle)
        awaitLoaded(vm)
        assertTrue(vm.editor.value.legacyOrder)
        vm.beginEditing()
        val order = listOf("bike", "countdown", "calendar")
        vm.prepareLayoutOrder(4, initial.map { it.id })
        assertFalse(vm.editor.value.legacyOrder)
        assertEquals(order, vm.editor.value.draft!!.map { it.id })
        assertEquals(vm.editor.value.committed, vm.editor.value.draft)
        assertFalse(vm.editor.value.hasChanges)
        assertEquals(0, dao.saveAttempts)
        assertEquals(initial, dao.rows.value.map { it.instance() })
        vm.prepareLayoutOrder(4, initial.map { it.id })
        assertEquals(order, vm.editor.value.draft!!.map { it.id })
        vm.cancelEditing()
        assertEquals(0, dao.saveAttempts)
        assertEquals(1, dao.marker!!.formatVersion)
        val restored = viewModel(dao, handle)
        awaitLoaded(restored)
        assertFalse(restored.editor.value.legacyOrder)
        assertEquals(order, restored.editor.value.committed!!.map { it.id })
    }

    @Test fun restoredUnsavedLegacyReorderDoesNotReplaceCommittedOrderOnCancel() = runBlocking {
        val initial = legacyResizeWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val handle = SavedStateHandle(mapOf("overview_draft" to OverviewLayoutJson.encodeToString(initial.reversed())))
        val vm = viewModel(dao, handle)
        awaitLoaded(vm)
        vm.prepareLayoutOrder(4, initial.map { it.id })
        assertEquals(listOf("bike", "countdown", "calendar"), vm.editor.value.committed!!.map { it.id })
        assertEquals(listOf("countdown", "bike", "calendar"), vm.editor.value.draft!!.map { it.id })
        assertTrue(vm.editor.value.hasChanges)
        vm.cancelEditing()
        assertEquals(listOf("bike", "countdown", "calendar"), vm.editor.value.committed!!.map { it.id })
        assertEquals(initial, dao.rows.value.map { it.instance() })
        assertEquals(0, dao.saveAttempts)
    }

    private fun legacyResizeWidgets() = listOf(
        OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike(com.clhs.score.data.YouBikeStation.SCHOOL))
            .copy(id = "bike", size = OverviewWidgetSize(2, 1)),
        OverviewWidgetInstance.create("calendar", OverviewWidgetConfig.ListItems()).copy(id = "calendar"),
        OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("Exam", "2026-10-14"))
            .copy(id = "countdown", size = OverviewWidgetSize(2, 1)),
    )
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val viewModels = ViewModelStore()
    private val jobs = mutableListOf<Job>()

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() {
        viewModels.clear()
        runBlocking { jobs.joinAll() }
        Dispatchers.resetMain()
    }

    @Test
    fun failedSavePreservesDraftAndCommittedLayoutAndSuccessfulRetryCommitsOnce() = runBlocking {
        val initial = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val handle = SavedStateHandle()
        val vm = viewModel(dao, handle)
        awaitLoaded(vm)
        vm.beginEditing()
        vm.removeWidget(initial.first().id)
        val draft = vm.editor.value.draft!!
        dao.saveFailure = IOException("test save failure")

        assertFalse(vm.saveLayout())
        assertEquals(draft, vm.editor.value.draft)
        assertEquals(initial, dao.rows.value.map { it.instance() })
        assertTrue(vm.editor.value.hasChanges)
        assertFalse(vm.editor.value.saving)
        assertNotNull(vm.editor.value.error)
        assertNotNull(handle.get<String>("overview_draft"))

        dao.saveFailure = null
        assertTrue(vm.saveLayout())
        assertEquals(draft, dao.rows.value.map { it.instance() })
        assertFalse(vm.editor.value.editing)
        assertNull(vm.editor.value.error)
        assertNull(handle.get<String>("overview_draft"))
        assertEquals(2, dao.saveAttempts)
    }

    @Test
    fun cancelDiscardsReorderResizeRemovalAndResetWithoutWriting() = runBlocking {
        val initial = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val handle = SavedStateHandle()
        val vm = viewModel(dao, handle)
        awaitLoaded(vm)
        vm.beginEditing()
        vm.moveWidget(initial.last().id, 0)
        vm.putWidget(initial.single { it.type == "schedule" }.copy(size = OverviewWidgetSize(4, 3)))
        vm.removeWidget(initial[1].id)
        vm.resetLayout()
        assertTrue(vm.editor.value.hasChanges)

        vm.cancelEditing()

        assertFalse(vm.editor.value.editing)
        assertFalse(vm.editor.value.hasChanges)
        assertFalse(vm.editor.value.canUndoReset)
        assertEquals(initial, dao.rows.value.map { it.instance() })
        assertEquals(0, dao.saveAttempts)
        assertNull(handle.get<String>("overview_draft"))
        assertNull(handle.get<String>("overview_reset_undo"))
    }

    @Test
    fun savedStateReconstructionRestoresDraftAndChangesWithoutSavingIt() = runBlocking {
        val initial = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val handle = SavedStateHandle()
        val first = viewModel(dao, handle)
        awaitLoaded(first)
        first.beginEditing()
        first.moveWidget(initial.last().id, 0)
        first.putWidget(OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("段考", "2027-01-01")))
        val expected = first.editor.value.draft!!
        val serialized = handle.get<String>("overview_draft")!!
        viewModels.clear()

        val restored = viewModel(dao, SavedStateHandle(mapOf("overview_draft" to serialized)))
        awaitLoaded(restored)

        assertTrue(restored.editor.value.editing)
        assertTrue(restored.editor.value.hasChanges)
        assertEquals(expected, restored.editor.value.draft)
        assertEquals(initial, dao.rows.value.map { it.instance() })
        assertEquals(0, dao.saveAttempts)
    }

    @Test
    fun singletonEditsKeepIdentityAndUnchangedOperationsRemainCleanWhileMultipleTypesCanRepeat() = runBlocking {
        val initial = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val vm = viewModel(dao)
        awaitLoaded(vm)
        vm.beginEditing()
        assertFalse(vm.editor.value.hasChanges)
        vm.putWidget(initial.first())
        vm.moveWidget(initial.first().id, 0)
        assertFalse(vm.editor.value.hasChanges)

        vm.putWidget(OverviewWidgetInstance.create("schedule").copy(size = OverviewWidgetSize(4, 3)))
        val schedule = vm.editor.value.draft!!.filter { it.type == "schedule" }
        assertEquals(1, schedule.size)
        assertEquals(initial.single { it.type == "schedule" }.id, schedule.single().id)
        assertEquals(OverviewWidgetSize(4, 3), schedule.single().size)

        vm.putWidget(OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()))
        vm.putWidget(OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()))
        vm.putWidget(OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike()))
        assertEquals(2, vm.editor.value.draft!!.count { it.type == "weather" })
        assertEquals(1, vm.editor.value.draft!!.count { it.type == "youbike" })
        assertEquals(0, dao.saveAttempts)
    }

    @Test
    fun resetAndUndoIncludingAfterReconstructionRestoreTheExactDraft() = runBlocking {
        val initial = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val handle = SavedStateHandle()
        val vm = viewModel(dao, handle)
        awaitLoaded(vm)
        vm.beginEditing()
        vm.removeWidget(initial.first().id)
        val before = vm.editor.value.draft!!
        vm.resetLayout()
        assertTrue(vm.editor.value.canUndoReset)
        assertEquals(listOf("schedule", "alerts", "calendar", "announcements"), vm.editor.value.draft!!.map { it.type })
        vm.undoReset()
        assertEquals(before, vm.editor.value.draft)
        assertFalse(vm.editor.value.canUndoReset)

        vm.resetLayout()
        val recreatedHandle = SavedStateHandle(mapOf(
            "overview_draft" to handle.get<String>("overview_draft"),
            "overview_reset_undo" to handle.get<String>("overview_reset_undo"),
        ))
        viewModels.clear()
        val recreated = viewModel(dao, recreatedHandle)
        awaitLoaded(recreated)
        assertTrue(recreated.editor.value.canUndoReset)
        recreated.undoReset()
        assertEquals(before, recreated.editor.value.draft)
        assertEquals(0, dao.saveAttempts)
    }

    @Test
    fun committedUnknownConfigurationSurvivesUnrelatedEditorChanges() = runBlocking {
        val unknown = OverviewWidgetInstance("future", "future-type", OverviewWidgetSize(5, 4),
            configVersion = 11, configuration = "{\"opaque\":[\"preserve\",1]}")
        val initial = defaultOverviewWidgets() + unknown
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata())
        val vm = viewModel(dao)
        awaitLoaded(vm)
        vm.beginEditing()
        vm.removeWidget(initial.first().id)

        assertTrue(vm.saveLayout())
        assertEquals(unknown, dao.rows.value.map { it.instance() }.single { it.id == "future" })
    }

    private suspend fun awaitLoaded(vm: OverviewViewModel) {
        withTimeout(10_000) { vm.editor.first { !it.loading && it.layoutError == null } }
    }

    @Test fun restoredDraftRemainsDirtyWhenCommittedLayoutCannotBeRead() = runBlocking {
        val initial = defaultOverviewWidgets()
        val draft = initial.drop(1)
        val dao = OverviewTestLayoutDao(initial, OverviewLayoutMetadata()).apply { readFailure = IOException("read failure") }
        val handle = SavedStateHandle(mapOf("overview_draft" to OverviewLayoutJson.encodeToString(draft)))
        val vm = viewModel(dao, handle)
        val failed = withTimeout(10_000) { vm.editor.first { !it.loading && it.layoutError != null } }
        assertFalse(vm.addCalendarCountdown("exam", "期中考"))
        assertTrue(failed.editing)
        assertTrue(failed.hasChanges)
        assertEquals(draft, failed.draft)
        assertEquals(initial, dao.rows.value.map { it.instance() })
        assertNotNull(handle.get<String>("overview_draft"))
        dao.readFailure = null
        vm.retryLayout()
        awaitLoaded(vm)
        assertTrue(vm.editor.value.hasChanges)
        assertEquals(draft, vm.editor.value.draft)
    }

    private fun viewModel(dao: OverviewTestLayoutDao, handle: SavedStateHandle = SavedStateHandle()): OverviewViewModel {
        val directory = temporaryFolder.newFolder()
        val instant = Instant.parse("2026-10-04T04:30:00Z")
        val publicClient = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.endsWith("ics"))
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR" else "[{\"pageNum\":0,\"totalPages\":1}]"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val schedule = object : ScheduleRepository {
            override suspend fun getScheduleYears(): List<ScheduleYearTermOption> = error("Guest must not query school")
            override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> = error("Guest must not query school")
            override suspend fun getLatestSchedule(): ScheduleReport? = error("Guest must not query school")
            override suspend fun fetchSchedule(yearValue: String, year: String, term: String, classNo: String,
                scope: ScheduleScope, targetDate: LocalDate, expectedSession: AuthenticatedSession?): ScheduleReport = error("Guest must not query school")
        }
        val settings = SettingsRepository(OverviewTestSettingsStore())
        return OverviewViewModel(
            coordinator = OverviewCoordinator(
                schedule, NetworkSchoolCalendarRepository(directory, publicClient, nowProvider = { instant }),
                NetworkSchoolAnnouncementsRepository(directory, publicClient, nowProvider = { instant }),
                flowOf(GradeReminderState()), clock = Clock.fixed(instant, ZoneId.of("UTC")),
            ),
            settingsRepository = settings, authState = MutableStateFlow(AuthState.Guest), activeSessionProvider = { null },
            dispatcher = Dispatchers.Unconfined, layoutRepository = OverviewLayoutRepository(dao, settings), savedStateHandle = handle,
        ).also {
            viewModels.put("editor-${jobs.size}", it)
            jobs += it.viewModelScope.coroutineContext.job
        }
    }
}
