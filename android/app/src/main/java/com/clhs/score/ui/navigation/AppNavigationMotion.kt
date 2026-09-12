@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MotionScheme
import androidx.navigation3.runtime.metadata
import androidx.navigation3.ui.NavDisplay

private const val DrillDownIncomingDisplacementDivisor = 12
private const val ParentOutgoingDisplacementDivisor = 24
private const val TopLevelFadeThroughInitialScale = 0.98f

internal fun topLevelNavigationMetadata(motionScheme: MotionScheme) = metadata {
    val fadeThrough = motionScheme.topLevelFadeThrough()
    put(NavDisplay.TransitionKey) { fadeThrough }
    put(NavDisplay.PopTransitionKey) { fadeThrough }
    put(NavDisplay.PredictivePopTransitionKey) { _ -> fadeThrough }
}

internal fun MotionScheme.drillDownNavigationTransition(): ContentTransform =
    (slideInHorizontally(
        animationSpec = defaultSpatialSpec(),
        initialOffsetX = { width -> width / DrillDownIncomingDisplacementDivisor },
    ) + fadeIn(defaultEffectsSpec())).togetherWith(
        slideOutHorizontally(
            animationSpec = defaultSpatialSpec(),
            targetOffsetX = { width -> -width / ParentOutgoingDisplacementDivisor },
        ) + fadeOut(fastEffectsSpec()),
    )

internal fun MotionScheme.drillUpNavigationTransition(): ContentTransform =
    (slideInHorizontally(
        animationSpec = defaultSpatialSpec(),
        initialOffsetX = { width -> -width / ParentOutgoingDisplacementDivisor },
    ) + fadeIn(defaultEffectsSpec())).togetherWith(
        slideOutHorizontally(
            animationSpec = defaultSpatialSpec(),
            targetOffsetX = { width -> width / DrillDownIncomingDisplacementDivisor },
        ) + fadeOut(fastEffectsSpec()),
    )

private fun MotionScheme.topLevelFadeThrough(): ContentTransform =
    (fadeIn(defaultEffectsSpec()) + scaleIn(
        initialScale = TopLevelFadeThroughInitialScale,
        animationSpec = defaultSpatialSpec(),
    )) togetherWith fadeOut(fastEffectsSpec())
