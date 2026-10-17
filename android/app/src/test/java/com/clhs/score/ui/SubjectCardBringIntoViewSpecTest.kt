package com.clhs.score.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectCardBringIntoViewSpecTest {
    @Test
    fun oversizedCardsKeepTheirTopVisibleRegardlessOfTheirStartingPosition() {
        for (offset in listOf(-120f, 0f, 120f)) {
            assertEquals(offset, distance(offset, size = 801f), 0f)
            assertEquals(offset, distance(offset, size = 1000f), 0f)
        }
    }

    @Test
    fun fittingCardsMoveOnlyFarEnoughToClearTheBottomNavigation() {
        assertEquals(100f, distance(offset = 200f, size = 700f), 0f)
        assertEquals(0f, distance(offset = 100f, size = 700f), 0f)
    }

    @Test
    fun fittingCardsRevealTheirTopWhenItIsAboveTheSafeArea() {
        assertEquals(-40f, distance(offset = -40f, size = 700f), 0f)
    }

    @Test
    fun exactFitKeepsBothEdgesVisible() {
        assertEquals(120f, distance(offset = 120f, size = 800f), 0f)
        assertEquals(-120f, distance(offset = -120f, size = 800f), 0f)
        assertEquals(0f, distance(offset = 0f, size = 800f), 0f)
    }

    private fun distance(offset: Float, size: Float) =
        SubjectCardBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize = 800f)
}
