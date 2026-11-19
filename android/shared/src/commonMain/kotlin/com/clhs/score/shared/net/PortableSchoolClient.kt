package com.clhs.score.shared.net

import com.clhs.score.data.AttendanceRecord
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.ExamOption
import com.clhs.score.data.GradeReport
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordHistoryStatistic
import com.clhs.score.data.RecordSemester
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleFetchResult
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleWeekOption
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.SchoolException
import com.clhs.score.data.SchoolJson
import com.clhs.score.data.SchoolTransientException
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.StudentRecordsParser
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.asObjectOrNull
import com.clhs.score.data.compareScheduleItems
import com.clhs.score.data.parseGradeReport
import com.clhs.score.data.parseOptions
import com.clhs.score.data.parseScheduleItemsOrNull
import com.clhs.score.data.parseScheduleWeekOptionsOrNull
import com.clhs.score.data.parseYearTermOrNull
import com.clhs.score.data.selectScheduleWeek
import com.clhs.score.data.string
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.currentEpochMillis
import com.clhs.score.shared.time.schoolToday
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MAX_SCHOOL_RESPONSE_BYTES = 8L * 1024 * 1024
const val MAX_CONCURRENT_SCHOOL_REQUESTS = 4
const val SCHOOL_HOST = "shcloud2.k12ea.gov.tw"
const val SCHOOL_BASE_URL = "https://$SCHOOL_HOST/CLHSTYC"
const val SCHOOL_LOGIN_URL = "$SCHOOL_BASE_URL/Auth/Auth/CloudLogin"
const val SCHOOL_HOME_URL = "$SCHOOL_BASE_URL/ICampus/Home/Index2"

/**
 * Authenticated school client: the portable counterpart of Android's `SchoolGradeClient`.
 *
 * Invariants kept from Android: every call goes through [SchoolSessionState.prepare] (or an explicit
 * login/restore), each request chain is pinned to one generation, at most
 * [MAX_CONCURRENT_SCHOOL_REQUESTS] requests are on the wire, and a response that arrives after the
 * authorization changed is dropped with a [CancellationException] instead of being returned.
 */
