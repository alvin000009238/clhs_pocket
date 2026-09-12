package com.clhs.score.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class GradesAdaptiveLayoutTest {
    @Test
    fun layoutUsesAvailableWidthBreakpoints() {
        assertEquals(GradesAdaptiveLayout.SingleColumn, gradesAdaptiveLayoutForWidth(599.dp))
        assertEquals(GradesAdaptiveLayout.TwoColumn, gradesAdaptiveLayoutForWidth(600.dp))
        assertEquals(GradesAdaptiveLayout.TwoColumn, gradesAdaptiveLayoutForWidth(839.dp))
        assertEquals(GradesAdaptiveLayout.ListDetail, gradesAdaptiveLayoutForWidth(840.dp))
    }

}
