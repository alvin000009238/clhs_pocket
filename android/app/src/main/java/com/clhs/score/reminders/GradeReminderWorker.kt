package com.clhs.score.reminders

import com.clhs.score.data.DeveloperDiagnostics
import com.clhs.score.data.SessionCleanupFailure
import com.clhs.score.data.SessionCleanupException
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.GradeReminderIdentity
import com.clhs.score.data.GradeReminderRepository
import com.clhs.score.data.GradeReportDiffer
import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.SchoolCookieJar
import com.clhs.score.data.SchoolGradeClient
import com.clhs.score.data.SchoolGradeRepository
import com.clhs.score.data.SchoolTransientException
import com.clhs.score.data.SessionStore
import com.clhs.score.data.SessionStorageException
import com.clhs.score.data.SessionStorageUnavailableException
import com.clhs.score.data.identity
import kotlinx.coroutines.CancellationException
import java.io.IOException

internal enum class ReminderFailureAction(val message: String) {
    STOP("登入狀態已失效，段考更新提醒已停止"),
    RETRY("網路連線暫時異常，稍後重試"),
    COUNT_FAILURE("檢查失敗"),
}

internal fun reminderFailureAction(error: Throwable): ReminderFailureAction = when (error) {
    is SchoolAuthenticationException -> ReminderFailureAction.STOP
    is SchoolTransientException, is IOException, is SessionStorageUnavailableException -> ReminderFailureAction.RETRY
    else -> ReminderFailureAction.COUNT_FAILURE
}

class GradeReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val reminderRepository = GradeReminderRepository(appContext)
    private val notifier = GradeReminderNotifier(appContext)
    private val scheduler = GradeReminderScheduler(appContext)

    override suspend fun doWork(): Result = try {
        checkGrades()
    } catch (error: SessionCleanupException) {
        error.failures.forEach {
            DeveloperDiagnostics.recordEvent(applicationContext, "PrivacyCleanup", it.name)
        }
        Result.retry()
    }

    private suspend fun checkGrades(): Result {
        val sessionStore = SessionStore(applicationContext)
        try {
            sessionStore.retryReminderCleanup()
        } catch (_: SessionStorageException) {
            DeveloperDiagnostics.recordEvent(applicationContext, "PrivacyCleanup", "REMINDER_SESSION_DELETE_FAILED")
            return Result.retry()
        }
        val now = System.currentTimeMillis()
        val state = try {
            reminderRepository.loadState()
        } catch (_: IOException) {
            return Result.retry()
        }
        if (!state.enabled) return Result.success()
        val identity = state.identity()

        if (now >= state.expiresAtMillis) {
            stopIfCurrent(sessionStore, identity, "段考更新提醒已超過 48 小時", notify = false)
            return Result.success()
        }

        val session = try {
            sessionStore.loadReminderSession(now, state.studentNo)
        } catch (_: SessionStorageUnavailableException) {
            return Result.retry()
        } catch (_: SessionStorageException) {
            stopIfCurrent(sessionStore, identity, "登入狀態已失效，段考更新提醒已停止")
            return Result.success()
        }
        if (session == null) {
            stopIfCurrent(sessionStore, identity, "登入狀態已失效，段考更新提醒已停止")
            return Result.success()
        }

        return runCatching {
            val repository = SchoolGradeRepository(
                client = SchoolGradeClient(cookieJar = SchoolCookieJar()),
                sessionStore = sessionStore,
                cacheStore = GradeCacheStore(applicationContext),
            )
            val report = repository.fetchGrades(
                session = session,
                yearValue = state.yearValue,
                examValue = state.examValue,
                forceRefresh = true,
            )
            val newSnapshot = GradeReportDiffer.snapshot(report)
            val oldSnapshot = state.snapshot ?: newSnapshot
            val changeSet = GradeReportDiffer.diff(
                before = oldSnapshot,
                after = newSnapshot,
                studentNo = state.studentNo,
                yearValue = state.yearValue,
                examValue = state.examValue,
                examName = state.examName,
                checkedAtMillis = now,
            )
            reminderRepository.mutate {
                val updated = updateIfCurrent(identity) { current ->
                    sessionStore.withReminderAuthorization(session) {
                        current.copy(
                        lastCheckedAtMillis = now,
                        snapshot = newSnapshot,
                        latestChangeSet = changeSet.takeIf { it.hasChanges } ?: current.latestChangeSet,
                        consecutiveFailures = 0,
                        stoppedReason = null,
                        )
                    }
                }
                if (updated && changeSet.hasChanges) {
                    sessionStore.withReminderAuthorization(session) { notifier.showChangedNotification(changeSet) }
                }
            }
            Result.success()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            when (val action = reminderFailureAction(error)) {
                ReminderFailureAction.STOP -> {
                    stopIfCurrent(sessionStore, identity, action.message)
                    Result.success()
                }
                ReminderFailureAction.RETRY -> Result.retry()
                ReminderFailureAction.COUNT_FAILURE -> {
                    reminderRepository.mutate {
                        val current = loadState()
                        if (current.identity() != identity) return@mutate
                        val failures = current.consecutiveFailures + 1
                        if (failures >= MAX_FAILURES_BEFORE_STOP) {
                            val cleanupFailures = linkedSetOf<SessionCleanupFailure>()
                            sessionStore.cleanupStep(cleanupFailures, SessionCleanupFailure.REMINDER_STATE_DELETE_FAILED) {
                                stop("連續檢查失敗，段考更新提醒已停止")
                            }
                            sessionStore.cleanupStep(cleanupFailures, SessionCleanupFailure.REMINDER_SESSION_DELETE_FAILED) {
                                sessionStore.clearReminderSession()
                            }
                            sessionStore.cleanupStep(cleanupFailures, SessionCleanupFailure.BACKGROUND_WORK_CANCEL_FAILED) { scheduler.cancel() }
                            if (cleanupFailures.isNotEmpty()) throw SessionCleanupException(cleanupFailures)
                            notifier.showStoppedNotification("連續檢查失敗，段考更新提醒已停止")
                        } else {
                            saveState(
                                current.copy(
                                    lastCheckedAtMillis = now,
                                    consecutiveFailures = failures,
                                    stoppedReason = action.message,
                                ),
                            )
                        }
                    }
                    Result.success()
                }
            }
        }
    }

    private suspend fun stopIfCurrent(
        sessionStore: SessionStore,
        expectedIdentity: GradeReminderIdentity,
        reason: String,
        notify: Boolean = true,
    ) {
        reminderRepository.mutate {
            if (loadState().identity() != expectedIdentity) return@mutate
            val failures = linkedSetOf<SessionCleanupFailure>()
            sessionStore.cleanupStep(failures, SessionCleanupFailure.REMINDER_STATE_DELETE_FAILED) { stop(reason) }
            sessionStore.cleanupStep(failures, SessionCleanupFailure.REMINDER_SESSION_DELETE_FAILED) { sessionStore.clearReminderSession() }
            sessionStore.cleanupStep(failures, SessionCleanupFailure.BACKGROUND_WORK_CANCEL_FAILED) { scheduler.cancel() }
            if (failures.isNotEmpty()) throw SessionCleanupException(failures)
            if (notify) notifier.showStoppedNotification(reason)
        }
    }

    private companion object {
        const val MAX_FAILURES_BEFORE_STOP = 3
    }
}
