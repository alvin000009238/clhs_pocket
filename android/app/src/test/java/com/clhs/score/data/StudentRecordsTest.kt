package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class StudentRecordsTest {
    @Test
    fun parsesOfficialSemesterAndCategoryContractsWithoutConvertingCounts() {
        val semesters = StudentRecordsParser.semesters("""[{"Year":114,"Term":2,"YearTermInfoFullName":"114 下"},{"Year":"115","Term":"1","YearTermInfoFullName":"115 上"}]""")
        assertEquals(listOf(115, 114), semesters.map { it.year })
        val history = StudentRecordsParser.history(success("""[{"AbsentType":"事假","FirstGradeFirstTerm":2,"SecondGradeFirstTerm":null,"Order":1}]"""), "AbsentType").single()
        assertEquals("事假", history.category)
        assertEquals(2, history.semesterCounts.first())
        assertNull(history.semesterCounts[1])
        val merit = StudentRecordsParser.merits(success("""[{"MertiPenaltyText":"嘉獎","MertiPenaltyType":"獎","MertiPenaltyCount":2,"MertipenaltyReason":"虛構服務紀錄","MeritPenaltyDateDisplayString":"115/09/21","MeritPenaltyCheckDateDisplayString":"115/09/22","MertipenaltyCleanDateDisplayString":null,"MertipenaltyYearTerm":"115上"}]""")).single()
        assertEquals("嘉獎", merit.category)
        assertEquals("獎", merit.group)
        assertEquals(2, merit.count)
        assertEquals("", merit.cleanDate)
    }

    @Test
    fun sortsAttendanceAndHistoryBySignedOrderWithoutChangingCounts() {
        val summary = StudentRecordsParser.attendanceSummary(success("""[
            {"AbsentType":"事假","AbsentNumber":2,"Order":1},
            {"AbsentType":"病假","AbsentNumber":1,"Order":0},
            {"AbsentType":"全部","AbsentNumber":0,"Order":-1}
        ]"""))
        assertEquals(listOf(
            RecordCategoryCount("全部", 0),
            RecordCategoryCount("病假", 1),
            RecordCategoryCount("事假", 2),
        ), summary)
        val history = StudentRecordsParser.history(success("""[
            {"AbsentType":"事假","FirstGradeFirstTerm":2,"Order":1},
            {"AbsentType":"病假","FirstGradeFirstTerm":1,"Order":0},
            {"AbsentType":"全部","FirstGradeFirstTerm":0,"Order":-1}
        ]"""), "AbsentType")
        assertEquals(listOf("全部", "病假", "事假"), history.map { it.category })
        assertEquals(listOf(0, 1, 2), history.map { it.semesterCounts.first() })
        assertTrue(runCatching {
            StudentRecordsParser.history(success("""[{"AbsentType":"事假","FirstGradeFirstTerm":-1,"Order":-1}]"""), "AbsentType")
        }.exceptionOrNull() is SchoolException)
    }

    @Test
    fun usesActualAbsentSectionsAndPreservesRemarksInsteadOfAllSectionHeaders() {
        val detail = StudentRecordsParser.attendanceDetails(success("""{"th":["一","二","三"],"absentDetailList":[{"AbsentDateDisplayText":"115/09/21","AbsentText":"事假","AbsentCount":1,"SectionList":["一","二","三"],"SectionListDisplay":"二","AbsentSectionList":[{"SectionName":"二","AbsentText":"事假","Remark":"已核准"}]}]}""")).single()
        assertEquals("二；二 已核准", detail.sections)
        assertFalse(detail.sections.contains("三"))
        val fallback = StudentRecordsParser.attendanceDetails(success("""{"absentDetailList":[{"AbsentDateDisplayText":"115/09/21","AbsentText":"事假","AbsentCount":1,"SectionListDisplay":"","AbsentSectionList":[{"SectionName":"二","AbsentText":"事假","Remark":"已核准"}]}]}""")).single()
        assertEquals("二 事假 已核准", fallback.sections)
    }

    @Test
    fun rejectsMalformedOrFailedPayloadsButAcceptsExplicitEmptyResults() {
        for (json in listOf("{}", "[]", "bad json", """{"Status":"Success","Result":null}""", """{"Status":"Fail","Result":[]}""", success("{}"), success("""[{"AbsentType":"事假","AbsentNumber":-1}]"""))) {
            val error = runCatching { StudentRecordsParser.attendanceSummary(json) }.exceptionOrNull()
            assertTrue("Must reject $json", error is SchoolException)
            assertFalse(error is SchoolAuthenticationException)
        }
        assertTrue(StudentRecordsParser.attendanceSummary(success("[]")).isEmpty())
        assertTrue(StudentRecordsParser.merits(success("[]")).isEmpty())
        assertTrue(StudentRecordsParser.attendanceDetails(success("""{"absentDetailList":[]}""")).isEmpty())
        assertTrue(runCatching { StudentRecordsParser.semesters("""[{"Year":115,"Term":3,"YearTermInfoFullName":"invalid"}]""") }.exceptionOrNull() is SchoolException)
    }

    @Test
    fun postsNestedSemesterKeysAndCapturedSessionAndClassifiesUnauthorized() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = SchoolGradeClient(server.url("/CLHSTYC/").toString())
            val session = AuthenticatedSession("DEMO-001", "fixture-token", mapOf("ASP.NET_SessionId" to "fixture-cookie"))
            server.enqueue(MockResponse().setBody(success("[]")))
            assertTrue(client.fetchMerits(session, RecordSemester(115, 1, "115上")).isEmpty())
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/CLHSTYC/ICampus/TutorShSheMoralMeritPenalty/GetMeritPenaltyDetailsList", request.path)
            assertEquals("POST", request.method)
            val fields = URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertTrue(fields.contains("studentNo=DEMO-001"))
            assertTrue(fields.contains("__RequestVerificationToken=fixture-token"))
            assertTrue(fields.contains("searchItem[Year]=115"))
            assertTrue(fields.contains("searchItem[Term]=1"))
            assertTrue(request.getHeader("Cookie").orEmpty().contains("fixture-cookie"))
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { client.fetchMeritHistory(session) }.exceptionOrNull() is SchoolAuthenticationException)
        }
    }

    @Test
    fun loadsAttendanceSummaryAndActualDetailsFromTheirIndependentOfficialEndpoints() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    when (request.path?.substringAfterLast('/')) {
                        "GetStudentYearTermDropdownList" -> MockResponse().setBody("""[{"Year":115,"Term":1,"YearTermInfoFullName":"115 上"}]""")
                        "GetTutorShSheAbsentTermStatistics" -> MockResponse().setBody(success("""[{"AbsentType":"事假","AbsentNumber":1,"Order":-1}]"""))
                        "GetTutorShSheAbsentDetails" -> MockResponse().setBody(success("""{"absentDetailList":[{"AbsentDateDisplayText":"115/09/21","AbsentText":"事假","AbsentCount":1,"SectionListDisplay":"二"}]}"""))
                        else -> MockResponse().setResponseCode(404)
                    }
            }
            server.start()
            val client = SchoolGradeClient(server.url("/CLHSTYC/").toString())
            val session = AuthenticatedSession("DEMO-001", "fixture-token", emptyMap())
            val semester = client.fetchRecordSemesters(session).single()
            val attendance = client.fetchAttendance(session, semester)
            assertEquals(listOf(RecordCategoryCount("事假", 1)), attendance.summary)
            assertEquals("二", attendance.details.single().sections)
            assertEquals("事假", attendance.details.single().category)
        }
    }

    @Test
    fun oldUnauthorizedResponseCannotExpireReplacementSession() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = SchoolGradeClient(server.url("/CLHSTYC/").toString())
            val session = AuthenticatedSession("DEMO-001", "old-token", emptyMap())
            server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(1, TimeUnit.SECONDS))
            val old = async(Dispatchers.IO) { runCatching { client.fetchMeritHistory(session) }.exceptionOrNull() }
            requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            client.restoreSession(session.copy(apiToken = "new-token"))
            assertTrue(old.await() is kotlinx.coroutines.CancellationException)
        }
    }

    private fun success(result: String) = """{"Status":"Success","Result":$result}"""
}
