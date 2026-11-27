package com.clhs.score.data

object GradeReminderText {
    fun fieldLabel(field: GradeChangeField): String = when (field) {
        GradeChangeField.SUMMARY_PRESENCE -> "整體成績狀態"
        GradeChangeField.EXAM_NAME -> "考試名稱"
        GradeChangeField.SUMMARY_TOTAL -> "總分"
        GradeChangeField.SUMMARY_AVERAGE -> "平均"
        GradeChangeField.SUMMARY_CLASS_RANK -> "班排"
        GradeChangeField.SUMMARY_CLASS_RANK_COUNT -> "班排名人數"
        GradeChangeField.SUMMARY_CATEGORY_RANK -> "類排"
        GradeChangeField.SUMMARY_CATEGORY_RANK_COUNT -> "類排名人數"
        GradeChangeField.SUMMARY_FLUNK_COUNT -> "不及格數"
        GradeChangeField.SUBJECT_PRESENCE -> "科目狀態"
        GradeChangeField.SUBJECT_SCORE -> "分數"
        GradeChangeField.SUBJECT_CLASS_AVERAGE -> "班平均"
        GradeChangeField.SUBJECT_CLASS_RANK -> "班排"
        GradeChangeField.SUBJECT_CLASS_RANK_COUNT -> "班排名人數"
        GradeChangeField.SUBJECT_YEAR_RANK -> "類排"
        GradeChangeField.SUBJECT_YEAR_RANK_COUNT -> "類排名人數"
        GradeChangeField.SUBJECT_FLUNK -> "不及格"
        GradeChangeField.SUBJECT_ABSENT -> "缺考"
        GradeChangeField.SUBJECT_CHEATING -> "作弊"
        GradeChangeField.STANDARD_PRESENCE -> "五標與分布狀態"
        GradeChangeField.STANDARD_TOP -> "頂標"
        GradeChangeField.STANDARD_FRONT -> "前標"
        GradeChangeField.STANDARD_AVERAGE -> "均標"
        GradeChangeField.STANDARD_BACK -> "後標"
        GradeChangeField.STANDARD_BOTTOM -> "底標"
        GradeChangeField.STANDARD_DEVIATION -> "標準差"
        GradeChangeField.DISTRIBUTION_ABOVE_90 -> "90 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_80 -> "80 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_70 -> "70 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_60 -> "60 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_50 -> "50 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_40 -> "40 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_30 -> "30 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_20 -> "20 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_10 -> "10 分以上人數"
        GradeChangeField.DISTRIBUTION_ABOVE_0 -> "0 分以上人數"
    }

    fun notificationTitle(changeSet: GradeChangeSet): String =
        "${changeSet.examName.ifBlank { "段考" }}資訊更新"

    fun notificationBody(changeSet: GradeChangeSet): String {
        val changes = changeSet.changes
        if (changes.isEmpty()) return "成績資訊已更新"

        val targets = changes
            .map { notificationTargetLabel(it) }
            .distinct()
        val listed = targets.take(MAX_NOTIFICATION_DETAIL_ITEMS)
        val suffix = if (targets.size > listed.size) {
            "等 ${targets.size} 項有新資訊"
        } else {
            "有新資訊"
        }

        return listed.joinToString("、") + suffix
    }

    fun detailLines(changeSet: GradeChangeSet): List<String> =
        changeSet.changes.map { change ->
            val oldValue = change.oldValue ?: "--"
            val newValue = change.newValue ?: "--"
            "${change.targetName.ifBlank { "總覽" }}｜${fieldLabel(change.field)}：$oldValue -> $newValue"
        }

    private const val MAX_NOTIFICATION_DETAIL_ITEMS = 3
    private const val SUMMARY_TARGET = "總覽"
    private const val SUMMARY_NOTIFICATION_LABEL = "整體成績"

    private fun notificationTargetLabel(change: GradeChange): String {
        val target = change.subjectName ?: change.targetName
        return if (target.isBlank() || target == SUMMARY_TARGET) {
            SUMMARY_NOTIFICATION_LABEL
        } else {
            target
        }
    }
}
