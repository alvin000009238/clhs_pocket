package com.clhs.score.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SettingsTransferRestoreException(val rollbackFailed: Boolean, cause: Exception) :
    Exception("Settings restore failed", cause)

/** Stores retain their own transactions. Failed multi-store writes are compensated in reverse order. */
internal class SettingsTransferRepository(
    private val settings: SettingsRepository,
    private val layout: OverviewLayoutRepository,
    private val announcements: AnnouncementReminderRepository,
    private val course: CourseNotificationPreferences,
    private val schedule: ScheduleRepository,
    private val currentSession: () -> AuthenticatedSession?,
    private val restoreCoursePreference: suspend (Boolean) -> Unit = course::setRemindBeforeClass,
) {
    private companion object { val importMutex = Mutex() }

    suspend fun export(): SettingsTransferDocument {
        val session = currentSession()
        val board = layout.observeLayout().first()
        val subjects = session?.let { schedule.subjectOverridesFlow().first() }
        checkSession(session)
        return SettingsTransferDocument(
            app = "clhs-pocket", version = 1,
            settings = PortableSettings.from(settings.settings.first()),
            overviewOrderVersion = if (board.legacyOrder) 1 else 2,
            overview = board.widgets,
            announcements = PortableAnnouncementPreferences.from(announcements.loadState()),
            remindBeforeClass = course.state.first().remindBeforeClass,
            subjects = subjects,
        ).also { checkSession(session); SettingsTransferCodec.validate(it) }
    }

    suspend fun restore(document: SettingsTransferDocument, includeSubjects: Boolean, expectedSession: AuthenticatedSession?) =
        importMutex.withLock {
            SettingsTransferCodec.validate(document)
            if (includeSubjects) {
                require(document.subjects != null && expectedSession != null) { "請先登入再匯入自訂科目" }
                checkSession(expectedSession)
            }
            val previous = export()
            val previousAnnouncements = announcements.loadState()
            val undo = mutableListOf<suspend () -> Unit>()
            try {
                undo += { layout.restore(OverviewLayoutSnapshot(previous.overview, previous.overviewOrderVersion == 1)) }
                layout.restore(OverviewLayoutSnapshot(document.overview, document.overviewOrderVersion == 1))
                undo += { announcements.restoreSnapshot(previousAnnouncements) }
                announcements.restorePortable(document.announcements)
                undo += { restoreCoursePreference(previous.remindBeforeClass) }
                restoreCoursePreference(document.remindBeforeClass)
                if (includeSubjects) {
                    val session = requireNotNull(expectedSession)
                    checkSession(session)
                    undo += {
                        checkSession(session)
                        schedule.restoreSubjectOverrides(requireNotNull(previous.subjects), session)
                    }
                    schedule.restoreSubjectOverrides(requireNotNull(document.subjects), session)
                }
                undo += { settings.restorePortable(previous.settings) }
                settings.restorePortable(document.settings)
            } catch (error: Exception) {
                var rollbackFailed = false
                withContext(NonCancellable) {
                    undo.asReversed().forEach { rollback ->
                        try { rollback() } catch (failure: Exception) { rollbackFailed = true; error.addSuppressed(failure) }
                    }
                }
                if (error is CancellationException && !rollbackFailed) throw error
                throw SettingsTransferRestoreException(rollbackFailed, error)
            }
        }

    private fun checkSession(expected: AuthenticatedSession?) {
        if (currentSession() != expected) throw CancellationException("Session changed")
    }
}