class PortableSchoolClient internal constructor(
    private val transport: HttpTransport,
    baseUrl: String,
    private val now: () -> Long,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor(transport: HttpTransport, baseUrl: String = SCHOOL_BASE_URL) :
        this(transport, baseUrl, ::currentEpochMillis, Dispatchers.Default)

    private val baseUrl: PortableUrl = requireNotNull(PortableUrl.parse(baseUrl.trimEnd('/') + "/")) { "Invalid base URL" }
    private val origin: String = this.baseUrl.origin
    private val state = SchoolSessionState()
    private val permits = Semaphore(MAX_CONCURRENT_SCHOOL_REQUESTS)

    val concurrentRequestLimit: Int get() = MAX_CONCURRENT_SCHOOL_REQUESTS

    private fun prepareSession(session: AuthenticatedSession): Long = state.prepare(session, baseUrl.host)

    suspend fun loadStructure(session: AuthenticatedSession): List<YearTermOption> {
        val generation = prepareSession(session)
        val yearTerms = postOptions(
            generation = generation,
            path = "ICampus/CommonData/GetGradeCanQueryYearTermListByStudentNo",
            referer = gradesPageUrl(),
            form = listOf(
                "searchType" to "各次考試單科成績",
                "studentNo" to session.studentNo,
                "__RequestVerificationToken" to session.apiToken,
            ),
        )
        return coroutineScope {
            yearTerms.map { (text, value) ->
                async { YearTermOption(text = text, value = value, exams = loadExams(session, value, generation)) }
            }.awaitAll()
        }
    }

    suspend fun fetchGrades(session: AuthenticatedSession, yearValue: String, examValue: String): GradeReport {
        val generation = prepareSession(session)
        val (year, term) = parseYearTermOrNull(yearValue) ?: throw SchoolException("學期資料格式錯誤")
        val body = postForm(
            generation = generation,
            path = "ICampus/TutorShGrade/GetScoreForStudentExamContent",
            referer = gradesPageUrl(),
            form = listOf(
                "StudentNo" to session.studentNo,
                "SearchType" to "單次考試所有成績",
                "__RequestVerificationToken" to session.apiToken,
                "Year" to year,
                "Term" to term,
                "ExamNo" to examValue,
            ),
        )
        return parseSchoolPayload { parseGradeReport(body) }.also { report ->
            if (report.studentInfo.studentNo.isNotBlank() && report.studentInfo.studentNo != session.studentNo) {
                throw SchoolException("學校系統回傳的成績帳號不一致")
            }
        }
    }

    suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo {
        val generation = prepareSession(session)
        val body = executeBody("GET", homePageUrl(), defaultHeaders(homePageUrl().toString()), null, generation)
        val rawUserInfo = USER_INFO_PATTERN.find(body)?.groupValues?.get(1)
            ?: throw SchoolException("學校系統未提供帳號資訊")
        val userInfo = runCatching { SchoolJson.parseToJsonElement(rawUserInfo).asObjectOrNull() }.getOrNull()
            ?: throw SchoolException("學校系統帳號資訊格式錯誤")
        val studentNo = userInfo.string("No")
        if (studentNo != session.studentNo) throw SchoolAuthenticationException("登入帳號資訊不一致")
        return StudentInfo(
            studentNo = studentNo,
            studentName = userInfo.string("UserName"),
            className = userInfo.string("ClassName"),
            seatNo = userInfo.string("SeatNo"),
            updatedAt = "",
            showClassRank = false,
            showClassRankCount = false,
            showCategoryRank = false,
            showCategoryRankCount = false,
        )
    }

    suspend fun fetchRecordSemesters(session: AuthenticatedSession): List<RecordSemester> {
        val generation = prepareSession(session)
        val body = recordRequest(session, generation, "CommonData/GetStudentYearTermDropdownList")
        return parseSchoolPayload { StudentRecordsParser.semesters(body) }
    }

    suspend fun fetchAttendanceHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> {
        val generation = prepareSession(session)
        val body = recordRequest(session, generation, "TutorShSheAbsentStatistics/GetTutorShSheAbsentStatistics")
        return parseSchoolPayload { StudentRecordsParser.history(body, "AbsentType") }
    }

    suspend fun fetchMeritHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> {
        val generation = prepareSession(session)
        val body = recordRequest(session, generation, "TutorShSheMoralMeritPenalty/GetMeritPenaltyStatisticsList")
        return parseSchoolPayload { StudentRecordsParser.history(body, "MeritPenaltyType") }
    }

    suspend fun fetchAttendance(session: AuthenticatedSession, semester: RecordSemester): AttendanceRecord {
        val generation = prepareSession(session)
        return coroutineScope {
            val summary = async {
                val body = recordRequest(
                    session, generation, "TutorShSheAbsentStatistics/GetTutorShSheAbsentTermStatistics", semester,
                )
                parseSchoolPayload { StudentRecordsParser.attendanceSummary(body) }
            }
            val details = async {
                val body = recordRequest(
                    session, generation, "TutorShSheAbsentStatistics/GetTutorShSheAbsentDetails", semester,
                )
                parseSchoolPayload { StudentRecordsParser.attendanceDetails(body) }
            }
            AttendanceRecord(summary.await(), details.await())
        }
    }

    suspend fun fetchMerits(session: AuthenticatedSession, semester: RecordSemester): List<MeritRecord> {
        val generation = prepareSession(session)
        val body = recordRequest(
            session, generation, "TutorShSheMoralMeritPenalty/GetMeritPenaltyDetailsList", semester,
        )
        return parseSchoolPayload { StudentRecordsParser.merits(body) }
    }

    private suspend fun recordRequest(
        session: AuthenticatedSession,
        generation: Long,
        path: String,
        semester: RecordSemester? = null,
    ): String = postForm(
        generation = generation,
        path = "ICampus/$path",
        referer = resolve("ICampus/StudentInfo/Index"),
        form = buildList {
            add("studentNo" to session.studentNo)
            add("__RequestVerificationToken" to session.apiToken)
            if (semester != null) {
                add("searchItem[Year]" to semester.year.toString())
                add("searchItem[Term]" to semester.term.toString())
            }
        },
    )

    private suspend fun getSchedulePageToken(generation: Long): String {
        requireGeneration(generation)
        state.scheduleToken(generation)?.let { return it }
        val page = executeBody(
            "GET", resolve("ClassTableV2/ClassTable"), defaultHeaders(homePageUrl().toString()), null, generation,
        )
        val token = findNamedInputValue(page, TOKEN_FIELD) ?: throw SchoolAuthenticationException()
        if (!state.setScheduleToken(generation, token)) throw CancellationException("Session changed")
        return token
    }

    private fun scheduleHeaders(): List<HttpHeader> =
        defaultHeaders(resolve("ClassTableV2/ClassTable").toString()) +
            HttpHeader("Origin", origin) + HttpHeader("X-Requested-With", "XMLHttpRequest")

    private fun scheduleFormDefaults(token: String, weekNo: String = ""): List<Pair<String, String>> = listOf(
        TOKEN_FIELD to token,
        "SchoolCode" to "030305",
        "WeekNo" to weekNo,
        "ClassroomNo" to "",
        "CrossName" to "",
        "TeacherNo" to "",
        "SubjectNo" to "",
        "ShowWindow" to "left",
        "IsReverse" to "false",
        "教師超鐘點顯示" to "顯示",
        "教師姓名" to "正常顯示",
        "學生能檢視的課程" to "學生能檢視整天的課程",
        "檢視權限設定" to "",
        "是否顯示午休" to "隱藏",
        "是否顯示早自習" to "隱藏",
        "是否顯示節次時間" to "顯示",
        "顯示科目名稱" to "全名",
        "是否顯示總時數" to "否",
        "是否顯示實施日期" to "否",
        "實施開始日期" to "",
        "實施結束日期" to "",
    )

    private fun parseOptionList(json: String): List<Pair<String, String>> {
        val rootArray = when (val root = runCatching { SchoolJson.parseToJsonElement(json) }.getOrNull()) {
            is JsonArray -> root
            is JsonObject ->
                (root["Data"] ?: root["data"] ?: root["Result"] ?: root["result"] ?: root["items"]) as? JsonArray
                    ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return rootArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = ((obj["Text"] ?: obj["text"] ?: obj["DisplayText"]) as? JsonPrimitive)?.content
            val value = ((obj["Value"] ?: obj["value"]) as? JsonPrimitive)?.content
            if (text != null && value != null) text to value else null
        }
    }

    suspend fun getScheduleYears(session: AuthenticatedSession): List<ScheduleYearTermOption> {
        val generation = prepareSession(session)
        val token = getSchedulePageToken(generation)
        val json = executeBody(
            "POST", resolve("ClassTableV2/ClassTable/GetYearTermList"), scheduleHeaders(),
            listOf(TOKEN_FIELD to token), generation, expectJson = true,
        )
        return parseOptionList(json).map { (text, value) -> ScheduleYearTermOption(text, value) }
    }

    suspend fun getScheduleClasses(session: AuthenticatedSession, year: String, term: String): List<ScheduleClassOption> {
        val generation = prepareSession(session)
        val token = getSchedulePageToken(generation)
        val json = executeBody(
            "POST", resolve("ClassTableV2/ClassTable/GetClassNoList"), scheduleHeaders(),
            scheduleFormDefaults(token) + listOf("Year" to year, "Term" to term, "ClassNo" to "", "TimetableType" to ""),
            generation, expectJson = true,
        )
        return parseOptionList(json).map { (text, value) -> ScheduleClassOption(text, value) }
    }

    suspend fun getScheduleWeeks(session: AuthenticatedSession, year: String, term: String): List<ScheduleWeekOption> =
        loadScheduleWeeks(year, term, prepareSession(session))

    private suspend fun loadScheduleWeeks(year: String, term: String, generation: Long): List<ScheduleWeekOption> {
        val token = getSchedulePageToken(generation)
        val json = executeBody(
            "POST", resolve("ClassTableV2/ClassTable/GetWeekNoList"), scheduleHeaders(),
            scheduleFormDefaults(token) + listOf("Year" to year, "Term" to term, "ClassNo" to "", "TimetableType" to ""),
            generation, expectJson = true,
        )
        return parseScheduleWeekOptionsOrNull(json) ?: throw SchoolException("無法解析課表週次資料結構")
    }

    suspend fun querySchedule(
        session: AuthenticatedSession,
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate = schoolToday(),
    ): ScheduleFetchResult {
        val generation = prepareSession(session)

        suspend fun semesterReportOrNull(): ScheduleReport? = try {
            fetchScheduleRequest(session, yearValue, year, term, classNo, null, generation)
        } catch (error: Exception) {
            if (error is CancellationException || error is SchoolAuthenticationException) throw error
            null
        }

        if (scope == ScheduleScope.SEMESTER) {
            return try {
                ScheduleFetchResult.Available(fetchScheduleRequest(session, yearValue, year, term, classNo, null, generation))
            } catch (error: Exception) {
                if (error is CancellationException || error is SchoolAuthenticationException) throw error
                ScheduleFetchResult.Failed(error)
            }
        }

        val week = try {
            selectScheduleWeek(loadScheduleWeeks(year, term, generation), targetDate)
        } catch (error: Exception) {
            if (error is CancellationException || error is SchoolAuthenticationException) throw error
            return ScheduleFetchResult.Failed(error, semesterReportOrNull())
        } ?: return ScheduleFetchResult.Unpublished(targetDate, semesterReportOrNull())

        val weekReport = try {
            fetchScheduleRequest(session, yearValue, year, term, classNo, week, generation)
        } catch (error: Exception) {
            if (error is CancellationException || error is SchoolAuthenticationException) throw error
            return ScheduleFetchResult.Failed(error, semesterReportOrNull())
        }
        val baseline = semesterReportOrNull()
        val report = weekReport.copy(changes = baseline?.let { compareScheduleItems(it.items, weekReport.items) })
        return if (report.items.isEmpty()) {
            ScheduleFetchResult.Empty(report, baseline)
        } else {
            ScheduleFetchResult.Available(report, baseline)
        }
    }

    private suspend fun fetchScheduleRequest(
        session: AuthenticatedSession,
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        week: ScheduleWeekOption?,
        generation: Long,
    ): ScheduleReport {
        val token = getSchedulePageToken(generation)
        val form = scheduleFormDefaults(token, week?.value.orEmpty()) + listOf("Year" to year, "Term" to term) +
            if (classNo.isNotBlank()) {
                listOf("ClassNo" to classNo, "TimetableType" to "Class")
            } else {
                listOf("StudentNo" to session.studentNo, "TimetableType" to "Student")
            }
        val timetableJson = executeBody(
            "POST", resolve("ClassTableV2/ClassTable/GetTimeTable"), scheduleHeaders(), form, generation, expectJson = true,
        )
        val items = parseScheduleItemsOrNull(timetableJson) ?: throw SchoolException("無法解析課表資料結構")
        if (week == null && items.isEmpty()) throw SchoolException("目前沒有可用的學期課表")
        return ScheduleReport(
            yearTermValue = yearValue,
            classNo = classNo,
            scope = if (week == null) ScheduleScope.SEMESTER else ScheduleScope.CURRENT_WEEK,
            weekNo = week?.value,
            weekStartDate = week?.startDate,
            weekEndDate = week?.endDate,
            items = items,
        )
    }

    /** Unconditionally installs [session] and starts a new generation (Android `restoreSession`). */
    fun restoreSession(session: AuthenticatedSession) {
        state.replace(session, session.cookies, baseUrl.host)
    }

    /**
     * Verifies cookies captured by the login web view and returns the session they authorize.
     * The session carries the cookie jar as it is after the verification request.
     */
    suspend fun loginWithCookies(studentNo: String, cookies: Map<String, String>): AuthenticatedSession {
        val generation = state.replace(null, cookies, baseUrl.host)
        val gradesPage = executeBody(
            "GET", gradesPageUrl(), defaultHeaders(resolve("Auth/Auth/CloudLogin").toString()), null, generation,
        )
        val apiToken = findNamedInputValue(gradesPage, TOKEN_FIELD) ?: throw SchoolAuthenticationException()
        val session = AuthenticatedSession(
            studentNo = studentNo,
            apiToken = apiToken,
            cookies = state.cookieSnapshot(now()),
        )
        if (!state.setSession(generation, session)) throw CancellationException("Session changed")
        return session
    }

    fun clearSession() {
        state.clear()
    }

    /** Cookies of the current authorization; empty after [clearSession]. */
    fun cookieSnapshot(): Map<String, String> = state.cookieSnapshot(now())

    private suspend fun loadExams(session: AuthenticatedSession, yearValue: String, generation: Long): List<ExamOption> {
        val (year, term) = parseYearTermOrNull(yearValue) ?: throw SchoolException("學期資料格式錯誤")
        return postOptions(
            generation = generation,
            path = "ICampus/CommonData/GetGradeCanQueryExamNoListByStudentNo",
            referer = gradesPageUrl(),
            form = listOf(
                "searchType" to "單次考試所有成績",
                "studentNo" to session.studentNo,
                "year" to year,
                "term" to term,
                "__RequestVerificationToken" to session.apiToken,
            ),
        ).map { (text, value) -> ExamOption(text = text, value = value) }
    }

    private suspend fun postOptions(
        generation: Long,
        path: String,
        referer: PortableUrl,
        form: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val body = postForm(generation, path, referer, form)
        return parseSchoolPayload { parseOptions(body) }
    }

    private suspend fun postForm(
        generation: Long,
        path: String,
        referer: PortableUrl,
        form: List<Pair<String, String>>,
    ): String = executeBody(
        "POST", resolve(path),
        defaultHeaders(referer.toString()) + HttpHeader("Origin", origin) + HttpHeader("X-Requested-With", "XMLHttpRequest"),
        form, generation, expectJson = true,
    )

    private fun requireGeneration(generation: Long) {
        if (!state.isCurrent(generation)) throw CancellationException("Session changed")
    }

    /** A parser failure on a body the server did return is a school error, never a crash or a silent empty value. */
    private inline fun <T> parseSchoolPayload(block: () -> T): T = try {
        block()
    } catch (error: SchoolException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw SchoolException("學校系統資料格式錯誤", error)
    }

    private suspend fun executeBody(
        method: String,
        url: PortableUrl,
        headers: List<HttpHeader>,
        form: List<Pair<String, String>>?,
        generation: Long,
        expectJson: Boolean = false,
    ): String = withContext(dispatcher) {
        requireGeneration(generation)
        try {
            val (finalUrl, response) = try {
                withTimeout(CALL_TIMEOUT_MILLIS) { send(method, url, headers, form, generation) }
            } catch (error: TimeoutCancellationException) {
                throw HttpTransportException(HttpFailureKind.TIMEOUT, "School request timed out")
            }
            requireGeneration(generation)
            if (finalUrl.encodedPath.contains(LOGIN_PATH, ignoreCase = true)) throw SchoolAuthenticationException()
            if (!response.isSuccessful) {
                throw when (val code = response.statusCode) {
                    401, 403 -> SchoolAuthenticationException()
                    408, 429 -> SchoolTransientException("學校系統暫時無法回應")
                    in 500..599 -> SchoolTransientException("學校系統暫時無法回應")
                    else -> SchoolException("學校系統回應異常 HTTP $code")
                }
            }
            val body = response.body
            if (expectJson && body.trimStart().startsWith("<")) {
                if (body.contains("CloudLogin", ignoreCase = true) || body.contains("name=\"LoginId\"", ignoreCase = true)) {
                    throw SchoolAuthenticationException()
                }
                throw SchoolException("學校系統回傳非預期內容")
            }
            requireGeneration(generation)
            body
        } catch (error: SchoolException) {
            requireGeneration(generation)
            throw error
        } catch (error: HttpTransportException) {
            currentCoroutineContext().ensureActive()
            requireGeneration(generation)
            throw when (error.kind) {
                HttpFailureKind.TOO_LARGE -> SchoolException("學校系統回應內容過大")
                HttpFailureKind.CANCELLED -> CancellationException("Request cancelled")
                else -> SchoolTransientException("無法連線學校系統", error)
            }
        }
    }

    /** Sends one request chain, following redirects itself so every hop uses the generation-bound cookies. */
    private suspend fun send(
        method: String,
        url: PortableUrl,
        headers: List<HttpHeader>,
        form: List<Pair<String, String>>?,
        generation: Long,
    ): Pair<PortableUrl, HttpResponse> {
        var currentUrl = url
        var currentMethod = method
        var currentBody = form?.let(PortableUrl::formEncode)
        repeat(MAX_REDIRECTS + 1) {
            requireGeneration(generation)
            if (!currentUrl.isHttps && baseUrl.isHttps) throw SchoolException("學校系統回應異常")
            val cookies = state.cookiesFor(generation, currentUrl, now())
            val requestHeaders = buildList {
                addAll(headers)
                if (currentBody != null) add(HttpHeader("Content-Type", "application/x-www-form-urlencoded"))
                if (cookies.isNotEmpty()) add(HttpHeader("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" }))
            }
            val response = permits.withPermit {
                transport.execute(
                    HttpRequest(currentMethod, currentUrl.toString(), requestHeaders, currentBody, CALL_TIMEOUT_MILLIS, MAX_SCHOOL_RESPONSE_BYTES),
                )
            }
            state.saveFromResponse(generation, currentUrl, response.setCookies, now())
            val location = response.header("Location")
            if (response.statusCode !in REDIRECT_CODES || location == null) return currentUrl to response
            currentUrl = currentUrl.resolve(location) ?: return currentUrl to response
            if (response.statusCode != 307 && response.statusCode != 308) {
                currentMethod = "GET"
                currentBody = null
            }
        }
        throw SchoolTransientException("學校系統重新導向次數過多")
    }

    private fun gradesPageUrl(): PortableUrl = baseUrl.withPath("ICampus/StudentInfo/Index", listOf("page" to "成績查詢"))

    private fun homePageUrl(): PortableUrl = resolve("ICampus/Home/Index2")

    private fun resolve(path: String): PortableUrl = baseUrl.withPath(path)

    private fun defaultHeaders(referer: String?): List<HttpHeader> = buildList {
        add(HttpHeader("Accept", "*/*"))
        add(HttpHeader("User-Agent", USER_AGENT))
        if (!referer.isNullOrBlank()) add(HttpHeader("Referer", referer))
    }

    private companion object {
        const val LOGIN_PATH = "/Auth/Auth/CloudLogin"
        const val TOKEN_FIELD = "__RequestVerificationToken"
        const val CALL_TIMEOUT_MILLIS = 30_000L
        const val MAX_REDIRECTS = 20
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val USER_INFO_PATTERN = Regex("""const\s+userInfo\s*=\s*JSON\.parse\(\s*'([^']+)'\s*\)""")
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
