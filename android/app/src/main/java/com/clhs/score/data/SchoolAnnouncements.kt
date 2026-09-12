package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URI
import java.time.Instant

const val SCHOOL_ANNOUNCEMENTS_WEB_URL = "https://www.clhs.tyc.edu.tw/ischool/publish_page/0/"
const val SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH = 100

data class SchoolAnnouncement(
    val id: String,
    val title: String,
    val date: String,
    val category: String,
    val unit: String,
    val issuer: String,
    val isPinned: Boolean,
    val contentType: String,
    val externalUrl: String? = null,
)

data class AnnouncementUnit(
    val id: String,
    val name: String,
)

data class SchoolAnnouncementPage(
    val announcements: List<SchoolAnnouncement>,
    val pageIndex: Int,
    val totalPages: Int,
    val fetchedAt: Instant,
)

data class SchoolAnnouncementImage(
    val url: String,
    val description: String,
    val canPreview: Boolean,
)

data class SchoolAnnouncementAttachment(
    val name: String,
    val sizeBytes: Long?,
    val url: String,
)

data class SchoolAnnouncementDetail(
    val id: String,
    val title: String,
    val date: String,
    val category: String,
    val unit: String,
    val issuer: String,
    val htmlContent: String,
    val images: List<SchoolAnnouncementImage>,
    val attachments: List<SchoolAnnouncementAttachment>,
    val officialUrl: String,
)

