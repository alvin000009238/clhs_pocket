package com.clhs.score

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.clhs.score.ui.PocketOverflowMenuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PocketOverflowMenuStateTest {
    @Test
    fun anchorReleaseKeepsMenuOpenAndEndsPointerOwnership() {
        val expansion = mutableListOf<Boolean>()
        var executions = 0
        val state = PocketOverflowMenuState { expansion += it }
        state.items += item(Rect(100f, 200f, 300f, 250f)) { executions++ }

        state.start(Offset(120f, 220f))
        assertEquals(listOf(true), expansion)
        state.finish(execute = true, keepOpen = true)
        state.finish(execute = false)
        state.finish(execute = true)

        assertEquals(listOf(true), expansion)
        assertEquals(0, executions)
        assertFalse(state.dragging)
        assertNull(state.pointer)
    }

    @Test
    fun releaseRunsOnlyTheEnabledItemUnderTheFinalPointerOnce() {
        val expansion = mutableListOf<Boolean>()
        val actions = mutableListOf<String>()
        val state = PocketOverflowMenuState { expansion += it }
        state.items += item(Rect(100f, 200f, 300f, 250f)) { actions += "first" }
        state.items += item(Rect(100f, 250f, 300f, 300f)) { actions += "second" }

        state.start(Offset(120f, 220f))
        state.pointer = Offset(120f, 270f)
        state.finish(execute = true)
        state.finish(execute = true)

        assertEquals(listOf("second"), actions)
        assertEquals(listOf(true, false), expansion)
    }

    @Test
    fun outsideDisabledAndCancelledReleasesNeverExecute() {
        var executions = 0
        val expansion = mutableListOf<Boolean>()
        val state = PocketOverflowMenuState { expansion += it }
        val target = item(Rect(100f, 200f, 300f, 250f)) { executions++ }
        state.items += target

        state.start(Offset(99f, 220f))
        state.finish(execute = true)
        target.enabled = false
        state.start(Offset(120f, 220f))
        state.finish(execute = true)
        target.enabled = true
        state.start(Offset(120f, 220f))
        state.finish(execute = false)

        assertEquals(0, executions)
        assertEquals(listOf(true, false, true, false, true, false), expansion)
    }

    @Test
    fun releaseUsesCurrentEnablementAndAction() {
        val actions = mutableListOf<String>()
        val state = PocketOverflowMenuState {}
        val target = item(Rect(100f, 200f, 300f, 250f)) { actions += "stale" }
        state.items += target
        state.start(Offset(120f, 220f))
        target.onClick = { actions += "current" }
        state.finish(execute = true)
        state.start(Offset(120f, 220f))
        target.enabled = false
        state.finish(execute = true)

        assertEquals(listOf("current"), actions)
    }

    private fun item(bounds: Rect, onClick: () -> Unit) = PocketOverflowMenuState.Item().also {
        it.bounds = { bounds }
        it.enabled = true
        it.onClick = onClick
    }
}
