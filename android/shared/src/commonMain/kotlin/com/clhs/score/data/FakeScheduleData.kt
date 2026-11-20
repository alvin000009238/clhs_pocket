package com.clhs.score.data

import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.dayNumber
import com.clhs.score.shared.time.epochDay
import com.clhs.score.shared.time.localDateOf
import com.clhs.score.shared.time.monthNumber
import com.clhs.score.shared.time.parseInstant
import com.clhs.score.shared.time.parseLocalDate
import com.clhs.score.shared.time.schoolToday
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.weekday
import com.clhs.score.shared.time.isoNumber
import com.clhs.score.shared.time.yearNumber
import kotlinx.serialization.json.JsonObject

object FakeScheduleData {
    fun currentYearValue(today: LocalDate = schoolToday()): String {
        val year = today.yearNumber() - 1911 - if (today.monthNumber() < 8) 1 else 0
        val term = if (today.monthNumber() >= 8 || today.monthNumber() == 1) 1 else 2
        return "${year}_$term"
    }

    val years: List<ScheduleYearTermOption>
        get() {
            val current = currentYearValue()
            val (year, term) = requireNotNull(parseYearTermOrNull(current))
            val previous = if (term == "2") "${year}_1" else "${year.toInt() - 1}_2"
            return listOf(current, previous).map { value ->
                val (academicYear, academicTerm) = requireNotNull(parseYearTermOrNull(value))
                ScheduleYearTermOption("${academicYear}學年度第${academicTerm}學期", value)
            }
        }

    val classes = listOf(
        ScheduleClassOption("高二 30 班", "230"),
        ScheduleClassOption("高二 31 班", "231"),
    )

    fun report(
        yearValue: String,
        classNo: String,
        scope: ScheduleScope = ScheduleScope.SEMESTER,
        today: LocalDate = schoolToday(),
    ): ScheduleReport {
        val weekStart = today.shiftDays(-(today.weekday().isoNumber - 1).toLong())
        val (academicYear, academicTerm) = requireNotNull(parseYearTermOrNull(yearValue))
        val semesterStart = if (academicTerm == "1") {
            localDateOf(academicYear.toInt() + 1911, 8, 1)
        } else {
            localDateOf(academicYear.toInt() + 1912, 2, 1)
        }
        val firstWeekStart = semesterStart.shiftDays(-(semesterStart.weekday().isoNumber - 1).toLong())
        val weekNo = ((weekStart.epochDay() - firstWeekStart.epochDay()) / 7 + 1)
            .coerceAtLeast(1).toString()
        val classroom = classes.firstOrNull { it.value == classNo }?.text
            ?.replace(" ", "")?.removeSuffix("班") ?: classNo
        val semesterReport = ScheduleReport(
            yearTermValue = yearValue,
            classNo = classNo,
            scope = ScheduleScope.SEMESTER,
            items = listOf(
                ScheduleItem(dayOfWeek = 1, period = 1, subjectName = "國語文", teacherName = "張三", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 1, period = 2, subjectName = "數學", teacherName = "李四", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 1, period = 3, subjectName = "英語文", teacherName = "王五", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 1, period = 4, subjectName = "體育", teacherName = "趙六", classroom = "操場"),
                ScheduleItem(dayOfWeek = 1, period = 5, subjectName = "物理", teacherName = "孫七", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 1, period = 6, subjectName = "化學", teacherName = "周八", classroom = "實驗室"),
                ScheduleItem(dayOfWeek = 1, period = 7, subjectName = "歷史", teacherName = "吳九", classroom = "高二30"),

                ScheduleItem(dayOfWeek = 2, period = 1, subjectName = "數學", teacherName = "李四", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 2, period = 2, subjectName = "英語文", teacherName = "王五", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 2, period = 3, subjectName = "地理", teacherName = "鄭十", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 2, period = 4, subjectName = "國語文", teacherName = "張三", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 2, period = 5, subjectName = "美術", teacherName = "林一", classroom = "美術教室"),
                ScheduleItem(dayOfWeek = 2, period = 6, subjectName = "美術", teacherName = "林一", classroom = "美術教室"),
                ScheduleItem(dayOfWeek = 2, period = 7, subjectName = "班會", teacherName = "導師", classroom = "高二30"),

                ScheduleItem(dayOfWeek = 3, period = 1, subjectName = "英語文", teacherName = "王五", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 3, period = 2, subjectName = "國語文", teacherName = "張三", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 3, period = 3, subjectName = "數學", teacherName = "李四", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 3, period = 4, subjectName = "公民", teacherName = "陳二", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 3, period = 5, subjectName = "物理", teacherName = "孫七", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 3, period = 6, subjectName = "音樂", teacherName = "黃三", classroom = "音樂教室"),
                ScheduleItem(dayOfWeek = 3, period = 7, subjectName = "體育", teacherName = "趙六", classroom = "體育館"),

                ScheduleItem(dayOfWeek = 4, period = 1, subjectName = "化學", teacherName = "周八", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 4, period = 2, subjectName = "歷史", teacherName = "吳九", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 4, period = 3, subjectName = "國語文", teacherName = "張三", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 4, period = 4, subjectName = "數學", teacherName = "李四", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 4, period = 5, subjectName = "英語文", teacherName = "王五", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 4, period = 7, subjectName = "社團", teacherName = "各社團指導", classroom = "各處"),

                ScheduleItem(dayOfWeek = 5, period = 1, subjectName = "物理", teacherName = "孫七", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 5, period = 2, subjectName = "化學", teacherName = "周八", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 5, period = 3, subjectName = "英語文", teacherName = "王五", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 5, period = 4, subjectName = "數學", teacherName = "李四", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 5, period = 5, subjectName = "國語文", teacherName = "張三", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 5, period = 6, subjectName = "地理", teacherName = "鄭十", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 5, period = 7, subjectName = "公民", teacherName = "陳二", classroom = "高二30"),
                ScheduleItem(dayOfWeek = 5, period = 8, subjectName = "生涯規劃", teacherName = "代理教師", classroom = "專題教室")
            ).map { item ->
                if (item.classroom == "高二30") item.copy(classroom = classroom) else item
            },
        )
        if (scope == ScheduleScope.SEMESTER) return semesterReport

        val weekItems = semesterReport.items
            .filterNot { it.dayOfWeek == 1 && it.period == 4 }
            .map { item ->
                if (item.dayOfWeek == 2 && item.period == 3) {
                    item.copy(subjectName = "數學", teacherName = "李四", classroom = "高二31")
                } else {
                    item
                }
            } + ScheduleItem(
                dayOfWeek = 3,
                period = 8,
                subjectName = "生涯規劃",
                teacherName = "代理教師",
                classroom = "專題教室",
            )

        return semesterReport.copy(
            scope = ScheduleScope.CURRENT_WEEK,
            weekNo = weekNo,
            weekStartDate = weekStart.toString(),
            weekEndDate = weekStart.shiftDays(6).toString(),
            items = weekItems,
            changes = compareScheduleItems(semesterReport.items, weekItems),
        )
    }
}
