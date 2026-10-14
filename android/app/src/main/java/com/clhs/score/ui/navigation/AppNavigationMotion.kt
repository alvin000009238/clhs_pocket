@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.metadata
import androidx.navigation3.ui.NavDisplay
import com.clhs.score.ui.theme.PocketMotion

private const val DrillDownIncomingDisplacementDivisor = 12
private const val ParentOutgoingDisplacementDivisor = 24

internal fun topLevelNavigationMetadata() = metadata {
    // Root chrome stays still; only the body opts into the NavDisplay transition.
    val transition = EnterTransition.None togetherWith ExitTransition.None
    put(NavDisplay.TransitionKey) { transition }
    put(NavDisplay.PopTransitionKey) { transition }
    put(NavDisplay.PredictivePopTransitionKey) { _ -> transition }
}

internal fun PocketMotion.Navigation.drillDownNavigationTransition(): ContentTransform =
    (slideInHorizontally(
        animationSpec = position,
        initialOffsetX = { width -> width / DrillDownIncomingDisplacementDivisor },
    ) + fadeIn(enterEffects)).togetherWith(
        slideOutHorizontally(
            animationSpec = position,
            targetOffsetX = { width -> -width / ParentOutgoingDisplacementDivisor },
        ) + fadeOut(exitEffects),
    )

internal fun PocketMotion.Navigation.drillUpNavigationTransition(): ContentTransform =
    (slideInHorizontally(
        animationSpec = position,
        initialOffsetX = { width -> -width / ParentOutgoingDisplacementDivisor },
    ) + fadeIn(enterEffects)).togetherWith(
        slideOutHorizontally(
            animationSpec = position,
            targetOffsetX = { width -> width / DrillDownIncomingDisplacementDivisor },
        ) + fadeOut(exitEffects),
    )
