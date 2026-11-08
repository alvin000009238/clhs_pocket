package com.clhs.score.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SubpageLayout(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    floatingTitle: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    summaryContent: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    FloatingSubpageLayout(onBack = onBack, modifier = modifier, snackbarHost = snackbarHost, actions = actions) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
            .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 1200.dp).fillMaxWidth()) {
            content()
            summaryContent()
        }
    }
}
