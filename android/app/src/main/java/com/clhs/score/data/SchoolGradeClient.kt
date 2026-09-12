package com.clhs.score.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Dispatcher as OkHttpDispatcher
import org.jsoup.Jsoup
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

internal const val MAX_SCHOOL_RESPONSE_BYTES = 8L * 1024 * 1024
internal const val MAX_CONCURRENT_SCHOOL_REQUESTS = 4

open class SchoolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class SchoolAuthenticationException(
    message: String = "登入狀態已失效",
) : SchoolException(message)

class SchoolTransientException(
    message: String,
    cause: Throwable? = null,
) : SchoolException(message, cause)

class SchoolGradeClient(
    baseUrl: String = DEFAULT_BASE_URL,
    private val cookieJar: SchoolCookieJar = SchoolCookieJar(),
    okHttpClient: OkHttpClient? = null,
) {
    private val baseUrl: HttpUrl = baseUrl.ensureTrailingSlash().toHttpUrl()
    private val origin: String = "${this.baseUrl.scheme}://${this.baseUrl.host}"
    private val client: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .dispatcher(
            OkHttpDispatcher().apply {
                maxRequests = MAX_CONCURRENT_SCHOOL_REQUESTS
                maxRequestsPerHost = MAX_CONCURRENT_SCHOOL_REQUESTS
            },
        )
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    internal val concurrentRequestLimit: Int
        get() = client.dispatcher.maxRequestsPerHost

    private var currentStudentNo: String? = null
    private var cachedScheduleToken: String? = null
    private val sessionLock = Any()

    private fun prepareSession(session: AuthenticatedSession) {
        synchronized(sessionLock) {
            if (currentStudentNo != session.studentNo) {
                cookieJar.replace(session.cookies, domain = baseUrl.host)
                currentStudentNo = session.studentNo
                cachedScheduleToken = null
            }
        }
    }


    suspend fun loadStructure(session: AuthenticatedSession): List<YearTermOption> {
        prepareSession(session)
        val yearTerms = postOptions(
            path = "ICampus/CommonData/GetGradeCanQueryYearTermListByStudentNo",
            referer = gradesPageUrl().toString(),
            form = mapOf(
                "searchType" to "各次考試單科成績",
                "studentNo" to session.studentNo,
                "__RequestVerificationToken" to session.apiToken,
            ),
        )
        return coroutineScope {
            yearTerms.map { (text, value) ->
                async {
                    YearTermOption(
                        text = text,
                        value = value,
                        exams = loadExams(session, value),
                    )
                }
            }.awaitAll()
        }
    }

    suspend fun fetchGrades(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
    ): GradeReport = withContext(Dispatchers.IO) {
        prepareSession(session)
        val (year, term) = parseYearTermOrNull(yearValue)
            ?: throw SchoolException("學期資料格式錯誤")
        val body = postForm(
            path = "ICampus/TutorShGrade/GetScoreForStudentExamContent",
            referer = gradesPageUrl().toString(),
            form = mapOf(
                "StudentNo" to session.studentNo,
                "SearchType" to "單次考試所有成績",
                "__RequestVerificationToken" to session.apiToken,
                "Year" to year,
                "Term" to term,
                "ExamNo" to examValue,
            ),
        )
        parseGradeReport(body)
    }

    suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo = withContext(Dispatchers.IO) {
        prepareSession(session)
        val body = executeBody(
            Request.Builder()
                .url(homePageUrl())
                .headers(defaultHeaders(referer = homePageUrl().toString()))
                .get()
                .build(),
        )
        val rawUserInfo = USER_INFO_PATTERN.find(body)?.groupValues?.get(1)
            ?: throw SchoolException("學校系統未提供帳號資訊")
        val userInfo = runCatching { SchoolJson.parseToJsonElement(rawUserInfo).asObjectOrNull() }
            .getOrNull()
            ?: throw SchoolException("學校系統帳號資訊格式錯誤")
        val studentNo = userInfo.string("No")
        if (studentNo != session.studentNo) throw SchoolAuthenticationException("登入帳號資訊不一致")
        StudentInfo(
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

    private suspend fun getSchedulePageToken(session: AuthenticatedSession): String {
        prepareSession(session)
        synchronized(sessionLock) {
            cachedScheduleToken?.let { return it }
        }
        val pageResponse = executeBody(
            Request.Builder()
                .url(resolve("ClassTableV2/ClassTable"))
                .headers(defaultHeaders(referer = resolve("ICampus/Home/Index2").toString()))
                .get()
                .build(),
        )
        val token = hiddenInput(pageResponse)
            ?: throw SchoolAuthenticationException()
        synchronized(sessionLock) {
            cachedScheduleToken = token
        }
        return token
    }

    private fun scheduleHeaders(): Headers =
        defaultHeaders(referer = resolve("ClassTableV2/ClassTable").toString()).newBuilder()
            .add("Origin", origin)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

    private fun buildScheduleFormDefaults(token: String, weekNo: String = ""): FormBody.Builder =
        FormBody.Builder()
            .add("__RequestVerificationToken", token)
            .add("SchoolCode", "030305")
            .add("WeekNo", weekNo)
            .add("ClassroomNo", "")
            .add("CrossName", "")
            .add("TeacherNo", "")
            .add("SubjectNo", "")
            .add("ShowWindow", "left")
            .add("IsReverse", "false")
            .add("教師超鐘點顯示", "顯示")
            .add("教師姓名", "正常顯示")
            .add("學生能檢視的課程", "學生能檢視整天的課程")
            .add("檢視權限設定", "")
            .add("是否顯示午休", "隱藏")
            .add("是否顯示早自習", "隱藏")
            .add("是否顯示節次時間", "顯示")
            .add("顯示科目名稱", "全名")
            .add("是否顯示總時數", "否")
            .add("是否顯示實施日期", "否")
            .add("實施開始日期", "")
            .add("實施結束日期", "")

    private fun parseOptionList(json: String): List<Pair<String, String>> {
        val root = runCatching { SchoolJson.parseToJsonElement(json) }.getOrNull()
        val rootArray = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                (root["Data"] ?: root["data"] ?: root["Result"] ?: root["result"] ?: root["items"]) as? JsonArray ?: JsonArray(emptyList())
            }
            else -> JsonArray(emptyList())
        }
        return rootArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = (obj["Text"] ?: obj["text"] ?: obj["DisplayText"])?.let { if (it is JsonPrimitive) it.content else null }
            val value = (obj["Value"] ?: obj["value"])?.let { if (it is JsonPrimitive) it.content else null }
            if (text != null && value != null) text to value else null
        }
    }

    suspend fun getScheduleYears(session: AuthenticatedSession): List<ScheduleYearTermOption> = withContext(Dispatchers.IO) {
        val token = getSchedulePageToken(session)
        val body = FormBody.Builder()
            .add("__RequestVerificationToken", token)
            .build()
        val json = executeBody(
            Request.Builder()
                .url(resolve("ClassTableV2/ClassTable/GetYearTermList"))
                .headers(scheduleHeaders())
                .post(body)
                .build(),
            expectJson = true,
        )
        parseOptionList(json).map { (text, value) -> ScheduleYearTermOption(text, value) }
    }

    suspend fun getScheduleClasses(
        session: AuthenticatedSession,
        year: String,
        term: String
    ): List<ScheduleClassOption> = withContext(Dispatchers.IO) {
        val token = getSchedulePageToken(session)
        val body = buildScheduleFormDefaults(token)
            .add("Year", year)
            .add("Term", term)
            .add("ClassNo", "")
            .add("TimetableType", "")
            .build()
        val json = executeBody(
            Request.Builder()
                .url(resolve("ClassTableV2/ClassTable/GetClassNoList"))
                .headers(scheduleHeaders())
                .post(body)
                .build(),
            expectJson = true,
        )
        parseOptionList(json).map { (text, value) -> ScheduleClassOption(text, value) }
    }

    suspend fun getScheduleWeeks(
        session: AuthenticatedSession,
        year: String,
        term: String,
    ): List<ScheduleWeekOption> = withContext(Dispatchers.IO) {
        val token = getSchedulePageToken(session)
        val body = buildScheduleFormDefaults(token)
            .add("Year", year)
            .add("Term", term)
            .add("ClassNo", "")
            .add("TimetableType", "")
            .build()
        val json = executeBody(
            Request.Builder()
                .url(resolve("ClassTableV2/ClassTable/GetWeekNoList"))
                .headers(scheduleHeaders())
                .post(body)
                .build(),
            expectJson = true,
        )
        parseScheduleWeekOptions(json)
    }

    suspend fun fetchSchedule(
        session: AuthenticatedSession,
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate = LocalDate.now(),
        onSemesterReport: suspend (ScheduleReport) -> Unit = {},
    ): ScheduleReport = withContext(Dispatchers.IO) {
        if (scope == ScheduleScope.CURRENT_WEEK) {
            val weekReport = try {
                val week = selectScheduleWeek(
                    options = getScheduleWeeks(session, year, term),
                    targetDate = targetDate,
                )
                    ?: throw SchoolException("目前沒有可用的當週課表")
                fetchScheduleRequest(session, yearValue, year, term, classNo, week)
            } catch (error: Exception) {
                if (error is CancellationException || error is SchoolAuthenticationException) throw error
                return@withContext fetchScheduleRequest(session, yearValue, year, term, classNo, null)
            }

            val changes = try {
                val semesterReport = fetchScheduleRequest(session, yearValue, year, term, classNo, null)
                onSemesterReport(semesterReport)
                compareScheduleItems(semesterReport.items, weekReport.items)
            } catch (error: Exception) {
                if (error is CancellationException || error is SchoolAuthenticationException) throw error
                null
            }
            return@withContext weekReport.copy(changes = changes)
        }
        fetchScheduleRequest(session, yearValue, year, term, classNo, null)
    }

    private suspend fun fetchScheduleRequest(
        session: AuthenticatedSession,
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        week: ScheduleWeekOption?,
    ): ScheduleReport {
        val token = getSchedulePageToken(session)
        val form = buildScheduleFormDefaults(token, week?.value.orEmpty())
            .add("Year", year)
            .add("Term", term)
            .apply {
                if (classNo.isNotBlank()) {
                    add("ClassNo", classNo)
                    add("TimetableType", "Class")
                } else {
                    add("StudentNo", session.studentNo)
                    add("TimetableType", "Student")
                }
            }
            .build()

        val timetableJson = executeBody(
            Request.Builder()
                .url(resolve("ClassTableV2/ClassTable/GetTimeTable"))
                .headers(scheduleHeaders())
                .post(form)
                .build(),
            expectJson = true,
        )

        val items = parseScheduleItems(timetableJson)

        if (items.isEmpty()) {
            throw SchoolException("無法解析課表資料結構或無課表")
        }

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

    fun restoreSession(session: AuthenticatedSession) {
        cookieJar.replace(session.cookies, domain = baseUrl.host)
        synchronized(sessionLock) {
            currentStudentNo = session.studentNo
            cachedScheduleToken = null
        }
    }

    suspend fun loginWithCookies(
        studentNo: String,
        cookies: Map<String, String>,
    ): AuthenticatedSession = withContext(Dispatchers.IO) {
        cookieJar.replace(cookies, domain = baseUrl.host)
        synchronized(sessionLock) {
            currentStudentNo = studentNo
            cachedScheduleToken = null
        }
        val gradesPage = executeBody(
            Request.Builder()
                .url(gradesPageUrl())
                .headers(defaultHeaders(referer = resolve("Auth/Auth/CloudLogin").toString()))
                .get()
                .build(),
        )
        val apiToken = hiddenInput(gradesPage)
            ?: throw SchoolAuthenticationException()
        AuthenticatedSession(
            studentNo = studentNo,
            apiToken = apiToken,
            cookies = cookieJar.snapshot(),
        )
    }

    fun clearSession() {
        cookieJar.clear()
        synchronized(sessionLock) {
            currentStudentNo = null
            cachedScheduleToken = null
        }
    }

    private suspend fun loadExams(session: AuthenticatedSession, yearValue: String): List<ExamOption> {
        val (year, term) = parseYearTermOrNull(yearValue)
            ?: throw SchoolException("學期資料格式錯誤")
        return postOptions(
            path = "ICampus/CommonData/GetGradeCanQueryExamNoListByStudentNo",
            referer = gradesPageUrl().toString(),
            form = mapOf(
                "searchType" to "單次考試所有成績",
                "studentNo" to session.studentNo,
                "year" to year,
                "term" to term,
                "__RequestVerificationToken" to session.apiToken,
            ),
        ).map { (text, value) -> ExamOption(text = text, value = value) }
    }

    private suspend fun postOptions(
        path: String,
        referer: String,
        form: Map<String, String>,
    ): List<Pair<String, String>> = parseOptions(postForm(path, referer, form))

    private suspend fun postForm(
        path: String,
        referer: String,
        form: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            form.forEach { (name, value) -> add(name, value) }
        }.build()
        executeBody(
            Request.Builder()
                .url(resolve(path))
                .headers(
                    defaultHeaders(referer).newBuilder()
                        .add("Origin", origin)
                        .add("X-Requested-With", "XMLHttpRequest")
                        .build(),
                )
                .post(body)
                .build(),
            expectJson = true,
        )
    }

    private suspend fun <T> execute(request: Request, block: suspend (Response) -> T): T {
        return try {
            client.newCall(request).executeCancellable { response ->
                if (response.request.url.encodedPath.contains(LOGIN_PATH, ignoreCase = true)) {
                    throw SchoolAuthenticationException()
                }
                if (!response.isSuccessful) {
                    throw when (val code = response.code) {
                        401, 403 -> SchoolAuthenticationException()
                        408, 429 ->
                            SchoolTransientException("學校系統暫時無法回應")
                        in 500..599 -> SchoolTransientException("學校系統暫時無法回應")
                        else -> SchoolException("學校系統回應異常 HTTP $code")
                    }
                }
                block(response)
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw SchoolTransientException("無法連線學校系統", error)
        }
    }

    private suspend fun executeBody(request: Request, expectJson: Boolean = false): String =
        execute(request) { response ->
            val responseBody = response.body
            if (responseBody.contentLength() > MAX_SCHOOL_RESPONSE_BYTES ||
                responseBody.source().request(MAX_SCHOOL_RESPONSE_BYTES + 1)
            ) {
                throw SchoolException("學校系統回應內容過大")
            }
            val body = responseBody.string()
            if (expectJson && body.trimStart().startsWith("<")) {
                if (body.contains("CloudLogin", ignoreCase = true) ||
                    body.contains("name=\"LoginId\"", ignoreCase = true)
                ) {
                    throw SchoolAuthenticationException()
                }
                throw SchoolException("學校系統回傳非預期內容")
            }
            body
        }

    private fun hiddenInput(html: String): String? {
        val doc = Jsoup.parse(html)
        val element = doc.selectFirst("""[name="__RequestVerificationToken"]""")
        return (element?.attr("value") ?: element?.text())?.trim()?.takeIf { it.isNotBlank() }
    }


    private fun gradesPageUrl(): HttpUrl = resolve("ICampus/StudentInfo/Index")
        .newBuilder()
        .addQueryParameter("page", "成績查詢")
        .build()

    private fun homePageUrl(): HttpUrl = resolve("ICampus/Home/Index2")

    private fun resolve(path: String): HttpUrl = baseUrl.resolve(path)
        ?: throw IllegalArgumentException("Invalid path: $path")

    private fun defaultHeaders(referer: String?): Headers = Headers.Builder()
        .add("Accept", "*/*")
        .add("User-Agent", USER_AGENT)
        .apply {
            if (!referer.isNullOrBlank()) add("Referer", referer)
        }
        .build()

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"


    companion object {
        const val DEFAULT_BASE_URL = "https://shcloud2.k12ea.gov.tw/CLHSTYC"
        private const val LOGIN_PATH = "/Auth/Auth/CloudLogin"
        private val USER_INFO_PATTERN = Regex(
            """const\s+userInfo\s*=\s*JSON\.parse\(\s*'([^']+)'\s*\)""",
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
