package com.clhs.score.domain.overview

import com.clhs.score.data.FakeData
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleScope
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class OverviewCourseChangesTest {
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
