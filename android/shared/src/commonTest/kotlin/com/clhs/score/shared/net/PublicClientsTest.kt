package com.clhs.score.shared.net

import com.clhs.score.data.AnnouncementCategory
import com.clhs.score.data.AnnouncementUnit
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeStation
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.localDateOf
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.parseInstant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicClientsTest {
    private val fetchedAt = parseInstant("2026-08-09T00:00:00Z")

    private fun dateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        localDateOf(year, month, day).at(localTimeOf(hour, minute))

    // Announcements

    @Test
    fun announcementListKeepsServerOrderAndOnlySafeExternalLinks() {
        val page = PublicAnnouncementParser.parsePage(PublicFixtures.RECORDED_LIST_JSON, fetchedAt)
        assertEquals(0, page.pageIndex)
        assertEquals(7532, page.totalPages)
        assertEquals(listOf("45072", "45068", "external"), page.announcements.map { it.id })
        assertEquals(listOf("公告", "最新消息", "通知"), page.announcements.map { it.category })
        assertEquals(listOf("讀者服務組", "設備組", "學務處"), page.announcements.map { it.unit })
        assertTrue(page.announcements.first().isPinned)
        assertFalse(page.announcements[1].isPinned)
        assertEquals("https://example.org/activity", page.announcements.last().externalUrl)
        assertFailsWith<PublicDataException> { PublicAnnouncementParser.parsePage("{}", fetchedAt) }
        assertFailsWith<PublicDataException> { PublicAnnouncementParser.parsePage("<html>", fetchedAt) }
    }

    @Test
    fun filterOptionsRejectInvalidIdsAndDeduplicate() {
        val units = PublicAnnouncementParser.parseUnits(
            """
            <select class="other select-unit-x">
              <option value="-1">全部</option>
              <option value="69">總務處官網</option>
              <option value="69">重複 ID</option>
              <option value="70">總務處官網</option>
              <option value="bad">不合法</option>
            </select>
            """.trimIndent(),
        )
        assertEquals(listOf("-1", "69"), units.map(AnnouncementUnit::id))
        assertEquals(listOf("全部", "總務處官網"), units.map(AnnouncementUnit::name))
        val options = PublicAnnouncementParser.parseFilterOptions(
            """
            <select class='select-unit-x'><option value='425'>首頁</option></select>
            <select class='select-attr-x'>
              <option value='-1'>全部</option><option value='12'>升學&amp;資訊</option>
              <option value='12'>重複</option><option value='bad'>不合法</option>
            </select>
            """.trimIndent(),
        )
        assertEquals(listOf("-1", "12"), options.categories.map(AnnouncementCategory::id))
        assertEquals("升學&資訊", options.categories.last().name)
        assertFailsWith<PublicDataException> { PublicAnnouncementParser.parseUnits("<select class='select-unit-x'></select>") }
    }

    @Test
    fun detailSanitizesActiveContentAndDecodesOfficialAttachments() {
        val detail = PublicAnnouncementParser.parseDetail(PublicFixtures.RECORDED_DETAIL_JSON, "45072", "公告")
        assertEquals("公告", detail.category)
        assertEquals(
            "<p><strong>閉館日期</strong><a href=\"https://www.clhs.tyc.edu.tw/notice\">詳情</a><a>bad</a></p>" +
                "<p><strong>⚠️ 此內容包含表格</strong></p><p>請點擊下方「<strong>查看原文</strong>」來查看完整表格內容。</p>",
            detail.htmlContent,
        )
        assertEquals(2, detail.images.size)
        assertEquals("公告圖片", detail.images.first().description)
        assertTrue(detail.images.first().canPreview)
        assertEquals("https://www.clhs.tyc.edu.tw/ischool/static/image/news.jpg", detail.images.first().url)
        assertFalse(detail.images.last().canPreview)
        assertEquals("八月開館時間.pdf", detail.attachments.single().name)
        assertEquals(245_760L, detail.attachments.single().sizeBytes)
        assertEquals(
            "https://www.clhs.tyc.edu.tw/ischool/news/attached/45072/%E5%85%AB%E6%9C%88%E9%96%8B%E9%A4%A8%E6%99%82%E9%96%93.pdf",
            detail.attachments.single().url,
        )
        assertEquals("https://www.clhs.tyc.edu.tw/ischool/public/news_view/show.php?nid=45072", detail.officialUrl)
    }

    @Test
    fun headingsBecomeBoldParagraphsAndUnknownTagsAreUnwrapped() {
        val heading = PublicAnnouncementParser.parseDetail(PublicFixtures.HEADING_DETAIL_JSON, "45049", "公告")
        assertEquals("<p><strong>1.學生名單請參閱附加檔案</strong></p>", heading.htmlContent)
        fun html(content: String) = PublicAnnouncementParser.parseDetail(
            """[{"rcode":200,"newsId":"1","content":"${PortableUrl.encodeComponent(content)}"}]""", "1",
        ).htmlContent
        assertEquals("<p>a &lt; b &amp; c</p>", html("<p onclick=\"x()\">a &lt; b &amp; c</p>"))
        assertEquals("<p><strong>已粗體</strong></p>", html("<h2><strong>已粗體</strong></h2>"))
        assertEquals("文字<br>下一行", html("<font color=red><center>文字<br/>下一行</center></font>"))
        assertEquals("<ul><li>一</li><li>二</li></ul>", html("<ul><li>一</li><li>二</li></ul>"))
        assertEquals("<a>x</a>", html("<a href=\"data:text/html,1\">x</a>"))
        assertEquals("<a>x</a>", html("<a href=\"https://user:pw@evil.example/\">x</a>"))
        assertEquals("前後", html("前<iframe src=\"https://x.example\"><p>inner</p></iframe><style>p{}</style>後"))
        assertEquals("<p>未關閉</p>", html("<p>未關閉"))
        // Hostile nesting is capped at 32 emitted levels; the text survives.
        val deep = html("<div>".repeat(5000) + "<b>".repeat(5000) + "深層" + "</b>".repeat(5000) + "</div>".repeat(5000))
        assertTrue(deep.contains("深層"))
        assertTrue(deep.split("<b>").size - 1 <= 32, "nesting must be capped")
        assertEquals("前後", html("前<script/>alert(1)</script>後"))
        assertEquals("前", html("前<iframe/><p>inner</p>"))
        assertEquals("<p>“引號”—破折號…&nbsp;&amp;ldquo 中文©</p>", html("<p>&ldquo;引號&rdquo;&mdash;破折號&hellip;&nbsp;&amp;ldquo &#20013;&#x6587;&copy;</p>"))
    }

    @Test
    fun imagesInsideRemovedTablesAreStillListed() {
        val detail = PublicAnnouncementParser.parseDetail(
            "[{\"rcode\":200,\"newsId\":\"1\",\"content\":\"" + PortableUrl.encodeComponent(
                "<p>說明</p><table><tr><td><img src=\"/ischool/a.jpg\" alt=\"海報\"></td></tr></table>",
            ) + "\"}]",
            "1",
        )
        assertTrue(detail.htmlContent.startsWith("<p>說明</p>"))
        assertEquals(listOf("https://www.clhs.tyc.edu.tw/ischool/a.jpg"), detail.images.map { it.url })
        assertEquals("海報", detail.images.single().description)
    }

    @Test
    fun attachmentNamesAreEncodedAsOneSegment() {
        val detail = PublicAnnouncementParser.parseDetail(
            "[{\"rcode\":200,\"newsId\":\"45072\",\"content\":\"\",\"attachedfile\":" +
                "\"[[\\\"o\\\",1,\\\"../a/b?.pdf\\\"],[\\\"o\\\",1,\\\"..\\\"],[\\\"o\\\",1,\\\"100%#.pdf\\\"]]\"}]",
            "45072",
        )
        assertEquals(
            listOf(
                "https://www.clhs.tyc.edu.tw/ischool/news/attached/45072/..%2Fa%2Fb%3F.pdf",
                "https://www.clhs.tyc.edu.tw/ischool/news/attached/45072/%2E%2E",
                "https://www.clhs.tyc.edu.tw/ischool/news/attached/45072/100%25%23.pdf",
            ),
            detail.attachments.map { it.url },
        )
    }

    @Test
    fun unsafeUrlsAndUnavailableDetailsAreRejected() {
        assertNull(PublicAnnouncementParser.safeWebUrl("javascript:alert(1)"))
        assertNull(PublicAnnouncementParser.safeWebUrl("file:///etc/passwd"))
        assertNull(PublicAnnouncementParser.safeWebUrl("https://user@example.org/"))
        assertEquals("https://www.clhs.tyc.edu.tw/a/b", PublicAnnouncementParser.safeWebUrl("/a/b"))
        assertFailsWith<PublicDataException> { PublicAnnouncementParser.parseDetail("""[{"rcode":404}]""", "1") }
        assertFailsWith<PublicDataException> { PublicAnnouncementParser.parseDetail("""[{"rcode":200,"content":"%E5%"}]""", "1") }
        val duplicate = PublicAnnouncementParser.parseDetail(
            """[{"rcode":200,"newsId":"45072","content":"","attachedfile":"[[\"o\",123,\"same.pdf\"],[\"o\",123,\"same.pdf\"]]"}]""",
            "45072",
        )
        assertEquals("same.pdf", duplicate.attachments.single().name)
    }

    @Test
    fun announcementRequestsUseOfficialFormAndNeverCarryCookies() = runTest {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.contains("show.php") -> response("<script>var g_news_unique_id = \"public-detail-uid\";</script>")
                request.url.contains("news_query_json_content.php") -> response(PublicFixtures.RECORDED_DETAIL_JSON)
                else -> response(PublicFixtures.RECORDED_LIST_JSON, setCookies = listOf(cookie("session", "must-not-return")))
            }
        }
        val client = PublicAnnouncementsClient(transport)
        client.fetchPageBody(0)
        client.fetchPageBody(1, keyword = " 模擬考 ", unitId = "425", categoryId = "12")
        assertEquals("45072", client.fetchDetail("45072", "公告").id)

        val first = transport.requests[0]
        assertEquals("POST", first.method)
        assertEquals("https://www.clhs.tyc.edu.tw/ischool/widget/site_news/news_query_json.php", first.url)
        assertTrue(first.body!!.contains("flock=&keyword=&maxRows=20&order=DESC&pageNum=0"))
        val second = transport.requests[1].body!!
        assertTrue(second.contains("flock=unit_425-attr_12"))
        assertTrue(second.contains("keyword=%E6%A8%A1%E6%93%AC%E8%80%83"))
        assertTrue(second.contains("pageNum=1"))
        assertEquals("https://www.clhs.tyc.edu.tw/ischool/public/news_view/show.php?nid=45072", transport.requests[2].url)
        assertEquals(
            "https://www.clhs.tyc.edu.tw/ischool/widget/site_news/news_query_json_content.php?nid=45072&dir=0&uid=public-detail-uid",
            transport.requests[3].url,
        )
        transport.requests.forEach { request ->
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
        }
        assertFailsWith<IllegalArgumentException> { client.fetchPageBody(0, unitId = "bad") }
        assertFailsWith<IllegalArgumentException> { client.fetchPageBody(0, categoryId = "12-unit_1") }
        assertFailsWith<IllegalArgumentException> { client.fetchPageBody(-1) }
        assertEquals(4, transport.requests.size)
    }

    @Test
    fun publicClientsDoNotFollowRedirectsAndReportHttpFailures() = runTest {
        var next = response(status = 302, headers = listOf(HttpHeader("Location", "https://elsewhere.example/")))
        val transport = FakeHttpTransport { next }
        val client = PublicAnnouncementsClient(transport)
        assertEquals(302, assertFailsWith<PublicDataException> { client.fetchFilterOptionsBody() }.statusCode)
        assertEquals(1, transport.requests.size)
        next = response(status = 500)
        assertEquals(500, assertFailsWith<PublicDataException> { client.fetchPageBody(0) }.statusCode)
    }

    // Calendar

    @Test
    fun icsHandlesAllDayTimedFoldedCancelledAndRecurringEvents() {
        val feed = SchoolCalendarIcs.parse("\uFEFF" + PublicFixtures.SAMPLE_ICS.replace("\n", "\r\n"))
        assertEquals(2, feed.events.size)
        assertEquals(1, feed.skippedRecurringEvents)
        val allDay = feed.events[0]
        assertEquals("all-day", allDay.id)
        assertEquals("段考,第一天延長", allDay.title)
        assertEquals(dateTime(2026, 8, 10, 0, 0), allDay.start)
        assertEquals(dateTime(2026, 8, 12, 0, 0), allDay.endExclusive)
        assertTrue(allDay.isAllDay)
        val timed = feed.events[1]
        assertEquals(dateTime(2026, 8, 12, 9, 0), timed.start)
        assertEquals(dateTime(2026, 8, 12, 10, 30), timed.endExclusive)
        assertFalse(timed.isAllDay)
        assertEquals("圖書館;二樓", timed.location)
    }

    @Test
    fun icsConvertsFixedOffsetZonesAndRejectsOversizedDurations() {
        val feed = SchoolCalendarIcs.parse(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:utc
            DTSTART;TZID=UTC:20260901T003000
            SUMMARY:UTC
            END:VEVENT
            BEGIN:VEVENT
            UID:tokyo
            DTSTART;TZID=Etc/GMT-9:20260901T093000
            SUMMARY:Tokyo
            END:VEVENT
            BEGIN:VEVENT
            UID:offset
            DTSTART;TZID="UTC-05:00":20260831T193000
            SUMMARY:Offset
            END:VEVENT
            BEGIN:VEVENT
            UID:taipei
            DTSTART;TZID=Asia/Taipei:20260901T083000
            DURATION:P99999999999999W
            SUMMARY:Taipei
            END:VEVENT
            BEGIN:VEVENT
            UID:overflow
            DTSTART;TZID=Asia/Taipei:20260901T083000
            DURATION:PT99999999999999999999S
            SUMMARY:Overflow
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        val byId = feed.events.associateBy { it.id }
        assertEquals(dateTime(2026, 9, 1, 8, 30), byId.getValue("utc").start)
        assertEquals(dateTime(2026, 9, 1, 8, 30), byId.getValue("tokyo").start)
        assertEquals(dateTime(2026, 9, 1, 8, 30), byId.getValue("offset").start)
        assertEquals(dateTime(2026, 9, 1, 8, 30), byId.getValue("taipei").start)
        // An unusable duration falls back to the default end instead of a wrapped-around value.
        assertEquals(byId.getValue("taipei").start, byId.getValue("taipei").endExclusive)
        assertEquals(byId.getValue("overflow").start, byId.getValue("overflow").endExclusive)
    }

    @Test
    fun icsAppliesDurationDefaultsAndIgnoresNestedAlarms() {
        val feed = SchoolCalendarIcs.parse(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Taipei:20260901T083000
            DURATION:PT1H30M
            SUMMARY:校慶預演
            BEGIN:VALARM
            TRIGGER:-PT15M
            DESCRIPTION:提醒
            SUMMARY:不是標題
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:no-end
            DTSTART;VALUE=DATE:20260903
            END:VEVENT
            BEGIN:VEVENT
            UID:backwards
            DTSTART:20260904T000000Z
            DTEND:20260903T000000Z
            SUMMARY:結束早於開始
            END:VEVENT
            BEGIN:VEVENT
            UID:no-start
            SUMMARY:沒有開始時間
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        assertEquals(listOf("校慶預演", "未命名活動", "結束早於開始"), feed.events.map { it.title })
        val timed = feed.events[0]
        assertEquals(dateTime(2026, 9, 1, 8, 30), timed.start)
        assertEquals(dateTime(2026, 9, 1, 10, 0), timed.endExclusive)
        assertEquals("${timed.start}-校慶預演", timed.id)
        assertEquals(dateTime(2026, 9, 4, 0, 0), feed.events[1].endExclusive)
        assertEquals(feed.events[2].start, feed.events[2].endExclusive)
        assertFailsWith<PublicDataException> { SchoolCalendarIcs.parse("<html>Not a calendar</html>") }
    }

    @Test
    fun calendarFollowsOnlyHttpsRedirectsWithoutCookies() = runTest {
        val transport = FakeHttpTransport { request ->
            if (request.url.endsWith("basic.ics")) {
                response(status = 302, headers = listOf(HttpHeader("Location", "https://cdn.example/feed.ics")), setCookies = listOf(cookie("a", "b")))
            } else response(PublicFixtures.SAMPLE_ICS)
        }
        assertTrue(PublicCalendarClient(transport).fetchFeedBody().startsWith("BEGIN:VCALENDAR"))
        assertEquals("https://cdn.example/feed.ics", transport.requests[1].url)
        assertNull(transport.requests[1].header("Cookie"))
        val insecure = FakeHttpTransport { response(status = 301, headers = listOf(HttpHeader("Location", "http://cdn.example/feed.ics"))) }
        assertFailsWith<PublicDataException> { PublicCalendarClient(insecure).fetchFeedBody() }
        assertEquals(1, insecure.requests.size)
    }

    // Weather

    @Test
    fun openMeteoUsesFixedCoordinatesAndParsesNextHourlyProbability() = runTest {
        val transport = FakeHttpTransport { response(PublicFixtures.OPEN_METEO_RESPONSE) }
        val raw = PublicWeatherClient(transport).fetchBody(WeatherSource.OPEN_METEO, WeatherLocation.SCHOOL, cwaKey = "unused-secret")
        val request = transport.requests.single()
        assertTrue(request.url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=24.9621&longitude=121.2112&"))
        assertTrue(request.url.contains("daily=temperature_2m_max%2Ctemperature_2m_min"))
        assertNull(request.header("Authorization"))
        assertFalse(request.url.contains("unused-secret"))
        val weather = WeatherParser.parse(WeatherSource.OPEN_METEO, raw, parseInstant("2026-08-30T04:30:00Z"), WeatherLocation.SCHOOL)
        assertEquals(27, weather.temperatureCelsius)
        assertEquals(29, weather.apparentTemperatureCelsius)
        assertEquals("有雨", weather.condition)
        assertEquals(65, weather.precipitationProbability)
        assertEquals(dateTime(2026, 8, 30, 13, 0), weather.precipitationStart)
        assertEquals(dateTime(2026, 8, 30, 14, 0), weather.precipitationEndExclusive)
        assertEquals("中壢區", weather.locationLabel)
        assertNull(weather.maximumTemperatureCelsius)
        assertEquals(2, weather.forecast.size)
        assertEquals(parseInstant("2026-08-30T04:00:00Z").epochMillis(), weather.forecast.first().startsAt.epochMillis())
    }

    @Test
    fun openMeteoDailyExtremaMatchTheSchoolDate() {
        val raw = PublicFixtures.OPEN_METEO_RESPONSE.trimEnd().removeSuffix("}") +
            ""","daily":{"time":["2026-08-30","2026-08-31"],"temperature_2m_max":[32.6,35.0],"temperature_2m_min":[null,24.2]}}"""
        // 17:00 UTC is already the next school day.
        val weather = WeatherParser.parse(WeatherSource.OPEN_METEO, raw, parseInstant("2026-08-30T17:00:00Z"), WeatherLocation.SCHOOL)
        assertEquals(35, weather.maximumTemperatureCelsius)
        assertEquals(24, weather.minimumTemperatureCelsius)
        val sameDay = WeatherParser.parse(WeatherSource.OPEN_METEO, raw, parseInstant("2026-08-30T04:00:00Z"), WeatherLocation.SCHOOL)
        assertEquals(33, sameDay.maximumTemperatureCelsius)
        assertNull(sameDay.minimumTemperatureCelsius)
    }

    @Test
    fun cwaSendsKeyOnlyInAuthorizationHeaderAndParsesTheRequestedDistrict() = runTest {
        val secret = "CWA-TEST-KEY-0000"
        val transport = FakeHttpTransport { response(PublicFixtures.CWA_RESPONSE) }
        val raw = PublicWeatherClient(transport).fetchBody(WeatherSource.CWA, WeatherLocation.SCHOOL, secret)
        val request = transport.requests.single()
        assertEquals(secret, request.header("Authorization"))
        assertFalse(request.url.contains(secret))
        assertTrue(request.url.startsWith("https://opendata.cwa.gov.tw/api/v1/rest/datastore/F-D0047-005?LocationName="))
        assertNull(request.header("Cookie"))
        val weather = WeatherParser.parse(WeatherSource.CWA, raw, parseInstant("2026-08-30T05:00:00Z"), WeatherLocation.SCHOOL)
        assertEquals(28, weather.temperatureCelsius)
        assertEquals(31, weather.apparentTemperatureCelsius)
        assertEquals("多雲短暫雨", weather.condition)
        assertEquals(40, weather.precipitationProbability)
        assertEquals(dateTime(2026, 8, 30, 12, 0), weather.precipitationStart)
        assertEquals(dateTime(2026, 8, 30, 18, 0), weather.precipitationEndExclusive)
        assertFailsWith<IllegalArgumentException> {
            PublicWeatherClient(transport).fetchBody(WeatherSource.CWA, WeatherLocation.SCHOOL, null)
        }
    }

    @Test
    fun cwaChoosesNearestPointAndRefusesPointsFromAnEarlierDay() {
        val nearest = WeatherParser.parse(
            WeatherSource.CWA, PublicFixtures.CWA_NEAREST_POINT_RESPONSE, parseInstant("2026-08-30T03:30:00Z"), WeatherLocation.SCHOOL,
        )
        assertEquals(28, nearest.temperatureCelsius)
        assertEquals("晴", nearest.condition)
        assertFailsWith<Exception> {
            WeatherParser.parse(WeatherSource.CWA, PublicFixtures.CWA_ALL_PAST_RESPONSE, parseInstant("2026-08-30T03:30:00Z"), WeatherLocation.SCHOOL)
        }
        assertFailsWith<Exception> {
            WeatherParser.parse(WeatherSource.CWA, """{"success":"false"}""", fetchedAt, WeatherLocation.SCHOOL)
        }
    }

    @Test
    fun cwaKeyValidationDistinguishesFormatAuthorizationAndApiRejection() = runTest {
        var next = response(PublicFixtures.CWA_ALL_PAST_RESPONSE)
        val transport = FakeHttpTransport { next }
        val client = PublicWeatherClient(transport)
        assertEquals(CwaKeyValidation.VALID, client.validateCwaKey("  key  "))
        assertEquals("key", transport.requests.single().header("Authorization"))
        next = response("""{"success":"false"}""")
        assertEquals(CwaKeyValidation.API_REJECTED, client.validateCwaKey("key"))
        next = response(status = 401)
        assertEquals(CwaKeyValidation.INVALID_AUTHORIZATION, client.validateCwaKey("key"))
        next = response(status = 500)
        assertEquals(CwaKeyValidation.UNAVAILABLE, client.validateCwaKey("key"))
        val count = transport.requests.size
        assertEquals(CwaKeyValidation.INVALID_FORMAT, client.validateCwaKey(" "))
        assertEquals(CwaKeyValidation.INVALID_FORMAT, client.validateCwaKey("a\nb"))
        assertEquals(CwaKeyValidation.INVALID_FORMAT, client.validateCwaKey("a".repeat(257)))
        assertEquals(count, transport.requests.size)
    }

    @Test
    fun weatherRejectsLocationsOutsideTheSupportedContract() {
        val abroad = WeatherLocation.SCHOOL.copy(timezoneId = "Asia/Tokyo")
        assertFailsWith<IllegalArgumentException> { WeatherParser.requireSupported(WeatherSource.OPEN_METEO, abroad) }
        assertFailsWith<IllegalArgumentException> {
            WeatherParser.requireSupported(WeatherSource.CWA, WeatherLocation.SCHOOL.copy(cwaDataset = null))
        }
        assertFailsWith<IllegalArgumentException> {
            WeatherParser.requireSupported(WeatherSource.CWA, WeatherLocation.SCHOOL.copy(cwaDataset = "../secret"))
        }
        assertFailsWith<IllegalArgumentException> {
            WeatherParser.requireSupported(WeatherSource.OPEN_METEO, WeatherLocation.SCHOOL.copy(latitude = 91.0))
        }
    }

    // YouBike

    private fun station(vararg overrides: Pair<String, String?>): String {
        val fields = linkedMapOf<String, String?>(
            "station_no" to "\"500304002\"", "name_tw" to "\"中壢高中\"", "district_tw" to "\"中壢區\"",
            "address_tw" to "\"三光路115號\"", "parking_spaces" to "52", "available_spaces" to "4", "empty_spaces" to "48",
            "status" to "1", "available_spaces_detail" to """{"yb2":"3","eyb":"1"}""", "updated_at" to "\"2026-08-09 08:00:00\"",
        )
        overrides.forEach { (key, value) -> fields[key] = value }
        return fields.filterValues { it != null }.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    @Test
    fun youBikeSnapshotValidatesCountsAndConvertsSchoolTime() {
        val snapshot = YouBikeFeed.snapshot("[${station()}]", fetchedAt)
        assertEquals(4, snapshot.availableBikes)
        assertEquals(48, snapshot.emptyDocks)
        assertEquals(52, snapshot.totalDocks)
        assertEquals(3, snapshot.ordinaryBikes)
        assertEquals(1, snapshot.electricBikes)
        assertTrue(snapshot.isOperating)
        assertEquals(fetchedAt.epochMillis(), snapshot.updatedAt.epochMillis())
        for (detail in listOf(null, "\"broken\"", """{"yb2":"4","eyb":"1"}""", """{"yb2":"-1","eyb":"5"}""")) {
            val partial = YouBikeFeed.snapshot("[${station("available_spaces_detail" to detail)}]", fetchedAt)
            assertEquals(4, partial.availableBikes)
            assertNull(partial.ordinaryBikes)
        }
        assertFalse(YouBikeFeed.snapshot("[${station("status" to "2")}]", fetchedAt).isOperating)
        val invalid = listOf(
            "station_no" to "\"other\"", "available_spaces" to "-1", "empty_spaces" to "53", "parking_spaces" to "\"invalid\"",
            "status" to "3", "updated_at" to "\"2026-02-30 12:43:03\"", "updated_at" to "\"2026-08-09T08:00:00\"",
        )
        for ((field, value) in invalid) {
            assertFailsWith<Exception>("$field=$value") { YouBikeFeed.snapshot("[${station(field to value)}]", fetchedAt) }
        }
        assertFailsWith<Exception> { YouBikeFeed.snapshot("[]", fetchedAt) }
        assertFailsWith<Exception> { YouBikeFeed.snapshot("<html>Request Rejected</html>", fetchedAt) }
        assertFailsWith<Exception> { YouBikeFeed.snapshot("[${station()},${station()}]", fetchedAt) }
        assertEquals(4, YouBikeFeed.snapshot("[${station("station_no" to "\"other\"")},${station()}]", fetchedAt).availableBikes)
    }

    @Test
    fun youBikeSearchMatchesNameDistrictAndAddressAndRequestHasNoCredentials() = runTest {
        val feed = "[${station()},${station("station_no" to "\"2\"", "name_tw" to "\"台北車站\"", "district_tw" to "\"中正區\"", "address_tw" to "\"忠孝西路\"")}]"
        assertEquals(listOf("500304002"), YouBikeFeed.search(feed, "三光路").map(YouBikeStation::id))
        assertEquals(listOf("2"), YouBikeFeed.search(feed, "臺北").map(YouBikeStation::id))
        assertTrue(YouBikeFeed.search(feed, "  ").isEmpty())
        val transport = FakeHttpTransport { response(feed, setCookies = listOf(cookie("a", "b"))) }
        val client = PublicYouBikeClient(transport)
        client.fetchFeedBody()
        client.fetchFeedBody()
        assertEquals("https://apis.youbike.com.tw/json/station-yb2.json", transport.requests.first().url)
        transport.requests.forEach { assertTrue(it.headers.isEmpty()) }
    }

    // URL helpers

    @Test
    fun urlsResolveEncodeAndDecodeLikeTheWireFormatExpects() {
        val base = PortableUrl.parse("https://Example.org:8443/a/b/c?x=1")!!
        assertEquals("https://example.org:8443/a/b/c?x=1", base.toString())
        assertEquals("https://example.org:8443/d", base.resolve("/d").toString())
        assertEquals("https://example.org:8443/a/b/d?y=2", base.resolve("d?y=2").toString())
        assertEquals("https://example.org:8443/a/d", base.resolve("../d").toString())
        assertEquals("https://other.example/z", base.resolve("//other.example/z").toString())
        assertEquals("http://plain.example/", base.resolve("http://plain.example").toString())
        assertNull(PortableUrl.parse("ftp://example.org/"))
        assertNull(PortableUrl.parse("https:///nohost"))
        assertTrue(PortableUrl.parse("https://user:pw@example.org/")!!.hasUserInfo)
        assertEquals("a=%E4%B8%AD+%E6%96%87&b%5B0%5D=1%2B1", PortableUrl.formEncode(listOf("a" to "中 文", "b[0]" to "1+1")))
        assertEquals("中 文+", PortableUrl.percentDecode("%E4%B8%AD%20%E6%96%87+"))
        assertFailsWith<Exception> { PortableUrl.percentDecode("%E4%") }
        assertFailsWith<Exception> { PortableUrl.percentDecode("%ZZ") }
    }

    @Test
    fun rawQueryCharactersFromAServerAreEncodedButExistingEscapesAreKept() {
        val parsed = PortableUrl.parse("https://example.org/p?q=中 文&r=%E4%B8%AD&s=\"x\"")!!
        assertEquals("q=%E4%B8%AD%20%E6%96%87&r=%E4%B8%AD&s=%22x%22", parsed.encodedQuery)
        val redirected = parsed.resolve("/next?ReturnUrl=/a b&page=成績")!!
        assertEquals("https://example.org/next?ReturnUrl=/a%20b&page=%E6%88%90%E7%B8%BE", redirected.toString())
    }
}
