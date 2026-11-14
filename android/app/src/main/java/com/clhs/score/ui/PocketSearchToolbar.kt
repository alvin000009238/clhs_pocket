package com.clhs.score.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.clhs.score.ui.theme.PocketMotion

@Composable
internal fun PocketSearchToolbar(
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    query: String, onQueryChange: (String) -> Unit, maxLength: Int,
    searchLabel: String, collapsedWidth: androidx.compose.ui.unit.Dp = 48.dp,
    moreActions: @Composable RowScope.() -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val collapse = { keyboard?.hide(); onExpandedChange(false) }
    BackHandler(expanded, onBack = collapse)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        val width by animateDpAsState(
            if (expanded) maxWidth.coerceAtMost(560.dp) else collapsedWidth,
            PocketMotion.contentReflow(), label = "搜尋框寬度",
        )
        Surface(
            modifier = Modifier.width(width).height(48.dp).pocketFloatingShadow(MaterialTheme.shapes.extraLarge),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (expanded) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        OutlinedRoundedSymbol("search", contentDescription = null)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { if (it.length <= maxLength) onQueryChange(it) },
                        modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = searchLabel },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { collapse() }),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { input ->
                            Box {
                                if (query.isEmpty()) Text("搜尋…", style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                input()
                            }
                        },
                    )
                    IconButton(onClick = { onQueryChange("") }, enabled = query.isNotEmpty()) {
                        OutlinedRoundedSymbol("close", contentDescription = "清除關鍵字")
                    }
                    IconButton(onClick = collapse) {
                        OutlinedRoundedSymbol("arrow_forward", contentDescription = "收合搜尋並保留關鍵字")
                    }
                } else {
                    IconButton(onClick = { onExpandedChange(true) }) {
                        OutlinedRoundedSymbol("search", contentDescription = searchLabel)
                    }
                    if (!expanded) moreActions()
                }
            }
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) { focus.requestFocus(); keyboard?.show() }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "搜尋工具列平板", widthDp = 1280, heightDp = 180)
@androidx.compose.ui.tooling.preview.Preview(name = "搜尋工具列", widthDp = 390, heightDp = 180)
@androidx.compose.ui.tooling.preview.Preview(name = "搜尋工具列窄螢幕", widthDp = 320, heightDp = 180, fontScale = 1.5f)
@Composable
private fun SearchToolbarPreview(
    @androidx.compose.ui.tooling.preview.PreviewParameter(SearchToolbarQueryProvider::class) query: String,
) {
    com.clhs.score.ui.theme.ScoreTheme {
        var expanded by remember { mutableStateOf(true) }
        FloatingSubpageLayout(onBack = {}, toolbar = {
            PocketSearchToolbar(expanded, { expanded = it }, query, {}, 100, "搜尋公告", 96.dp) {
                IconButton(onClick = {}) { OutlinedRoundedSymbol("more_vert", contentDescription = "更多選項") }
            }
        }) {}
    }
}

class SearchToolbarQueryProvider : androidx.compose.ui.tooling.preview.PreviewParameterProvider<String> {
    override val values = sequenceOf("", "校慶")
}

@androidx.compose.ui.tooling.preview.Preview(name = "收合搜尋工具列", widthDp = 320, heightDp = 180)
@Composable
private fun CollapsedSearchToolbarPreview() {
    com.clhs.score.ui.theme.ScoreTheme {
        FloatingSubpageLayout(onBack = {}, toolbar = {
            PocketSearchToolbar(false, {}, "", {}, 100, "搜尋公告", 96.dp) {
                IconButton(onClick = {}) { OutlinedRoundedSymbol("more_vert", contentDescription = "更多選項") }
            }
        }) {}
    }
}

