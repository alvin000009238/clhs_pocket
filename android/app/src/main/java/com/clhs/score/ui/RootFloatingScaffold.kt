package com.clhs.score.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clhs.score.R
import com.clhs.score.ui.navigation.PocketScaffold
import com.clhs.score.ui.theme.OutfitFontFamily

internal val LocalRootBodyMotionEnabled = staticCompositionLocalOf { false }
internal val LocalRootTopClearance = staticCompositionLocalOf { 0.dp }

@Composable
internal fun Modifier.rootBodyMotion(): Modifier {
    if (!LocalRootBodyMotionEnabled.current) return this
    val motion = com.clhs.score.ui.theme.PocketMotion.navigation()
    val scope = androidx.navigation3.ui.LocalNavAnimatedContentScope.current
    return with(scope) {
        this@rootBodyMotion.animateEnterExit(
            enter = androidx.compose.animation.slideInVertically(motion.position) { height -> (height / 100).coerceAtMost(16) },
            exit = androidx.compose.animation.ExitTransition.None,
        )
    }
}

@Composable
internal fun PocketBrand(dynamicColor: Boolean, endPadding: Dp = 0.dp) {
    val color = if (dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
        MaterialTheme.colorScheme.primary else colorResource(R.color.clhs_pocket_icon_blue)
    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = endPadding)
        .clearAndSetSemantics { contentDescription = "壢中 Pocket" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(R.drawable.clhs_pocket_logo), contentDescription = null,
            tint = color, modifier = Modifier.size(32.dp))
        Text("Pocket", fontFamily = OutfitFontFamily, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Callers place the brand and starting insets inside their scroll content. */
@Composable
internal fun RootFloatingScaffold(
    dynamicColor: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, @Composable () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val statusHeight = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    var actionsWidth by remember { mutableStateOf(152.dp) }
    var actionsHeight by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        PocketScaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            snackbarHost = snackbarHost,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            CompositionLocalProvider(LocalRootTopClearance provides statusHeight + 8.dp + actionsHeight) {
                content(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
                    .add(WindowInsets(top = 8.dp)).asPaddingValues()) {
                    PocketBrand(dynamicColor, endPadding = actionsWidth)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(statusHeight + 12.dp).background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surface.copy(alpha = 0f)))))
        Surface(
            modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = 8.dp, end = 16.dp)
                .onSizeChanged {
                    with(density) {
                        actionsWidth = it.width.toDp()
                        actionsHeight = it.height.toDp()
                    }
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp, shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
