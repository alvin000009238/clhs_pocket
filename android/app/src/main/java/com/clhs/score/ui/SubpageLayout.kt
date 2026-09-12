package com.clhs.score.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val scrollBehavior = if (title == null) {
        null
    } else {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    }
    Scaffold(
        modifier = modifier.then(
            if (scrollBehavior == null) {
                Modifier
            } else {
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            },
        ),
        snackbarHost = snackbarHost,
        containerColor = containerColor,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            if (title != null) {
                val navigationIcon: @Composable () -> Unit = {
                    IconButton(
                        onClick = onBack,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        OutlinedRoundedSymbol(icon = "arrow_back", contentDescription = "返回")
                    }
                }
                val colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                )
                val titleContent: @Composable () -> Unit = { Text(title) }
                LargeFlexibleTopAppBar(
                    title = titleContent,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    colors = colors,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 1200.dp)
                .fillMaxWidth(),
        ) {
            content()

            if (title == null) {
                // 頂部漸層遮罩，產生淡出效果
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    containerColor,
                                    containerColor.copy(alpha = 0f)
                                )
                            )
                        ),
                )

                // 置頂內容 (例如計分板)，繪製於漸層之上
                summaryContent()

                // 浮動返回按鈕
                IconButton(
                    onClick = onBack,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    OutlinedRoundedSymbol(icon = "arrow_back", contentDescription = "返回")
                }
                floatingTitle?.let {
                    Text(
                        text = it,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 80.dp, top = 26.dp, end = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
