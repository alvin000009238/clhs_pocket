package com.clhs.score.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinAttemptLimiterTest {
    @Test
    fun fifthFailureStartsPersistedCappedBackoffAndSuccessResetsIt() {
        var now = 1_000L
        var persisted = PinAttemptState()
        var limiter = PinAttemptLimiter(
            initialState = persisted,
            onStateChange = { persisted = it },
            nowProvider = { now },
        )

        repeat(4) { assertEquals(0L, limiter.recordFailure()) }
        assertEquals(30_000L, limiter.recordFailure())
        assertEquals(30_000L, limiter.remainingDelayMillis())

        limiter = PinAttemptLimiter(persisted, { persisted = it }, { now })
        assertEquals(30_000L, limiter.remainingDelayMillis())

        now += 30_000L
        assertEquals(60_000L, limiter.recordFailure())
        now += 60_000L
        repeat(4) {
            val delay = limiter.recordFailure()
            assertEquals(minOf(120_000L shl it, 300_000L), delay)
            now += delay
        }
        assertEquals(300_000L, limiter.recordFailure())

        limiter.reset()
        assertEquals(PinAttemptState(), persisted)
        assertEquals(0L, limiter.remainingDelayMillis())
    }
}
