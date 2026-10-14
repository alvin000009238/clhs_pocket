@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset

/**
 * Semantic UI motion backed by the app's Material [MotionScheme]. Effects describe visual changes
 * without geometry; spatial motion describes position, bounds, or shape changes. Keep clock,
 * progress, shimmer, and gesture-following animation domain-specific instead of forcing them here.
 */
object PocketMotion {
    /** Fast spatial motion for selections that change position, such as reordered rows. */
    @Composable
    fun <T> selection(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

    /** Fast effects motion for small visual feedback that leaves layout geometry unchanged. */
    @Composable
    fun <T> microVisualState(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

    /** Default effects motion for color, alpha, and other non-spatial state changes. */
    @Composable
    fun <T> stateChange(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

    /** Default spatial motion for size changes and content reflow. */
    @Composable
    fun <T> contentReflow(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    /** Spatial and effects specs for the established top-level and child navigation hierarchy. */
    @Composable
    fun navigation(): Navigation {
        val scheme = MaterialTheme.motionScheme
        return remember(scheme) {
            Navigation(
                position = scheme.defaultSpatialSpec(),
                scale = scheme.defaultSpatialSpec(),
                enterEffects = scheme.defaultEffectsSpec(),
                exitEffects = scheme.fastEffectsSpec(),
            )
        }
    }

    /** Preserve the established subject disclosure timing until a deliberate UX change is approved. */
    internal fun subjectDisclosureEnter(): EnterTransition =
        fadeIn(tween(durationMillis = 140, delayMillis = 40, easing = LinearOutSlowInEasing)) +
            expandVertically(
                expandFrom = Alignment.Top,
                clip = false,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            )

    internal fun subjectDisclosureExit(): ExitTransition =
        fadeOut(tween(durationMillis = 90, easing = FastOutSlowInEasing)) +
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                clip = false,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            )

    class Navigation internal constructor(
        val position: FiniteAnimationSpec<IntOffset>,
        val scale: FiniteAnimationSpec<Float>,
        val enterEffects: FiniteAnimationSpec<Float>,
        val exitEffects: FiniteAnimationSpec<Float>,
    )
}
