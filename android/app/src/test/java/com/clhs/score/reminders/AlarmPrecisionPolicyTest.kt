package com.clhs.score.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmPrecisionPolicyTest {
    @Test
    fun api29OptInUsesExactBoundaryWithoutSpecialAccess() {
        assertEquals(
            CourseAlarmPrecision.EXACT,
            selectCourseAlarmPrecision(true, false, CourseAlarmPurpose.BOUNDARY, 29),
        )
    }

    @Test
    fun api31OptInAndGrantUsesExactBoundary() {
        assertEquals(
            CourseAlarmPrecision.EXACT,
            selectCourseAlarmPrecision(true, true, CourseAlarmPurpose.BOUNDARY, 31),
        )
    }

    @Test
    fun api31DeniedAccessFallsBackToInexact() {
        assertEquals(
            CourseAlarmPrecision.INEXACT,
            selectCourseAlarmPrecision(true, false, CourseAlarmPurpose.BOUNDARY, 31),
        )
    }

    @Test
    fun systemGrantDoesNotOverrideAppOptOut() {
        assertEquals(
            CourseAlarmPrecision.INEXACT,
            selectCourseAlarmPrecision(false, true, CourseAlarmPurpose.BOUNDARY, 35),
        )
    }

    @Test
    fun recoveryAlwaysRemainsInexactEvenWhenExactIsEnabled() {
        assertEquals(
            CourseAlarmPrecision.INEXACT,
            selectCourseAlarmPrecision(true, true, CourseAlarmPurpose.RECOVERY, 35),
        )
    }

    @Test
    fun reliabilitySummaryReflectsIndependentSystemCapabilities() {
        assertEquals("使用系統預設排程", reliabilityMode(exactEnabled = false, batteryExempt = false))
        assertEquals("標準排程可靠性", reliabilityMode(exactEnabled = true, batteryExempt = false))
        assertEquals("標準排程可靠性", reliabilityMode(exactEnabled = false, batteryExempt = true))
        assertEquals("排程可靠性良好", reliabilityMode(exactEnabled = true, batteryExempt = true))
        assertEquals("仍受最佳化限制", batteryOptimizationStatus(false))
        assertEquals("已排除", batteryOptimizationStatus(true))
    }
}
