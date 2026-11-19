package com.clhs.score.shared.app

/**
 * Swift-facing, display-ready snapshots consumed by the native iOS UI.
 *
 * These are deliberately flat: SwiftUI renders them and never recomputes
 * grades, schedule resolution or dashboard aggregation on its own.
 */
data class PocketClock(
    val year: Int,
    val month: Int,
    val day: Int,
    /** 1 = Monday … 7 = Sunday. */
    val dayOfWeek: Int,
    val minuteOfDay: Int,
)

data class PocketProfile(
    val studentName: String,
    val className: String,
    val seatNo: String,
    val studentNo: String,
    val isDemo: Boolean,
)

data class PocketCourseHero(
    val headline: String,
    val courseName: String,
    val supportingText: String,
    val isInClass: Boolean,
    /** 0…1 while in class, otherwise -1. */
    val progress: Double,
    val timeText: String,
    val nextLine: String?,
)

enum class PocketWeatherKind { SUNNY, PARTLY_CLOUDY, CLOUDY, RAIN, THUNDER }

data class PocketWeather(
    val location: String,
    val temperatureText: String,
    val condition: String,
    val kind: PocketWeatherKind,
    val highText: String,
    val lowText: String,
    val rainText: String,
    val apparentText: String,
)

data class PocketYouBikeStation(
    val id: String,
    val name: String,
    val bikesText: String,
    val docksText: String,
    val detailText: String,
)

data class PocketCountdown(
    val id: String,
    val label: String,
    val title: String,
    val dateText: String,
)

data class PocketEventRow(
    val id: String,
    val monthText: String,
    val dayText: String,
    val title: String,
    val subtitle: String,
    val isOngoing: Boolean,
)

data class PocketAnnouncement(
    val id: String,
    val title: String,
    val dateText: String,
    val shortDateText: String,
    val unit: String,
    val category: String,
    val isPinned: Boolean,
    val isUnread: Boolean,
    val isBookmarked: Boolean = false,
)

data class PocketHome(
    val hero: PocketCourseHero?,
    val weather: PocketWeather,
    val countdowns: List<PocketCountdown>,
    val youBike: List<PocketYouBikeStation>,
    val events: List<PocketEventRow>,
    val announcements: List<PocketAnnouncement>,
    val unreadAnnouncementCount: Int,
)

enum class PocketScheduleScope { SEMESTER, CURRENT_WEEK }

enum class PocketCellStatus { NORMAL, CURRENT, ENDED }

enum class PocketCellChange { NONE, ADDED, CANCELLED, CHANGED }

data class PocketScheduleDay(
    val dayOfWeek: Int,
    val label: String,
    val dateText: String,
    val isToday: Boolean,
)

data class PocketPeriod(
    val number: Int,
    val start: String,
    val end: String,
)

data class PocketScheduleCell(
    val id: String,
    val dayOfWeek: Int,
    val period: Int,
    val span: Int,
    val subject: String,
    val teacher: String,
    val classroom: String,
    val timeText: String,
    val colorLight: Long,
    val colorDark: Long,
    val status: PocketCellStatus,
    val change: PocketCellChange,
    val changeNote: String,
    /** Subject name as published by the school, the key for a personal override. */
    val originalSubject: String = subject,
    val isOverridden: Boolean = false,
)

data class PocketSchedule(
    val scope: PocketScheduleScope,
    val scopeLabel: String,
    val termText: String,
    val className: String,
    val rangeText: String,
    val weekText: String,
    val days: List<PocketScheduleDay>,
    val periods: List<PocketPeriod>,
    val cells: List<PocketScheduleCell>,
    val changeCount: Int,
    /** False while no timetable has been loaded for this view. */
    val isAvailable: Boolean = true,
)

data class PocketExamOption(val text: String, val value: String)

data class PocketGradeTerm(
    val text: String,
    val value: String,
    val exams: List<PocketExamOption>,
)

data class PocketSubjectInsight(
    val name: String,
    val detail: String,
    val percentText: String,
)

data class PocketSubjectGrade(
    val name: String,
    val scoreText: String,
    /** 0…1 of the full mark; -1 when the grade has no numeric score. */
    val fraction: Double,
    val averageFraction: Double,
    val classAverageText: String,
    val diffText: String,
    /** -1 below average, 0 level, 1 above. */
    val diffSign: Int,
    val classRankText: String,
    val yearRankText: String,
    val isFlunk: Boolean,
)

data class PocketTrendPoint(val examName: String, val average: Double, val averageText: String)

data class PocketGradeReport(
    val examValue: String,
    val examTitle: String,
    val termTitle: String,
    val averageText: String,
    val totalText: String,
    val deltaText: String,
    val deltaSign: Int,
    val classRankText: String,
    val categoryRankText: String,
    val classTopText: String,
    val categoryTopText: String,
    val strengths: List<PocketSubjectInsight>,
    val weaknesses: List<PocketSubjectInsight>,
    val subjects: List<PocketSubjectGrade>,
    val trend: List<PocketTrendPoint>,
    val updatedAtText: String,
)

data class PocketCampus(
    val featured: PocketAnnouncement?,
    val announcements: List<PocketAnnouncement>,
    val unreadCount: Int,
    val calendarHeadline: String,
    val events: List<PocketEventRow>,
)
