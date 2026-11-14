package com.clhs.score.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingSubpageLayout(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentMaxWidth: androidx.compose.ui.unit.Dp = 720.dp,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    actionsPadding: PaddingValues = PaddingValues(0.dp),
    toolbar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val top = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val bottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val gutter = ((maxWidth - contentMaxWidth) / 2).coerceAtLeast(0.dp)
                androidx.compose.runtime.CompositionLocalProvider(LocalReadingGutter provides gutter) {
                    content(PaddingValues(top = top + 64.dp, bottom = bottom))
                }
            }
            Box(Modifier.fillMaxWidth().height(top + 12.dp).background(
                Brush.verticalGradient(listOf(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                )),
            ))
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                PocketBackButton(onBack, Modifier.pocketFloatingShadow(MaterialTheme.shapes.extraLarge))
                if (toolbar != null) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { toolbar() }
                } else {
                Spacer(Modifier.weight(1f))
                Surface(modifier = Modifier.pocketFloatingShadow(MaterialTheme.shapes.extraLarge),
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                    Row(Modifier.padding(actionsPadding), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = actions)
                }
                }
            }
            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding()) { snackbarHost() }
        }
    }
}

internal val LocalReadingGutter = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

@Composable
internal fun readingContentPadding(padding: PaddingValues): PaddingValues {
    val gutter = LocalReadingGutter.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction) + gutter,
        top = padding.calculateTopPadding(),
        end = padding.calculateEndPadding(direction) + gutter,
        bottom = padding.calculateBottomPadding(),
    )
}
