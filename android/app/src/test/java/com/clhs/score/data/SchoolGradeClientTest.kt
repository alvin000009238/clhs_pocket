package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class SchoolGradeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SchoolGradeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SchoolGradeClient(baseUrl = server.url("/CLHSTYC/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }
    @Test
    fun loadStructureAndFetchGradesMapResponses() = runTest {
        val session = AuthenticatedSession(
            studentNo = "DEMO-001",
            apiToken = "api-token",
            cookies = mapOf("ASP.NET_SessionId" to "abc"),
        )
        server.enqueue(jsonResponse("""[{"DisplayText":"114學年度 上學期","Value":"114_1"}]"""))
        server.enqueue(jsonResponse("""[{"DisplayText":"期末考","Value":"期末考"}]"""))
        server.enqueue(jsonResponse(gradeJson))

        val structure = client.loadStructure(session)
        val report = client.fetchGrades(session, "114_1", "期末考")

        assertEquals("114學年度 上學期", structure.single().text)
        assertEquals("期末考", structure.single().exams.single().text)
        assertEquals("範例學生", report.studentInfo.studentName)
        assertEquals("DEMO-001", report.studentInfo.studentNo)
        assertEquals(114, report.examSummary?.year)
        assertEquals("下", report.examSummary?.termText)
        assertEquals("第二次段考", report.examSummary?.examName)
        assertEquals("國語文", report.subjects.single().subjectName)
        assertEquals(78.0, report.subjects.single().scoreValue, 0.001)
        assertEquals(80.0, report.standards.single().top ?: 0.0, 0.001)
    }

    @Test
    fun fetchStudentInfoMapsHomePageUserInfo() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <script>
                    const userInfo = JSON.parse('{"ClassNo":"230","ClassName":"高二 30 班","SeatNo":"12","No":"DEMO-001","UserName":"範例學生","Year":115,"Term":1}');
                </script>
                """.trimIndent(),
            ),
        )

        val student = client.fetchStudentInfo(testSession())

        assertEquals("DEMO-001", student.studentNo)
        assertEquals("範例學生", student.studentName)
        assertEquals("高二 30 班", student.className)
        assertEquals("12", student.seatNo)
        assertEquals("/CLHSTYC/ICampus/Home/Index2", server.takeRequest().path)
    }

    @Test
    fun loadStructureClassifiesUnauthorizedResponse() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching { client.loadStructure(testSession()) }.exceptionOrNull()

        assertTrue(error is SchoolAuthenticationException)
    }

    @Test
    fun loadStructureClassifiesLoginHtmlAsExpiredSession() = runTest {
        server.enqueue(
            htmlResponse(
                """<form action="/CLHSTYC/Auth/Auth/CloudLogin"><input name="LoginId" /></form>""",
            ),
        )

        val error = runCatching { client.loadStructure(testSession()) }.exceptionOrNull()

        assertTrue(error is SchoolAuthenticationException)
    }

    @Test
    fun loadStructureClassifiesServiceUnavailableAsTransient() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val error = runCatching { client.loadStructure(testSession()) }.exceptionOrNull()

        assertTrue(error is SchoolTransientException)
    }

    @Test
    fun defaultClientLimitsConcurrentSchoolRequests() {
        assertEquals(MAX_CONCURRENT_SCHOOL_REQUESTS, client.concurrentRequestLimit)
    }

    @Test
    fun cancellingLoadStructureCancelsTheHttpCall() = runBlocking {
        server.enqueue(jsonResponse("[]").setBodyDelay(5, TimeUnit.SECONDS))
        val load = launch(Dispatchers.Default) { client.loadStructure(testSession()) }
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)

        withTimeout(1.seconds) {
            load.cancelAndJoin()
        }

        assertTrue(load.isCancelled)
    }

    @Test
    fun loadStructureRejectsOversizedContentLength() = runTest {
        server.enqueue(jsonResponse("x".repeat(MAX_SCHOOL_RESPONSE_BYTES.toInt() + 1)))

        val error = runCatching { client.loadStructure(testSession()) }.exceptionOrNull()

        assertEquals("學校系統回應內容過大", error?.message)
    }

    @Test
    fun loadStructureRejectsOversizedChunkedBody() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setChunkedBody("x".repeat(MAX_SCHOOL_RESPONSE_BYTES.toInt() + 1), 1024),
        )

        val error = runCatching { client.loadStructure(testSession()) }.exceptionOrNull()

        assertEquals("學校系統回應內容過大", error?.message)
    }

    @Test
    fun loadStructureAcceptsBodyAtSizeLimit() = runTest {
        server.enqueue(
            jsonResponse("[]" + " ".repeat(MAX_SCHOOL_RESPONSE_BYTES.toInt() - 2)),
        )

        assertTrue(client.loadStructure(testSession()).isEmpty())
    }

    @Test
    fun strictYearTermParserRejectsMalformedValuesWithoutHardcodedYear() {
        assertEquals("114" to "1", parseYearTermOrNull("114_1"))
        assertEquals("115" to "4", parseYearTermOrNull("115_4"))
        assertEquals("999" to "2", parseYearTermOrNull("999_2"))
        listOf(null, "", "1141", "114_1_extra", "year_1", "114_term").forEach { value ->
            assertNull(value, parseYearTermOrNull(value))
        }
    }

    @Test
    fun fetchGradesRejectsMalformedYearBeforeNetworkRequest() = runTest {
        val error = runCatching {
            client.fetchGrades(testSession(), "bad", "期末考")
        }.exceptionOrNull()

        assertEquals("學期資料格式錯誤", error?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun parseScheduleItemsRecursesThroughNestedAliasesAndDeduplicatesSlots() {
        val items = parseScheduleItems(scheduleJson)

        assertEquals(2, items.size)
        assertEquals(
            ScheduleItem(
                dayOfWeek = 1,
                period = 2,
                subjectName = "數學",
                teacherName = "範例教師",
                classroom = "示範教室",
            ),
            items[0],
        )
        assertEquals(
            ScheduleItem(
                dayOfWeek = 3,
                period = 4,
                subjectName = "英語文",
                teacherName = "代理教師",
                classroom = "語言教室",
            ),
            items[1],
        )
    }

    @Test
    fun parseScheduleItemsRejectsInvalidWeekdays() {
        val items = parseScheduleItems(
            """
                [
                  {"SubjectName":"有效課程","WeekDay":1,"SectionSeq":1},
                  {"SubjectName":"錯誤課程","WeekDay":8,"SectionSeq":1}
                ]
            """.trimIndent(),
        )

        assertEquals(listOf(ScheduleItem(1, 1, "有效課程")), items)
    }

    @Test
    fun fetchSchedulePostsSelectionAndParsesRecursiveTimetableResponse() = runTest {
        val session = AuthenticatedSession(
            studentNo = "DEMO-001",
            apiToken = "api-token",
            cookies = mapOf("ASP.NET_SessionId" to "abc"),
        )
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(jsonResponse(scheduleJson))

        val report = client.fetchSchedule(
            session = session,
            yearValue = "114_1",
            year = "114",
            term = "1",
            classNo = "230",
            scope = ScheduleScope.SEMESTER,
        )

        assertEquals("114_1", report.yearTermValue)
        assertEquals(2, report.items.size)
        assertEquals("數學", report.items.first().subjectName)
        assertEquals(ScheduleScope.SEMESTER, report.scope)

        assertEquals("/CLHSTYC/ClassTableV2/ClassTable", server.takeRequest().path)
        val timetableRequest = server.takeRequest()
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetTimeTable", timetableRequest.path)
        val form = timetableRequest.body.readUtf8()
        assertTrue(form.contains("__RequestVerificationToken=schedule-token"))
        assertTrue(form.contains("Year=114"))
        assertTrue(form.contains("Term=1"))
        assertTrue(form.contains("ClassNo=230"))
        assertTrue(form.contains("TimetableType=Class"))
        assertEquals(1, Regex("(?:^|&)WeekNo=").findAll(form).count())
    }

    @Test
    fun parseScheduleWeeksRejectsInvalidValuesAndDates() {
        val weeks = parseScheduleWeekOptions(
            """
                [
                  {"Value":"4","Selected":false,"Item":{"IsSelected":true,"StartDate":"2026-07-19T00:00:00","EndDate":"2026-07-25T00:00:00"}},
                  {"Value":"0","Selected":true,"Item":{"StartDateDisplay":"2026-07-19","EndDateDisplay":"2026-07-25"}},
                  {"Value":"5","Selected":true,"Item":{"StartDateDisplay":"bad","EndDateDisplay":"2026-08-01"}}
                ]
            """.trimIndent(),
        )

        assertEquals(1, weeks.size)
        assertEquals("4", weeks.single().value)
    }

    @Test
    fun currentWeekReportIsOnlyValidInsideItsDateRange() {
        val report = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = emptyList(),
        )

        assertTrue(report.isValidOn(LocalDate.parse("2026-07-19")))
        assertTrue(report.isValidOn(LocalDate.parse("2026-07-25")))
        assertFalse(report.isValidOn(LocalDate.parse("2026-07-26")))
    }

    @Test
    fun currentWeekRefreshesAtLastClassEndInsteadOfWeekEndDate() {
        val report = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekNo = "4",
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = listOf(
                ScheduleItem(dayOfWeek = 4, period = 8, subjectName = "英文"),
                ScheduleItem(dayOfWeek = 5, period = 4, subjectName = "國文"),
            ),
        )

        assertFalse(report.shouldRefreshAt(LocalDateTime.parse("2026-07-24T11:59")))
        assertTrue(report.shouldRefreshAt(LocalDateTime.parse("2026-07-24T12:00")))
        assertEquals(
            LocalDate.parse("2026-07-26"),
            report.refreshTargetDateAt(LocalDateTime.parse("2026-07-24T12:00")),
        )
        assertEquals(
            LocalDateTime.parse("2026-07-24T12:00"),
            report.refreshAt(),
        )
    }

    @Test
    fun currentWeekRefreshBoundaryHandlesPastInvalidAndUnknownPeriods() {
        val unknownPeriod = ScheduleReport(
            "115_4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-07-19",
            weekEndDate = "2026-07-25",
            items = listOf(ScheduleItem(dayOfWeek = 5, period = 9, subjectName = "未知節次")),
        )
        val invalidDate = unknownPeriod.copy(weekStartDate = "invalid")
        val emptyWeek = unknownPeriod.copy(items = emptyList())
        val semester = unknownPeriod.copy(scope = ScheduleScope.SEMESTER)

        assertFalse(unknownPeriod.shouldRefreshAt(LocalDateTime.parse("2026-07-24T16:54")))
        assertTrue(unknownPeriod.shouldRefreshAt(LocalDateTime.parse("2026-07-24T16:55")))
        assertTrue(unknownPeriod.shouldRefreshAt(LocalDateTime.parse("2026-07-26T00:00")))
        assertFalse(emptyWeek.shouldRefreshAt(LocalDateTime.parse("2026-07-25T23:59")))
        assertTrue(emptyWeek.shouldRefreshAt(LocalDateTime.parse("2026-07-26T00:00")))
        assertEquals(
            LocalDate.parse("2026-07-26"),
            unknownPeriod.refreshTargetDateAt(LocalDateTime.parse("2026-07-26T00:00")),
        )
        assertTrue(invalidDate.shouldRefreshAt(LocalDateTime.parse("2026-07-20T08:00")))
        assertFalse(semester.shouldRefreshAt(LocalDateTime.parse("2026-07-26T00:00")))
    }

    @Test
    fun compareScheduleItemsFindsVisibleChangesAndIgnoresOrder() {
        val unchanged = ScheduleItem(1, 1, "國文", "甲師", "101")
        assertEquals(
            emptyList<ScheduleChange>(),
            compareScheduleItems(
                listOf(unchanged, ScheduleItem(3, 1, "數學")),
                listOf(ScheduleItem(3, 1, "數學"), unchanged),
            ),
        )

        val semester = listOf(
            unchanged,
            ScheduleItem(1, 2, "英文", "乙師", "102"),
            ScheduleItem(1, 3, "物理", "丙師", "103"),
            ScheduleItem(1, 4, "化學", "丁師", "104"),
            ScheduleItem(1, 5, "體育", "戊師", "操場"),
            ScheduleItem(2, 2, "歷史", "己師", "201"),
        )
        val week = listOf(
            ScheduleItem(2, 1, "生涯規劃", "庚師", "202"),
            unchanged,
            ScheduleItem(1, 2, "數學", "乙師", "102"),
            ScheduleItem(1, 3, "物理", "代理教師", "103"),
            ScheduleItem(1, 4, "化學", "丁師", "實驗室"),
            ScheduleItem(2, 2, "公民", "辛師", "203"),
        )

        val changes = compareScheduleItems(semester, week)

        assertEquals(
            listOf(
                ScheduleChangeType.MODIFIED,
                ScheduleChangeType.MODIFIED,
                ScheduleChangeType.MODIFIED,
                ScheduleChangeType.REMOVED,
                ScheduleChangeType.ADDED,
                ScheduleChangeType.MODIFIED,
            ),
            changes.map { it.type },
        )
        assertEquals(listOf(2, 3, 4, 5, 1, 2), changes.map { it.period })
    }

    @Test
    fun scheduleReportSerializationOmitsRawApiPayload() {
        val report = ScheduleReport(
            yearTermValue = "114_1",
            classNo = "230",
            scope = ScheduleScope.SEMESTER,
            items = parseScheduleItems(scheduleJson),
        )

        val serialized = SchoolJson.encodeToString(report)

        assertFalse(serialized.contains("\"rawData\""))
        assertFalse(serialized.contains("TeacherNameDisplay"))
    }

    @Test
    fun scheduleUsesEveryColorBeforeRepeatingOne() {
        val subjects = predefinedColors.indices.map { index ->
            "科目${index.toString().padStart(2, '0')}"
        }
        val colors = getSubjectColors(subjects)
        val colorsWithRepeat = getSubjectColors(subjects + "科目30")

        assertEquals(predefinedColors.size, colors.values.toSet().size)
        assertEquals(colors.getValue("科目00"), colorsWithRepeat.getValue("科目30"))
        assertEquals(darkPredefinedColors.size, getSubjectColors(subjects, isDarkTheme = true).values.toSet().size)
    }

    @Test
    fun subjectPalettesMatchTheFixedAccentColors() {
        assertEquals(
            listOf(
                0xFF984357L, 0xFF699C5EL, 0xFF5D70C3L, 0xFF984A26L, 0xFF31A18AL,
                0xFF8D60B2L, 0xFF805C0EL, 0xFF209DB1L, 0xFFAB558AL, 0xFF616A0DL,
                0xFF5591C9L, 0xFFB75357L, 0xFF217541L, 0xFF8483CAL, 0xFFB06018L,
                0xFF0B726CL, 0xFFA877B4L, 0xFF8F7412L, 0xFF016E8BL, 0xFFBE708EL,
                0xFF618524L, 0xFF3A62A4L, 0xFFC37363L, 0xFF128C64L, 0xFF6A539DL,
                0xFFB67F40L, 0xFF08888EL, 0xFF884880L, 0xFF978F3CL, 0xFF1481B3L,
            ),
            predefinedColors,
        )
        assertEquals(
            listOf(
                0xFFDD8898L, 0xFFAADA9FL, 0xFFA3B7FEL, 0xFFDC8F6DL, 0xFF83DFC8L,
                0xFFCFA6F4L, 0xFFC79D53L, 0xFF7BDBEFL, 0xFFEF9CCDL, 0xFFA1AD5DL,
                0xFF9BCDFEL, 0xFFFA9597L, 0xFF72BD8DL, 0xFFC3C2FFL, 0xFFF3A16AL,
                0xFF62C1BAL, 0xFFE6B7F0L, 0xFFD2B854L, 0xFF56BED9L, 0xFFF5AECAL,
                0xFFA9CD6BL, 0xFF83A8E8L, 0xFFF5AE9FL, 0xFF65CBA5L, 0xFFA998DFL,
                0xFFF0B979L, 0xFF58CDD1L, 0xFFCB8CC2L, 0xFFD6CE80L, 0xFF65C1EAL,
            ),
            darkPredefinedColors,
        )
    }

    @Test
    fun currentWeekScheduleLoadsWeekContainingTargetDateAndPostsItOnce() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", mapOf("ASP.NET_SessionId" to "abc"))
        var cachedSemesterReport: ScheduleReport? = null
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(jsonResponse(currentWeekJson))
        server.enqueue(jsonResponse(scheduleJson))
        server.enqueue(jsonResponse(scheduleJson))

        val report = client.fetchSchedule(
            session,
            "115_4",
            "115",
            "4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            targetDate = LocalDate.parse("2026-07-24"),
            onSemesterReport = { cachedSemesterReport = it },
        )

        assertEquals(ScheduleScope.CURRENT_WEEK, report.scope)
        assertEquals("4", report.weekNo)
        assertEquals("2026-07-19", report.weekStartDate)
        assertEquals("2026-07-25", report.weekEndDate)
        server.takeRequest()
        val weekRequest = server.takeRequest()
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetWeekNoList", weekRequest.path)
        assertTrue(weekRequest.body.readUtf8().contains("Year=115"))
        val timetableRequest = server.takeRequest()
        val form = timetableRequest.body.readUtf8()
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetTimeTable", timetableRequest.path)
        assertTrue(form.contains("WeekNo=4"))
        assertEquals(1, Regex("(?:^|&)WeekNo=").findAll(form).count())
        val semesterRequest = server.takeRequest()
        val semesterForm = semesterRequest.body.readUtf8()
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetTimeTable", semesterRequest.path)
        assertTrue(semesterForm.contains("WeekNo=&"))
        assertEquals(1, Regex("(?:^|&)WeekNo=").findAll(semesterForm).count())
        assertEquals(emptyList<ScheduleChange>(), report.changes)
        assertEquals(ScheduleScope.SEMESTER, cachedSemesterReport?.scope)
        assertEquals(report.items, cachedSemesterReport?.items)
    }

    @Test
    fun currentWeekComparisonFailureKeepsWeekReport() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", emptyMap())
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(jsonResponse(currentWeekJson))
        server.enqueue(jsonResponse(scheduleJson))
        server.enqueue(MockResponse().setResponseCode(500))

        val report = client.fetchSchedule(
            session,
            "115_4",
            "115",
            "4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            targetDate = LocalDate.parse("2026-07-24"),
        )

        assertEquals(ScheduleScope.CURRENT_WEEK, report.scope)
        assertEquals(2, report.items.size)
        assertEquals(null, report.changes)
        server.takeRequest()
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("WeekNo=4"))
        assertTrue(server.takeRequest().body.readUtf8().contains("WeekNo=&"))
    }

    @Test
    fun currentWeekComparisonAuthenticationFailureIsNotHidden() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", emptyMap())
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(jsonResponse(currentWeekJson))
        server.enqueue(jsonResponse(scheduleJson))
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching {
            client.fetchSchedule(
                session,
                "115_4",
                "115",
                "4",
                "230",
                ScheduleScope.CURRENT_WEEK,
                targetDate = LocalDate.parse("2026-07-24"),
            )
        }.exceptionOrNull()

        assertTrue(error is SchoolAuthenticationException)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun currentWeekIgnoresFalseSelectedFlagsWhenDateMatches() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", emptyMap())
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(jsonResponse(currentWeekJson.replace("\"Selected\": true", "\"Selected\": false")))
        server.enqueue(jsonResponse(scheduleJson))
        server.enqueue(jsonResponse(scheduleJson))

        val report = client.fetchSchedule(
            session,
            "115_4",
            "115",
            "4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            targetDate = LocalDate.parse("2026-07-24"),
        )

        assertEquals(ScheduleScope.CURRENT_WEEK, report.scope)
        assertEquals("4", report.weekNo)
        server.takeRequest()
        server.takeRequest()
        val form = server.takeRequest().body.readUtf8()
        assertEquals(1, Regex("(?:^|&)WeekNo=").findAll(form).count())
        assertTrue(form.contains("WeekNo=4"))
    }

    @Test
    fun currentWeekUsesTargetDateWhenServerSelectedFlagIsStale() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", emptyMap())
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(jsonResponse(currentWeekJson))
        server.enqueue(jsonResponse(scheduleJson))
        server.enqueue(jsonResponse(scheduleJson))

        val report = client.fetchSchedule(
            session,
            "115_4",
            "115",
            "4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            targetDate = LocalDate.parse("2026-07-27"),
        )

        assertEquals(ScheduleScope.CURRENT_WEEK, report.scope)
        assertEquals("5", report.weekNo)
        server.takeRequest()
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("WeekNo=5"))
    }

    @Test
    fun currentWeekApiFailureFallsBackToSemester() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", emptyMap())
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(jsonResponse(scheduleJson))

        val report = client.fetchSchedule(
            session,
            "115_4",
            "115",
            "4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            targetDate = LocalDate.parse("2026-07-24"),
        )

        assertEquals(ScheduleScope.SEMESTER, report.scope)
        server.takeRequest()
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetWeekNoList", server.takeRequest().path)
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetTimeTable", server.takeRequest().path)
    }

    @Test
    fun currentWeekAuthenticationFailureDoesNotFallBackWithAnotherRequest() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", emptyMap())
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(jsonResponse(scheduleJson))

        val error = runCatching {
            client.fetchSchedule(
                session,
                "115_4",
                "115",
                "4",
                "230",
                ScheduleScope.CURRENT_WEEK,
                targetDate = LocalDate.parse("2026-07-24"),
            )
        }.exceptionOrNull()

        assertTrue(error is SchoolAuthenticationException)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun currentWeekTimetableFailureFallsBackToSemester() = runTest {
        val session = AuthenticatedSession("DEMO-001", "api-token", emptyMap())
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token" />"""))
        server.enqueue(jsonResponse(currentWeekJson))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(jsonResponse(scheduleJson))

        val report = client.fetchSchedule(
            session,
            "115_4",
            "115",
            "4",
            "230",
            ScheduleScope.CURRENT_WEEK,
            targetDate = LocalDate.parse("2026-07-24"),
        )

        assertEquals(ScheduleScope.SEMESTER, report.scope)
        server.takeRequest()
        server.takeRequest()
        val failedWeeklyForm = server.takeRequest().body.readUtf8()
        val semesterForm = server.takeRequest().body.readUtf8()
        assertTrue(failedWeeklyForm.contains("WeekNo=4"))
        assertTrue(semesterForm.contains("WeekNo=&"))
    }

    @Test
    fun fetchScheduleRefreshesTokenAfterSessionRestore() = runTest {
        val firstSession = AuthenticatedSession(
            studentNo = "DEMO-001",
            apiToken = "api-token",
            cookies = mapOf("ASP.NET_SessionId" to "abc"),
        )
        val restoredSession = AuthenticatedSession(
            studentNo = "DEMO-002",
            apiToken = "api-token-2",
            cookies = mapOf("ASP.NET_SessionId" to "def"),
        )
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token-1" />"""))
        server.enqueue(jsonResponse(scheduleJson))
        server.enqueue(htmlResponse("""<input name="__RequestVerificationToken" value="schedule-token-2" />"""))
        server.enqueue(jsonResponse(scheduleJson))

        client.fetchSchedule(firstSession, "114_1", "114", "1", "230", ScheduleScope.SEMESTER)
        client.restoreSession(restoredSession)
        client.fetchSchedule(restoredSession, "114_1", "114", "1", "230", ScheduleScope.SEMESTER)

        server.takeRequest()
        server.takeRequest()
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable", server.takeRequest().path)
        val secondTimetableRequest = server.takeRequest()
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetTimeTable", secondTimetableRequest.path)
        assertTrue(secondTimetableRequest.body.readUtf8().contains("__RequestVerificationToken=schedule-token-2"))
    }

    @Test
    fun clearSessionClearsCookieJar() {
        val jar = SchoolCookieJar()
        val clientWithJar = SchoolGradeClient(
            baseUrl = server.url("/CLHSTYC/").toString(),
            cookieJar = jar,
        )
        jar.replace(mapOf("ASP.NET_SessionId" to "abc"), domain = server.url("/").host)

        assertFalse(jar.snapshot().isEmpty())
        clientWithJar.clearSession()
        assertTrue(jar.snapshot().isEmpty())
    }

    private fun htmlResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun testSession() = AuthenticatedSession(
        studentNo = "DEMO-001",
        apiToken = "api-token",
        cookies = mapOf("ASP.NET_SessionId" to "abc"),
    )

    private val gradeJson = """
        {
          "Message": "",
          "Result": {
            "StudentNo": "DEMO-001",
            "StudentName": "範例學生",
            "StudentClassName": "示範班級",
            "StudentSeatNo": "00",
            "Show班級排名": true,
            "Show班級排名人數": true,
            "Show類組排名": true,
            "Show類組排名人數": true,
            "ExamItem": {
              "Year": 114,
              "Term": "上",
              "ExamName": "期末考",
              "ClassRank": 15,
              "ClassCount": 37,
              "類組排名": 78,
              "類組排名Count": 221
            },
            "SubjectExamInfoList": [
              {
                "Year": 114,
                "Term": 2,
                "ExamName": "第二次段考",
                "YearTermItem": {
                  "Year": 114,
                  "Term": 2,
                  "TermText": "下"
                },
                "SubjectName": "國語文",
                "Score": 78,
                "ScoreDisplay": "78.00",
                "ClassAVGScore": 70.11,
                "ClassAVGScoreDisplay": "70.11",
                "ClassRank": 6,
                "ClassRankCount": 37,
                "YearTermDisplay": "114學年度 上學期"
              }
            ],
            "成績五標List": [
              {
                "SubjectName": "國語文",
                "頂標": 80,
                "前標": 75,
                "均標": 70,
                "後標": 60,
                "底標": 50,
                "標準差": 12,
                "大於90Count": 1,
                "大於80Count": 3,
                "大於70Count": 10,
                "大於60Count": 12,
                "大於50Count": 5,
                "大於40Count": 2,
                "大於30Count": 1,
                "大於20Count": 1,
                "大於10Count": 1,
                "大於0Count": 1
              }
            ]
          }
        }
    """.trimIndent()

    private val scheduleJson = """
        {
          "payload": {
            "weeks": [
              {
                "items": [
                  {
                    "SubjectName": "數學",
                    "WeekDay": "1",
                    "SectionSeq": "2",
                    "TeacherNameDisplay": "範例教師",
                    "ClassroomName": "示範教室"
                  },
                  {
                    "SubjectName": "重複資料",
                    "WeekDay": "1",
                    "SectionSeq": "2"
                  },
                  {
                    "CourseName": "英語文",
                    "DayOfWeek": "3",
                    "Period": "4",
                    "FirstTeacherName": "代理教師",
                    "ClassroomDisplay": "語言教室"
                  },
                  {
                    "SubjectDisplay": "",
                    "DayOfWeek": "5",
                    "Period": "6"
                  }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private val currentWeekJson = """
        [
          {
            "DisplayText": "第4週 (2026-07-19~2026-07-25)",
            "Value": "4",
            "Selected": true,
            "Item": {
              "WeekNo": "4",
              "StartDateDisplay": "2026-07-19",
              "EndDateDisplay": "2026-07-25",
              "IsSelected": false
            }
          },
          {
            "DisplayText": "第5週 (2026-07-26~2026-08-01)",
            "Value": "5",
            "Selected": false,
            "Item": {
              "WeekNoInt": 5,
              "WeekNo": "5",
              "StartDateDisplay": "2026-07-26",
              "EndDateDisplay": "2026-08-01",
              "IsSelected": false
            }
          }
        ]
    """.trimIndent()
}
