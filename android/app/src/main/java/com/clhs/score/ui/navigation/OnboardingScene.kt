package com.clhs.score.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

internal const val OnboardingStepKey = "pocket.onboarding.step"

// A stable scene keeps the frame composed as the existing route stack advances or pops.
internal class OnboardingSceneStrategy(
    private val render: @Composable (Int) -> Unit,
) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        val step = entries.last().metadata[OnboardingStepKey] as? Int ?: return null
        return OnboardingScene(entries, step, render)
    }
}

private data class OnboardingScene(
    override val entries: List<NavEntry<NavKey>>,
    val step: Int,
    val render: @Composable (Int) -> Unit,
) : Scene<NavKey> {
    override val key: Any = OnboardingStepKey
    override val previousEntries = entries.dropLast(1)
    override val content: @Composable () -> Unit = { render(step) }
}
