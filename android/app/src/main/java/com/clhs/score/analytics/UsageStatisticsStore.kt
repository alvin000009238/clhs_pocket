package com.clhs.score.analytics

import android.content.Context
import androidx.core.content.edit

enum class UsageMetric {
    APP_OPEN,
    OVERVIEW_OPEN,
    GRADES_OPEN,
    CAMPUS_OPEN,
    GRADE_QUERY,
    SCHEDULE_OPEN,
    SCHEDULE_CUSTOMIZATIONS_OPEN,
    SCHOOL_CALENDAR_OPEN,
    SCHOOL_ANNOUNCEMENTS_OPEN,
    SCHOOL_ANNOUNCEMENT_DETAIL_OPEN,
    ANNOUNCEMENT_REMINDER_OPEN,
    PERSONAL_OPEN,
    SUBJECT_TREND_OPEN,
    SCORE_SIMULATOR_OPEN,
    GRADE_EXPORT,
    GRADE_REMINDER_START,
    ANNOUNCEMENT_REMINDER_START,
}

data class UsageStatistics(
    val startedAtMillis: Long?,
    val counts: Map<UsageMetric, Long>,
) {
    fun count(metric: UsageMetric): Long = counts[metric] ?: 0L
}

class UsageStatisticsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun recordEvent(name: String, parameters: Map<String, Any?>) {
        val metric = usageMetricForEvent(name, parameters) ?: return
        synchronized(lock) {
            val key = metric.preferenceKey()
            preferences.edit {
                putLong(key, preferences.getLong(key, 0L) + 1L)
                if (!preferences.contains(KEY_STARTED_AT)) {
                    putLong(KEY_STARTED_AT, System.currentTimeMillis())
                }
            }
        }
    }

    fun snapshot(): UsageStatistics = synchronized(lock) {
        UsageStatistics(
            startedAtMillis = preferences.takeIf { it.contains(KEY_STARTED_AT) }
                ?.getLong(KEY_STARTED_AT, 0L),
            counts = UsageMetric.entries.associateWith { metric ->
                preferences.getLong(metric.preferenceKey(), 0L)
            },
        )
    }

    private fun UsageMetric.preferenceKey(): String = "count_${name.lowercase()}"

    private companion object {
        const val PREFERENCES_NAME = "usage_statistics"
        const val KEY_STARTED_AT = "started_at"
        val lock = Any()
    }
}

internal fun usageMetricForEvent(
    name: String,
    parameters: Map<String, Any?>,
): UsageMetric? = when (name) {
    AnalyticsEvents.APP_OPEN_ROUTE -> UsageMetric.APP_OPEN
    AnalyticsEvents.GRADE_QUERY -> UsageMetric.GRADE_QUERY.onSuccess(parameters)
    AnalyticsEvents.SCHEDULE_OPEN -> UsageMetric.SCHEDULE_OPEN
    // SCORE_SIMULATOR_OPEN is counted by SCORE_SIMULATOR_USED, which also carries its subject bucket.
    AnalyticsEvents.SCREEN_VIEW -> usageMetricForScreenName(parameters[AnalyticsParams.SCREEN_NAME] as? String)
    AnalyticsEvents.FEATURE_OPEN -> if (
        parameters[AnalyticsParams.FEATURE] == AnalyticsValues.FEATURE_SUBJECT_TREND
    ) UsageMetric.SUBJECT_TREND_OPEN else when (parameters[AnalyticsParams.FEATURE]) {
        AnalyticsValues.FEATURE_OVERVIEW -> UsageMetric.OVERVIEW_OPEN
        AnalyticsValues.FEATURE_SCHEDULE -> UsageMetric.SCHEDULE_OPEN
        AnalyticsValues.FEATURE_GRADES -> UsageMetric.GRADES_OPEN
        AnalyticsValues.FEATURE_CAMPUS -> UsageMetric.CAMPUS_OPEN
        else -> null
    }
    AnalyticsEvents.SCORE_SIMULATOR_USED -> UsageMetric.SCORE_SIMULATOR_OPEN
    AnalyticsEvents.EXPORT_GRADES -> UsageMetric.GRADE_EXPORT.onSuccess(parameters)
    AnalyticsEvents.GRADE_REMINDER_START -> UsageMetric.GRADE_REMINDER_START.onSuccess(parameters)
    AnalyticsEvents.ANNOUNCEMENT_REMINDER_START -> UsageMetric.ANNOUNCEMENT_REMINDER_START.onSuccess(parameters)
    else -> null
}

private fun UsageMetric.onSuccess(parameters: Map<String, Any?>): UsageMetric? =
    takeIf { parameters[AnalyticsParams.RESULT] == AnalyticsValues.RESULT_SUCCESS }

private fun usageMetricForScreenName(screenName: String?): UsageMetric? = when (screenName) {
    AnalyticsScreenNames.OVERVIEW -> UsageMetric.OVERVIEW_OPEN
    AnalyticsScreenNames.SCHEDULE -> UsageMetric.SCHEDULE_OPEN
    AnalyticsScreenNames.GRADES -> UsageMetric.GRADES_OPEN
    AnalyticsScreenNames.CAMPUS -> UsageMetric.CAMPUS_OPEN
    AnalyticsScreenNames.SCHEDULE_CUSTOMIZATIONS -> UsageMetric.SCHEDULE_CUSTOMIZATIONS_OPEN
    AnalyticsScreenNames.SCHOOL_CALENDAR -> UsageMetric.SCHOOL_CALENDAR_OPEN
    AnalyticsScreenNames.SCHOOL_ANNOUNCEMENTS -> UsageMetric.SCHOOL_ANNOUNCEMENTS_OPEN
    AnalyticsScreenNames.SCHOOL_ANNOUNCEMENT_DETAIL -> UsageMetric.SCHOOL_ANNOUNCEMENT_DETAIL_OPEN
    AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_SETTINGS,
    AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_UNITS,
    -> UsageMetric.ANNOUNCEMENT_REMINDER_OPEN
    AnalyticsScreenNames.PERSONAL -> UsageMetric.PERSONAL_OPEN
    AnalyticsScreenNames.SUBJECT_TREND -> UsageMetric.SUBJECT_TREND_OPEN
    else -> null
}
