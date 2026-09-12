package com.clhs.score.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduleSubjectOverrideTest {
    @Test
    fun overridesApplyByOriginalSubjectWithoutMutatingSource() {
        val source = listOf(
            ScheduleItem(1, 1, "彈性學習時間", "王老師", "101"),
            ScheduleItem(3, 2, "彈性學習時間", "李老師", "202"),
            ScheduleItem(2, 1, "國文", "陳老師", "303"),
        )
        val override = ScheduleSubjectOverride(
            originalSubjectName = "彈性學習時間",
            customSubjectName = "彈性課",
            customTeacherName = "自訂教師",
        )

        val displayed = source.applySubjectOverrides(listOf(override))

        assertEquals("彈性課", displayed[0].subjectName)
        assertEquals("自訂教師", displayed[1].teacherName)
        assertEquals("101", displayed[0].classroom)
        assertEquals(source[2], displayed[2])
        assertEquals("彈性學習時間", source[0].subjectName)
    }

    @Test
    fun blankValuesInheritAndAllBlankRemovesOverride() {
        val normalized = ScheduleSubjectOverride(
            originalSubjectName = " 國文 ",
            customSubjectName = " 國語文 ",
            customTeacherName = "  ",
            customClassroom = null,
        ).normalizedOrNull()

        assertEquals("國文", normalized?.originalSubjectName)
        assertEquals("國語文", normalized?.customSubjectName)
        assertNull(normalized?.customTeacherName)
        assertNull(
            ScheduleSubjectOverride("國文", "", " ", null).normalizedOrNull(),
        )
    }

    @Test
    fun customValuesAreTrimmedBeforeTheEightyCharacterLimit() {
        val eightyCharacters = "課".repeat(SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH)

        assertEquals(
            eightyCharacters,
            ScheduleSubjectOverride("國文", "  $eightyCharacters  ").normalizedOrNull()?.customSubjectName,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleSubjectOverride("國文", "課".repeat(SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH + 1))
                .normalizedOrNull()
        }
    }

    @Test
    fun displayedChangesKeepOriginalChangeType() {
        val change = ScheduleChange(
            type = ScheduleChangeType.MODIFIED,
            dayOfWeek = 1,
            period = 1,
            semesterItem = ScheduleItem(1, 1, "國文", "甲師", "101"),
            weekItem = ScheduleItem(1, 1, "國文", "乙師", "102"),
        )
        val report = ScheduleReport(
            yearTermValue = "114_2",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            items = listOf(requireNotNull(change.weekItem)),
            changes = listOf(change),
            subjectOverrides = listOf(ScheduleSubjectOverride("國文", "國語文")),
        )

        val displayed = report.displayChanges().single()

        assertEquals(ScheduleChangeType.MODIFIED, displayed.type)
        assertEquals("國語文", displayed.semesterItem?.subjectName)
        assertEquals("國語文", displayed.weekItem?.subjectName)
    }

    @Test
    fun oldReportsDecodeWithNoOverrides() {
        val report = Json.decodeFromString<ScheduleReport>(
            """{"yearTermValue":"114_2","classNo":"230","scope":"SEMESTER","items":[]}""",
        )

        assertEquals(emptyList<ScheduleSubjectOverride>(), report.subjectOverrides)
    }
}
