package com.clhs.score.data

import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

internal fun fakeYouBikeSnapshot(now: Instant): YouBikeSnapshot = YouBikeSnapshot(
    availableBikes = 4, emptyDocks = 44, totalDocks = 52,
    ordinaryBikes = 2, electricBikes = 2, isOperating = true,
    updatedAt = now, fetchedAt = now,
)

enum class StudentScenario {
    NORMAL, EXCELLENT, STRUGGLING, SPECIAL
}

object MockGradeSystem {
    private const val DEMO_STUDENT_NO = "DEMO-000"
    private const val DEMO_STUDENT_NAME = "範例學生"
    private const val DEMO_CLASS_NAME = "示範班級"
    private const val DEMO_SEAT_NO = "00"

    data class SubjectSpec(
        val name: String,
        val classAverage: Double,
        val standard: GradeStandard,
    )

    val subjectSpecs = listOf(
        SubjectSpec(
            name = "國文",
            classAverage = 76.2,
            standard = GradeStandard("國文", 91.0, 84.0, 76.0, 68.0, 55.0, 10.4, 4, 9, 12, 8, 3, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "英文",
            classAverage = 74.8,
            standard = GradeStandard("英文", 90.0, 82.0, 75.0, 66.0, 52.0, 11.6, 3, 8, 13, 8, 4, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "數學",
            classAverage = 70.5,
            standard = GradeStandard("數學", 88.0, 80.0, 71.0, 60.0, 45.0, 14.2, 5, 7, 9, 8, 5, 2, 1, 1, 0, 0),
        ),
        SubjectSpec(
            name = "自然",
            classAverage = 73.4,
            standard = GradeStandard("自然", 89.0, 81.0, 73.0, 64.0, 50.0, 12.3, 4, 7, 12, 8, 5, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "社會",
            classAverage = 75.0,
            standard = GradeStandard("社會", 92.0, 84.0, 75.0, 67.0, 54.0, 10.8, 3, 10, 11, 9, 3, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "地理",
            classAverage = 77.1,
            standard = GradeStandard("地理", 93.0, 85.0, 77.0, 69.0, 56.0, 9.9, 4, 9, 12, 8, 3, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "歷史",
            classAverage = 72.6,
            standard = GradeStandard("歷史", 89.0, 82.0, 73.0, 65.0, 52.0, 11.1, 2, 8, 12, 10, 4, 1, 1, 0, 0, 0),
        ),
    )

    fun generateReport(
        scenario: StudentScenario = StudentScenario.NORMAL,
        year: Int = 114,
        termText: String = "第 1 學期",
        examName: String = "期末考",
        customTotalScore: Double? = null,
        customAverageScore: Double? = null,
        customClassRank: Double? = null,
        customCategoryRank: Double? = null,
        customScores: List<Double>? = null,
        customSubjectNames: List<String>? = null,
    ): GradeReport {
        val yearTermDisplay = "$year 學年度 $termText"
        
        val scores = customScores ?: when (scenario) {
            StudentScenario.NORMAL -> listOf(78.0, 75.0, 80.0, 76.0, 70.0, 79.0, 74.0)
            StudentScenario.EXCELLENT -> listOf(95.0, 98.0, 100.0, 92.0, 96.0, 90.0, 94.0)
            StudentScenario.STRUGGLING -> listOf(45.0, 50.0, 30.0, 40.0, 55.0, 60.0, 42.0)
            StudentScenario.SPECIAL -> listOf(80.0, -1.0, 90.0, -2.0, 70.0, 85.0, 75.0) // -1:缺考, -2:作弊
        }

        val classCount = 38
        val yearRankCount = 226

        val subjects = subjectSpecs.zip(scores).mapIndexedNotNull { index, (spec, scoreValue) ->
            if (scoreValue == -999.0) return@mapIndexedNotNull null
            
            val subjectName = customSubjectNames?.getOrNull(index) ?: spec.name
            val classAverage = spec.classAverage
            val isAbsent = scoreValue == -1.0
            val isCheating = scoreValue == -2.0
            val actualScore = if (isAbsent || isCheating) 0.0 else scoreValue
            val scoreDisplay = when {
                isAbsent -> "缺考"
                isCheating -> "作弊"
                else -> "%.2f".format(Locale.ROOT, actualScore)
            }

            val subjectClassRank = if (isAbsent || isCheating) classCount else subjectRank(actualScore, classAverage, index)
            val subjectYearRank = if (isAbsent || isCheating) yearRankCount else (subjectClassRank * 6 - 2).coerceIn(1, yearRankCount)

            SubjectScore(
                subjectName = subjectName,
                scoreDisplay = scoreDisplay,
                score = if (isAbsent || isCheating) null else actualScore,
                classAverageDisplay = "%.2f".format(Locale.ROOT, classAverage),
                classAverage = classAverage,
                classRank = subjectClassRank,
                classRankCount = classCount,
                yearRank = subjectYearRank,
                yearRankCount = yearRankCount,
                yearTermDisplay = yearTermDisplay,
                flunk = actualScore < 60.0,
                absent = isAbsent,
                cheating = isCheating,
            )
        }

        val credits = checkNotNull(SimulatorCredits.defaultCredits(subjects))
        val computedTotal = subjects.fold(BigDecimal.ZERO) { total, subject ->
            val score = if (subject.absent || subject.cheating) BigDecimal.ZERO else subject.scoreDisplay.toBigDecimal()
            total + score * BigDecimal(credits.getValue(cleanSubjectName(subject.subjectName)))
        }
        val totalCredits = credits.values.sum()
        val computedAverage = if (totalCredits > 0) {
            computedTotal.divide(BigDecimal(totalCredits), 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO.setScale(2)
        }
        val finalTotalScore = customTotalScore?.let { "%.2f".format(Locale.ROOT, it) }
            ?: computedTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val finalAverageScore = customAverageScore?.let { "%.2f".format(Locale.ROOT, it) }
            ?: computedAverage.toPlainString()
        
        val finalClassRank = customClassRank ?: when (scenario) {
            StudentScenario.EXCELLENT -> 1.0
            StudentScenario.STRUGGLING -> 35.0
            else -> 15.0
        }
        val finalCategoryRank = customCategoryRank ?: (finalClassRank * 6 - 2).coerceIn(1.0, yearRankCount.toDouble())

        return GradeReport(
            message = "fake mock data",
            studentInfo = StudentInfo(
                studentNo = DEMO_STUDENT_NO,
                studentName = DEMO_STUDENT_NAME,
                className = DEMO_CLASS_NAME,
                seatNo = DEMO_SEAT_NO,
                updatedAt = "2026-05-20 08:00",
                showClassRank = true,
                showClassRankCount = true,
                showCategoryRank = true,
                showCategoryRankCount = true,
            ),
            examSummary = ExamSummary(
                year = year,
                termText = termText,
                examName = examName,
                totalScoreDisplay = finalTotalScore,
                averageScoreDisplay = finalAverageScore,
                classRank = finalClassRank,
                classCount = classCount,
                categoryRank = finalCategoryRank,
                categoryRankCount = yearRankCount,
                flunkCount = subjects.count { it.flunk },
            ),
            subjects = subjects,
            standards = subjectSpecs.map { it.standard },
            rawResult = JsonObject(emptyMap()),
        )
    }

    private fun subjectRank(score: Double, classAverage: Double, index: Int): Int {
        val base = when {
            score - classAverage >= 12.0 -> 4
            score - classAverage >= 6.0 -> 8
            score >= classAverage -> 15
            score >= classAverage - 6.0 -> 23
            else -> 31
        }
        return (base + index).coerceIn(1, 38)
    }
}

object FakeData {
    val session = AuthenticatedSession(
        studentNo = "DEMO-000",
        apiToken = "fake-token",
        cookies = mapOf("fake" to "cookie"),
    )
    val structure: List<YearTermOption> = listOf(
        YearTermOption(
            text = "113 學年度 第 2 學期",
            value = "113_2",
            exams = listOf(
                ExamOption("第一次段考", "113_2_E1"),
                ExamOption("第二次段考", "113_2_E2"),
                ExamOption("期末考", "113_2_E3"),
            ),
        ),
        YearTermOption(
            text = "114 學年度 第 1 學期",
            value = "114_1",
            exams = listOf(
                ExamOption("第一次段考", "114_1_E1"),
                ExamOption("第二次段考", "114_1_E2"),
                ExamOption("期末考", "114_1_E4"),
            ),
        ),
    )

    const val CURRENT_YEAR_VALUE = "114_1"
    const val CURRENT_EXAM_VALUE = "114_1_E4"

    val announcementPage = SchoolAnnouncementPage(
        announcements = listOf(
            SchoolAnnouncement(
                id = "45072",
                title = "圖書館八月閉館時間異動公告",
                date = "2026/08/06",
                category = "公告",
                unit = "讀者服務組",
                issuer = "讀者服務組",
                isPinned = true,
                contentType = "content",
            ),
            SchoolAnnouncement(
                id = "45068",
                title = "中央研究院高中生命科學研究人才培育計畫",
                date = "2026/08/06",
                category = "最新消息",
                unit = "設備組",
                issuer = "設備組",
                isPinned = false,
                contentType = "content",
            ),
        ),
        pageIndex = 0,
        totalPages = 2,
        fetchedAt = Instant.parse("2026-08-09T05:00:00Z"),
    )

    val announcementDetail = SchoolAnnouncementDetail(
        id = "45072",
        title = "圖書館八月閉館時間異動公告",
        date = "2026-08-06 11:38:16",
        category = "公告",
        unit = "圖書館官網",
        issuer = "讀者服務組",
        htmlContent = "<p><strong>八月份閉館日期調整</strong></p><p>請同學留意開放時間，不便之處敬請見諒。</p>",
        images = emptyList(),
        attachments = listOf(
            SchoolAnnouncementAttachment(
                name = "八月開館時間.pdf",
                sizeBytes = 245_760,
                url = "https://www.clhs.tyc.edu.tw/ischool/news/attached/45072/sample.pdf",
            ),
        ),
        officialUrl = "https://www.clhs.tyc.edu.tw/ischool/public/news_view/show.php?nid=45072",
    )

    private val reports: Map<Pair<String, String>, GradeReport> by lazy {
        mapOf(
            ("114_1" to "114_1_E4") to MockGradeSystem.generateReport(
                year = 114, termText = "第 1 學期", examName = "期末考",
                customClassRank = 7.0, customCategoryRank = 38.0,
                customScores = listOf(84.0, 78.0, 92.0, 88.0, 73.0, 81.0, 70.0),
                customSubjectNames = listOf("國文", "英文", "選修數學甲", "自然", "社會", "地理", "歷史")
            ),
            ("114_1" to "114_1_E2") to MockGradeSystem.generateReport(
                year = 114, termText = "第 1 學期", examName = "第二次段考",
                customClassRank = 14.0, customCategoryRank = 71.0,
                customScores = listOf(76.0, 72.0, 82.0, 80.0, 68.0, 75.0, 70.0),
                customSubjectNames = listOf("國文", "英文", "數學A", "自然", "社會", "地理", "歷史")
            ),
            ("114_1" to "114_1_E1") to MockGradeSystem.generateReport(
                year = 114, termText = "第 1 學期", examName = "第一次段考",
                customClassRank = 18.0, customCategoryRank = 90.0,
                customScores = listOf(72.0, 70.0, 78.0, 76.0, 66.0, 73.0, 71.0),
                customSubjectNames = listOf("國文", "英文", "數學A", "自然", "社會", "地理", "歷史")
            ),
            ("113_2" to "113_2_E3") to MockGradeSystem.generateReport(
                year = 113, termText = "第 2 學期", examName = "期末考",
                customClassRank = 16.0, customCategoryRank = 82.0,
                customScores = listOf(74.0, 71.0, 80.0, 78.0, 67.0, 75.0, 69.0),
                customSubjectNames = listOf("國文", "英文", "數學", "自然", "社會", "地理", "歷史")
            ),
            ("113_2" to "113_2_E2") to MockGradeSystem.generateReport(
                year = 113, termText = "第 2 學期", examName = "第二次段考",
                customClassRank = 17.0, customCategoryRank = 85.0,
                customScores = listOf(70.0, 68.0, 78.0, 75.0, 65.0, 72.0, 65.0),
                customSubjectNames = listOf("國文", "英文", "數學", "自然", "社會", "地理", "歷史")
            ),
            ("113_2" to "113_2_E1") to MockGradeSystem.generateReport(
                year = 113, termText = "第 2 學期", examName = "第一次段考",
                customClassRank = 19.0, customCategoryRank = 92.0,
                customScores = listOf(68.0, 65.0, 75.0, 72.0, 62.0, 70.0, 62.0),
                customSubjectNames = listOf("國文", "英文", "數學", "自然", "社會", "地理", "歷史")
            ),
        )
    }

    fun reportFor(yearValue: String, examValue: String): GradeReport =
        reports[yearValue to examValue] ?: reports.getValue(CURRENT_YEAR_VALUE to CURRENT_EXAM_VALUE)
    fun latestReport(): GradeReport = reportFor(CURRENT_YEAR_VALUE, CURRENT_EXAM_VALUE)

    fun previousReport(): GradeReport = reportFor("114_1", "114_1_E2")

    fun trendReports(): List<Pair<String, GradeReport>> = listOf(
        "第一次段考" to reportFor("114_1", "114_1_E1"),
        "第二次段考" to reportFor("114_1", "114_1_E2"),
        "期末考" to reportFor("114_1", "114_1_E4"),
    )

    fun simulatorHistoryReports(): List<GradeReport> = listOf(
        reportFor("114_1", "114_1_E1"),
        reportFor("114_1", "114_1_E2"),
        reportFor("114_1", "114_1_E4"),
    )
}

class FakeGradeRepository(
    private val sessionStore: SessionStore? = null,
    private val cacheStore: GradeCacheStore? = null,
) : GradeRepository {
    private val sessionLock = Any()
    private var sessionGeneration = 0L
    private var simulatorSessionRevoked = false
    private val simulatorCredits = mutableMapOf<Triple<String, String, String>, SavedSimulatorCredits>()
    @Volatile
    private var activeSession: AuthenticatedSession? = null

    override suspend fun restoreSession(): AuthenticatedSession? = activeSession

    override fun activateSession(session: AuthenticatedSession) {
        check(sessionStore?.isAuthorized(session) != false) { "Session authority cannot be verified" }
        synchronized(sessionLock) {
            activeSession = session
            sessionGeneration++
            simulatorSessionRevoked = false
        }
    }

    override suspend fun loadStructure(session: AuthenticatedSession, forceRefresh: Boolean): List<YearTermOption> = FakeData.structure

    override suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo =
        MockGradeSystem.generateReport().studentInfo

    override suspend fun fetchGrades(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        forceRefresh: Boolean,
    ): GradeReport = FakeData.reportFor(yearValue, examValue)

    override suspend fun loadSimulatorCredits(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
    ): SavedSimulatorCredits? {
        val generation = captureSimulatorSessionGeneration(session)
        val settings = if (cacheStore != null) {
            cacheStore.loadSimulatorCredits(session.studentNo, yearValue, examValue)
        } else {
            synchronized(sessionLock) {
                if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
                simulatorCredits[Triple(session.studentNo, yearValue, examValue)]
            }
        }
        sessionStore?.validateSession(session)
        if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
        return settings?.copy(subjectNames = settings.subjectNames.toList(), credits = settings.credits.toMap())
    }

    override suspend fun saveSimulatorCredits(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        settings: SavedSimulatorCredits?,
    ) {
        val generation = captureSimulatorSessionGeneration(session)
        if (cacheStore != null) {
            cacheStore.saveSimulatorCredits(session.studentNo, yearValue, examValue, settings) {
                isCurrentSimulatorSession(session, generation)
            }
        } else {
            val snapshot = settings?.copy(subjectNames = settings.subjectNames.toList(), credits = settings.credits.toMap())
            synchronized(sessionLock) {
                if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
                val key = Triple(session.studentNo, yearValue, examValue)
                if (snapshot == null) simulatorCredits.remove(key) else simulatorCredits[key] = snapshot
            }
        }
        sessionStore?.validateSession(session)
        if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
    }

    private suspend fun captureSimulatorSessionGeneration(session: AuthenticatedSession): Long {
        sessionStore?.validateSession(session)
        return synchronized(sessionLock) {
            when {
                simulatorSessionRevoked -> throw CancellationException("Session revoked")
                activeSession == null -> {
                    activeSession = session
                    sessionGeneration++
                }
                activeSession != session -> throw CancellationException("Session changed")
            }
            sessionGeneration
        }
    }

    private fun isCurrentSimulatorSession(session: AuthenticatedSession, generation: Long): Boolean =
        synchronized(sessionLock) {
            sessionStore?.isAuthorized(session) != false && activeSession == session && sessionGeneration == generation
        }

    override suspend fun logout(currentSession: AuthenticatedSession?) {
        synchronized(sessionLock) {
            activeSession = null
            sessionGeneration++
            simulatorSessionRevoked = true
            simulatorCredits.clear()
        }
        val failures = linkedSetOf<SessionCleanupFailure>()
        if (sessionStore != null) {
            sessionStore.cleanupStep(failures, SessionCleanupFailure.SESSION_DELETE_FAILED) { sessionStore.clear() }
            sessionStore.cleanupStep(failures, SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED) {
                cacheStore?.clearAll(preserveSubjectOverrides = true)
            }
        } else {
            cacheStore?.clearAll(preserveSubjectOverrides = true)
        }
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    override suspend fun loginWithCookies(
        studentNo: String,
        cookies: Map<String, String>,
    ): AuthenticatedSession {
        val authenticated = sessionStore?.establishSession(FakeData.session) ?: FakeData.session
        activateSession(authenticated)
        FakeScheduleRepository(cacheStore, { activeSession }, sessionStore).getLatestSchedule()
        return authenticated
    }
}

object FakeScheduleData {
    fun currentYearValue(today: LocalDate = LocalDate.now(SCHOOL_COURSE_ZONE)): String {
        val year = today.year - 1911 - if (today.monthValue < 8) 1 else 0
        val term = if (today.monthValue >= 8 || today.monthValue == 1) 1 else 2
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
        today: LocalDate = LocalDate.now(SCHOOL_COURSE_ZONE),
    ): ScheduleReport {
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val (academicYear, academicTerm) = requireNotNull(parseYearTermOrNull(yearValue))
        val semesterStart = if (academicTerm == "1") {
            LocalDate.of(academicYear.toInt() + 1911, 8, 1)
        } else {
            LocalDate.of(academicYear.toInt() + 1912, 2, 1)
        }
        val firstWeekStart = semesterStart.minusDays((semesterStart.dayOfWeek.value - 1).toLong())
        val weekNo = (java.time.temporal.ChronoUnit.WEEKS.between(firstWeekStart, weekStart) + 1)
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
                ScheduleItem(dayOfWeek = 4, period = 6, subjectName = "地科", teacherName = "何四", classroom = "高二30"),
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
            weekEndDate = weekStart.plusDays(6).toString(),
            items = weekItems,
            changes = compareScheduleItems(semesterReport.items, weekItems),
        )
    }
}
