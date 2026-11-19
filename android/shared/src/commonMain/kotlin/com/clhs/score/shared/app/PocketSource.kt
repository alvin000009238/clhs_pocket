package com.clhs.score.shared.app

import com.clhs.score.data.AttendanceRecord
import com.clhs.score.data.FakeData
import com.clhs.score.data.FakeRecordData
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.GradeReport
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordSemester
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementDetail
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.YouBikeSnapshot
import com.clhs.score.data.YouBikeStation
import com.clhs.score.data.latestExam
import com.clhs.score.data.latestYearTerm
import com.clhs.score.data.parseYearTerm
import com.clhs.score.data.parseYearTermOrNull
import com.clhs.score.domain.overview.WeatherSnapshot
import com.clhs.score.shared.time.LocalDate

/**
 * Raw data behind every Swift-facing facade in this package. The demo source is the
 * shared synthetic fixtures; the live source holds whatever the app has loaded through
 * `PocketApi`, so a missing value means "not loaded yet", never "empty".
 */
internal interface PocketSource {
    val isDemo: Boolean
    val structure: List<YearTermOption>
    val currentYearValue: String
    val currentExamValue: String
    val studentInfo: StudentInfo?
    fun report(yearValue: String, examValue: String): GradeReport?

    val scheduleClasses: List<ScheduleClassOption>
    fun schedule(scope: ScheduleScope, today: LocalDate, classValue: String): ScheduleReport?

    fun announcements(clock: PocketClock): List<SchoolAnnouncement>
    fun announcementDetail(id: String): SchoolAnnouncementDetail?
    fun events(clock: PocketClock): List<SchoolCalendarEvent>

    val weatherLocations: List<WeatherLocation>
    val youBikeStations: List<YouBikeStation>
    fun weather(location: WeatherLocation): PocketWeather?
    fun youBike(station: YouBikeStation): PocketYouBikeStation?

    val recordSemesters: List<RecordSemester>
    fun attendance(semester: RecordSemester): AttendanceRecord?
    fun merits(semester: RecordSemester): List<MeritRecord>?
}

internal object DemoSource : PocketSource {
    override val isDemo = true
    override val structure get() = FakeData.structure
    override val currentYearValue get() = FakeData.CURRENT_YEAR_VALUE
    override val currentExamValue get() = FakeData.CURRENT_EXAM_VALUE
    override val studentInfo get() = FakeData.latestReport().studentInfo
    override fun report(yearValue: String, examValue: String) = FakeData.reportFor(yearValue, examValue)

    override val scheduleClasses get() = FakeScheduleData.classes
    override fun schedule(scope: ScheduleScope, today: LocalDate, classValue: String): ScheduleReport {
        val classNo = scheduleClasses.firstOrNull { it.value == classValue } ?: scheduleClasses.first()
        return FakeScheduleData.report(FakeScheduleData.currentYearValue(today), classNo.value, scope, today)
    }

    override fun announcements(clock: PocketClock) = DemoCampus.demoAnnouncements(clock)
    override fun announcementDetail(id: String): SchoolAnnouncementDetail? = null
    override fun events(clock: PocketClock) = DemoCampus.demoEvents(clock)

    override val weatherLocations get() = DemoLive.locations
    override val youBikeStations get() = DemoLive.stations
    override fun weather(location: WeatherLocation) = DemoLive.weather(location)
    override fun youBike(station: YouBikeStation) = DemoLive.youBike(station)

    override val recordSemesters get() = FakeRecordData.semesters
    override fun attendance(semester: RecordSemester) = FakeRecordData.attendance(semester)
    override fun merits(semester: RecordSemester) = FakeRecordData.merits(semester)
}

/**
 * Data loaded from the real services. Swift fills it on the main thread after each
 * `PocketApi` call and clears the private half when the session ends; nothing here is persisted.
 */
object PocketLive : PocketSource {
    override val isDemo = false

    override var structure: List<YearTermOption> = emptyList()
    override var studentInfo: StudentInfo? = null
    private val reports = mutableMapOf<String, GradeReport>()
    override var scheduleClasses: List<ScheduleClassOption> = emptyList()
    private val schedules = mutableMapOf<String, ScheduleReport>()
    private var announcementList: List<SchoolAnnouncement> = emptyList()
    private val details = mutableMapOf<String, SchoolAnnouncementDetail>()
    private var eventList: List<SchoolCalendarEvent> = emptyList()
    override var weatherLocations: List<WeatherLocation> = DemoLive.locations
    override var youBikeStations: List<YouBikeStation> = DemoLive.stations
    private val weatherByLocation = mutableMapOf<String, WeatherSnapshot>()
    private val bikesByStation = mutableMapOf<String, YouBikeSnapshot>()
    override var recordSemesters: List<RecordSemester> = emptyList()
    private val attendanceBySemester = mutableMapOf<String, AttendanceRecord>()
    private val meritsBySemester = mutableMapOf<String, List<MeritRecord>>()

    private val currentTerm get() = structure.filter { it.exams.isNotEmpty() }.latestYearTerm() ?: structure.latestYearTerm()
    override val currentYearValue: String get() = currentTerm?.value.orEmpty()
    override val currentExamValue: String get() = currentTerm?.latestExam()?.value.orEmpty()

    /** Stored oldest first, the order the facades expect. */
    fun putStructure(items: List<YearTermOption>) {
        structure = items.sortedWith(
            compareBy<YearTermOption>({ parseYearTerm(it.value, "0", "0").first.toIntOrNull() ?: 0 })
                .thenBy { parseYearTerm(it.value, "0", "0").second.toIntOrNull() ?: 0 },
        )
    }

