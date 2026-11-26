package com.clhs.score.domain.overview

import com.clhs.score.data.FakeData
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.compareScheduleItems
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class OverviewCourseChangesTest {
    @Test fun newOfficialChangeGetsNewAlertWhileDateCopyAndOverridesKeepIdentity() {
        val now = LocalDateTime.of(2026, 11, 16, 8, 0)
        val first = changedRoomReport("305")
        fun alert(report: ScheduleReport, time: LocalDateTime = now) =
            scheduleOverviewItems(report, time).single { it.kind == OverviewItemKind.ScheduleChange }

        val original = alert(first)
        assertEquals(original.id, alert(first, now.minusDays(1)).id)
        assertEquals(original.id, alert(first.copy(subjectOverrides = listOf(
            ScheduleSubjectOverride("地科", customSubjectName = "地球科學"),
        ))).id)
        assertNotEquals(original.id, alert(changedRoomReport("402")).id)
        assertNotEquals(original.id, alert(first.copy(classNo = "231")).id)
    }

    @Test fun officialRoomAndTeacherChangesSurvivePersonalOverrides() {
        val report = changedRoomReport("305").copy(subjectOverrides = listOf(
            ScheduleSubjectOverride("地科", "地球科學", "自訂教師", "地科教室"),
        ))
        val now = LocalDateTime.of(2026, 11, 16, 8, 0)
        val alert = scheduleOverviewItems(report, now).single { it.kind == OverviewItemKind.ScheduleChange }

        assertEquals("地球科學 · 教師 王五 → 李四 · 教室 201 → 305", alert.supportingText)
        assertEquals(alert, scheduleOverviewHero(report, now)?.scheduleChanges?.single())
    }

    private fun changedRoomReport(room: String): ScheduleReport {
        val before = ScheduleItem(1, 6, "地科", "王五", "201")
        val after = before.copy(teacherName = "李四", classroom = room)
        return ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22",
            items = listOf(after), changes = compareScheduleItems(listOf(before), listOf(after)))
    }

    @Test fun demoHasGradeAndScheduleNotificationsOnSchoolDaysAndWeekends() {
        val monday = LocalDateTime.of(2026, 11, 9, 8, 0)
        val grade = gradeOverviewItems(FakeData.overviewGradeReminder).single()
        val exam = grade.destination as OverviewDestination.GradeExam
        assertTrue(FakeData.structure.single { it.value == exam.yearValue }.exams.any { it.value == exam.examValue })

        repeat(7) { day ->
            val now = monday.plusDays(day.toLong())
            val target = if (day < 5) now else monday.plusWeeks(1)
            val report = FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, target.toLocalDate())
            assertTrue("Expected a course change on ${now.dayOfWeek}",
                scheduleOverviewItems(report, now).any { it.kind == OverviewItemKind.ScheduleChange })
        }
    }

    @Test fun nextAddedLessonMatchesByDateAndPeriodWithoutParsingCopy() {
        val now = LocalDateTime.of(2026, 11, 11, 15, 6)
        val report = FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, now.toLocalDate())
        val hero = requireNotNull(scheduleOverviewHero(report, now))
        val key = requireNotNull(hero.nextCourse?.key)
        assertEquals(8, key.period)
        val added = hero.changesFor(key).single()
        assertEquals(ScheduleChangeType.ADDED, added.courseChange?.type)
        assertEquals(listOf(added.copy(title = "Changed display copy", supportingText = "Changed details")),
            hero.copy(scheduleChanges = listOf(added.copy(title = "Changed display copy", supportingText = "Changed details"))).changesFor(key))
        assertTrue(hero.changesFor(key.copy(date = key.date.plusDays(1))).isEmpty())
        assertTrue(hero.changesFor(key.copy(period = 7)).isEmpty())
        assertTrue(hero.changesFor(null).isEmpty())
        val cancelled = added.copy(courseChange = OverviewCourseChange(key, ScheduleChangeType.REMOVED))
        assertTrue(hero.copy(scheduleChanges = listOf(cancelled)).changesFor(key).isEmpty())
        assertEquals(listOf(cancelled), hero.copy(scheduleChanges = listOf(cancelled)).scheduleChanges)
    }
}
