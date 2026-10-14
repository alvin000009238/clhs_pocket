package com.clhs.score

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.SceneStrategyScope
import com.clhs.score.ui.navigation.*
import org.junit.Assert.*
import org.junit.Test

class RootSceneStrategyTest {
    private fun entry(route: NavKey, root: Boolean = true) = NavEntry(route,
        metadata = if (root) mapOf(RootSceneDestinationKey to route) else emptyMap()) {}
    private fun scene(entries: List<NavEntry<NavKey>>) = with(RootSceneStrategy()) {
        SceneStrategyScope<NavKey>().calculateScene(entries)
    }

    @Test fun rootsShareHomeSceneIdentityAndPreserveBackEntries() {
        val home = entry(OverviewRoute)
        val child = entry(PersonalRoute, root = false)
        val schedule = entry(ScheduleRoute)
        val first = requireNotNull(scene(listOf(home)))
        val other = requireNotNull(scene(listOf(home, child, schedule)))
        assertEquals(first.key, other.key)
        assertEquals(listOf(home, schedule), other.entries)
        assertEquals(listOf(home, child), other.previousEntries)
    }

    @Test fun childRoutesAndOnboardingKeepDefaultSceneStrategy() {
        val home = entry(OverviewRoute)
        assertNull(scene(listOf(home, entry(PersonalRoute, root = false))))
        assertNull(scene(listOf(entry(WelcomeRoute, root = false))))
        assertNull(scene(listOf(entry(ScheduleRoute))))
    }
}
