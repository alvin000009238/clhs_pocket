package com.clhs.score.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.LocalDate

class GradeCacheStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadIoFailureIsCacheMissAndSaveIoFailureIsIgnored() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val store = GradeCacheStore(FailingDataStore(IOException("disk unavailable")), diagnostics::add)

        assertNull(store.loadStructure("student"))
        store.saveStructure("student", emptyList())

        assertTrue(diagnostics.any { it.contains("cache read failed") })
        assertTrue(diagnostics.any { it.contains("cache write failed") })
    }

    @Test
    fun cancellationIsNotConvertedToCacheMiss() {
        val store = GradeCacheStore(FailingDataStore(CancellationException("cancelled"))) {}

        assertThrows(CancellationException::class.java) {
            runBlocking { store.loadStructure("student") }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { store.saveStructure("student", emptyList()) }
        }
    }

    @Test
    fun privacyCleanupRemainsStrictOnIoFailure() {
        val store = GradeCacheStore(FailingDataStore(IOException("disk unavailable"))) {}

        assertThrows(IOException::class.java) {
            runBlocking { store.clearAll() }
        }
        assertThrows(IOException::class.java) {
            runBlocking { store.clearWidgetScheduleReport() }
        }
    }

    @Test
    fun malformedPreferencesAreResetToEmpty() = runTest {
        val file = temporaryFolder.newFile("grade-cache.preferences_pb")
        file.writeBytes(byteArrayOf(0x0A, 0x7F))
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )

        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun onlyCurrentWeekReportsCanBecomeWidgetSnapshots() {
        val week = scheduleReport("week", ScheduleScope.CURRENT_WEEK, changes = emptyList())
        val semester = scheduleReport("semester", ScheduleScope.SEMESTER)

        assertEquals(week.copy(changes = null), week.toWidgetReportOrNull())
        assertNull(semester.toWidgetReportOrNull())
    }

    @Test
    fun semesterSaveUpdatesLatestWithoutReplacingWeekWidget() = runTest {
        val file = temporaryFolder.root.resolve("widget-scope.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = GradeCacheStore(dataStore) {}
        val today = LocalDate.now()
        val week = scheduleReport(
            "week",
            ScheduleScope.CURRENT_WEEK,
            changes = emptyList(),
            weekStartDate = today.minusDays(1).toString(),
            weekEndDate = today.plusDays(1).toString(),
        )
        val semester = scheduleReport("semester", ScheduleScope.SEMESTER)

        store.saveScheduleReport("student", "week", week)
        store.saveScheduleReport("student", "semester", semester)

        assertEquals(semester, store.loadLatestScheduleReport("student"))
        assertEquals(week.copy(changes = null), store.loadWidgetScheduleReport())
    }

    @Test
    fun semesterBecomesWidgetFallbackWhenNoWeekSnapshotExists() = runTest {
        val file = temporaryFolder.root.resolve("semester-widget-fallback.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = GradeCacheStore(dataStore) {}
        val semester = scheduleReport("semester", ScheduleScope.SEMESTER)

        store.saveScheduleReport("student", "semester", semester)

        assertEquals(semester.copy(changes = null), store.loadWidgetScheduleReport())
    }

    @Test
    fun latestSemesterReplacesExpiredWeekWidget() = runTest {
        val file = temporaryFolder.root.resolve("expired-widget-scope.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = GradeCacheStore(dataStore) {}
        val today = LocalDate.now()
        val expiredWeek = scheduleReport(
            "week",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = today.minusDays(7).toString(),
            weekEndDate = today.minusDays(1).toString(),
        )
        val semester = scheduleReport("semester", ScheduleScope.SEMESTER)

        store.saveScheduleReport("student", "week", expiredWeek)
        store.saveWidgetScheduleReport("student", semester)

        assertEquals(semester.copy(changes = null), store.loadWidgetScheduleReport())
    }

    @Test
    fun semesterReplacesCurrentWeekWidgetAfterItsLastClass() {
        val today = LocalDate.now()
        val currentWeek = scheduleReport(
            "week",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = today.minusWeeks(2).toString(),
            weekEndDate = today.toString(),
        ).copy(
            items = listOf(ScheduleItem(today.dayOfWeek.value, 1, "本週課程")),
        )
        val semester = scheduleReport("semester", ScheduleScope.SEMESTER)

        assertEquals(
            semester.copy(changes = null),
            semester.toWidgetReportOrNull(currentWeek, today),
        )
    }

    @Test
    fun loadingWidgetReportFallsBackToLatestSemesterAfterCurrentWeekEnds() = runTest {
        val file = temporaryFolder.root.resolve("widget-load-fallback.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = GradeCacheStore(dataStore) {}
        val today = LocalDate.now()
        val currentWeek = scheduleReport(
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = today.minusWeeks(2).toString(),
            weekEndDate = today.toString(),
        ).copy(items = listOf(ScheduleItem(today.dayOfWeek.value, 1, "本週課程")))
        val semester = scheduleReport("230", ScheduleScope.SEMESTER)

        store.saveScheduleReport("student", "114_2_230_current_week", currentWeek)
        store.saveScheduleReport("student", "114_2_230", semester)
        val json = Json { encodeDefaults = true }
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("widget_schedule_report")] = json.encodeToString(currentWeek)
            prefs[stringPreferencesKey("widget_schedule_student_no")] = "student"
        }

        assertEquals(semester.copy(changes = null), store.loadWidgetScheduleReport())
    }

    @Test
    fun latestScheduleCarriesFreshnessAndStudentCleanupRemovesIt() = runTest {
        val file = temporaryFolder.root.resolve("schedule-freshness.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = GradeCacheStore(dataStore) {}
        val report = scheduleReport("230", ScheduleScope.SEMESTER)

        store.saveScheduleReport("student", "114_2_230", report)

        val snapshot = store.loadLatestScheduleSnapshot("student")
        assertEquals(report, snapshot?.report)
        assertTrue(snapshot?.fetchedAtMillis != null)
        val keyedSnapshot = store.loadScheduleSnapshot("student", "114_2_230")
        assertEquals(report, keyedSnapshot?.report)
        assertTrue(keyedSnapshot?.fetchedAtMillis != null)

        store.clearStudent("student")

        assertNull(store.loadLatestScheduleSnapshot("student"))
        assertNull(store.loadScheduleSnapshot("student", "114_2_230"))
    }

    @Test
    fun studentInfoSurvivesStoreRecreationAndIsClearedWithStudent() = runTest {
        val file = temporaryFolder.root.resolve("student-info.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val studentInfo = FakeData.latestReport().studentInfo

        GradeCacheStore(dataStore) {}.saveStudentInfo("student", studentInfo)
        val restoredStore = GradeCacheStore(dataStore) {}

        assertEquals(studentInfo, restoredStore.loadStudentInfo("student"))
        restoredStore.clearStudent("student")
        assertNull(restoredStore.loadStudentInfo("student"))
    }

    @Test
    fun subjectOverridesPersistUpdateWidgetAndClearWithStudent() = runTest {
        val file = temporaryFolder.root.resolve("schedule-subject-overrides.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = GradeCacheStore(dataStore) {}
        val override = ScheduleSubjectOverride("彈性學習時間", customSubjectName = "彈性課")
        store.saveScheduleReport("student", "114_2", scheduleReport("230", ScheduleScope.SEMESTER))

        store.saveScheduleSubjectOverrides("student", listOf(override))

        assertEquals(listOf(override), store.scheduleSubjectOverridesFlow("student").first())
        assertEquals(listOf(override), store.loadWidgetScheduleReport()?.subjectOverrides)

        val otherOverride = ScheduleSubjectOverride("彈性學習時間", customSubjectName = "其他帳號")
        store.saveScheduleSubjectOverrides("other", listOf(otherOverride))
        store.clearStudent("student", preserveSubjectOverrides = true)
        assertNull(store.loadLatestScheduleSnapshot("student"))
        assertNull(store.loadWidgetScheduleReport())
        assertEquals(listOf(override), store.loadScheduleSubjectOverrides("student"))

        store.saveScheduleReport("other", "114_2", scheduleReport("230", ScheduleScope.SEMESTER))
        store.clearAll(preserveSubjectOverrides = true)
        val restoredStore = GradeCacheStore(dataStore) {}
        assertNull(restoredStore.loadLatestScheduleSnapshot("other"))
        assertNull(restoredStore.loadWidgetScheduleReport())
        assertEquals(listOf(override), restoredStore.loadScheduleSubjectOverrides("student"))
        assertEquals(listOf(otherOverride), restoredStore.loadScheduleSubjectOverrides("other"))

        restoredStore.clearStudent("student")
        assertEquals(emptyList<ScheduleSubjectOverride>(), restoredStore.loadScheduleSubjectOverrides("student"))
        assertEquals(listOf(otherOverride), restoredStore.loadScheduleSubjectOverrides("other"))
        restoredStore.clearAll()
        assertEquals(emptyList<ScheduleSubjectOverride>(), restoredStore.loadScheduleSubjectOverrides("other"))
    }

    @Test
    fun subjectOverrideSaveFailureIsPropagated() {
        val store = GradeCacheStore(FailingDataStore(IOException("disk unavailable"))) {}

        assertThrows(IOException::class.java) {
            runBlocking { store.saveScheduleSubjectOverrides("student", emptyList()) }
        }
    }

    @Test
    fun revokedWidgetSnapshotDisappearsEvenWhenPhysicalDeletionFails() = runTest {
        val disk = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("revoked-widget.preferences_pb")
        }
        var failWrites = false
        val faultyDisk = object : DataStore<Preferences> by disk {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites) throw IOException("injected deletion failure")
                return disk.updateData(transform)
            }
        }
        val allowed = kotlinx.coroutines.flow.MutableStateFlow(true)
        val store = GradeCacheStore(faultyDisk, {}, allowed)
        store.saveWidgetScheduleReport("student", scheduleReport("230", ScheduleScope.SEMESTER))
        val snapshots = store.widgetScheduleReportFlow().stateIn(
            backgroundScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null,
        )
        assertTrue(snapshots.first { it != null } != null)
        failWrites = true
        assertTrue(runCatching { store.clearWidgetScheduleReport() }.exceptionOrNull() is IOException)
        allowed.value = false
        assertNull(snapshots.first { it == null })
        assertNull(store.loadWidgetScheduleReport())
        assertTrue(disk.data.first().asMap().isNotEmpty())
    }

    private fun scheduleReport(
        classNo: String,
        scope: ScheduleScope,
        changes: List<ScheduleChange>? = null,
        weekStartDate: String? = if (scope == ScheduleScope.CURRENT_WEEK) "2026-08-10" else null,
        weekEndDate: String? = if (scope == ScheduleScope.CURRENT_WEEK) "2026-08-16" else null,
    ) = ScheduleReport(
        yearTermValue = "114_2",
        classNo = classNo,
        scope = scope,
        weekStartDate = weekStartDate,
        weekEndDate = weekEndDate,
        items = emptyList(),
        changes = changes,
    )

    private class FailingDataStore(private val failure: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            throw failure
        }
    }
}
