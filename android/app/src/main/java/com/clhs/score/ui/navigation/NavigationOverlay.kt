package com.clhs.score.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Space belongs to the shell; scroll owners apply it inside their scrollable content. */
internal val LocalNavigationClearance = staticCompositionLocalOf { 0.dp }
internal val LocalNavigationCompact = compositionLocalOf { false }
internal val LocalExpandNavigation = compositionLocalOf<() -> Unit> { {} }
internal val LocalRootReselect = staticCompositionLocalOf<RootReselect?> { null }
// The schedule's exportable ComposeView has its own composition and nested-scroll tree.
internal val LocalNavigationScrollConnection = staticCompositionLocalOf<androidx.compose.ui.input.nestedscroll.NestedScrollConnection?> { null }

internal class RootReselect {
    var scrollToTop: (suspend () -> Unit)? = null
}

@Composable
internal fun navigationContentPadding(padding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        padding.calculateLeftPadding(direction), padding.calculateTopPadding(),
        padding.calculateRightPadding(direction),
        padding.calculateBottomPadding() + LocalNavigationClearance.current,
    )
}

@Composable
internal fun Modifier.navigationContentPadding(): Modifier =
    padding(bottom = LocalNavigationClearance.current)

@Composable
internal fun RegisterRootScroll(scrollToTop: suspend () -> Unit) {
    val owner = LocalRootReselect.current
    val latest = rememberUpdatedState(scrollToTop)
    DisposableEffect(owner) {
        val action: suspend () -> Unit = { latest.value() }
        owner?.scrollToTop = action
        onDispose { if (owner?.scrollToTop === action) owner.scrollToTop = null }
    }
}

@Composable
internal fun RegisterRootScroll(state: ScrollState) = RegisterRootScroll {
    if (state.value > 0) state.animateScrollTo(0)
}
