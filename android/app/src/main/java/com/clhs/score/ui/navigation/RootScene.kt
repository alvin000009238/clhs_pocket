package com.clhs.score.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

internal const val RootSceneDestinationKey = "pocket.root.destination"

/** Keep the expensive home grid composed while another root occupies the viewport. */
internal class RootSceneStrategy : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        val active = entries.last()
        if (TopLevelDestination.entries.none { it.route == active.metadata[RootSceneDestinationKey] }) return null
        val home = entries.firstOrNull { it.metadata[RootSceneDestinationKey] == OverviewRoute } ?: return null
        return RootScene(home, active, entries.dropLast(1))
    }
}

private data class RootScene(
    val home: NavEntry<NavKey>,
    val active: NavEntry<NavKey>,
    override val previousEntries: List<NavEntry<NavKey>>,
) : Scene<NavKey> {
    override val key: Any = home.contentKey
    override val entries = if (home.contentKey == active.contentKey) listOf(home) else listOf(home, active)
    override val content: @Composable () -> Unit = {
        val homeVisible = home.contentKey == active.contentKey
        val homeOwner = rememberLifecycleOwner(maxLifecycle =
            if (homeVisible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED)
        val reselect = LocalRootReselect.current
        Box(Modifier.fillMaxSize()) {
            key(home.contentKey) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides homeOwner,
                    LocalRootReselect provides if (homeVisible) reselect else null,
                ) {
                    Box(Modifier.fillMaxSize().then(if (homeVisible) Modifier else
                        Modifier.clearAndSetSemantics {}.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            // Retain measured nodes without placing, drawing, or exposing them to input.
                            layout(placeable.width, placeable.height) {}
                        })) { home.Content() }
                }
            }
            if (!homeVisible) key(active.contentKey) { active.Content() }
        }
    }
}
