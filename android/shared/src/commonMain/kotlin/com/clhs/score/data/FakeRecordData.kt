package com.clhs.score.data

/** Demo attendance and merit records shared by the Android demo flavor and iOS demo mode. */
object FakeRecordData {
    val semesters: List<RecordSemester> =
        listOf(RecordSemester(115, 1, "115 學年度第 1 學期"), RecordSemester(114, 2, "114 學年度第 2 學期"), RecordSemester(114, 1, "114 學年度第 1 學期"))

    val attendanceHistory: List<RecordHistoryStatistic> =
        listOf(
            RecordHistoryStatistic("病假", listOf(2, 0, 4, 0, null, null, null, null, null, null)),
            RecordHistoryStatistic("事假", listOf(1, 0, 2, 0, null, null, null, null, null, null)),
            RecordHistoryStatistic("曠課", listOf(0, 0, 0, 0, null, null, null, null, null, null)),
        )

    val meritHistory: List<RecordHistoryStatistic> =
        listOf(RecordHistoryStatistic("嘉獎", listOf(2, 1, 2, 0, null, null, null, null, null, null)))

    fun attendance(semester: RecordSemester): AttendanceRecord =
        if (semester.year == 115) AttendanceRecord(
            listOf(RecordCategoryCount("病假", 4), RecordCategoryCount("事假", 2), RecordCategoryCount("曠課", 0), RecordCategoryCount("遲到", 0), RecordCategoryCount("公假", 0)),
            listOf(
                AttendanceDetail("115/10/21", "病假", 3, "第 1–3 節"),
                AttendanceDetail("115/10/15", "事假", 2, "第 3–4 節"),
                AttendanceDetail("115/10/08", "病假", 1, "第 2 節"),
            ),
        ) else if (semester.term == 2) AttendanceRecord(
            listOf(RecordCategoryCount("病假", 0), RecordCategoryCount("事假", 0), RecordCategoryCount("曠課", 0)),
            emptyList(),
        ) else AttendanceRecord(emptyList(), emptyList())

    fun merits(semester: RecordSemester): List<MeritRecord> =
        if (semester.year == 115) listOf(
            MeritRecord("115/10/22", "115/10/26", "", "嘉獎", 2, "協助校園活動，服務認真。", "115 學年度第 1 學期", "獎"),
            MeritRecord("115/10/14", "115/10/16", "115/10/23", "警告", 1, "未依規定完成教室清潔，已完成服務銷過。", "115 學年度第 1 學期", "懲"),
            MeritRecord("115/10/06", "115/10/08", "", "小功", 1, "代表班級參與校內服務。", "115 學年度第 1 學期", "獎"),
        ) else emptyList()
}
