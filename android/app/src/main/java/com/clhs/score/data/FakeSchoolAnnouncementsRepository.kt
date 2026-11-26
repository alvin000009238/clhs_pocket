package com.clhs.score.data

internal class FakeSchoolAnnouncementsRepository(
    private val readRepository: AnnouncementReadRepository?,
) : SchoolAnnouncementsRepository {
    private val page = FakeData.announcementPage.copy(totalPages = 1)
    private val filters = AnnouncementFilterOptions(
        units = listOf(AnnouncementUnit(ALL_UNITS_ID, "全部")) +
            page.announcements.map { it.unit }.distinct().mapIndexed { index, name ->
                AnnouncementUnit((index + 1).toString(), name)
            },
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
        val query = keyword.trim()
        val announcements = if (pageIndex == 0) page.announcements.filter { item ->
            item.title.contains(query, ignoreCase = true) &&
                (unitId == ALL_UNITS_ID || filters.units.any { it.id == unitId && it.name == item.unit }) &&
                (categoryId == ALL_UNITS_ID || filters.categories.any { it.id == categoryId && it.name == item.category })
        } else emptyList()
        readRepository?.discoverForDisplay(announcements)
        return page.copy(announcements = announcements, pageIndex = pageIndex)
    }

    override suspend fun loadCachedFilterOptions(): AnnouncementFilterOptions = filters

    override suspend fun loadFilterOptions(): AnnouncementFilterOptions = filters

    override suspend fun loadDetail(id: String, categoryHint: String): SchoolAnnouncementDetail {
        val announcement = page.announcements.first { it.id == id }
        return if (id == FakeData.announcementDetail.id) FakeData.announcementDetail else SchoolAnnouncementDetail(
            id = announcement.id,
            title = announcement.title,
            date = announcement.date,
            category = announcement.category,
            unit = announcement.unit,
            issuer = announcement.issuer,
            htmlContent = "<p>此為展示用公告內容。</p>",
            images = emptyList(),
            attachments = emptyList(),
            officialUrl = schoolAnnouncementOfficialUrl(id),
        )
    }
}
