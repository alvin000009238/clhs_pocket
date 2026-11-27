package com.clhs.score.data

import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.parseInstant
import com.clhs.score.shared.time.schoolToday
import com.clhs.score.shared.time.shiftDays

object FakeData {
    val calendarEvents: List<SchoolCalendarEvent> by lazy {
        val today = schoolToday()
        val midnight = localTimeOf(0, 0)
        listOf(
            SchoolCalendarEvent("preview-exam", "期中考與試場準備", today.at(localTimeOf(8, 10)), today.at(localTimeOf(16, 0)), false, "各班教室"),
            SchoolCalendarEvent("preview-event", "校園活動與跨領域探索成果發表", today.shiftDays(1).at(midnight), today.shiftDays(3).at(midnight), true, "活動中心"),
        )
    }

    val session = AuthenticatedSession(
        studentNo = "DEMO-000",
        apiToken = "fake-token",
        cookies = mapOf("fake" to "cookie"),
    )
    val structure: List<YearTermOption> = listOf(
        YearTermOption(
            text = "113 學年度 第 2 學期",
            value = "113_2",
            exams = listOf(
                ExamOption("第一次段考", "113_2_E1"),
                ExamOption("第二次段考", "113_2_E2"),
                ExamOption("期末考", "113_2_E3"),
            ),
        ),
        YearTermOption(
            text = "114 學年度 第 1 學期",
            value = "114_1",
            exams = listOf(
                ExamOption("第一次段考", "114_1_E1"),
                ExamOption("第二次段考", "114_1_E2"),
                ExamOption("期末考", "114_1_E4"),
            ),
        ),
    )

    const val CURRENT_YEAR_VALUE = "114_1"
    const val CURRENT_EXAM_VALUE = "114_1_E4"

    val announcementPage = SchoolAnnouncementPage(
        announcements = listOf(
            SchoolAnnouncement(
                id = "45072",
                title = "圖書館八月閉館時間異動公告",
                date = "2026/08/06",
                category = "公告",
                unit = "讀者服務組",
                issuer = "讀者服務組",
                isPinned = true,
                contentType = "content",
            ),
            SchoolAnnouncement(
                id = "45068",
                title = "中央研究院高中生命科學研究人才培育計畫",
                date = "2026/08/06",
                category = "最新消息",
                unit = "設備組",
                issuer = "設備組",
                isPinned = false,
                contentType = "content",
            ),
        ),
        pageIndex = 0,
        totalPages = 2,
        fetchedAt = parseInstant("2026-08-09T05:00:00Z"),
    )

    val announcementDetail = SchoolAnnouncementDetail(
        id = "45072",
        title = "圖書館八月閉館時間異動公告",
        date = "2026-08-06 11:38:16",
        category = "公告",
        unit = "圖書館官網",
        issuer = "讀者服務組",
        htmlContent = "<p><strong>八月份閉館日期調整</strong></p><p>請同學留意開放時間，不便之處敬請見諒。</p>",
        images = emptyList(),
        attachments = listOf(
            SchoolAnnouncementAttachment(
                name = "八月開館時間.pdf",
                sizeBytes = 245_760,
                url = "https://www.clhs.tyc.edu.tw/ischool/news/attached/45072/sample.pdf",
            ),
        ),
        officialUrl = "https://www.clhs.tyc.edu.tw/ischool/public/news_view/show.php?nid=45072",
    )

    val examAnnouncementDetail = announcementDetail.let { sample ->
        sample.copy(
            title = "【第二次期中考】本學年度第一學期高一至高三考試範圍與考程表",
            issuer = "實驗研究組",
            unit = "實驗研究組官網",
            htmlContent = """
                <p><strong>考試範圍更新</strong></p>
                <ul>
                    <li>高三選修化學的修正範圍為選修化學四第一章。
                        <ul><li>請以最新版本的考試範圍為準。</li></ul>
                    </li>
                    <li>高二選修化學的修正範圍請見附件。</li>
                </ul>
                <p>1. 完整考試範圍請見<a href="${sample.officialUrl}">學校網站</a>。</p>
                <p>2. 考試期間，如需請假，請依學校公告程序辦理。</p>
                <p>3. 請確認各節考試的起訖時間，並依時程進入指定教室。</p>
            """.trimIndent(),
            attachments = listOf(
                sample.attachments.first().copy(name = "期中考考程表.pdf"),
                sample.attachments.first().copy(
                    name = "考試範圍.pdf",
                    sizeBytes = 36_864,
                    url = "${sample.attachments.first().url}?file=scope",
                ),
            ),
        )
    }

