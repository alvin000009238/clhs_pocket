package com.clhs.score.data

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class DemoAnnouncementUnitUnavailableException : IOException()

private val demoAnnouncementDateFormat = DateTimeFormatter.ofPattern("uuuu/MM/dd")
    .withZone(ZoneId.of("Asia/Taipei"))

internal class FakeSchoolAnnouncementsRepository(
    private val readRepository: AnnouncementReadRepository?,
    private val loadBookmark: suspend (String) -> AnnouncementBookmark? = { null },
    private val now: () -> Instant = Instant::now,
) : SchoolAnnouncementsRepository {
    private val page = FakeData.announcementPage.copy(totalPages = 1)
    private val filters = AnnouncementFilterOptions(
        units = listOf(
            AnnouncementUnit(ALL_UNITS_ID, "全部"),
            AnnouncementUnit("399", "讀者服務組"),
            AnnouncementUnit("2", "設備組"),
        ),
        categories = listOf(AnnouncementCategory(ALL_UNITS_ID, "全部")) +
            page.announcements.map { it.category }.distinct().mapIndexed { index, name ->
                AnnouncementCategory((index + 1).toString(), name)
            },
    )

    override suspend fun loadCached(): SchoolAnnouncementPage = loadPage(0)

    override suspend fun loadPage(
        pageIndex: Int, keyword: String, unitId: String, categoryId: String,
    ): SchoolAnnouncementPage {
        require(pageIndex >= 0)
        val unit = if (unitId == ALL_UNITS_ID) null else {
            filters.units.firstOrNull { it.id == unitId }?.name
                ?: throw DemoAnnouncementUnitUnavailableException()
        }
        val fetchedAt = now()
        val date = demoAnnouncementDateFormat.format(fetchedAt)
        val query = keyword.trim()
        val announcements = if (pageIndex == 0) page.announcements.filter { item ->
            item.title.contains(query, ignoreCase = true) &&
                (unit == null || unit == item.unit) &&
                (categoryId == ALL_UNITS_ID || filters.categories.any { it.id == categoryId && it.name == item.category })
        }.map { it.copy(date = date) } else emptyList()
        readRepository?.discoverForDisplay(announcements)
        return page.copy(announcements = announcements, pageIndex = pageIndex, fetchedAt = fetchedAt)
    }

    override suspend fun loadCachedFilterOptions(): AnnouncementFilterOptions = filters

    override suspend fun loadFilterOptions(): AnnouncementFilterOptions = filters

    override suspend fun loadDetail(id: String, categoryHint: String): SchoolAnnouncementDetail {
        require(id.isNotBlank())
        val announcement = page.announcements.firstOrNull { it.id == id }
        val date = demoAnnouncementDateFormat.format(now())
        if (id == FakeData.announcementDetail.id) {
            return FakeData.announcementDetail.copy(date = date, attachments = emptyList())
        }
        val bookmark = if (announcement == null) loadBookmark(id) else null
        return SchoolAnnouncementDetail(
            id = id,
            title = announcement?.title ?: bookmark?.title ?: "公告摘要不可用",
            date = if (announcement != null) date else bookmark?.date.orEmpty(),
            category = announcement?.category ?: bookmark?.category ?: categoryHint,
            unit = announcement?.unit ?: bookmark?.unit.orEmpty(),
            issuer = announcement?.issuer ?: bookmark?.issuer.orEmpty(),
            htmlContent = if (announcement != null) "<p>此為展示用公告內容。</p>"
                else "<p>請使用右上方的「查看原文」至校網閱讀完整公告。</p>",
            images = emptyList(),
            attachments = emptyList(),
            officialUrl = schoolAnnouncementOfficialUrl(id),
            isSummaryOnly = announcement == null,
        )
    }
}
