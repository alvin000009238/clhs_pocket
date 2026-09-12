package com.clhs.score.reminders

import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.SchoolException
import com.clhs.score.data.SchoolTransientException
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.SessionStorageUnavailableException
import com.clhs.score.data.identity
import com.clhs.score.data.mutateReminderStateIfCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class GradeReminderWorkerTest {
    @Test
    fun failurePolicySeparatesAuthenticationTransientAndPermanentErrors() {
        assertEquals(
            ReminderFailureAction.STOP,
            reminderFailureAction(SchoolAuthenticationException()),
        )
        assertEquals(
            ReminderFailureAction.RETRY,
            reminderFailureAction(SchoolTransientException("temporary")),
        )
        assertEquals(ReminderFailureAction.RETRY, reminderFailureAction(IOException("offline")))
        assertEquals(
            ReminderFailureAction.RETRY,
            reminderFailureAction(SessionStorageUnavailableException()),
        )
        assertEquals(
            ReminderFailureAction.COUNT_FAILURE,
            reminderFailureAction(SchoolException("permanent")),
        )
    }

    @Test
    fun failureMessagesDoNotExposeExceptionDetails() {
        val secret = "student=123456&token=secret"

        ReminderFailureAction.entries.forEach { action ->
            assertFalse(action.message.contains(secret))
        }
    }

    @Test
    fun staleReminderIdentityCannotMutateNewState() {
        val original = reminderState(activatedAt = 1)
        val replacement = reminderState(activatedAt = 2)

        assertEquals(
            null,
            mutateReminderStateIfCurrent(replacement, original.identity()) {
                it.copy(consecutiveFailures = 1)
            },
        )
        assertEquals(
            1,
            mutateReminderStateIfCurrent(original, original.identity()) {
                it.copy(consecutiveFailures = 1)
            }?.consecutiveFailures,
        )
    }

    private fun reminderState(activatedAt: Long) = GradeReminderState(
        enabled = true,
        studentNo = "DEMO-001",
        yearValue = "114_1",
        examValue = "E1",
        activatedAtMillis = activatedAt,
        expiresAtMillis = activatedAt + 1_000,
    )
}
