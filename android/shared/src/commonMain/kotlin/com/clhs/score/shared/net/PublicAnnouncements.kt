package com.clhs.score.shared.net

import com.clhs.score.data.ALL_UNITS_ID
import com.clhs.score.data.ANNOUNCEMENT_UNIT_ID_REGEX
import com.clhs.score.data.AnnouncementCategory
import com.clhs.score.data.AnnouncementFilterOptions
import com.clhs.score.data.AnnouncementUnit
import com.clhs.score.data.SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementAttachment
import com.clhs.score.data.SchoolAnnouncementDetail
import com.clhs.score.data.SchoolAnnouncementImage
import com.clhs.score.data.SchoolAnnouncementPage
import com.clhs.score.data.defaultAnnouncementUnits
import com.clhs.score.data.int
import com.clhs.score.shared.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val PUBLIC_SCHOOL_SITE_URL = "https://www.clhs.tyc.edu.tw/"

/** Public school website announcements: list, filter options and article detail. Never authenticated. */
class PublicAnnouncementsClient(
    transport: HttpTransport,
    baseUrl: String = PUBLIC_SCHOOL_SITE_URL,
) {
    private val http = PublicHttp(transport)
    private val baseUrl = requireNotNull(PortableUrl.parse(baseUrl)) { "Invalid base URL" }

    /** Returns the raw response body; parse it with [PublicAnnouncementParser.parsePage]. */
    suspend fun fetchPageBody(
        pageIndex: Int,
        keyword: String = "",
        unitId: String = ALL_UNITS_ID,
        categoryId: String = ALL_UNITS_ID,
    ): String {
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        val normalizedKeyword = keyword.trim()
        require(normalizedKeyword.length <= SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH) { "keyword is too long" }
        require(ANNOUNCEMENT_UNIT_ID_REGEX.matches(unitId)) { "Invalid announcement unit" }
        require(ANNOUNCEMENT_UNIT_ID_REGEX.matches(categoryId)) { "Invalid announcement category" }
        return http.postForm(
            baseUrl.withPath("ischool/widget/site_news/news_query_json.php"),
            listOf(
                "auth_type" to "user",
                "field" to "time",
                "flock" to listOfNotNull(
                    unitId.takeUnless { it == ALL_UNITS_ID }?.let { "unit_$it" },
                    categoryId.takeUnless { it == ALL_UNITS_ID }?.let { "attr_$it" },
                ).joinToString("-"),
                "keyword" to normalizedKeyword,
                "maxRows" to PAGE_SIZE.toString(),
                "order" to "DESC",
                "pageNum" to pageIndex.toString(),
                "tf" to "1",
                "uid" to LIST_WIDGET_UID,
                "use_cache" to "0",
            ),
            "Announcement list", MAX_LIST_BYTES,
        )
    }

    /** Raw home page HTML; parse it with [PublicAnnouncementParser.parseFilterOptions]. */
    suspend fun fetchFilterOptionsBody(): String =
        http.get(baseUrl.withPath("home"), "Announcement units", MAX_HOME_BYTES)

    suspend fun fetchDetail(id: String, categoryHint: String = ""): SchoolAnnouncementDetail {
        require(id.isNotBlank()) { "Announcement id is required" }
        val viewHtml = http.get(
            baseUrl.withPath("ischool/public/news_view/show.php", listOf("nid" to id)), "Announcement page", MAX_VIEW_BYTES,
        )
        val uid = UNIQUE_ID_REGEX.find(viewHtml)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
            ?: throw PublicDataException("Announcement identifier is missing")
        val contentJson = http.get(
            baseUrl.withPath(
                "ischool/widget/site_news/news_query_json_content.php",
                listOf("nid" to id, "dir" to "0", "uid" to uid),
            ),
            "Announcement detail", MAX_DETAIL_BYTES,
        )
        return PublicAnnouncementParser.parseDetail(contentJson, id, categoryHint)
    }

    companion object {
        const val MAX_LIST_BYTES = 1024L * 1024
        const val MAX_HOME_BYTES = 2L * 1024 * 1024
        private const val LIST_WIDGET_UID = "WID_549_2_3e2e399a2649fb6ba9918090490f4741fd4453bf"
        private const val PAGE_SIZE = 20
        private const val MAX_VIEW_BYTES = 2L * 1024 * 1024
        private const val MAX_DETAIL_BYTES = 2L * 1024 * 1024
        private val UNIQUE_ID_REGEX = Regex("""g_news_unique_id\s*=\s*["']([^"']+)["']""")
    }
}

