package com.clhs.score.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActiveSessionResolverTest {
    @Test
    fun currentSessionPrefersUnlockedActiveSession() = runTest {
        val stored = AuthenticatedSession("stored", "stored-token", mapOf("a" to "1"))
        val active = AuthenticatedSession("active", "active-token", mapOf("b" to "2"))
        val resolver = ActiveSessionResolver(
            activeSessionProvider = { active },
            storedSessionProvider = { stored },
            biometricSessionPresentProvider = { true },
        )

        assertSame(active, resolver.currentSession())
    }

    @Test
    fun currentSessionFallsBackToStoredSessionWhenBiometricSessionIsAbsent() = runTest {
        val stored = AuthenticatedSession("stored", "stored-token", mapOf("a" to "1"))
        val resolver = ActiveSessionResolver(
            activeSessionProvider = { null },
            storedSessionProvider = { stored },
            biometricSessionPresentProvider = { false },
        )

        assertSame(stored, resolver.currentSession())
    }

    @Test
    fun currentSessionDoesNotUseStoredSessionWhenBiometricSessionIsPresent() = runTest {
        val stored = AuthenticatedSession("stored", "stored-token", mapOf("a" to "1"))
        val resolver = ActiveSessionResolver(
            activeSessionProvider = { null },
            storedSessionProvider = { stored },
            biometricSessionPresentProvider = { true },
        )

        assertNull(resolver.currentSession())
    }

    @Test
    fun currentSessionDoesNotUseStoredSessionWhenSessionAccessIsNotAllowed() = runTest {
        val stored = AuthenticatedSession("stored", "stored-token", mapOf("a" to "1"))
        val resolver = ActiveSessionResolver(
            activeSessionProvider = { null },
            storedSessionProvider = { stored },
            biometricSessionPresentProvider = { false },
            sessionAccessAllowedProvider = { false },
        )

        assertNull(resolver.currentSession())
    }

    @Test
    fun requireSessionReportsNotLoggedInWhenNoAllowedSessionExists() = runTest {
        val resolver = ActiveSessionResolver(
            activeSessionProvider = { null },
            storedSessionProvider = { null },
            biometricSessionPresentProvider = { false },
        )

        val error = runCatching { resolver.requireSession() }.exceptionOrNull()

        assertEquals("未登入", error?.message)
    }
}