    val announcementDetailWithImage = announcementDetail.copy(
        images = listOf(
            SchoolAnnouncementImage(
                url = "https://example.invalid/announcement.png",
                description = "示範公告圖片",
                canPreview = true,
            ),
        ),
    )

    private val reports: Map<Pair<String, String>, GradeReport> by lazy {
        mapOf(
            ("114_1" to "114_1_E4") to MockGradeSystem.generateReport(
                year = 114, termText = "第 1 學期", examName = "期末考",
                customClassRank = 7.0, customCategoryRank = 38.0,
                customScores = listOf(84.0, 78.0, 92.0, 88.0, 73.0, 81.0, 70.0),
                customSubjectNames = listOf("國文", "英文", "選修數學甲", "自然", "社會", "地理", "歷史")
            ),
            ("114_1" to "114_1_E2") to MockGradeSystem.generateReport(
                year = 114, termText = "第 1 學期", examName = "第二次段考",
                customClassRank = 14.0, customCategoryRank = 71.0,
                customScores = listOf(76.0, 72.0, 82.0, 80.0, 68.0, 75.0, 70.0),
                customSubjectNames = listOf("國文", "英文", "數學A", "自然", "社會", "地理", "歷史")
            ),
            ("114_1" to "114_1_E1") to MockGradeSystem.generateReport(
                year = 114, termText = "第 1 學期", examName = "第一次段考",
                customClassRank = 18.0, customCategoryRank = 90.0,
                customScores = listOf(72.0, 70.0, 78.0, 76.0, 66.0, 73.0, 71.0),
                customSubjectNames = listOf("國文", "英文", "數學A", "自然", "社會", "地理", "歷史")
            ),
            ("113_2" to "113_2_E3") to MockGradeSystem.generateReport(
                year = 113, termText = "第 2 學期", examName = "期末考",
                customClassRank = 16.0, customCategoryRank = 82.0,
                customScores = listOf(74.0, 71.0, 80.0, 78.0, 67.0, 75.0, 69.0),
                customSubjectNames = listOf("國文", "英文", "數學", "自然", "社會", "地理", "歷史")
            ),
            ("113_2" to "113_2_E2") to MockGradeSystem.generateReport(
                year = 113, termText = "第 2 學期", examName = "第二次段考",
                customClassRank = 17.0, customCategoryRank = 85.0,
                customScores = listOf(70.0, 68.0, 78.0, 75.0, 65.0, 72.0, 65.0),
                customSubjectNames = listOf("國文", "英文", "數學", "自然", "社會", "地理", "歷史")
            ),
            ("113_2" to "113_2_E1") to MockGradeSystem.generateReport(
                year = 113, termText = "第 2 學期", examName = "第一次段考",
                customClassRank = 19.0, customCategoryRank = 92.0,
                customScores = listOf(68.0, 65.0, 75.0, 72.0, 62.0, 70.0, 62.0),
                customSubjectNames = listOf("國文", "英文", "數學", "自然", "社會", "地理", "歷史")
            ),
        )
    }

    fun reportFor(yearValue: String, examValue: String): GradeReport =
        reports[yearValue to examValue] ?: reports.getValue(CURRENT_YEAR_VALUE to CURRENT_EXAM_VALUE)
    fun latestReport(): GradeReport = reportFor(CURRENT_YEAR_VALUE, CURRENT_EXAM_VALUE)

    fun previousReport(): GradeReport = reportFor("114_1", "114_1_E2")

    fun trendReports(): List<Pair<String, GradeReport>> = listOf(
        "第一次段考" to reportFor("114_1", "114_1_E1"),
        "第二次段考" to reportFor("114_1", "114_1_E2"),
        "期末考" to reportFor("114_1", "114_1_E4"),
    )

    fun simulatorHistoryReports(): List<GradeReport> = listOf(
        reportFor("114_1", "114_1_E1"),
        reportFor("114_1", "114_1_E2"),
        reportFor("114_1", "114_1_E4"),
    )
}
