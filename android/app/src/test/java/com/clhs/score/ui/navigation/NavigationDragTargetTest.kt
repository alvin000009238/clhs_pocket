package com.clhs.score.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDragTargetTest {
    @Test fun shortMovementDoesNotSwitchEvenWithHighVelocity() {
        assertEquals(1, navigationDragTarget(1.12f, 1, 12f, 3))
    }

    @Test fun slowDragUsesNearestAnchorAndFlickCanReachItEarly() {
        assertEquals(0, navigationDragTarget(0.35f, 0, 0f, 3))
        assertEquals(1, navigationDragTarget(0.35f, 0, 4f, 3))
        assertEquals(2, navigationDragTarget(1.8f, 0, 0f, 3))
        assertEquals(3, navigationDragTarget(2.95f, 0, 0f, 3))
    }

    @Test fun ReversalAndEdgesRespectDirectionAndBounds() {
        assertEquals(0, navigationDragTarget(0.65f, 0, -4f, 3))
        assertEquals(0, navigationDragTarget(0f, 3, -20f, 3))
        assertEquals(3, navigationDragTarget(3f, 0, 20f, 3))
    }
}
