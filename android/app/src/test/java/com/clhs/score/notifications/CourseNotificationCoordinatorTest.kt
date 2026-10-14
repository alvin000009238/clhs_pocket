package com.clhs.score.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.clhs.score.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CourseNotificationCoordinatorTest {
    @Test fun advanceOptInPersistsRearmsImmediatelyAndPreservesTodayPause() = runTest {
        val file = folder.newFile("advance.preferences_pb")
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(
            scope = backgroundScope, produceFile = { file }))
        val clock = MutableClock(Instant.parse("2026-09-21T00:05:00Z"))
        var rendered: CourseResolution? = null
        val coordinator = CourseNotificationCoordinator(preferences, clock, { report }, { true },
            { value, _ -> rendered = value }, { rendered = null })
        assertFalse(preferences.state.first().remindBeforeClass)
        coordinator.setEnabled(true)
        assertNull(rendered?.upcoming)
        coordinator.setRemindBeforeClass(true)
        assertTrue(preferences.state.first().remindBeforeClass)
        assertEquals(1, rendered?.upcoming?.item?.period)
        assertEquals(Instant.parse("2026-09-21T00:10:00Z"), rendered?.nextBoundary)
        coordinator.setRemindBeforeClass(false)
        assertNull(rendered?.upcoming)
        coordinator.setRemindBeforeClass(true)
        coordinator.closeToday(preferences.state.first().generation)
        val pausedUntil = preferences.state.first().pausedUntil
        coordinator.setRemindBeforeClass(false)
        coordinator.setRemindBeforeClass(true)
        assertEquals(pausedUntil, preferences.state.first().pausedUntil)
        assertNull(rendered?.upcoming)
        assertEquals(Instant.ofEpochMilli(pausedUntil), rendered?.nextBoundary)
        coordinator.setEnabled(false)
        coordinator.setRemindBeforeClass(true)
        assertNull(rendered)
        assertFalse(preferences.state.first().enabled)
    }

    @get:Rule val folder = TemporaryFolder()
    private val report = ScheduleReport("1151", "", ScheduleScope.CURRENT_WEEK,
        weekStartDate = "2026-09-21", weekEndDate = "2026-09-27",
        items = listOf(ScheduleItem(1, 1, "國文"), ScheduleItem(1, 2, "英文"), ScheduleItem(2, 1, "數學")))
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    @Test fun enabledStateSurvivesClosingAndReopeningTheDataStore() = runTest {
        val file = folder.newFile("reopen.preferences_pb")
        val firstJob = Job()
        val firstStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + firstJob), produceFile = { file })
        try {
            val preferences = CourseNotificationPreferences(firstStore)
            preferences.setEnabled(true)
            preferences.setRemindBeforeClass(true)
        } finally { firstJob.cancelAndJoin() }
        val reopened = CourseNotificationPreferences(PreferenceDataStoreFactory.create(
            scope = backgroundScope, produceFile = { file }))
        assertTrue(reopened.state.first().enabled)
        assertTrue(reopened.state.first().remindBeforeClass)
        reopened.setEnabled(false)
        assertFalse(reopened.state.first().enabled)
    }

    @Test fun defaultOffDoesNotInheritLegacyOptInAndRejectsMissingDataOrPermission() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { folder.newFile("defaults.preferences_pb") })
        store.edit { it[booleanPreferencesKey("notifications_enabled")] = true }
        val preferences = CourseNotificationPreferences(store)
        val clock = MutableClock(Instant.parse("2026-09-21T00:30:00Z"))
        var snapshot: ScheduleReport? = null
        var permission = true
        var posted: CourseResolution? = null
        val coordinator = CourseNotificationCoordinator(preferences, clock, { snapshot }, { permission }, { value, _ -> posted = value }, { posted = null })
        coordinator.refresh()
        assertFalse(preferences.state.first().enabled)
        assertNull(posted)
        assertNotNull(coordinator.setEnabled(true))
        snapshot = report
        permission = false
        assertNotNull(coordinator.setEnabled(true))
        assertFalse(preferences.state.first().enabled)
        permission = true
        assertNull(coordinator.setEnabled(true))
        assertNotNull(posted?.current)
        permission = false // Same behavior for revoked runtime permission or a blocked channel.
        coordinator.refresh()
        assertNull(posted)
        assertTrue(preferences.state.first().enabled)
    }

    @Test fun semesterScheduleCanEnableAndResolvesTheNextWeekdayOccurrence() = runTest {
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(
            scope = backgroundScope, produceFile = { folder.newFile("semester-enabled.preferences_pb") },
        ))
        val semester = report.copy(
            scope = ScheduleScope.SEMESTER,
            weekStartDate = null,
            weekEndDate = null,
            items = listOf(ScheduleItem(1, 1, "學期國文")),
        )
        val clock = MutableClock(Instant.parse("2026-09-25T01:05:00Z"))
        var posted: CourseResolution? = null
        val coordinator = CourseNotificationCoordinator(
            preferences, clock, { semester }, { true }, { value, _ -> posted = value }, { posted = null },
        )

        assertNull(coordinator.setEnabled(true))
        assertTrue(preferences.state.first().enabled)
        assertEquals(Instant.parse("2026-09-28T00:10:00Z"), posted?.nextBoundary)

        clock.now = Instant.parse("2026-09-28T00:30:00Z")
        coordinator.refresh()
        assertEquals("學期國文", posted?.current?.item?.subjectName)
    }

    @Test fun closePausesTodayAndLateCloseCannotPauseANewOptIn() = runTest {
        val file = folder.newFile("close.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val preferences = CourseNotificationPreferences(store)
        val clock = MutableClock(Instant.parse("2026-09-21T00:30:00Z"))
        var posted: CourseResolution? = null
        val coordinator = CourseNotificationCoordinator(preferences, clock, { report }, { true }, { value, _ -> posted = value }, { posted = null })
        coordinator.setEnabled(true)
        val generation = preferences.state.first().generation
        coordinator.closeToday(generation)
        assertTrue(preferences.state.first().enabled)
        assertNull(posted?.current)
        assertEquals(Instant.parse("2026-09-21T16:00:00Z"), posted?.nextBoundary)
        coordinator.refresh() // Swipe, foreground and snapshot refresh must retain today's pause.
        assertNull(posted?.current)
        val reopened = CourseNotificationCoordinator(preferences, clock, { report }, { true },
            { value, _ -> posted = value }, { posted = null })
        reopened.refresh()
        assertNull(posted?.current)
        clock.now = Instant.parse("2026-09-22T00:30:00Z")
        reopened.refresh()
        assertEquals("數學", posted?.current?.item?.subjectName)
        coordinator.setEnabled(true)
        coordinator.closeToday(generation)
        assertTrue(preferences.state.first().enabled)
        assertNotNull(posted?.current)
        assertEquals(0L, preferences.state.first().pausedUntil)
        coordinator.closeToday(preferences.state.first().generation)
        coordinator.setEnabled(false)
        clock.now = Instant.parse("2026-09-23T00:30:00Z")
        coordinator.refresh()
        assertFalse(preferences.state.first().enabled)
        assertNull(posted)
    }

    @Test fun serializedRefreshCommitDeleteAndRapidTogglesCannotResurrectDisabledOutput() = runTest {
        val file = folder.newFile("race.preferences_pb")
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        val clock = MutableClock(Instant.parse("2026-09-21T00:30:00Z"))
        var snapshot: ScheduleReport? = report
        var posted: CourseResolution? = null
        val coordinator = CourseNotificationCoordinator(preferences, clock, { snapshot }, { true }, { value, _ -> posted = value }, { posted = null })
        coordinator.setEnabled(true)
        val gate = CompletableDeferred<Unit>()
        val commit = launch { coordinator.commit { gate.await(); snapshot = report.copy(items = listOf(ScheduleItem(1, 1, "新課程"))) } }
        runCurrent()
        assertNull(posted)
        val alarm = launch { coordinator.refresh() }
        val disable = launch { coordinator.setEnabled(false) }
        gate.complete(Unit)
        listOf(commit, alarm, disable).forEach { it.join() }
        coordinator.refresh() // Late pending broadcast after disabling.
        assertFalse(preferences.state.first().enabled)
        assertNull(posted)
        repeat(10) { coordinator.setEnabled(true); coordinator.setEnabled(false) }
        assertNull(posted)
    }

    @Test fun logoutExpiryClockJumpAndFailedPrivateDeletionDoNotKeepOldCourse() = runTest {
        val file = folder.newFile("invalidate.preferences_pb")
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        val clock = MutableClock(Instant.parse("2026-09-21T00:30:00Z"))
        var snapshot: ScheduleReport? = report
        var posted: CourseResolution? = null
        val coordinator = CourseNotificationCoordinator(preferences, clock, { snapshot }, { true }, { value, _ -> posted = value }, { posted = null })
        coordinator.setEnabled(true)
        coordinator.commit { snapshot = null }
        assertNull(posted?.current)
        assertNull(posted?.nextBoundary)
        coordinator.commit { snapshot = report }
        assertNotNull(posted?.current)
        clock.now = Instant.parse("2026-09-21T01:15:00Z")
        coordinator.refresh()
        assertEquals("英文", posted?.current?.item?.subjectName)
        clock.now = Instant.parse("2026-09-28T00:30:00Z")
        coordinator.refresh()
        assertNull(posted?.nextBoundary)
        try { coordinator.commit { throw IOException("delete failed") }; fail() } catch (_: IOException) { }
        assertNull(posted)
    }

    @Test fun boundaryCrossedDuringPublicationIsRecomputedWithoutPastAlarmLoop() = runTest {
        val file = folder.newFile("publication-boundary.preferences_pb")
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        val clock = MutableClock(Instant.parse("2026-09-21T00:59:59Z"))
        var posted: CourseResolution? = null
        var renders = 0
        val coordinator = CourseNotificationCoordinator(preferences, clock, { report }, { true }, { value, _ ->
            posted = value
            if (++renders == 1) clock.now = Instant.parse("2026-09-21T01:00:00Z")
        }, { posted = null })
        coordinator.setEnabled(true)
        assertNull(posted?.current)
        assertEquals(Instant.parse("2026-09-21T01:10:00Z"), posted?.nextBoundary)
        assertEquals(2, renders)
    }

    @Test fun transientFailureGetsOneRecoveryAndSuccessRestoresTheCourseBoundary() = runTest {
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(
            scope = backgroundScope, produceFile = { folder.newFile("recovery.preferences_pb") },
        ))
        val clock = MutableClock(Instant.parse("2026-09-21T00:30:00Z"))
        var failRead = false
        var recoverySchedules = 0
        var posted: CourseResolution? = null
        val coordinator = CourseNotificationCoordinator(
            preferences = preferences,
            clock = clock,
            loadReport = { if (failRead) throw IOException("temporary snapshot failure") else report },
            canPost = { true },
            render = { value, _ -> posted = value },
            cancel = { posted = null },
            scheduleRecovery = { recoverySchedules++ },
        )

        assertNull(coordinator.setEnabled(true))
        failRead = true
        assertEquals(CourseNotificationRefreshOutcome.TRANSIENT_FAILURE, coordinator.refresh())
        assertEquals(1, recoverySchedules)
        assertNotNull(posted?.current)

        assertEquals(CourseNotificationRefreshOutcome.TRANSIENT_FAILURE, coordinator.refresh(fromRecovery = true))
        assertEquals(1, recoverySchedules)

        failRead = false
        assertEquals(CourseNotificationRefreshOutcome.SUCCESS, coordinator.refresh(fromRecovery = true))
        assertEquals(Instant.parse("2026-09-21T01:00:00Z"), posted?.nextBoundary)
        assertEquals(1, recoverySchedules)
    }

    @Test fun timeoutRecoveryIsBoundedAndDisabledStateCannotReschedule() = runTest {
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(
            scope = backgroundScope, produceFile = { folder.newFile("timeout-recovery.preferences_pb") },
        ))
        var recoverySchedules = 0
        var cancellations = 0
        val coordinator = CourseNotificationCoordinator(
            preferences = preferences,
            clock = MutableClock(Instant.parse("2026-09-21T00:30:00Z")),
            loadReport = { report },
            canPost = { true },
            render = { _, _ -> },
            cancel = { cancellations++ },
            scheduleRecovery = { recoverySchedules++ },
        )

        coordinator.setEnabled(true)
        assertEquals(CourseNotificationRefreshOutcome.TRANSIENT_FAILURE, coordinator.receiverTimedOut(fromRecovery = false))
        assertEquals(CourseNotificationRefreshOutcome.TRANSIENT_FAILURE, coordinator.receiverTimedOut(fromRecovery = true))
        assertEquals(1, recoverySchedules)

        coordinator.setEnabled(false)
        coordinator.receiverTimedOut(fromRecovery = false)
        assertEquals(1, recoverySchedules)
        assertEquals(2, cancellations)
    }

    @Test fun coldTimeoutReadsPersistedOptInBeforeSchedulingRecovery() = runTest {
        val preferences = CourseNotificationPreferences(PreferenceDataStoreFactory.create(
            scope = backgroundScope, produceFile = { folder.newFile("cold-timeout-recovery.preferences_pb") },
        ))
        preferences.setEnabled(true)
        var recoverySchedules = 0
        val coordinator = CourseNotificationCoordinator(
            preferences = preferences,
            clock = MutableClock(Instant.parse("2026-09-21T00:30:00Z")),
            loadReport = { report },
            canPost = { true },
            render = { _, _ -> },
            cancel = {},
            scheduleRecovery = { recoverySchedules++ },
        )

        assertEquals(CourseNotificationRefreshOutcome.TRANSIENT_FAILURE, coordinator.receiverTimedOut(fromRecovery = false))
        assertEquals(1, recoverySchedules)
    }

    @Test fun recoveryGateRejectsDisabledAndInvalidatedSession() {
        assertTrue(shouldScheduleCourseRecovery(true, 4, 4, 8, 8))
        assertFalse(shouldScheduleCourseRecovery(false, 4, 4, 8, 8))
        assertFalse(shouldScheduleCourseRecovery(true, 4, 5, 8, 8))
        assertFalse(shouldScheduleCourseRecovery(true, 4, 4, 8, 9))
        assertFalse(shouldScheduleCourseRecovery(true, 4, 4, 8, 8, sessionRevoked = true))
    }
}