/** Portable replacement for the Jsoup-based Android `SchoolAnnouncementParser`; same accepted formats. */
object PublicAnnouncementParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val siteBase = requireNotNull(PortableUrl.parse(PUBLIC_SCHOOL_SITE_URL))
    private val allowedTags = setOf(
        "p", "div", "br", "b", "strong", "i", "em", "u", "h1", "h2", "h3", "h4",
        "h5", "h6", "ul", "ol", "li", "a", "span", "blockquote", "hr", "sub", "sup",
    )
    private val removedWithContent = setOf("script", "style", "iframe", "form", "object", "embed", "table")
    private val headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private const val TABLE_NOTICE =
        "<p><strong>⚠️ 此內容包含表格</strong></p><p>請點擊下方「<strong>查看原文</strong>」來查看完整表格內容。</p>"
    private val LEGACY_UNICODE_REGEX = Regex("%u([0-9a-fA-F]{4})")
    private val EXPLICIT_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun parsePage(source: String, fetchedAt: Instant): SchoolAnnouncementPage {
        val array = parseArray(source, "Invalid announcement list")
        val metadata = array.firstOrNull() as? JsonObject ?: throw PublicDataException("Invalid announcement list")
        val announcements = array.drop(1).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.text("newsId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val contentType = item.text("content_type").orEmpty().ifBlank { "content" }
            SchoolAnnouncement(
                id = id,
                title = item.text("title").orEmpty().ifBlank { "未命名消息" },
                date = item.text("time").orEmpty(),
                category = item.text("attr_name") ?: item.text("attr").orEmpty(),
                unit = item.text("name") ?: item.text("unit_name") ?: item.text("unit").orEmpty(),
                issuer = item.text("name") ?: item.text("issuer").orEmpty(),
                isPinned = item.text("top")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true,
                contentType = contentType,
                externalUrl = if (contentType.equals("url", ignoreCase = true)) safeWebUrl(item.text("content")) else null,
            )
        }
        return SchoolAnnouncementPage(
            announcements = announcements.distinctBy(SchoolAnnouncement::id),
            pageIndex = metadata.int("pageNum") ?: 0,
            totalPages = metadata.int("totalPages")?.coerceAtLeast(0) ?: 0,
            fetchedAt = fetchedAt,
        )
    }

    fun parseUnits(source: String): List<AnnouncementUnit> {
        val units = selectOptions(source, "select-unit-x").mapNotNull { (value, text) ->
            val id = value.trim()
            val name = text.trim()
            if (!ANNOUNCEMENT_UNIT_ID_REGEX.matches(id) || name.isBlank()) null else AnnouncementUnit(id, name)
        }.distinctBy(AnnouncementUnit::id)
        if (units.isEmpty()) throw PublicDataException("Invalid announcement units")
        return (defaultAnnouncementUnits() + units).distinctBy(AnnouncementUnit::id).distinctBy(AnnouncementUnit::name)
    }

    fun parseFilterOptions(source: String): AnnouncementFilterOptions {
        val categories = selectOptions(source, "select-attr-x").mapNotNull { (value, text) ->
            val id = value.trim()
            val name = text.trim()
            if (!ANNOUNCEMENT_UNIT_ID_REGEX.matches(id) || name.isBlank()) null else AnnouncementCategory(id, name)
        }
        return AnnouncementFilterOptions(
            units = parseUnits(source),
            categories = (AnnouncementFilterOptions().categories + categories).distinctBy(AnnouncementCategory::id),
        )
    }

    fun parseDetail(source: String, requestedId: String, categoryHint: String = ""): SchoolAnnouncementDetail {
        val item = parseArray(source, "Invalid announcement detail").firstOrNull() as? JsonObject
            ?: throw PublicDataException("Invalid announcement detail")
        if (item.int("rcode")?.let { it != 200 } == true) throw PublicDataException("Announcement detail is unavailable")
        val id = item.text("newsId")?.takeIf(String::isNotBlank) ?: requestedId
        val decodedHtml = try {
            PortableUrl.percentDecode(item.text("content").orEmpty())
        } catch (error: Exception) {
            throw PublicDataException("Invalid announcement content", cause = error)
        }
        val article = sanitizeArticle(decodedHtml)
        return SchoolAnnouncementDetail(
            id = id,
            title = item.text("title").orEmpty().ifBlank { "未命名消息" },
            date = item.text("time").orEmpty(),
            category = categoryHint,
            unit = item.text("unit").orEmpty(),
            issuer = item.text("issuer").orEmpty(),
            htmlContent = article.first,
            images = article.second,
            attachments = parseAttachments(item.text("attachedfile"), id),
            officialUrl = officialUrl(id),
        )
    }

    fun officialUrl(id: String): String =
        siteBase.withPath("ischool/public/news_view/show.php", listOf("nid" to id)).toString()

    internal fun decodeLegacyFileName(source: String): String {
        val unicodeDecoded = LEGACY_UNICODE_REGEX.replace(source) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return runCatching { PortableUrl.percentDecode(unicodeDecoded) }.getOrDefault(unicodeDecoded)
    }

    /** Absolute http(s) URL without user info, resolved against the school site; null for anything else. */
    fun safeWebUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val explicitScheme = EXPLICIT_SCHEME.find(raw)?.groupValues?.get(1)?.lowercase()
        if (explicitScheme != null && explicitScheme != "http" && explicitScheme != "https") return null
        val url = (if (explicitScheme != null) PortableUrl.parse(raw) else siteBase.resolve(raw)) ?: return null
        if (url.hasUserInfo) return null
        return url.toString()
    }

    private class OpenElement(val name: String, val emitted: Boolean, val headingStart: Int)

    /**
     * Reduces an article fragment to the tags the app renders. Active content and tables are removed
     * with their contents, images are returned separately, unknown tags are unwrapped, and links keep
     * only absolute http(s) targets.
     */
    private fun sanitizeArticle(source: String): Pair<String, List<SchoolAnnouncementImage>> {
        val output = StringBuilder()
        val images = ArrayList<SchoolAnnouncementImage>()
        val open = ArrayList<OpenElement>()
        var skipName: String? = null
        var skipDepth = 0
        var tableNoticeWritten = false

        fun addImage(token: HtmlToken.Start) {
            val url = token.attributes["src"]?.let(::safeWebUrl) ?: return
            images.add(
                SchoolAnnouncementImage(
                    url = url,
                    description = token.attributes["alt"].orEmpty().trim().ifBlank { "消息圖片" },
                    canPreview = PortableUrl.parse(url)?.let { it.isHttps && it.host == siteBase.host } == true,
                ),
            )
        }

        fun close(element: OpenElement) {
            if (element.headingStart >= 0) {
                val inner = output.substring(element.headingStart)
                val alreadyBold = inner.startsWith("<strong>") && inner.endsWith("</strong>") &&
                    inner.indexOf("<strong>", 1) < 0
                if (!alreadyBold) {
                    output.setLength(element.headingStart)
                    output.append("<strong>").append(inner).append("</strong>")
                }
                output.append("</p>")
            } else if (element.emitted) {
                output.append("</").append(element.name).append('>')
            }
        }

        for (token in tokenizeHtml(source)) {
            if (skipName != null) {
                when (token) {
                    // Content is dropped, but a picture inside a layout table is still listed.
                    is HtmlToken.Start -> when (token.name) {
                        skipName -> skipDepth++
                        "img" -> addImage(token)
                    }
                    is HtmlToken.End -> if (token.name == skipName && --skipDepth == 0) skipName = null
                    is HtmlToken.Text -> Unit
                }
                continue
            }
            when (token) {
                is HtmlToken.Text -> output.append(escapeHtmlText(token.text))
                is HtmlToken.Start -> {
                    val name = token.name
                    val insideHeading = open.any { it.headingStart >= 0 }
                    when {
                        name in removedWithContent -> {
                            if (name == "table" && !tableNoticeWritten) {
                                output.append(TABLE_NOTICE)
                                tableNoticeWritten = true
                            }
                            // `/>` does not close a non-void element, so its content is still removed.
                            if (name !in VOID_HTML_TAGS) {
                                skipName = name
                                skipDepth = 1
                            }
                        }
                        name == "img" -> addImage(token)
                        name in headings -> {
                            output.append("<p>")
                            open.add(OpenElement(name, emitted = true, headingStart = output.length))
                        }
                        name == "br" || name == "hr" -> output.append('<').append(name).append('>')
                        name in VOID_HTML_TAGS || token.selfClosing -> Unit
                        name == "span" && insideHeading -> open.add(OpenElement(name, emitted = false, headingStart = -1))
                        name in allowedTags -> {
                            output.append('<').append(name)
                            if (name == "a") {
                                token.attributes["href"]?.let(::safeWebUrl)?.let {
                                    output.append(" href=\"").append(escapeHtmlAttribute(it)).append('"')
                                }
                                token.attributes["title"]?.let {
                                    output.append(" title=\"").append(escapeHtmlAttribute(it)).append('"')
                                }
                            }
                            output.append('>')
                            open.add(OpenElement(name, emitted = true, headingStart = -1))
                        }
                        else -> open.add(OpenElement(name, emitted = false, headingStart = -1))
                    }
                }
                is HtmlToken.End -> {
                    val index = open.indexOfLast { it.name == token.name }
                    if (index >= 0) {
                        while (open.size > index) close(open.removeAt(open.lastIndex))
                    }
                }
            }
        }
        while (open.isNotEmpty()) close(open.removeAt(open.lastIndex))
        return output.toString().trim() to images.distinctBy(SchoolAnnouncementImage::url)
    }

    private fun parseAttachments(source: String?, newsId: String): List<SchoolAnnouncementAttachment> {
        if (source.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(source) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val fields = element as? JsonArray ?: return@mapNotNull null
            val encodedName = (fields.getOrNull(2) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val name = decodeLegacyFileName(encodedName).trim().ifBlank { return@mapNotNull null }
            SchoolAnnouncementAttachment(
                name = name,
                sizeBytes = (fields.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                url = siteBase.withPathSegments("ischool", "news", "attached", newsId, name).toString(),
            )
        }.distinctBy(SchoolAnnouncementAttachment::url)
    }

    private fun parseArray(source: String, errorMessage: String): JsonArray =
        runCatching { json.parseToJsonElement(source) as? JsonArray }.getOrNull() ?: throw PublicDataException(errorMessage)

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }
}
