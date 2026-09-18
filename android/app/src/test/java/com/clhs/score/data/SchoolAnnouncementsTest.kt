package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
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
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

class SchoolAnnouncementsTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parserReadsRecordedListShapeWithoutFilteringOrReordering() {
        val page = SchoolAnnouncementParser.parsePage(
            RECORDED_LIST_JSON,
            Instant.parse("2026-08-09T00:00:00Z"),
        )

        assertEquals(0, page.pageIndex)
        assertEquals(7532, page.totalPages)
        assertEquals(listOf("45072", "45068", "external"), page.announcements.map { it.id })
        assertEquals(listOf("公告", "最新消息", "通知"), page.announcements.map { it.category })
        assertEquals(listOf("讀者服務組", "設備組", "學務處"), page.announcements.map { it.unit })
        assertTrue(page.announcements.first().isPinned)
        assertEquals("https://example.org/activity", page.announcements.last().externalUrl)
    }

    @Test
    fun repositoryPostsEmptyFlockCachesFirstPageAndNeverStoresCookies() = runTest {
        server.enqueue(jsonResponse(RECORDED_LIST_JSON).setHeader("Set-Cookie", "session=must-not-return"))
        server.enqueue(jsonResponse(RECORDED_LIST_JSON))
        val cacheDirectory = Files.createTempDirectory("school-announcement-test").toFile()
        val fetchedAt = Instant.parse("2026-08-09T00:00:00Z")
        val repository = NetworkSchoolAnnouncementsRepository(
            cacheDirectory = cacheDirectory,
            baseUrl = server.url("/"),
            nowProvider = { fetchedAt },
        )

        try {
            val network = repository.loadPage(0)
            val cached = repository.loadCached()
            repository.loadPage(1)

            assertEquals(network.announcements, cached?.announcements)
            assertEquals(fetchedAt, cached?.fetchedAt)
            val first = server.takeRequest()
            val second = server.takeRequest()
            val firstBody = first.body.readUtf8()
            assertEquals("POST", first.method)
            assertTrue(firstBody.contains("flock="))
            assertTrue(firstBody.contains("maxRows=20"))
            assertFalse(firstBody.contains("unit="))
            assertNull(first.getHeader("Cookie"))
            assertNull(second.getHeader("Cookie"))
            assertTrue(second.body.readUtf8().contains("pageNum=1"))
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun unitParserRejectsInvalidIdsAndDeduplicatesIdsAndNames() {
        val units = SchoolAnnouncementParser.parseUnits(
            """
            <select class="select-unit-x">
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
    }

    @Test
    fun unitRepositoryKeepsLastSuccessfulCacheAndCanRetryAfterFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "<select class='select-unit-x'><option value='-1'>全部</option><option value='425'>首頁</option></select>",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500))
        val cacheDirectory = Files.createTempDirectory("announcement-unit-test").toFile()
        val repository = NetworkSchoolAnnouncementsRepository(cacheDirectory, baseUrl = server.url("/"))

        try {
            assertEquals(defaultAnnouncementUnits(), repository.loadCachedUnits())
            runCatching { repository.loadUnits() }
            assertEquals(listOf("-1"), repository.loadCachedUnits().map(AnnouncementUnit::id))
            assertEquals(listOf("-1", "425"), repository.loadUnits().map(AnnouncementUnit::id))
            runCatching { repository.loadUnits() }
            assertEquals(listOf("-1", "425"), repository.loadCachedUnits().map(AnnouncementUnit::id))
            assertEquals("/home", server.takeRequest().path)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun repositoryPostsValidatedUnitWithoutCookies() = runTest {
        server.enqueue(jsonResponse(RECORDED_LIST_JSON))
        val cacheDirectory = Files.createTempDirectory("announcement-unit-post-test").toFile()
        val repository = NetworkSchoolAnnouncementsRepository(cacheDirectory, baseUrl = server.url("/"))

        try {
            repository.loadPage(0, unitId = "425")
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("flock=unit_425"))
            assertFalse(body.contains("&unit="))
            assertNull(request.getHeader("Cookie"))
            assertTrue(runCatching { repository.loadPage(0, unitId = "bad") }.isFailure)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun repositorySearchUsesUtf8KeywordForEveryPageWithoutReplacingDefaultCache() = runTest {
        server.enqueue(jsonResponse(RECORDED_LIST_JSON))
        server.enqueue(jsonResponse(RECORDED_SEARCH_JSON))
        server.enqueue(jsonResponse(RECORDED_SEARCH_JSON.replace("\"pageNum\":0", "\"pageNum\":1")))
        val cacheDirectory = Files.createTempDirectory("school-announcement-search-test").toFile()
        val repository = NetworkSchoolAnnouncementsRepository(
            cacheDirectory = cacheDirectory,
            baseUrl = server.url("/"),
        )

        try {
            val defaultPage = repository.loadPage(0)
            repository.loadPage(0, " 模擬考 ")
            repository.loadPage(1, "模擬考")
            val cached = repository.loadCached()

            assertEquals(defaultPage.announcements, cached?.announcements)
            val defaultRequest = server.takeRequest()
            val firstSearchRequest = server.takeRequest()
            val secondSearchRequest = server.takeRequest()
            assertTrue(defaultRequest.body.readUtf8().contains("keyword="))
            assertTrue(firstSearchRequest.body.readUtf8().contains("keyword=%E6%A8%A1%E6%93%AC%E8%80%83"))
            val secondBody = secondSearchRequest.body.readUtf8()
            assertTrue(secondBody.contains("keyword=%E6%A8%A1%E6%93%AC%E8%80%83"))
            assertTrue(secondBody.contains("pageNum=1"))
            assertNull(firstSearchRequest.getHeader("Cookie"))
            assertNull(secondSearchRequest.getHeader("Cookie"))
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun cancellingPageLoadStopsTheBlockingHttpCall() = runBlocking {
        repeat(20) {
            server.enqueue(jsonResponse(RECORDED_LIST_JSON).setBodyDelay(5, TimeUnit.SECONDS))
            val cacheDirectory = Files.createTempDirectory("school-announcement-cancel-test").toFile()
            val repository = NetworkSchoolAnnouncementsRepository(
                cacheDirectory = cacheDirectory,
                baseUrl = server.url("/"),
            )

            try {
                val load = launch(Dispatchers.Default) { repository.loadPage(0) }
                assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
                load.cancel()

                withTimeout(1.seconds) { load.join() }
                assertTrue(load.isCancelled)
            } finally {
                cacheDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun detailParserSanitizesActiveContentAndDecodesOfficialAttachments() {
        val detail = SchoolAnnouncementParser.parseDetail(RECORDED_DETAIL_JSON, "45072", "公告")

        assertEquals("公告", detail.category)
        assertTrue(detail.htmlContent.contains("閉館日期"))
        assertTrue(detail.htmlContent.contains("https://www.clhs.tyc.edu.tw/notice"))
        assertFalse(detail.htmlContent.contains("script", ignoreCase = true))
        assertFalse(detail.htmlContent.contains("javascript:", ignoreCase = true))
        assertTrue(detail.htmlContent.contains("⚠️ 此內容包含表格"))
        assertEquals(1, detail.htmlContent.windowed("⚠️ 此內容包含表格".length).count { it == "⚠️ 此內容包含表格" })
        assertEquals(2, detail.images.size)
        assertEquals(1, detail.images.count { it.canPreview })
        assertTrue(detail.images.first { it.canPreview }.url.startsWith("https://www.clhs.tyc.edu.tw/"))
        assertEquals("八月開館時間.pdf", detail.attachments.single().name)
        assertEquals(245_760L, detail.attachments.single().sizeBytes)
        assertTrue(detail.attachments.single().url.endsWith("/%E5%85%AB%E6%9C%88%E9%96%8B%E9%A4%A8%E6%99%82%E9%96%93.pdf"))
        assertNull(safeAnnouncementWebUrl("javascript:alert(1)"))
    }

    @Test
    fun duplicateAttachmentsAreCollapsedByUrl() {
        val detail = SchoolAnnouncementParser.parseDetail(
            """
            [{
              "rcode":200,
              "newsId":"45072",
              "content":"",
              "attachedfile":"[[\"opaque\",123,\"same.pdf\"],[\"opaque\",123,\"same.pdf\"]]"
            }]
            """.trimIndent(),
            "45072",
            "公告",
        )

        assertEquals("same.pdf", detail.attachments.single().name)
    }

    @Test
    fun oversizedSchoolHeadingsBecomeBoldBodyParagraphs() {
        val detail = SchoolAnnouncementParser.parseDetail(HEADING_DETAIL_JSON, "45049", "公告")

        assertFalse(detail.htmlContent.contains("<h1", ignoreCase = true))
        assertTrue(detail.htmlContent.contains("<p><strong>1.學生名單請參閱附加檔案</strong></p>"))
    }

    @Test
    fun repositoryUsesTwoStepUidFlowForDetail() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<script>var g_news_unique_id = \"public-detail-uid\";</script>"),
        )
        server.enqueue(jsonResponse(RECORDED_DETAIL_JSON))
        val cacheDirectory = Files.createTempDirectory("school-announcement-detail-test").toFile()
        val repository = NetworkSchoolAnnouncementsRepository(
            cacheDirectory = cacheDirectory,
            baseUrl = server.url("/"),
        )

        try {
            val detail = repository.loadDetail("45072", "公告")

            assertEquals("45072", detail.id)
            assertEquals("/ischool/public/news_view/show.php?nid=45072", server.takeRequest().path)
            assertEquals(
                "/ischool/widget/site_news/news_query_json_content.php?nid=45072&dir=0&uid=public-detail-uid",
                server.takeRequest().path,
            )
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun repositoryAllowsUidPageLargerThanLegacyViewLimit() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    "<script>var g_news_unique_id = \"public-detail-uid\";</script>"
                        .padEnd(600 * 1024, 'x'),
                ),
        )
        server.enqueue(jsonResponse(RECORDED_DETAIL_JSON))
        val cacheDirectory = Files.createTempDirectory("school-announcement-large-view-test").toFile()
        val repository = NetworkSchoolAnnouncementsRepository(
            cacheDirectory = cacheDirectory,
            baseUrl = server.url("/"),
        )

        try {
            assertEquals("45072", repository.loadDetail("45072", "公告").id)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private companion object {
        val RECORDED_LIST_JSON = """
            [
              {"pageNum":0,"maxRows":2,"totalPages":7532},
              {"newsId":"45072","top":1,"time":"2026/08/06","attr":"1","attr_name":"公告","title":"圖書館八月閉館時間異動公告","unit":"399","unit_name":"圖書館官網","issuer":"100103","name":"讀者服務組","content_type":"content","content":null},
              {"newsId":"45068","top":"0","time":"2026/08/06","attr_name":"最新消息","title":"中央研究院高中生命科學研究人才培育計畫","unit_name":"設備組官網","name":"設備組","content_type":"content"},
              {"newsId":"external","top":false,"time":"","attr_name":"通知","title":"校外活動","unit_name":"學務處","content_type":"url","content":"https://example.org/activity","future_field":"ignored"}
            ]
        """.trimIndent()

        val RECORDED_SEARCH_JSON = """
            [
              {"pageNum":0,"maxRows":20,"totalPages":2},
              {"newsId":"search-result","top":0,"time":"2026/08/17","attr_name":"公告","title":"高三第二次模擬考日程表","name":"教學組","content_type":"content"}
            ]
        """.trimIndent()

        val RECORDED_DETAIL_JSON = """
            [{
              "rcode":200,
              "newsId":"45072",
              "time":"2026-08-06 11:38:16",
              "title":"圖書館八月閉館時間異動公告",
              "unit":"圖書館官網",
              "issuer":"讀者服務組",
              "content":"%3Cscript%3Ealert(1)%3C%2Fscript%3E%3Cp%3E%3Cstrong%3E%E9%96%89%E9%A4%A8%E6%97%A5%E6%9C%9F%3C%2Fstrong%3E%3Ca%20href%3D%22%2Fnotice%22%3E%E8%A9%B3%E6%83%85%3C%2Fa%3E%3Ca%20href%3D%22javascript%3Aalert(1)%22%3Ebad%3C%2Fa%3E%3C%2Fp%3E%3Cimg%20src%3D%22%2Fischool%2Fstatic%2Fimage%2Fnews.jpg%22%20alt%3D%22%E5%85%AC%E5%91%8A%E5%9C%96%E7%89%87%22%3E%3Cimg%20src%3D%22https%3A%2F%2Ftracker.example%2Fpixel.png%22%3E%3Ctable%3E%3Ctr%3E%3Ctd%3Edata%3C%2Ftd%3E%3C%2Ftr%3E%3C%2Ftable%3E%3Ctable%3E%3Ctr%3E%3Ctd%3Emore%3C%2Ftd%3E%3C%2Ftr%3E%3C%2Ftable%3E",
              "content_type":"content",
              "attachedfile":"[[\"opaque\",245760,\"%u516B%u6708%u958B%u9928%u6642%u9593.pdf\"]]"
            }]
        """.trimIndent()

        val HEADING_DETAIL_JSON = """
            [{
              "rcode":200,
              "newsId":"45049",
              "title":"重補修課程開課時間及學生名單公告",
              "content":"%3Ch1%3E%3Cspan%20style%3D%22font-size%3A%2020px%3B%22%3E1.%E5%AD%B8%E7%94%9F%E5%90%8D%E5%96%AE%E8%AB%8B%E5%8F%83%E9%96%B1%E9%99%84%E5%8A%A0%E6%AA%94%E6%A1%88%3C%2Fspan%3E%3C%2Fh1%3E",
              "attachedfile":"[]"
            }]
        """.trimIndent()
    }
}
