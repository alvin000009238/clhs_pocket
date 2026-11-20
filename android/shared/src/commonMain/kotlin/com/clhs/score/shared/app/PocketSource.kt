package com.clhs.score.shared.app

import com.clhs.score.data.AttendanceRecord
import com.clhs.score.data.ExamOption
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
import com.clhs.score.shared.time.monthNumber
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.yearNumber

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
    /** Set from the app clock so the synthetic terms and timestamps agree with the date on screen. */
    var today: LocalDate? = null

    // The fixtures end in academic year 114; relabel them so the newest term is the clock's term.
    private val yearShift: Int
        get() = today?.let { date ->
            val academicYear = date.yearNumber() - 1911 - if (date.monthNumber() < 8) 1 else 0
            academicYear - (FakeData.structure.maxOfOrNull { parseYearTerm(it.value, "0", "0").first.toIntOrNull() ?: 0 } ?: academicYear)
        } ?: 0

    override val structure: List<YearTermOption>
        get() = yearShift.let { shift ->
            if (shift == 0) FakeData.structure else FakeData.structure.map { term ->
                val year = parseYearTerm(term.value, "0", "0").first
                term.copy(text = term.text.replaceFirst(year, ((year.toIntOrNull() ?: 0) + shift).toString()))
            }
        }
    override val currentYearValue get() = FakeData.CURRENT_YEAR_VALUE
    override val currentExamValue get() = FakeData.CURRENT_EXAM_VALUE
    override val studentInfo get() = FakeData.latestReport().studentInfo
    override fun report(yearValue: String, examValue: String): GradeReport = FakeData.reportFor(yearValue, examValue).let { report ->
        val date = today ?: return@let report
        report.copy(studentInfo = report.studentInfo.copy(updatedAt = date.shiftDays(-2).slashText().replace('/', '-')))
    }

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

    // One entry per class and week, so browsing another week keeps the current one.
    private fun scheduleKey(scope: ScheduleScope, classValue: String) = "${scope.name}/$classValue/"
    override fun schedule(scope: ScheduleScope, today: LocalDate, classValue: String): ScheduleReport? {
        fun find(value: String) = scheduleKey(scope, value).let { prefix ->
            schedules.entries.firstOrNull { it.key.startsWith(prefix) && it.value.isValidOn(today) }?.value
        }
        return find(classValue) ?: find("")
    }
    /** [classValue] is empty for the signed-in student's own class. */
    fun putSchedule(classValue: String, report: ScheduleReport) {
        schedules[scheduleKey(report.scope, classValue) + report.weekStartDate.orEmpty()] = report
    }

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
    fun currentTermExamValues(): List<String> =
        currentTerm?.let { term -> term.exams.map { PocketExamKey.of(term.value, it.value) } }.orEmpty()

    /** True once a grade check has fetched the exam list for the signed-in student. */
    private var examListChecked = false

    /**
     * Call from a grade check right after storing the freshly fetched exam list, with the exam
     * keys known before it. Returns whether [key] is an exam that appeared since the previous
     * check. An empty list confirmed by an earlier check is a baseline too, so the first exam a
     * student ever gets counts as new; the very first check after sign-in never does.
     */
    fun noteExamListChecked(knownBefore: List<String>, key: String?): Boolean {
        val isNew = examListChecked && key != null && key !in knownBefore
        examListChecked = true
        return isNew
    }

    /** The term an exam key names, or null when it is unknown. */
    fun yearValueOf(examValue: String): String? = located(examValue)?.first?.value

    /** The school's exam code inside an exam key. */
    fun examCodeOf(examValue: String): String? = located(examValue)?.second?.value

    private fun located(key: String) = locateExam(key)?.takeIf { (term, exam) ->
        val (termValue, examValue) = PocketExamKey.parse(key)
        exam.value == examValue && (termValue == null || termValue == term.value)
    }

    /** Drops everything that belongs to a signed-in student. Public data stays. */
    fun clearPrivate() {
        structure = emptyList()
        examListChecked = false
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

    /** The demo fixtures follow this date (academic year labels, "updated at"). */
    fun setDemoDate(clock: PocketClock) { DemoSource.today = clock.date() }
    fun useLive() { current = PocketLive }
}

/** The active timetable for platform features (widget snapshot, reminders, Live Activity). */
object PocketSchedules {
    fun currentWeek(today: LocalDate): ScheduleReport? = PocketData.current.schedule(ScheduleScope.CURRENT_WEEK, today, "")
}

/**
 * What the grade screens pass around as an "exam value": the term and the exam code together.
 * Exam codes repeat across terms on the school system, so the code alone does not identify a report.
 */
internal object PocketExamKey {
    private const val SEPARATOR = "|"

    fun of(termValue: String, examValue: String): String = termValue + SEPARATOR + examValue

    /** Term value (null for a bare exam code) and exam code. */
    fun parse(key: String): Pair<String?, String> =
        if (SEPARATOR in key) key.substringBefore(SEPARATOR) to key.substringAfter(SEPARATOR) else null to key
}

/**
 * The term and exam a key names. A bare exam code resolves to the newest term that has it; an
 * unknown key falls back to the latest exam of the current term, and is null only without a structure.
 */
internal fun PocketSource.locateExam(key: String): Pair<YearTermOption, ExamOption>? {
    val (termValue, examValue) = PocketExamKey.parse(key)
    val term = (if (termValue != null) structure.firstOrNull { it.value == termValue } else null)
        ?: structure.lastOrNull { term -> term.exams.any { it.value == examValue } }?.takeIf { termValue == null }
        ?: structure.firstOrNull { it.value == currentYearValue }
        ?: return null
    val exam = term.exams.firstOrNull { it.value == examValue } ?: term.exams.lastOrNull() ?: return null
    return term to exam
}
