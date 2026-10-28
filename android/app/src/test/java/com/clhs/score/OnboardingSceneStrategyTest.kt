package com.clhs.score

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.SceneStrategyScope
import com.clhs.score.ui.navigation.*
import org.junit.Assert.*
import org.junit.Test

class OnboardingSceneStrategyTest {
    private fun scene(entries: List<NavEntry<NavKey>>) = with(OnboardingSceneStrategy {}) {
        SceneStrategyScope<NavKey>().calculateScene(entries)
    }

    @Test fun stepsShareFrameIdentityAndRetainOneStepBackNavigation() {
        val routes = listOf(WelcomeRoute, AppearanceRoute, PermissionsRoute, AccountRoute)
        val entries = routes.mapIndexed { step, route ->
            NavEntry<NavKey>(route, metadata = mapOf(OnboardingStepKey to step)) {}
        }
        val first = requireNotNull(scene(entries.take(1)))
        for (count in 2..4) {
            val current = requireNotNull(scene(entries.take(count)))
            assertEquals(first.key, current.key)
            assertEquals(entries.take(count), current.entries)
            assertEquals(entries.take(count - 1), current.previousEntries)
        }
        assertTrue(first.previousEntries.isEmpty())
        assertNull(scene(entries + NavEntry<NavKey>(WebViewLoginRoute) {}))
        assertNull(scene(listOf(NavEntry<NavKey>(OverviewRoute) {})))
    }
}