class NetworkSchoolAnnouncementsRepository(
    cacheDirectory: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .build(),
    private val baseUrl: HttpUrl = SCHOOL_BASE_URL,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val cache = LatestCacheFile(cacheDirectory, CACHE_FILE_NAME)
    private val cacheFile = cache.file
    private val unitsCache = LatestCacheFile(cacheDirectory, UNITS_CACHE_FILE_NAME)
    private val unitsCacheFile = unitsCache.file

    suspend fun loadCached(): SchoolAnnouncementPage? = withContext(Dispatchers.IO) {
        readCache()
    }

    suspend fun loadPage(pageIndex: Int, keyword: String = "", unitId: String = ALL_UNITS_ID): SchoolAnnouncementPage {
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        val normalizedKeyword = keyword.trim()
        require(normalizedKeyword.length <= SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH) {
            "keyword is too long"
        }
        require(ANNOUNCEMENT_UNIT_ID_REGEX.matches(unitId)) { "Invalid announcement unit" }
        val cacheGeneration = if (
            pageIndex == 0 && normalizedKeyword.isEmpty() && unitId == ALL_UNITS_ID
        ) {
            cache.beginWrite()
        } else {
            null
        }
        val requestContext = currentCoroutineContext()
        return runInterruptibleHttp {
            val requestBody = FormBody.Builder()
                .add("auth_type", "user")
                .add("field", "time")
                .add("flock", if (unitId == ALL_UNITS_ID) "" else "unit_$unitId")
                .add("keyword", normalizedKeyword)
                .add("maxRows", PAGE_SIZE.toString())
                .add("order", "DESC")
                .add("pageNum", pageIndex.toString())
                .add("tf", "1")
                .add("uid", LIST_WIDGET_UID)
                .add("use_cache", "0")
                .build()
            val request = Request.Builder()
                .url(endpoint("ischool/widget/site_news/news_query_json.php"))
                .post(requestBody)
                .build()
            val bytes = executeLimited(request, MAX_LIST_BYTES, "Announcement list")
            val fetchedAt = nowProvider()
            val page = SchoolAnnouncementParser.parsePage(bytes.toString(Charsets.UTF_8), fetchedAt)
            if (cacheGeneration != null) {
                cache.commit(bytes, fetchedAt.toEpochMilli(), cacheGeneration, requestContext)
            }
            page
        }
    }

    suspend fun loadCachedUnits(): List<AnnouncementUnit> = withContext(Dispatchers.IO) {
        readUnitsCache()
    }

    suspend fun loadUnits(): List<AnnouncementUnit> {
        val generation = unitsCache.beginWrite()
        val requestContext = currentCoroutineContext()
        return runInterruptibleHttp {
            val request = Request.Builder().url(endpoint("home")).get().build()
            val bytes = executeLimited(request, MAX_HOME_BYTES, "Announcement units")
            val units = SchoolAnnouncementParser.parseUnits(bytes.toString(Charsets.UTF_8))
            unitsCache.commit(bytes, nowProvider().toEpochMilli(), generation, requestContext)
            units
        }
    }

    suspend fun loadDetail(id: String, categoryHint: String = ""): SchoolAnnouncementDetail =
        runInterruptibleHttp {
            require(id.isNotBlank()) { "Announcement id is required" }
            val officialUrl = announcementUrl(id, baseUrl)
            val viewRequest = Request.Builder().url(officialUrl).get().build()
            val viewHtml = executeLimited(viewRequest, MAX_VIEW_BYTES, "Announcement page")
                .toString(Charsets.UTF_8)
            val uid = UNIQUE_ID_REGEX.find(viewHtml)?.groupValues?.getOrNull(1)
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("Announcement identifier is missing")
            val contentUrl = endpoint("ischool/widget/site_news/news_query_json_content.php")
                .newBuilder()
                .addQueryParameter("nid", id)
                .addQueryParameter("dir", "0")
                .addQueryParameter("uid", uid)
                .build()
            val contentRequest = Request.Builder().url(contentUrl).get().build()
            val contentJson = executeLimited(contentRequest, MAX_DETAIL_BYTES, "Announcement detail")
                .toString(Charsets.UTF_8)
            SchoolAnnouncementParser.parseDetail(contentJson, id, categoryHint)
        }

    private fun endpoint(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    private fun executeLimited(request: Request, maxBytes: Long, label: String): ByteArray {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$label request failed with HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > maxBytes) throw IOException("$label response is too large")
            return readLimited(body.source(), maxBytes, label)
        }
    }

    private fun readCache(): SchoolAnnouncementPage? {
        if (!cacheFile.isFile || cacheFile.length() !in 1..MAX_LIST_BYTES) return null
        return runCatching {
            SchoolAnnouncementParser.parsePage(
                cacheFile.readText(Charsets.UTF_8),
                Instant.ofEpochMilli(cacheFile.lastModified()),
            )
        }.getOrNull()
    }

    private fun readUnitsCache(): List<AnnouncementUnit> {
        if (!unitsCacheFile.isFile || unitsCacheFile.length() !in 1..MAX_HOME_BYTES) {
            return defaultAnnouncementUnits()
        }
        return runCatching {
            SchoolAnnouncementParser.parseUnits(unitsCacheFile.readText(Charsets.UTF_8))
        }.getOrElse { defaultAnnouncementUnits() }
    }

    private fun readLimited(source: okio.BufferedSource, maxBytes: Long, label: String): ByteArray {
        val buffer = Buffer()
        var total = 0L
        while (total <= maxBytes) {
            val read = source.read(buffer, minOf(8_192L, maxBytes + 1L - total))
            if (read == -1L) break
            total += read
        }
        if (total > maxBytes) throw IOException("$label response is too large")
        return buffer.readByteArray()
    }

    private companion object {
        const val CACHE_FILE_NAME = "school-announcements.json"
        const val UNITS_CACHE_FILE_NAME = "school-announcement-units.html"
        const val LIST_WIDGET_UID = "WID_549_2_3e2e399a2649fb6ba9918090490f4741fd4453bf"
        const val PAGE_SIZE = 20
        const val MAX_LIST_BYTES = 1024L * 1024
        const val MAX_VIEW_BYTES = 2L * 1024 * 1024
        const val MAX_DETAIL_BYTES = 2L * 1024 * 1024
        const val MAX_HOME_BYTES = 2L * 1024 * 1024
        val UNIQUE_ID_REGEX = Regex("""g_news_unique_id\s*=\s*["']([^"']+)["']""")
    }
}