    override fun report(yearValue: String, examValue: String): GradeReport? = reports["$yearValue/$examValue"]
    fun putReport(yearValue: String, examValue: String, report: GradeReport) { reports["$yearValue/$examValue"] = report }
    fun hasReport(yearValue: String, examValue: String): Boolean = "$yearValue/$examValue" in reports

    private fun scheduleKey(scope: ScheduleScope, classValue: String) = "${scope.name}/$classValue"
    override fun schedule(scope: ScheduleScope, today: LocalDate, classValue: String): ScheduleReport? =
        (schedules[scheduleKey(scope, classValue)] ?: schedules[scheduleKey(scope, "")])?.takeIf { it.isValidOn(today) }
    /** [classValue] is empty for the signed-in student's own class. */
    fun putSchedule(classValue: String, report: ScheduleReport) { schedules[scheduleKey(report.scope, classValue)] = report }

    override fun announcements(clock: PocketClock): List<SchoolAnnouncement> = announcementList
    fun putAnnouncements(items: List<SchoolAnnouncement>) { announcementList = items }
    override fun announcementDetail(id: String): SchoolAnnouncementDetail? = details[id]
    fun putAnnouncementDetail(detail: SchoolAnnouncementDetail) { details[detail.id] = detail }

    override fun events(clock: PocketClock): List<SchoolCalendarEvent> = eventList
    fun putEvents(items: List<SchoolCalendarEvent>) { eventList = items }

    override fun weather(location: WeatherLocation): PocketWeather? = weatherByLocation[location.id]?.let { snapshot ->
        val text = snapshot.condition
        PocketWeather(
            location = location.name,
            temperatureText = "${snapshot.temperatureCelsius}°",
            condition = text,
            kind = when {
                "雷" in text -> PocketWeatherKind.THUNDER
                "雨" in text -> PocketWeatherKind.RAIN
                "陰" in text -> PocketWeatherKind.CLOUDY
                "雲" in text -> PocketWeatherKind.PARTLY_CLOUDY
                else -> PocketWeatherKind.SUNNY
            },
            highText = snapshot.maximumTemperatureCelsius?.let { "$it°" } ?: "--",
            lowText = snapshot.minimumTemperatureCelsius?.let { "$it°" } ?: "--",
            rainText = snapshot.precipitationProbability?.let { "$it%" } ?: "--",
            apparentText = snapshot.apparentTemperatureCelsius?.let { "$it°" } ?: "--",
        )
    }
    fun putWeather(locationId: String, snapshot: WeatherSnapshot) { weatherByLocation[locationId] = snapshot }

    override fun youBike(station: YouBikeStation): PocketYouBikeStation? = bikesByStation[station.id]?.let { snapshot ->
        val parts = listOfNotNull(
            snapshot.electricBikes?.let { "電輔車 $it" }, snapshot.ordinaryBikes?.let { "一般 $it" },
        )
        PocketYouBikeStation(
            station.id, station.name, "${snapshot.availableBikes}", "${snapshot.emptyDocks}",
            if (snapshot.isOperating) parts.joinToString(" · ") else "暫停營運",
        )
    }
    fun putYouBike(stationId: String, snapshot: YouBikeSnapshot) { bikesByStation[stationId] = snapshot }

    private fun semesterKey(semester: RecordSemester) = "${semester.year}/${semester.term}"
    override fun attendance(semester: RecordSemester): AttendanceRecord? = attendanceBySemester[semesterKey(semester)]
    fun putAttendance(semester: RecordSemester, record: AttendanceRecord) { attendanceBySemester[semesterKey(semester)] = record }
    override fun merits(semester: RecordSemester): List<MeritRecord>? = meritsBySemester[semesterKey(semester)]
    fun putMerits(semester: RecordSemester, records: List<MeritRecord>) { meritsBySemester[semesterKey(semester)] = records }

    /** `[year, term]` of a schedule or grade year option, or null when it is malformed. */
    fun yearTermParts(value: String): List<String>? = parseYearTermOrNull(value)?.let { listOf(it.first, it.second) }

    /** Exams of the newest term that has any, oldest first: what the grades screen needs first. */
    fun currentTermExamValues(): List<String> = currentTerm?.exams.orEmpty().map { it.value }

    /** The term a given exam belongs to, or null. */
    fun yearValueOf(examValue: String): String? = structure.firstOrNull { term -> term.exams.any { it.value == examValue } }?.value

    /** Drops everything that belongs to a signed-in student. Public data stays. */
    fun clearPrivate() {
        structure = emptyList()
        studentInfo = null
        reports.clear()
        scheduleClasses = emptyList()
        schedules.clear()
        recordSemesters = emptyList()
        attendanceBySemester.clear()
        meritsBySemester.clear()
    }

    fun clearAll() {
        clearPrivate()
        announcementList = emptyList()
        details.clear()
        eventList = emptyList()
        weatherByLocation.clear()
        bikesByStation.clear()
    }
}

/** Which source the facades read. Demo is the default so tests and previews stay deterministic. */
object PocketData {
    internal var current: PocketSource = DemoSource
        private set

    val isDemo: Boolean get() = current.isDemo

    fun useDemo() { current = DemoSource }
    fun useLive() { current = PocketLive }
}

/** The active timetable for platform features (widget snapshot, reminders, Live Activity). */
object PocketSchedules {
    fun currentWeek(today: LocalDate): ScheduleReport? = PocketData.current.schedule(ScheduleScope.CURRENT_WEEK, today, "")
}
