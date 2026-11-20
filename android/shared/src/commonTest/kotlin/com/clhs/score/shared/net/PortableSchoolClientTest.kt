package com.clhs.score.shared.net

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.RecordSemester
import com.clhs.score.data.ScheduleFetchResult
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.SchoolException
import com.clhs.score.data.SchoolTransientException
import com.clhs.score.shared.time.parseLocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableSchoolClientTest {
    private val session = AuthenticatedSession("DEMO-001", "api-token", mapOf("ASP.NET_SessionId" to "abc"))
    private val tokenPage = """<input name="__RequestVerificationToken" value="schedule-token" />"""

    private fun TestScope.client(handler: (HttpRequest) -> HttpResponse): Pair<PortableSchoolClient, FakeHttpTransport> {
        val transport = FakeHttpTransport(handler)
        return schoolClient(transport) to transport
    }

    @Test
    fun loadStructureAndFetchGradesMapResponsesAndCarryPreparedCookies() = runTest {
        val (client, transport) = client { request ->
            when {
                request.url.endsWith("GetGradeCanQueryYearTermListByStudentNo") ->
                    response("""[{"DisplayText":"114學年度 上學期","Value":"114_1"}]""")
                request.url.endsWith("GetGradeCanQueryExamNoListByStudentNo") ->
                    response("""[{"DisplayText":"期末考","Value":"期末考"}]""")
                else -> response(SchoolFixtures.gradeJson)
            }
        }
        val structure = client.loadStructure(session)
        val report = client.fetchGrades(session, "114_1", "期末考")

        assertEquals("114學年度 上學期", structure.single().text)
        assertEquals("期末考", structure.single().exams.single().text)
        assertEquals("範例學生", report.studentInfo.studentName)
        assertEquals("國語文", report.subjects.single().subjectName)
        assertEquals(3, transport.requests.size)
        transport.requests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("ASP.NET_SessionId=abc", request.header("Cookie"))
            assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
            assertEquals("https://shcloud2.k12ea.gov.tw", request.header("Origin"))
            assertTrue(request.body.orEmpty().contains("__RequestVerificationToken=api-token"))
        }
        assertEquals("/CLHSTYC/ICampus/TutorShGrade/GetScoreForStudentExamContent", transport.path(2))
        assertTrue(transport.requests[2].body!!.contains("ExamNo=%E6%9C%9F%E6%9C%AB%E8%80%83"))
        assertTrue(transport.requests[2].header("Referer")!!.endsWith("page=%E6%88%90%E7%B8%BE%E6%9F%A5%E8%A9%A2"))
    }

    @Test
    fun malformedOrMismatchedGradePayloadIsRejected() = runTest {
        var body = """{"Result":{}}"""
        val (client, _) = client { response(body) }
        assertFailsWith<SchoolException> { client.fetchGrades(session, "114_1", "期末考") }
        body = "not json"
        assertFailsWith<SchoolException> { client.fetchGrades(session, "114_1", "期末考") }
        body = SchoolFixtures.gradeJson.replace("DEMO-001", "DEMO-OTHER")
        assertFailsWith<SchoolException> { client.fetchGrades(session, "114_1", "期末考") }
        body = SchoolFixtures.gradeJson
        assertEquals("DEMO-001", client.fetchGrades(session, "114_1", "期末考").studentInfo.studentNo)
    }

    @Test
    fun malformedYearIsRejectedBeforeAnyRequest() = runTest {
        val (client, transport) = client { response("{}") }
        assertFailsWith<SchoolException> { client.fetchGrades(session, "bad", "期末考") }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun httpAndLoginResponsesAreClassified() = runTest {
        var next = response(status = 401)
        val (client, _) = client { next }
        assertFailsWith<SchoolAuthenticationException> { client.loadStructure(session) }
        next = response(status = 403)
        assertFailsWith<SchoolAuthenticationException> { client.loadStructure(session) }
        next = response(status = 503)
        assertFailsWith<SchoolTransientException> { client.loadStructure(session) }
        next = response(status = 429)
        assertFailsWith<SchoolTransientException> { client.loadStructure(session) }
        next = response(status = 404)
        val other = assertFailsWith<SchoolException> { client.loadStructure(session) }
        assertFalse(other is SchoolAuthenticationException || other is SchoolTransientException)
        next = response("""<html><form><input name="LoginId"></form></html>""")
        assertFailsWith<SchoolAuthenticationException> { client.loadStructure(session) }
        next = response("<html>maintenance</html>")
        val html = assertFailsWith<SchoolException> { client.loadStructure(session) }
        assertFalse(html is SchoolAuthenticationException)
    }

    @Test
    fun transportFailuresBecomeTransientAndOversizedBodiesAreRejected() = runTest {
        var kind = HttpFailureKind.CONNECTION
        val (client, transport) = client { throw HttpTransportException(kind, "offline") }
        assertFailsWith<SchoolTransientException> { client.loadStructure(session) }
        kind = HttpFailureKind.TIMEOUT
        assertFailsWith<SchoolTransientException> { client.loadStructure(session) }
        kind = HttpFailureKind.TOO_LARGE
        val tooLarge = assertFailsWith<SchoolException> { client.loadStructure(session) }
        assertFalse(tooLarge is SchoolTransientException)
        assertEquals(MAX_SCHOOL_RESPONSE_BYTES, transport.requests.last().maxResponseBytes)
    }

    @Test
    fun redirectToLoginPageMeansExpiredSessionAndRedirectHopsUseTheJar() = runTest {
        val (client, transport) = client { request ->
            when {
                request.url.contains("CloudLogin") -> response("<html>login</html>")
                else -> response(
                    status = 302,
                    headers = listOf(HttpHeader("Location", "/CLHSTYC/Auth/Auth/CloudLogin?ReturnUrl=x")),
                    setCookies = listOf(cookie("hop", "1")),
                )
            }
        }
        assertFailsWith<SchoolAuthenticationException> { client.loadStructure(session) }
        assertEquals(2, transport.requests.size)
        assertEquals("GET", transport.requests[1].method)
        assertNull(transport.requests[1].body)
        assertEquals("ASP.NET_SessionId=abc; hop=1", transport.requests[1].header("Cookie"))
    }

    @Test
    fun redirectToAnotherHostOrPlainHttpIsRefusedWithoutSendingAnything() = runTest {
        var location = "https://elsewhere.example/landing"
        val (client, transport) = client { request ->
            if (request.url.startsWith("https://shcloud2")) response(status = 302, headers = listOf(HttpHeader("Location", location)))
            else response("[]")
        }
        assertFailsWith<SchoolException> { client.loadStructure(session) }
        assertEquals(1, transport.requests.size)
        location = "http://shcloud2.k12ea.gov.tw/CLHSTYC/x"
        assertFailsWith<SchoolException> { client.loadStructure(session) }
        assertEquals(2, transport.requests.size)
        location = "https://shcloud2.k12ea.gov.tw:8443/CLHSTYC/x"
        assertFailsWith<SchoolException> { client.loadStructure(session) }
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests.all { it.url.startsWith("https://shcloud2.k12ea.gov.tw/") })
    }

    @Test
    fun postRedirectedToGetDropsTheFormAndItsHeadersButA307KeepsThem() = runTest {
        var status = 302
        val (client, transport) = client { request ->
            if (request.url.endsWith("/landing")) response("[]")
            else response(status = status, headers = listOf(HttpHeader("Location", "/CLHSTYC/landing")))
        }
        client.loadStructure(session)
        val followUp = transport.requests[1]
        assertEquals("GET", followUp.method)
        assertNull(followUp.body)
        assertNull(followUp.header("Origin"))
        assertNull(followUp.header("X-Requested-With"))
        assertNull(followUp.header("Content-Type"))
        assertEquals("ASP.NET_SessionId=abc", followUp.header("Cookie"))

        status = 307
        transport.requests.clear()
        client.loadStructure(session)
        val replayed = transport.requests[1]
        assertEquals("POST", replayed.method)
        assertEquals(transport.requests[0].body, replayed.body)
        assertEquals(transport.requests[0].header("Origin"), replayed.header("Origin"))
        assertEquals("application/x-www-form-urlencoded", replayed.header("Content-Type"))
    }

    @Test
    fun cookiesWithLongerPathsAreSentFirst() = runTest {
        var sent = 0
        val (client, transport) = client {
            response(
                "[]",
                setCookies = if (sent++ > 0) emptyList() else listOf(
                    HttpSetCookie("token", "root", domain = null, path = "/", expiresAtEpochMillis = null, secure = true),
                    HttpSetCookie("token", "app", domain = null, path = "/CLHSTYC", expiresAtEpochMillis = null, secure = true),
                ),
            )
        }
        client.loadStructure(session)
        client.loadStructure(session)
        assertEquals("token=app; ASP.NET_SessionId=abc; token=root", transport.requests.last().header("Cookie"))
    }

    @Test
    fun responseCookiesAreStoredReplacedAndExpired() = runTest {
        var setCookies = listOf(cookie("rotating", "one"), cookie("ASP.NET_SessionId", "renewed"))
        val (client, transport) = client { response("[]", setCookies = setCookies) }
        client.loadStructure(session)
        setCookies = listOf(cookie("rotating", "gone", expiresAt = 1L))
        client.loadStructure(session)
        client.loadStructure(session)
        assertEquals("ASP.NET_SessionId=abc", transport.requests[0].header("Cookie"))
        assertEquals("rotating=one; ASP.NET_SessionId=renewed", transport.requests[1].header("Cookie"))
        assertEquals("ASP.NET_SessionId=renewed", transport.requests[2].header("Cookie"))
        assertEquals(mapOf("ASP.NET_SessionId" to "renewed"), client.cookieSnapshot())
    }

    @Test
    fun atMostFourSchoolRequestsAreOnTheWire() = runTest {
        val transport = FakeHttpTransport().apply { hold = true }
        val client = schoolClient(transport)
        val jobs = (1..10).map { launch { client.fetchGrades(session, "114_1", "exam-$it") } }
        runCurrent()
        assertEquals(4, transport.pending.size)
        var answered = 0
        while (answered < 10) {
            runCurrent()
            assertTrue(transport.pending.count { !it.finished } <= MAX_CONCURRENT_SCHOOL_REQUESTS)
            transport.pending[answered++].respond(response(SchoolFixtures.gradeJson))
        }
        jobs.forEach { it.join() }
        assertEquals(4, transport.maxInFlight)
        assertEquals(10, transport.requests.size)
    }

    @Test
    fun cancellingACallCancelsTheTransportCall() = runTest {
        val transport = FakeHttpTransport().apply { hold = true }
        val client = schoolClient(transport)
        val job = launch { client.loadStructure(session) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(transport.pending.single().cancelled)
    }

    @Test
    fun responseOfPreviousAccountIsDroppedAndCannotTouchTheNewJar() = runTest {
        val transport = FakeHttpTransport().apply { hold = true }
        val client = schoolClient(transport)
        val stale = async { runCatching { client.fetchGrades(session, "114_1", "期末考") } }
        runCurrent()
        val staleCall = transport.pending.single()

        val other = AuthenticatedSession("DEMO-002", "other-token", mapOf("ASP.NET_SessionId" to "other"))
        client.restoreSession(other)
        staleCall.respond(
            response(SchoolFixtures.gradeJson, setCookies = listOf(cookie("ASP.NET_SessionId", "stale"), cookie("leak", "1"))),
        )
        assertIs<CancellationException>(stale.await().exceptionOrNull())
        assertEquals(mapOf("ASP.NET_SessionId" to "other"), client.cookieSnapshot())

        transport.hold = false
        val fresh = FakeHttpTransport { response("[]") }
        // The next request of the new account carries only its own cookie.
        val probe = schoolClient(fresh).also { it.restoreSession(other) }
        probe.loadStructure(other)
        assertEquals("ASP.NET_SessionId=other", fresh.requests.single().header("Cookie"))
    }

    @Test
    fun redirectHopOfAStaleChainSendsNoCookiesOfTheNewAccount() = runTest {
        val transport = FakeHttpTransport().apply { hold = true }
        val client = schoolClient(transport)
        val stale = async { runCatching { client.loadStructure(session) } }
        runCurrent()
        client.restoreSession(AuthenticatedSession("DEMO-002", "other-token", mapOf("ASP.NET_SessionId" to "other")))
        transport.pending.single().respond(response(status = 302, headers = listOf(HttpHeader("Location", "/CLHSTYC/next"))))
        assertIs<CancellationException>(stale.await().exceptionOrNull())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun loginWithCookiesVerifiesTheGradesPageAndReturnsTheSynchronizedJar() = runTest {
        val (client, transport) = client { request ->
            if (request.method == "POST") response("[]")
            else response(
                """<form><input type="hidden" name="__RequestVerificationToken" value=" page-token "></form>""",
                setCookies = listOf(cookie("extra", "1")),
            )
        }
        val created = client.loginWithCookies("DEMO-001", mapOf("ASP.NET_SessionId" to "web", "" to "ignored"))
        assertEquals(AuthenticatedSession("DEMO-001", "page-token", mapOf("ASP.NET_SessionId" to "web", "extra" to "1")), created)
        assertEquals("GET", transport.requests.single().method)
        assertEquals("/CLHSTYC/ICampus/StudentInfo/Index", transport.path(0))
        assertEquals("ASP.NET_SessionId=web", transport.requests.single().header("Cookie"))
        assertTrue(transport.requests.single().header("Referer")!!.endsWith("/Auth/Auth/CloudLogin"))
        // The verified session is current: using it does not start another generation or resend login.
        client.loadStructure(created)
        assertEquals("ASP.NET_SessionId=web; extra=1", transport.requests.last().header("Cookie"))
    }

    @Test
    fun loginWithoutTokenIsAnAuthenticationFailureAndClearSessionEmptiesTheJar() = runTest {
        val (client, _) = client { response("<html>no token</html>") }
        assertFailsWith<SchoolAuthenticationException> { client.loginWithCookies("DEMO-001", mapOf("a" to "b")) }
        client.restoreSession(session)
        assertEquals(session.cookies, client.cookieSnapshot())
        client.clearSession()
        assertTrue(client.cookieSnapshot().isEmpty())
    }

    @Test
    fun studentInfoIsReadFromTheHomePageAndMustMatchTheSession() = runTest {
        var number = "DEMO-001"
        val (client, transport) = client {
            response("""<script>const userInfo = JSON.parse('{"ClassName":"高二 30 班","SeatNo":"12","No":"$number","UserName":"範例學生"}');</script>""")
        }
        val student = client.fetchStudentInfo(session)
        assertEquals("範例學生", student.studentName)
        assertEquals("高二 30 班", student.className)
        assertEquals("/CLHSTYC/ICampus/Home/Index2", transport.path(0))
        number = "DEMO-999"
        assertFailsWith<SchoolAuthenticationException> { client.fetchStudentInfo(session) }
    }

    @Test
    fun semesterScheduleFetchesTokenOnceAndPostsClassSelection() = runTest {
        val (client, transport) = client { request ->
            if (request.method == "GET") response(tokenPage) else response(SchoolFixtures.scheduleJson)
        }
        val result = client.querySchedule(session, "114_1", "114", "1", "230", ScheduleScope.SEMESTER)
        val report = assertIs<ScheduleFetchResult.Available>(result).report
        assertEquals(2, report.items.size)
        assertEquals("數學", report.items.first().subjectName)
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable", transport.path(0))
        assertEquals("/CLHSTYC/ClassTableV2/ClassTable/GetTimeTable", transport.path(1))
        val form = transport.requests[1].body!!
        assertTrue(form.contains("__RequestVerificationToken=schedule-token"))
        assertTrue(form.contains("ClassNo=230") && form.contains("TimetableType=Class"))
        assertEquals(1, Regex("(?:^|&)WeekNo=").findAll(form).count())
        client.querySchedule(session, "114_1", "114", "1", "", ScheduleScope.SEMESTER)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[2].body!!.contains("StudentNo=DEMO-001&TimetableType=Student"))
    }

    @Test
    fun currentWeekUsesTheWeekContainingTheTargetDateAndKeepsSemesterBaseline() = runTest {
        val (client, transport) = client { request ->
            when {
                request.method == "GET" -> response(tokenPage)
                request.url.endsWith("GetWeekNoList") -> response(SchoolFixtures.currentWeekJson)
                else -> response(SchoolFixtures.scheduleJson)
            }
        }
        val result = client.querySchedule(
            session, "115_4", "115", "4", "230", ScheduleScope.CURRENT_WEEK, parseLocalDate("2026-07-24"),
        )
        val available = assertIs<ScheduleFetchResult.Available>(result)
        assertEquals("4", available.report.weekNo)
        assertEquals("2026-07-19", available.report.weekStartDate)
        assertEquals(emptyList(), available.report.changes)
        assertEquals(ScheduleScope.SEMESTER, available.semesterBaseline?.scope)
        assertTrue(transport.requests[2].body!!.contains("WeekNo=4"))
        assertTrue(transport.requests[3].body!!.contains("WeekNo=&"))

        val unpublished = client.querySchedule(
            session, "115_4", "115", "4", "230", ScheduleScope.CURRENT_WEEK, parseLocalDate("2026-09-01"),
        )
        assertIs<ScheduleFetchResult.Unpublished>(unpublished)
    }

    @Test
    fun weekFailureKeepsSemesterFallbackButAuthenticationFailureIsNotHidden() = runTest {
        var weekStatus = 500
        val (client, _) = client { request ->
            when {
                request.method == "GET" -> response(tokenPage)
                request.url.endsWith("GetWeekNoList") -> response(status = weekStatus)
                else -> response(SchoolFixtures.scheduleJson)
            }
        }
        val failed = client.querySchedule(
            session, "115_4", "115", "4", "230", ScheduleScope.CURRENT_WEEK, parseLocalDate("2026-07-24"),
        )
        assertEquals(2, assertIs<ScheduleFetchResult.Failed>(failed).semesterFallback?.items?.size)
        weekStatus = 401
        assertFailsWith<SchoolAuthenticationException> {
            client.querySchedule(session, "115_4", "115", "4", "230", ScheduleScope.CURRENT_WEEK, parseLocalDate("2026-07-24"))
        }
    }

    @Test
    fun scheduleTokenIsRefetchedAfterTheSessionIsRestored() = runTest {
        var token = 0
        val (client, transport) = client { request ->
            if (request.method == "GET") response("""<input name="__RequestVerificationToken" value="token-${++token}">""")
            else response(SchoolFixtures.scheduleJson)
        }
        client.querySchedule(session, "114_1", "114", "1", "230", ScheduleScope.SEMESTER)
        client.restoreSession(session)
        client.querySchedule(session, "114_1", "114", "1", "230", ScheduleScope.SEMESTER)
        assertTrue(transport.requests[1].body!!.contains("token-1"))
        assertTrue(transport.requests[3].body!!.contains("token-2"))
    }

    @Test
    fun recordsPostNestedSemesterKeysWithTheCapturedSession() = runTest {
        fun success(result: String) = """{"Status":"Success","Result":$result}"""
        val (client, transport) = client { request ->
            when (request.url.substringAfterLast('/')) {
                "GetStudentYearTermDropdownList" -> response("""[{"Year":115,"Term":1,"YearTermInfoFullName":"115 上"}]""")
                "GetTutorShSheAbsentTermStatistics" ->
                    response(success("""{"TermStatisticsList":[{"AbsentType":"事假","AbsentNumber":1,"Order":-1}]}"""))
                "GetTutorShSheAbsentDetails" -> response(
                    success("""{"absentDetailList":[{"AbsentDateDisplayText":"115/09/21","AbsentText":"事假","AbsentCount":1,"SectionListDisplay":"二"}]}"""),
                )
                "GetMeritPenaltyDetailsList" -> response(success("[]"))
                else -> response(status = 401)
            }
        }
        val semester = client.fetchRecordSemesters(session).single()
        assertEquals(RecordSemester(115, 1, "115 上"), semester)
        val attendance = client.fetchAttendance(session, semester)
        assertEquals(1, attendance.summary.single().count)
        assertEquals("二", attendance.details.single().sections)
        assertTrue(client.fetchMerits(session, semester).isEmpty())
        val body = transport.requests.first { it.url.endsWith("GetTutorShSheAbsentDetails") }.body!!
        assertTrue(body.contains("searchItem%5BYear%5D=115&searchItem%5BTerm%5D=1"))
        assertTrue(body.contains("studentNo=DEMO-001&__RequestVerificationToken=api-token"))
        assertFailsWith<SchoolAuthenticationException> { client.fetchMeritHistory(session) }
    }
}
