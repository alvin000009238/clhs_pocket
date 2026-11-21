package com.clhs.score.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.PopupProperties
import com.clhs.score.ui.theme.ScoreTheme

internal class PocketOverflowMenuState(
    private val setExpanded: (Boolean) -> Unit,
) {
    internal class Item {
        var bounds by mutableStateOf<() -> Rect>({ Rect.Zero })
        var enabled = false
        var onClick: () -> Unit = {}
    }

    internal val items = mutableListOf<Item>()
    internal var pointer by mutableStateOf<Offset?>(null)
    internal var dragging by mutableStateOf(false)
    internal var anchor: LayoutCoordinates? = null

    internal fun start(position: Offset) {
        dragging = true
        pointer = position
        setExpanded(true)
    }

    internal fun finish(execute: Boolean) {
        val action = if (execute) items.firstOrNull {
            it.enabled && pointer?.let { position -> it.bounds().contains(position) } == true
        }?.onClick else null
        val wasDragging = dragging
        dragging = false
        pointer = null
        if (wasDragging) setExpanded(false)
        action?.invoke()
    }
}

internal fun Modifier.pocketOverflowMenuAnchor(
    state: PocketOverflowMenuState,
    enabled: Boolean = true,
): Modifier = this
    .onGloballyPositioned { state.anchor = it }
    .pointerInput(state, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            // Start after IconButton handles this down; otherwise the long-press waiter sees
            // that same consumed down in Main and cancels before its timeout can begin.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val coordinates = state.anchor?.takeIf { it.isAttached } ?: return@awaitEachGesture
            state.start(coordinates.localToScreen(held.position))
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!coordinates.isAttached || event.changes.any { it.id != down.id && it.pressed }) break
                    state.pointer = coordinates.localToScreen(change.position)
                    val consumed = change.isConsumed
                    event.changes.forEach { it.consume() }
                    if (consumed) break
                    if (!change.pressed) {
                        state.finish(execute = true)
                        break
                    }
                }
            } finally {
                state.finish(execute = false)
            }
        }
    }

@Preview(name = "更多選項 · Light", showBackground = true)
@Preview(name = "更多選項 · Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PocketOverflowMenuPreview() {
    ScoreTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraSmall) {
            Column(Modifier.width(220.dp)) {
                PocketOverflowMenuItem(text = { Text("學分設定") }, onClick = {}, enabled = false)
                PocketOverflowMenuItem(text = { Text("段考更新提醒") }, onClick = {})
                PocketOverflowMenuItem(text = { Text("匯出成績") }, onClick = {})
            }
        }
    }
}

@Composable
internal fun rememberPocketOverflowMenuState(onExpandedChange: (Boolean) -> Unit): PocketOverflowMenuState {
    val currentChange by rememberUpdatedState(onExpandedChange)
    return remember { PocketOverflowMenuState { currentChange(it) } }
}

private val LocalOverflowMenu = staticCompositionLocalOf<PocketOverflowMenuState?> { null }

@Composable
internal fun PocketOverflowDropdownMenu(
    state: PocketOverflowMenuState,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(expanded) { if (!expanded) state.finish(execute = false) }
    DisposableEffect(state) { onDispose { state.finish(execute = false) } }
    // A focusable popup would cancel the touch stream still owned by the anchor window.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { state.finish(execute = false); onDismissRequest() },
        offset = offset,
        properties = PopupProperties(focusable = !state.dragging),
    ) {
        CompositionLocalProvider(LocalOverflowMenu provides state) { content() }
    }
}

@Composable
internal fun PocketOverflowMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val state = LocalOverflowMenu.current
    val item = remember { PocketOverflowMenuState.Item() }
    val currentClick by rememberUpdatedState(onClick)
    SideEffect { item.enabled = enabled; item.onClick = { currentClick() } }
    DisposableEffect(state, item) {
        state?.items?.add(item)
        onDispose { state?.items?.remove(item) }
    }
    val hovered = enabled && state?.pointer?.let { item.bounds().contains(it) } == true
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        modifier = modifier.onGloballyPositioned { coordinates ->
            item.bounds = {
                if (!coordinates.isAttached) Rect.Zero else {
                    val clipped = coordinates.boundsInWindow()
                    val origin = coordinates.localToScreen(coordinates.windowToLocal(clipped.topLeft))
                    Rect(origin, clipped.size)
                }
            }
        }.then(if (hovered) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
    )
}