internal object SchoolAnnouncementParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val articleSafelist = Safelist.none()
        .addTags(
            "p", "div", "br", "b", "strong", "i", "em", "u", "h1", "h2", "h3", "h4",
            "h5", "h6", "ul", "ol", "li", "a", "span", "blockquote", "hr", "sub", "sup",
        )
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https")

    fun parsePage(source: String, fetchedAt: Instant): SchoolAnnouncementPage {
        val array = parseArray(source, "Invalid announcement list")
        val metadata = array.firstOrNull() as? JsonObject
            ?: throw IOException("Invalid announcement list")
        val pageIndex = metadata.int("pageNum") ?: 0
        val totalPages = metadata.int("totalPages")?.coerceAtLeast(0) ?: 0
        val announcements = array.drop(1).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("newsId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val contentType = item.string("content_type").orEmpty().ifBlank { "content" }
            SchoolAnnouncement(
                id = id,
                title = item.string("title").orEmpty().ifBlank { "未命名消息" },
                date = item.string("time").orEmpty(),
                category = item.string("attr_name") ?: item.string("attr").orEmpty(),
                unit = item.string("name") ?: item.string("unit_name") ?: item.string("unit").orEmpty(),
                issuer = item.string("name") ?: item.string("issuer").orEmpty(),
                isPinned = item.isPinned(),
                contentType = contentType,
                externalUrl = if (contentType.equals("url", ignoreCase = true)) {
                    safeAnnouncementWebUrl(item.string("content"))
                } else {
                    null
                },
            )
        }
        return SchoolAnnouncementPage(
            announcements = announcements.distinctBy(SchoolAnnouncement::id),
            pageIndex = pageIndex,
            totalPages = totalPages,
            fetchedAt = fetchedAt,
        )
    }

    fun parseUnits(source: String): List<AnnouncementUnit> {
        val units = Jsoup.parse(source, SCHOOL_BASE_URL.toString())
            .select("select.select-unit-x option[value]")
            .mapNotNull { option ->
                val id = option.attr("value").trim()
                val name = option.text().trim()
                if (!ANNOUNCEMENT_UNIT_ID_REGEX.matches(id) || name.isBlank()) null else AnnouncementUnit(id, name)
            }
            .distinctBy(AnnouncementUnit::id)
        if (units.isEmpty()) throw IOException("Invalid announcement units")
        return (defaultAnnouncementUnits() + units)
            .distinctBy(AnnouncementUnit::id)
            .distinctBy(AnnouncementUnit::name)
    }

    fun parseDetail(source: String, requestedId: String, categoryHint: String = ""): SchoolAnnouncementDetail {
        val item = parseArray(source, "Invalid announcement detail").firstOrNull() as? JsonObject
            ?: throw IOException("Invalid announcement detail")
        if (item.int("rcode")?.let { it != 200 } == true) throw IOException("Announcement detail is unavailable")
        val id = item.string("newsId")?.takeIf(String::isNotBlank) ?: requestedId
        val decodedHtml = decodePercentEncoded(item.string("content").orEmpty())
        val article = parseArticle(decodedHtml)
        return SchoolAnnouncementDetail(
            id = id,
            title = item.string("title").orEmpty().ifBlank { "未命名消息" },
            date = item.string("time").orEmpty(),
            category = categoryHint,
            unit = item.string("unit").orEmpty(),
            issuer = item.string("issuer").orEmpty(),
            htmlContent = article.html,
            images = article.images,
            attachments = parseAttachments(item.string("attachedfile"), id),
            officialUrl = announcementUrl(id),
        )
    }

    internal fun decodeLegacyFileName(source: String): String {
        val unicodeDecoded = LEGACY_UNICODE_REGEX.replace(source) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return runCatching {
            URLDecoder.decode(unicodeDecoded.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(unicodeDecoded)
    }

    private fun parseArticle(source: String): ParsedArticle {
        val document = Jsoup.parseBodyFragment(source, SCHOOL_BASE_URL.toString())
        document.select("script,style,iframe,form,object,embed").remove()
        document.select("h1,h2,h3,h4,h5,h6").forEach { heading ->
            heading.select("span").forEach { it.unwrap() }
            heading.tagName("p")
            if (heading.children().singleOrNull()?.tagName() != "strong") {
                heading.html("<strong>${heading.html()}</strong>")
            }
        }
        val images = document.select("img[src]").mapNotNull { image ->
            val url = safeAnnouncementWebUrl(image.absUrl("src").ifBlank { image.attr("src") })
                ?: return@mapNotNull null
            SchoolAnnouncementImage(
                url = url,
                description = image.attr("alt").trim().ifBlank { "消息圖片" },
                canPreview = isOfficialPreviewImage(url),
            )
        }.distinctBy(SchoolAnnouncementImage::url)
        document.select("table").let { tables ->
            tables.firstOrNull()?.before(
                "<p><strong>⚠️ 此內容包含表格</strong></p><p>請點擊下方「<strong>查看原文</strong>」來查看完整表格內容。</p>",
            )
            tables.remove()
        }
        document.select("img").remove()
        val outputSettings = Document.OutputSettings().prettyPrint(false)
        val html = Jsoup.clean(
            document.body().html(),
            SCHOOL_BASE_URL.toString(),
            articleSafelist,
            outputSettings,
        ).trim()
        return ParsedArticle(html, images)
    }

    private fun parseAttachments(source: String?, newsId: String): List<SchoolAnnouncementAttachment> {
        if (source.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(source) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val fields = element as? JsonArray ?: return@mapNotNull null
            val encodedName = (fields.getOrNull(2) as? JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = decodeLegacyFileName(encodedName).trim().ifBlank { return@mapNotNull null }
            SchoolAnnouncementAttachment(
                name = name,
                sizeBytes = (fields.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                url = attachmentUrl(newsId, name),
            )
        }.distinctBy(SchoolAnnouncementAttachment::url)
    }

    private fun parseArray(source: String, errorMessage: String): JsonArray =
        runCatching { json.parseToJsonElement(source) as? JsonArray }
            .getOrNull() ?: throw IOException(errorMessage)

    private fun decodePercentEncoded(source: String): String = runCatching {
        URLDecoder.decode(source.replace("+", "%2B"), "UTF-8")
    }.getOrElse { throw IOException("Invalid announcement content", it) }

    private data class ParsedArticle(
        val html: String,
        val images: List<SchoolAnnouncementImage>,
    )

    private val LEGACY_UNICODE_REGEX = Regex("%u([0-9a-fA-F]{4})")
}

internal fun safeAnnouncementWebUrl(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val explicitScheme = runCatching { URI(raw).scheme?.lowercase() }.getOrNull()
    if (explicitScheme != null && explicitScheme !in setOf("http", "https")) return null
    val url = raw.toHttpUrlOrNull() ?: SCHOOL_BASE_URL.resolve(raw) ?: return null
    if (url.scheme !in setOf("http", "https")) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.toString()
}

private fun isOfficialPreviewImage(value: String): Boolean =
    value.toHttpUrlOrNull()?.let { url ->
        url.isHttps && url.host.equals(SCHOOL_BASE_URL.host, ignoreCase = true)
    } == true

private fun announcementUrl(id: String, baseUrl: HttpUrl = SCHOOL_BASE_URL): String =
    baseUrl.newBuilder()
        .addPathSegments("ischool/public/news_view/show.php")
        .addQueryParameter("nid", id)
        .build()
        .toString()

internal fun schoolAnnouncementOfficialUrl(id: String): String = announcementUrl(id)

const val ALL_UNITS_ID = "-1"
internal val ANNOUNCEMENT_UNIT_ID_REGEX = Regex("-1|[0-9]+")

internal fun defaultAnnouncementUnits(): List<AnnouncementUnit> = listOf(AnnouncementUnit(ALL_UNITS_ID, "全部"))

private fun attachmentUrl(newsId: String, fileName: String): String =
    SCHOOL_BASE_URL.newBuilder()
        .addPathSegments("ischool/news/attached")
        .addPathSegment(newsId)
        .addPathSegment(fileName)
        .build()
        .toString()

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }

private fun JsonObject.isPinned(): Boolean =
    string("top")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true

private val SCHOOL_BASE_URL = "https://www.clhs.tyc.edu.tw/".toHttpUrl()
