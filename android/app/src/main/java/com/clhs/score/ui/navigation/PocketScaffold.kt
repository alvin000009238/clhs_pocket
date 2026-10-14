package com.clhs.score.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Keep page actions and Snackbar above the shared navigation overlay without moving its surface. */
@Composable
internal fun PocketScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val clearance = LocalNavigationClearance.current
    val density = LocalDensity.current
    val overlayInset = if (clearance > 0.dp) {
        clearance + with(density) {
            (WindowInsets.navigationBars.getBottom(this) - contentWindowInsets.getBottom(this))
                .coerceAtLeast(0).toDp()
        }
    } else 0.dp
    Scaffold(
        modifier = modifier, topBar = topBar,
        bottomBar = {
            Box(Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                // Scaffold positions FAB from a supplied bottomBar, even when its content is empty.
                val extra = overlayInset.roundToPx()
                layout(placeable.width, placeable.height + extra) { placeable.placeRelative(0, 0) }
            }) { bottomBar() }
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor, contentColor = contentColor,
        contentWindowInsets = contentWindowInsets.add(WindowInsets(bottom = overlayInset)),
    ) { padding ->
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        content(PaddingValues(
            padding.calculateLeftPadding(direction), padding.calculateTopPadding(),
            padding.calculateRightPadding(direction),
            (padding.calculateBottomPadding() - overlayInset)
                .coerceAtLeast(contentWindowInsets.asPaddingValues().calculateBottomPadding()),
        ))
    }
}
