package com.clhs.score.data

import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.parseLocalDateOrNull

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

data class AnnouncementCategory(val id: String, val name: String)

data class AnnouncementFilterOptions(
    val units: List<AnnouncementUnit> = defaultAnnouncementUnits(),
    val categories: List<AnnouncementCategory> = listOf(AnnouncementCategory(ALL_UNITS_ID, "全部")),
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
    val isExternal: Boolean = false,
    val externalUrl: String? = null,
    val containsTables: Boolean = false,
    val isSummaryOnly: Boolean = false,
)

const val ALL_UNITS_ID = "-1"
val ANNOUNCEMENT_UNIT_ID_REGEX get() = Regex("-1|[0-9]+")

fun defaultAnnouncementUnits(): List<AnnouncementUnit> = listOf(AnnouncementUnit(ALL_UNITS_ID, "全部"))

fun parseAnnouncementDate(date: String): LocalDate? =
    parseLocalDateOrNull(date.trim().substringBefore(' ').substringBefore('T').replace('/', '-'))
