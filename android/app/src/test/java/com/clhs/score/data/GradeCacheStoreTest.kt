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
    @Test fun backgroundSemesterFallbackDoesNotPublishWidgetWhenMissingOrExpired() = runTest {
        for (hasExpiredWeek in listOf(false, true)) {
            val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporaryFolder.root.resolve("background-fallback-$hasExpiredWeek.preferences_pb")
            }
            val store = testGradeCacheStore(preferences) {}
            val expired = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
                weekStartDate = "2025-01-06", weekEndDate = "2025-01-12")
            if (hasExpiredWeek) store.saveWidgetScheduleReport("student", expired)
            val fallback = scheduleReport("231", ScheduleScope.SEMESTER)
            store.saveScheduleReport("student", "114_2_231", fallback, updateBrowseSelection = false)
            assertEquals(if (hasExpiredWeek) expired else null, store.loadWidgetScheduleReport())
            assertEquals(fallback, store.loadScheduleReport("student", "114_2_231"))
        }
    }

    @Test
    fun v211MigrationSeparatesFutureBrowserFromCurrentAutomaticWeekAndIsRepeatable() = runTest {
        val disk = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("v211-upgrade.preferences_pb")
        }
        val today = LocalDate.now(SCHOOL_COURSE_ZONE)
        val current = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = today.toString(), weekEndDate = today.plusDays(6).toString())
        val future = current.copy(weekStartDate = today.plusWeeks(1).toString(), weekEndDate = today.plusDays(13).toString())
        disk.edit {
            it[stringPreferencesKey("schedule_latest_student")] = Json.encodeToString(future)
            it[stringPreferencesKey("schedule_student_114_2_230_current_week")] = Json.encodeToString(current)
        }
        val store = testGradeCacheStore(disk) {}
        repeat(2) {
            assertEquals(future, store.loadLatestScheduleSnapshot("student")?.report)
            assertEquals(current, store.loadOverviewScheduleSnapshot("student")?.report)
            assertEquals(0L, store.loadOverviewScheduleSnapshot("student")?.fetchedAtMillis)
        }
    }

    @Test
    fun notificationUsesSavedTargetWeekBeforeSemesterFallback() = runTest {
        val disk = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("notification-target-week.preferences_pb")
        }
        val today = LocalDate.now(SCHOOL_COURSE_ZONE)
        val current = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = today.toString(), weekEndDate = today.plusDays(6).toString())
        val old = current.copy(weekStartDate = today.minusWeeks(1).toString(), weekEndDate = today.minusDays(1).toString())
        val semester = current.copy(scope = ScheduleScope.SEMESTER, items = listOf(ScheduleItem(1, 1, "Baseline")))
        disk.edit {
            it[stringPreferencesKey("widget_schedule_student_no")] = "student"
            it[stringPreferencesKey("widget_schedule_report")] = Json.encodeToString(old)
            it[stringPreferencesKey("schedule_student_114_2_230_current_week")] = Json.encodeToString(current)
            it[stringPreferencesKey("schedule_student_114_2_230")] = Json.encodeToString(semester)
        }
        val store = testGradeCacheStore(disk) {}
        assertEquals(current, store.courseNotificationScheduleReportFlow().first())
    }

    @Test
    fun fakeSecondTermWeeksStartInFollowingCalendarYear() = runTest {
        val weeks = FakeScheduleRepository().getScheduleWeeks("115", "2")
        assertEquals(LocalDate.of(2027, 2, 1), LocalDate.parse(weeks.first().startDate))
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadIoFailureIsCacheMissAndSaveIoFailureIsIgnored() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val store = testGradeCacheStore(FailingDataStore(IOException("disk unavailable")), diagnostics::add)

        assertNull(store.loadStructure("student"))
        assertNull(store.loadOverviewScheduleSnapshot("student"))
        store.saveStructure("student", emptyList())

        assertTrue(diagnostics.any { it.contains("cache read failed") })
        assertTrue(diagnostics.any { it.contains("cache write failed") })
    }

    @Test
    fun courseNotificationSnapshotFlowPreservesIoFailureForRecovery() = runTest {
        val store = testGradeCacheStore(
            FailingDataStore(IOException("disk unavailable")),
            {},
            kotlinx.coroutines.flow.flowOf(true),
        )

        assertTrue(runCatching { store.courseNotificationScheduleReportFlow().first() }.exceptionOrNull() is IOException)
        assertNull(store.widgetScheduleReportFlow().first())
    }

    @Test
    fun courseNotificationFlowFallsBackToCachedSemesterReportAfterWeeklyRefreshPoint() = runTest {
        val file = temporaryFolder.root.resolve("notification-semester-fallback.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = testGradeCacheStore(dataStore) {}
        val semester = scheduleReport("230", ScheduleScope.SEMESTER).copy(
            items = listOf(ScheduleItem(1, 1, "學期國文")),
        )
        val oldWeek = scheduleReport(
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-08-10",
            weekEndDate = "2026-08-16",
        ).copy(items = listOf(ScheduleItem(1, 1, "週課程")))

        store.saveScheduleReport("student", "114_2_230", semester)
        store.saveScheduleReport("student", "114_2_230_current_week", oldWeek)
        store.saveScheduleSubjectOverrides("student", listOf(ScheduleSubjectOverride("學期國文", "自訂國文")))

        val selected = store.courseNotificationScheduleReportFlow().first()
        assertEquals(ScheduleScope.SEMESTER, selected?.scope)
        assertEquals("自訂國文", selected?.displayItems()?.first()?.subjectName)
    }

    @Test
    fun courseNotificationFlowPrefersUsableFreshWeekAndFallsBackWhenItHasNoUsableItems() = runTest {
        val file = temporaryFolder.root.resolve("notification-week-priority.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = testGradeCacheStore(dataStore) {}
        val semester = scheduleReport("230", ScheduleScope.SEMESTER)
        val weekStart = LocalDate.now(SCHOOL_COURSE_ZONE).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
        val week = scheduleReport(
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = weekStart.toString(),
            weekEndDate = weekStart.plusDays(6).toString(),
        ).copy(items = listOf(ScheduleItem(1, 1, "週課程")))

        store.saveScheduleReport("student", "114_2_230", semester)
        store.saveScheduleReport("student", "114_2_230_current_week", week)

        assertEquals(week, store.courseNotificationScheduleReportFlow().first())

        store.saveScheduleReport("student", "114_2_230_current_week", week.copy(items = emptyList()))
        assertEquals(ScheduleScope.SEMESTER, store.courseNotificationScheduleReportFlow().first()?.scope)

        store.saveScheduleReport(
            "student", "114_2_230_current_week",
            week.copy(weekEndDate = weekStart.plusDays(7).toString()),
        )
        assertEquals(ScheduleScope.SEMESTER, store.courseNotificationScheduleReportFlow().first()?.scope)
    }

    @Test
    fun widgetFallsBackAfterLastClassWhileNotificationsAndEmptyWeekRemainAuthoritative() = runTest {
        val file = temporaryFolder.root.resolve("authoritative-current-week.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = testGradeCacheStore(dataStore) {}
        val today = LocalDate.now(SCHOOL_COURSE_ZONE)
        val yesterday = today.minusDays(1)
        val week = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = yesterday.toString(), weekEndDate = today.plusDays(1).toString(),
        ).copy(items = listOf(ScheduleItem(yesterday.dayOfWeek.value, 1, "週課程")))
        val semester = scheduleReport("230", ScheduleScope.SEMESTER)

        store.saveScheduleReport("student", "114_2_230_current_week", week)
        store.saveScheduleReport("student", "114_2_230", semester)
        assertEquals(week.copy(changes = null), store.courseNotificationScheduleReportFlow().first())
        assertEquals(semester.copy(changes = null), store.loadWidgetScheduleReport())
        assertEquals(store.loadWidgetScheduleReport(), store.widgetScheduleReportFlow().first())

        store.saveScheduleReport("student", "114_2_230_current_week", week.copy(items = emptyList()))
        assertEquals(emptyList<ScheduleItem>(), store.courseNotificationScheduleReportFlow().first()?.items)
        assertEquals(emptyList<ScheduleItem>(), store.loadWidgetScheduleReport()?.items)
    }

    @Test
    fun emptyOverviewWeekSurvivesManualFutureHistoricalAndSemesterBrowsing() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("overview-empty-week.preferences_pb")
        }
        val store = testGradeCacheStore(dataStore) {}
        val start = LocalDate.now(SCHOOL_COURSE_ZONE)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val current = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = start.toString(), weekEndDate = start.plusDays(6).toString())
        store.saveScheduleReport("student", "114_2_230_current_week", current)
        val original = store.loadOverviewScheduleSnapshot("student")
        assertEquals(current, original?.report)
        for (offset in listOf(1L, -1L)) {
            store.saveScheduleReport("student", "114_2_230_current_week", current.copy(
                weekStartDate = start.plusWeeks(offset).toString(),
                weekEndDate = start.plusWeeks(offset).plusDays(6).toString()))
            assertEquals(original, store.loadOverviewScheduleSnapshot("student"))
        }
        val semester = scheduleReport("230", ScheduleScope.SEMESTER)
        store.saveScheduleReport("student", "114_2_230", semester)
        assertEquals(original, store.loadOverviewScheduleSnapshot("student"))
        assertEquals(semester, store.loadLatestScheduleReport("student"))
    }

    @Test
    fun automaticNextWeekAndItsSemesterFallbackBecomeOverviewAuthority() = runTest {
        val store = testGradeCacheStore(PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("overview-automatic-week.preferences_pb")
        }) {}
        val start = LocalDate.now(SCHOOL_COURSE_ZONE)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val current = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = start.toString(), weekEndDate = start.plusDays(6).toString())
        val next = current.copy(weekStartDate = start.plusWeeks(1).toString(),
            weekEndDate = start.plusWeeks(1).plusDays(6).toString())
        store.saveScheduleReport("student", "114_2_230_current_week", current)
        store.saveScheduleReport("student", "114_2_230_current_week", next)
        assertEquals(current, store.loadOverviewScheduleSnapshot("student")?.report)
        store.saveScheduleReport("student", "114_2_230_current_week", next, updateOverviewSnapshot = true)
        val prepared = store.loadOverviewScheduleSnapshot("student")
        assertEquals(next, prepared?.report)
        assertTrue(prepared?.fetchedAtMillis != null)
        store.saveScheduleReport("student", "114_2_230_current_week", next.copy(
            weekStartDate = start.plusWeeks(2).toString(), weekEndDate = start.plusWeeks(2).plusDays(6).toString()))
        assertEquals(prepared, store.loadOverviewScheduleSnapshot("student"))
        val fallback = scheduleReport("230", ScheduleScope.SEMESTER)
        store.saveScheduleReport("student", "114_2_230", fallback, updateOverviewSnapshot = true)
        assertEquals(fallback, store.loadOverviewScheduleSnapshot("student")?.report)
    }

    @Test
    fun overviewAuthorityIsBoundToStudentClassAndYearAndRemovedByPrivacyCleanup() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("overview-isolation.preferences_pb")
        }
        val store = testGradeCacheStore(dataStore) {}
        val today = LocalDate.now(SCHOOL_COURSE_ZONE)
        val current = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = today.toString(), weekEndDate = today.plusDays(6).toString())
        store.saveScheduleReport("student", "114_2_230_current_week", current)
        store.saveScheduleReport("other", "114_2_230_current_week", current)
        assertNull(store.loadOverviewScheduleSnapshot("unknown"))
        store.saveScheduleReport("student", "114_2_231", current.copy(scope = ScheduleScope.SEMESTER, classNo = "231"))
        assertNull(store.loadOverviewScheduleSnapshot("student"))
        store.saveScheduleReport("student", "115_1_230", current.copy(scope = ScheduleScope.SEMESTER, yearTermValue = "115_1"))
        assertNull(store.loadOverviewScheduleSnapshot("student"))
        store.clearStudent("student", preserveSubjectOverrides = true)
        assertTrue(dataStore.data.first().asMap().keys.none { it.name.startsWith("schedule_student_") })
        assertEquals(current, store.loadOverviewScheduleSnapshot("other")?.report)
        store.clearAll(preserveSubjectOverrides = true)
        assertNull(store.loadOverviewScheduleSnapshot("other"))
    }

    @Test
    fun revokedSnapshotAuthoritySkipsPrivateSnapshotReads() = runTest {
        val store = testGradeCacheStore(
            FailingDataStore(IOException("private snapshot must not be read")),
            {},
            kotlinx.coroutines.flow.flowOf(false),
        )

        assertNull(store.courseNotificationScheduleReportFlow().first())
    }

    @Test
    fun cancellationIsNotConvertedToCacheMiss() {
        val store = testGradeCacheStore(FailingDataStore(CancellationException("cancelled"))) {}

        assertThrows(CancellationException::class.java) {
            runBlocking { store.loadStructure("student") }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { store.saveStructure("student", emptyList()) }
        }
    }

    @Test
    fun privacyCleanupRemainsStrictOnIoFailure() {
        val store = testGradeCacheStore(FailingDataStore(IOException("disk unavailable"))) {}

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
        val store = testGradeCacheStore(dataStore) {}
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
        val store = testGradeCacheStore(dataStore) {}
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
        val store = testGradeCacheStore(dataStore) {}
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
    fun browsingOtherWeeksPreservesCurrentWeekWidgetAuthority() {
        val today = LocalDate.of(2026, 11, 4)
        val current = scheduleReport("230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-02", weekEndDate = "2026-11-08")
        for (offset in listOf(-7L, 7L)) {
            val other = current.copy(
                weekStartDate = LocalDate.parse(current.weekStartDate).plusDays(offset).toString(),
                weekEndDate = LocalDate.parse(current.weekEndDate).plusDays(offset).toString(),
            )
            assertNull(other.toWidgetReportOrNull(current, today))
            assertEquals(current.copy(changes = null), current.toWidgetReportOrNull(other, today))
        }
    }
    @Test
    fun semesterDoesNotReplaceCurrentWeekWidgetAfterItsLastClass() {
        val today = LocalDate.now(SCHOOL_COURSE_ZONE)
        val currentWeek = scheduleReport(
            "week",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = today.minusDays(1).toString(),
            weekEndDate = today.plusDays(1).toString(),
        ).copy(
            items = listOf(ScheduleItem(today.minusDays(1).dayOfWeek.value, 1, "本週課程")),
        )
        val semester = scheduleReport("semester", ScheduleScope.SEMESTER)

        assertNull(semester.toWidgetReportOrNull(currentWeek, today))
    }

    @Test
    fun loadingWidgetReportFallsBackToLatestSemesterAfterCurrentWeekEnds() = runTest {
        val file = temporaryFolder.root.resolve("widget-load-fallback.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = gradeCacheCorruptionHandler,
            scope = backgroundScope,
            produceFile = { file },
        )
        val store = testGradeCacheStore(dataStore) {}
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
        val store = testGradeCacheStore(dataStore) {}
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

        testGradeCacheStore(dataStore) {}.saveStudentInfo("student", studentInfo)
        val restoredStore = testGradeCacheStore(dataStore) {}

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
        val store = testGradeCacheStore(dataStore) {}
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
        val restoredStore = testGradeCacheStore(dataStore) {}
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
        val store = testGradeCacheStore(FailingDataStore(IOException("disk unavailable"))) {}

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
        val store = testGradeCacheStore(faultyDisk, {}, allowed)
        store.saveWidgetScheduleReport("student", scheduleReport("230", ScheduleScope.SEMESTER))
        val snapshots = store.widgetScheduleReportFlow().stateIn(
            backgroundScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null,
        )
        assertTrue(snapshots.first { it != null } != null)
        disk.edit { it[stringPreferencesKey("widget_schedule_report")] = Json.encodeToString(scheduleReport("230", ScheduleScope.SEMESTER)) }
        failWrites = true
        assertTrue(runCatching { store.clearWidgetScheduleReport() }.exceptionOrNull() is IOException)
        allowed.value = false
        assertNull(snapshots.first { it == null })
        assertNull(store.loadWidgetScheduleReport())
        assertTrue(disk.data.first().asMap().isNotEmpty())
    }

    @Test
    fun manualBrowseOfExpiredCurrentWeekDoesNotOverwriteUpcomingOverviewPointer() = runTest {
        val now = java.time.LocalDateTime.parse("2026-07-24T17:00:00")
        val store = testScheduleSnapshotStore { now }
        val currentExpired = ScheduleReport(
            yearTermValue = "114_2",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(dayOfWeek = 5, period = 4, subjectName = "國文")),
        )
        val upcoming = currentExpired.copy(
            weekStartDate = "2026-07-26",
            weekEndDate = "2026-08-01",
            items = listOf(ScheduleItem(dayOfWeek = 1, period = 1, subjectName = "數學")),
        )

        store.save("student", upcoming, automatic = true, shouldCommit = { true }, capturedAt = 100L, browse = true)
        store.save("student", currentExpired, automatic = false, shouldCommit = { true }, capturedAt = 200L, browse = true)

        assertEquals("2026-07-26", store.selected("student", "overview")?.report?.weekStartDate)
        assertEquals("2026-07-19", store.selected("student", "browser")?.report?.weekStartDate)
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
