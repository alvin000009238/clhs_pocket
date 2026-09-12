package com.clhs.score.data

import android.content.Context
import androidx.core.content.edit

internal data class PinAttemptState(
    val failedAttempts: Int = 0,
    val nextAllowedAtMillis: Long = 0L,
)

internal class PinAttemptLimiter(
    initialState: PinAttemptState,
    private val onStateChange: (PinAttemptState) -> Unit,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private var state = initialState

    @Synchronized
    fun remainingDelayMillis(): Long =
        (state.nextAllowedAtMillis - nowProvider()).coerceAtLeast(0L)

    @Synchronized
    fun recordFailure(): Long {
        val failures = state.failedAttempts + 1
        val delay = if (failures < FIRST_DELAYED_FAILURE) {
            0L
        } else {
            (INITIAL_DELAY_MILLIS * (1L shl (failures - FIRST_DELAYED_FAILURE).coerceAtMost(4)))
                .coerceAtMost(MAX_DELAY_MILLIS)
        }
        state = PinAttemptState(
            failedAttempts = failures,
            nextAllowedAtMillis = nowProvider() + delay,
        )
        onStateChange(state)
        return delay
    }

    @Synchronized
    fun reset() {
        state = PinAttemptState()
        onStateChange(state)
    }

    companion object {
        private const val PREFS_NAME = "pin_unlock_attempts"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_NEXT_ALLOWED_AT = "next_allowed_at"
        private const val FIRST_DELAYED_FAILURE = 5
        private const val INITIAL_DELAY_MILLIS = 30_000L
        private const val MAX_DELAY_MILLIS = 5L * 60L * 1_000L

        fun create(context: Context): PinAttemptLimiter {
            val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return PinAttemptLimiter(
                initialState = PinAttemptState(
                    failedAttempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0).coerceAtLeast(0),
                    nextAllowedAtMillis = preferences.getLong(KEY_NEXT_ALLOWED_AT, 0L).coerceAtLeast(0L),
                ),
                onStateChange = { state ->
                    preferences.edit(commit = true) {
                        putInt(KEY_FAILED_ATTEMPTS, state.failedAttempts)
                        putLong(KEY_NEXT_ALLOWED_AT, state.nextAllowedAtMillis)
                    }
                },
            )
        }
    }
}
