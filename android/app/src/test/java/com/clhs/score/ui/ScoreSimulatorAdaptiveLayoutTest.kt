package com.clhs.score.ui

import androidx.compose.ui.unit.dp
import com.clhs.score.data.requiredSimulatorAverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreSimulatorAdaptiveLayoutTest {
    @Test
    fun targetReversalReportsRequiredAverageWithoutClamping() {
        assertEquals(110.0, requiredSimulatorAverage("220".toBigDecimal(), "0".toBigDecimal(), 2)!!.toDouble(), 0.001)
        assertNull(requiredSimulatorAverage("220".toBigDecimal(), "0".toBigDecimal(), 0))
        assertEquals("73.34", requiredSimulatorAverage("220".toBigDecimal(), "0".toBigDecimal(), 3)!!.toPlainString())
    }

    @Test
    fun wideWindowsUseConstrainedContentWidth() {
        assertFalse(scoreSimulatorUsesWideLayout(599.dp))
        assertTrue(scoreSimulatorUsesWideLayout(600.dp))
        assertTrue(scoreSimulatorUsesWideLayout(840.dp))
    }

}
