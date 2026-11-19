package com.clhs.score.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScheduleSubjectOverridesTest {
    @Test
    fun trimAndDropBlankFieldsWithoutReplacingTheOriginalSubject() {
        assertEquals(
            ScheduleSubjectOverride("國文", "國語文"),
            ScheduleSubjectOverride("  國文 ", " 國語文 ", "  ", null).normalizedOrNull(),
        )
        assertNull(ScheduleSubjectOverride("國文", "", " ", null).normalizedOrNull())
    }

    @Test
    fun validateLengthAfterTrimmingAndRejectBlankKeys() {
        val eighty = "課".repeat(SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH)
        assertEquals(eighty, ScheduleSubjectOverride("國文", "  $eighty  ").normalizedOrNull()?.customSubjectName)
        assertFailsWith<IllegalArgumentException> {
            ScheduleSubjectOverride("國文", "課".repeat(SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH + 1)).normalizedOrNull()
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduleSubjectOverride("   ", "國語文").normalizedOrNull()
        }
    }

    @Test
    fun deduplicateOnTrimmedOriginalNameWithLastValueWinningAndSort() {
        assertEquals(
            listOf(ScheduleSubjectOverride("國文", "新國文"), ScheduleSubjectOverride("數學", customClassroom = "203")),
            listOf(
                ScheduleSubjectOverride(" 數學 ", customClassroom = "203"),
                ScheduleSubjectOverride("國文", "舊國文"),
                ScheduleSubjectOverride(" 國文 ", "新國文"),
                ScheduleSubjectOverride("英文", ""),
            ).normalizedSubjectOverrides(),
        )
    }
}
