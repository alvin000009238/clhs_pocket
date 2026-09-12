package com.clhs.score.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.clhs.score.BuildConfig
import com.clhs.score.data.GradeReminderRepository
import com.clhs.score.data.identity
import com.clhs.score.data.GradeReminderSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GradeReminderDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val expectedAction = "${BuildConfig.APPLICATION_ID}.RUN_GRADE_REMINDER_WORKER_TEST"
        if (intent.action != expectedAction) {
            Log.w(TAG, "Ignored unexpected action: ${intent.action}")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                prepareStaleSnapshotAndRunWorker(context.applicationContext)
            }.onFailure { error ->
                Log.e(TAG, "Worker test setup failed", error)
            }
            pendingResult.finish()
        }
    }

    private suspend fun prepareStaleSnapshotAndRunWorker(context: Context) {
        val now = System.currentTimeMillis()
        val repository = GradeReminderRepository(context)
        val state = repository.loadState()

        if (!state.enabled) {
            Log.w(TAG, "Skipped worker test: grade reminder is not enabled")
            return
        }
        if (now >= state.expiresAtMillis) {
            Log.w(TAG, "Skipped worker test: grade reminder is expired")
            return
        }

        val snapshot = state.snapshot
        if (snapshot == null) {
            Log.w(TAG, "Skipped worker test: no baseline snapshot")
            return
        }

        val staleSnapshot = snapshot.withDebugStaleValue()
        if (staleSnapshot == null) {
            Log.w(TAG, "Skipped worker test: no testable snapshot field")
            return
        }

        val updated = repository.mutate {
            if (loadState().identity() != state.identity()) return@mutate false
            saveState(
                state.copy(
                    snapshot = staleSnapshot,
                    latestChangeSet = null,
                    consecutiveFailures = 0,
                    stoppedReason = null,
                    expiresAtMillis = maxOf(state.expiresAtMillis, now + MIN_TEST_VALIDITY_MILLIS),
                ),
            )
            true
        }
        if (!updated) return

        val request = OneTimeWorkRequestBuilder<GradeReminderWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(DEBUG_WORK_TAG)
            .addTag(GradeReminderScheduler.WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_DEBUG_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.i(TAG, "Prepared stale snapshot and enqueued GradeReminderWorker test")
    }

    private fun GradeReminderSnapshot.withDebugStaleValue(): GradeReminderSnapshot? {
        val firstSubject = subjects.firstOrNull()
        if (firstSubject != null) {
            return copy(
                subjects = listOf(firstSubject.copy(score = DEBUG_OLD_VALUE)) + subjects.drop(1),
            )
        }

        val currentSummary = summary ?: return null
        return copy(
            summary = currentSummary.copy(averageScore = DEBUG_OLD_VALUE),
        )
    }

    private companion object {
        const val DEBUG_WORK_TAG = "grade_reminder_debug_test"
        private const val UNIQUE_DEBUG_WORK_NAME = "grade_reminder_debug_test"
        private const val DEBUG_OLD_VALUE = "測試舊資料"
        private const val MIN_TEST_VALIDITY_MILLIS = 15 * 60 * 1000L
        private const val TAG = "GradeReminderDebug"
    }
}
