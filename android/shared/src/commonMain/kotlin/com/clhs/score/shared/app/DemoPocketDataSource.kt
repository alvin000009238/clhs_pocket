package com.clhs.score.shared.app

import com.clhs.score.data.FakeData

/**
 * Deterministic synthetic data for the Demo configuration.
 *
 * Nothing here is real school data. Dates are derived from the supplied clock so
 * the demo never goes stale.
 */
internal class DemoPocketDataSource : PocketDataSource {
    override val isDemo: Boolean = true

    override fun profile(): PocketProfile = FakeData.latestReport().studentInfo.let {
        PocketProfile(
            studentName = it.studentName,
            className = it.className,
            seatNo = it.seatNo,
            studentNo = it.studentNo,
            isDemo = true,
        )
    }

    override fun home(clock: PocketClock): PocketHome {
        val announcements = DemoCampus.announcements(clock)
        return PocketHome(
            hero = DemoSchedule.hero(clock),
            weather = PocketWeather(
                location = "中壢區",
                temperatureText = "24°",
                condition = "多雲時晴",
                kind = PocketWeatherKind.PARTLY_CLOUDY,
                highText = "27°",
                lowText = "19°",
                rainText = "20%",
                apparentText = "25°",
            ),
            countdowns = DemoCampus.countdowns(clock),
            youBike = listOf(
                PocketYouBikeStation("demo-1", "中壢高中", "4", "44", "電輔車 2 · 一般 2"),
                PocketYouBikeStation("demo-2", "中壢火車站(後站)", "12", "23", "電輔車 5 · 一般 7"),
            ),
            events = DemoCampus.events(clock).take(3),
            announcements = announcements.take(3),
            unreadAnnouncementCount = announcements.count { it.isUnread },
        )
    }

    override fun schedule(scope: PocketScheduleScope, clock: PocketClock): PocketSchedule =
        DemoSchedule.schedule(scope, clock)

    override fun gradeTerms(): List<PocketGradeTerm> = DemoGrades.terms

    override fun defaultExamValue(): String = DemoGrades.DEFAULT_EXAM

    override fun grades(examValue: String): PocketGradeReport = DemoGrades.report(examValue)

    override fun campus(clock: PocketClock): PocketCampus {
        val announcements = DemoCampus.announcements(clock)
        val events = DemoCampus.events(clock)
        return PocketCampus(
            featured = announcements.firstOrNull { it.isPinned } ?: announcements.firstOrNull(),
            announcements = announcements,
            unreadCount = announcements.count { it.isUnread },
            calendarHeadline = "${clock.year} 年 ${clock.month} 月",
            events = events,
        )
    }
}
