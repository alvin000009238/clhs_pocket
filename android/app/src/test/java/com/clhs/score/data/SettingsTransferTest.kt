package com.clhs.score.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.clhs.score.domain.overview.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class SettingsTransferTest {
    private fun document() = SettingsTransferDocument(
        app = "clhs-pocket", version = 1,
        settings = PortableSettings.from(AppSettings(themeMode = ThemeMode.DARK, startOnSchedule = true,
            backgroundPause = BackgroundPause(true, 1320, 360))),
        overviewOrderVersion = 2,
        overview = listOf(
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(id = "weather", ownRow = true),
            OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("期中考", "2026-10-16"))
                .copy(id = "exam", enabled = false),
            OverviewWidgetInstance("future", "future-widget", OverviewWidgetSize(2, 1), configVersion = 2, configuration = "opaque"),
        ),
        announcements = PortableAnnouncementPreferences(true, 180, setOf(ALL_UNITS_ID)),
        remindBeforeClass = true,
        subjects = listOf(ScheduleSubjectOverride("體育", "體育課", "王老師", "操場")),
    )

    @Test fun roundTripKeepsAllPortableDataAndNeverSerializesAccountOrLock() {
        val document = document()
        val encoded = SettingsTransferCodec.encode(document)
        assertEquals(document, SettingsTransferCodec.decode(encoded))
        for (forbidden in listOf("studentNo", "apiToken", "cookies", "authorizationId", "biometricEnabled",
            "weatherCredentialRevision", "hasCompletedOnboarding", "pausedUntil", "generation")) {
            assertFalse(forbidden, encoded.contains(forbidden))
        }
        assertEquals(document, SettingsTransferCodec.decode("\uFEFF" + encoded))
    }

    @Test fun rejectsUnknownMissingOversizedDeepAndInvalidDataBeforeAnyWrite() {
        val valid = SettingsTransferCodec.encode(document())
        val inputs = listOf("{}", valid.replace(""""version": 1""", """"version": 9"""),
            valid.replace(""""app": "clhs-pocket"""", """"app": "other-app""""),
            valid.dropLast(1) + """, "apiToken": "not-accepted"}""",
            "[".repeat(33) + "]".repeat(33), " ".repeat(SettingsTransferCodec.MAX_BYTES + 1))
        inputs.forEach { assertTrue(runCatching { SettingsTransferCodec.decode(it) }.isFailure) }
        val invalid = listOf(document().copy(overview = document().overview + document().overview.first()),
            document().copy(overview = listOf(document().overview.first().copy(configuration = "{}"))),
            document().copy(subjects = listOf(ScheduleSubjectOverride("體育"))),
            document().copy(subjects = listOf(ScheduleSubjectOverride("體育", "a".repeat(81)))),
            document().copy(settings = document().settings.copy(backgroundPauseEndMinute = 1320)),
            document().copy(announcements = document().announcements.copy(intervalMinutes = 1)))
        invalid.forEach { assertTrue(runCatching { SettingsTransferCodec.encode(it) }.isFailure) }
        assertTrue(runCatching { SettingsTransferCodec.read(ByteArrayInputStream(ByteArray(SettingsTransferCodec.MAX_BYTES + 1))) }.isFailure)
    }

    @Test fun readRejectsMalformedUtf8WithoutSubstitution() {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        assertTrue("Malformed UTF-8 must not be silently replaced", runCatching {
            SettingsTransferCodec.read(ByteArrayInputStream(invalidUtf8))
        }.isFailure)
    }

    @Test fun restoreKeepsDeviceLockAndOnboardingAndPreservesLegacyOrderVersion() = runTest {
        val settings = SettingsRepository(OverviewTestSettingsStore())
        settings.setBiometricEnabled(true)
        settings.setHasCompletedOnboarding(true)
        val dao = OverviewTestLayoutDao()
        val layout = OverviewLayoutRepository(dao, settings)
        val reminder = AnnouncementReminderRepository(OverviewTestSettingsStore())
        val course = CourseNotificationPreferences(OverviewTestSettingsStore())
        val repository = SettingsTransferRepository(settings, layout, reminder, course, FakeScheduleRepository(), { null })
        val restored = document().copy(overviewOrderVersion = 1, subjects = null)
        repository.restore(restored, false, null)
        assertEquals(restored, repository.export())
        assertTrue(settings.settings.first().biometricEnabled)
        assertTrue(settings.settings.first().hasCompletedOnboarding)
        assertEquals(1, dao.marker!!.formatVersion)
        repository.restore(restored.copy(overview = emptyList(), overviewOrderVersion = 2), false, null)
        assertTrue(layout.observe().first().isEmpty())
        assertEquals(2, dao.marker!!.formatVersion)
    }

    @Test fun laterWriteFailureRestoresEarlierStoresWithoutReportingSuccess() = runTest {
        val store = FailingOnceStore()
        val settings = SettingsRepository(store)
        val dao = OverviewTestLayoutDao()
        val layout = OverviewLayoutRepository(dao, settings)
        val reminder = AnnouncementReminderRepository(OverviewTestSettingsStore())
        val course = CourseNotificationPreferences(OverviewTestSettingsStore())
        val repository = SettingsTransferRepository(settings, layout, reminder, course, FakeScheduleRepository(), { null })
        val original = repository.export()
        store.failNext = true
        val failure = runCatching { repository.restore(document().copy(subjects = null), false, null) }.exceptionOrNull()
        assertTrue(failure is SettingsTransferRestoreException)
        assertFalse((failure as SettingsTransferRestoreException).rollbackFailed)
        assertEquals(original, repository.export())
    }

    @Test fun cancellationCompensatesCompletedWritesAndRemainsCancellation() = runTest {
        val settings = SettingsRepository(OverviewTestSettingsStore())
        val layout = OverviewLayoutRepository(OverviewTestLayoutDao(), settings)
        val announcements = AnnouncementReminderRepository(OverviewTestSettingsStore())
        announcements.restoreSnapshot(AnnouncementReminderState(enabled = true, baselineInitialized = true,
            knownIds = setOf("seen"), unreadIds = setOf("unread"), lastCheckedAtMillis = 42))
        val course = CourseNotificationPreferences(OverviewTestSettingsStore())
        val reachedCourse = CompletableDeferred<Unit>()
        var interrupt = true
        val repository = SettingsTransferRepository(settings, layout, announcements, course,
            FakeScheduleRepository(), { null }, restoreCoursePreference = { enabled ->
                course.setRemindBeforeClass(enabled)
                if (interrupt) {
                    interrupt = false
                    reachedCourse.complete(Unit)
                    awaitCancellation()
                }
            })
        val original = repository.export()
        val originalProgress = announcements.loadState()
        var cancellation: CancellationException? = null
        val restore = launch {
            try {
                repository.restore(document().copy(subjects = null,
                    announcements = PortableAnnouncementPreferences(true, 180, setOf("12"))), false, null)
                fail("Interrupted import must not report success")
            } catch (error: CancellationException) { cancellation = error }
        }
        try {
            reachedCourse.await()
            assertNotEquals(original.overview, layout.observe().first())
            assertFalse(announcements.loadState().baselineInitialized)
            assertTrue(course.state.first().remindBeforeClass)
        } finally { restore.cancelAndJoin() }

        assertNotNull("Cancellation must propagate after successful compensation", cancellation)
        assertEquals(original, repository.export())
        assertEquals(originalProgress, announcements.loadState())
    }

    @Test fun failedCompensationExplicitlyReportsPartialRestore() = runTest {
        val settings = SettingsRepository(OverviewTestSettingsStore())
        val layout = OverviewLayoutRepository(OverviewTestLayoutDao(), settings)
        val repository = SettingsTransferRepository(settings, layout, AnnouncementReminderRepository(OverviewTestSettingsStore()),
            CourseNotificationPreferences(OverviewTestSettingsStore()), FakeScheduleRepository(), { null },
            restoreCoursePreference = { throw IOException("test notification owner failure") })
        val original = repository.export()
        val failure = runCatching { repository.restore(document().copy(subjects = null), false, null) }.exceptionOrNull()
        assertTrue(failure is SettingsTransferRestoreException)
        assertTrue((failure as SettingsTransferRestoreException).rollbackFailed)
        assertEquals(original.overview, repository.export().overview)
    }

    @Test fun revokedAuthorizationCannotCommitSubjects() = runTest {
        val store = GradeCacheStore(OverviewTestSettingsStore()) {}
        val original = listOf(ScheduleSubjectOverride("數學", "原設定"))
        store.saveScheduleSubjectOverrides("student", original)
        val failure = runCatching {
            store.saveScheduleSubjectOverrides("student", document().subjects!!) { false }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(original, store.scheduleSubjectOverridesFlow("student").first())
    }

    @Test fun announcementScopeResetDoesNotOverwriteUnrelatedProgressWhenUnchanged() = runTest {
        val repository = AnnouncementReminderRepository(OverviewTestSettingsStore())
        repository.restoreSnapshot(AnnouncementReminderState(enabled = true, baselineInitialized = true,
            knownIds = setOf("seen"), unreadIds = setOf("unread"), lastCheckedAtMillis = 42))
        val original = repository.loadState()
        repository.restorePortable(PortableAnnouncementPreferences.from(original).copy(intervalMinutes = 180))
        assertEquals(original.copy(intervalMinutes = 180), repository.loadState())
        repository.restorePortable(PortableAnnouncementPreferences(false, 60, setOf(ALL_UNITS_ID)))
        assertFalse(repository.loadState().baselineInitialized)
        assertTrue(repository.loadState().knownIds.isEmpty())
    }

    @Test fun subjectsAreOptionalAndAccountChangesRejectBeforeWriting() = runTest {
        var session: AuthenticatedSession? = AuthenticatedSession("test-student", "test-placeholder", emptyMap())
        val captured = session!!
        val schedule = FakeScheduleRepository(activeSessionProvider = { session })
        val settings = SettingsRepository(OverviewTestSettingsStore())
        val layout = OverviewLayoutRepository(OverviewTestLayoutDao(), settings)
        val repository = SettingsTransferRepository(settings, layout, AnnouncementReminderRepository(OverviewTestSettingsStore()),
            CourseNotificationPreferences(OverviewTestSettingsStore()), schedule, { session })
        schedule.saveSubjectOverrides(listOf(ScheduleSubjectOverride("數學", "原設定")))
        repository.restore(document(), false, captured)
        assertEquals("原設定", schedule.subjectOverridesFlow().first().single().customSubjectName)
        repository.restore(document(), true, captured)
        assertEquals(document().subjects, schedule.subjectOverridesFlow().first())
        val original = repository.export()
        session = null
        assertTrue(runCatching { repository.restore(document().copy(overview = emptyList()), true, captured) }.isFailure)
        assertEquals(original.overview, repository.export().overview)
    }
}

private class FailingOnceStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data = state
    var failNext = false
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        if (failNext) { failNext = false; throw IOException("test write failure") }
        return transform(state.value).also { state.value = it }
    }
}
