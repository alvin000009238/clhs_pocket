package com.clhs.score.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatisticsStoreTest {
    @Test
    fun mapsOnlyRequestedUsageEvents() {
        val success = mapOf(AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS)
        val failure = mapOf(AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE)

        assertEquals(UsageMetric.APP_OPEN, usageMetricForEvent(AnalyticsEvents.APP_OPEN_ROUTE, emptyMap()))
        assertEquals(
            UsageMetric.OVERVIEW_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCREEN_VIEW, screenView(AnalyticsScreenNames.OVERVIEW)),
        )
        assertEquals(
            UsageMetric.SCHEDULE_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCREEN_VIEW, screenView(AnalyticsScreenNames.SCHEDULE)),
        )
        assertEquals(
            UsageMetric.GRADES_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCREEN_VIEW, screenView(AnalyticsScreenNames.GRADES)),
        )
        assertEquals(
            UsageMetric.CAMPUS_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCREEN_VIEW, screenView(AnalyticsScreenNames.CAMPUS)),
        )
        assertEquals(
            UsageMetric.SCHEDULE_CUSTOMIZATIONS_OPEN,
            usageMetricForEvent(
                AnalyticsEvents.SCREEN_VIEW,
                screenView(AnalyticsScreenNames.SCHEDULE_CUSTOMIZATIONS),
            ),
        )
        assertEquals(
            UsageMetric.SCHOOL_CALENDAR_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCREEN_VIEW, screenView(AnalyticsScreenNames.SCHOOL_CALENDAR)),
        )
        assertEquals(
            UsageMetric.SCHOOL_ANNOUNCEMENTS_OPEN,
            usageMetricForEvent(
                AnalyticsEvents.SCREEN_VIEW,
                screenView(AnalyticsScreenNames.SCHOOL_ANNOUNCEMENTS),
            ),
        )
        assertEquals(
            UsageMetric.SCHOOL_ANNOUNCEMENT_DETAIL_OPEN,
            usageMetricForEvent(
                AnalyticsEvents.SCREEN_VIEW,
                screenView(AnalyticsScreenNames.SCHOOL_ANNOUNCEMENT_DETAIL),
            ),
        )
        assertEquals(
            UsageMetric.ANNOUNCEMENT_REMINDER_OPEN,
            usageMetricForEvent(
                AnalyticsEvents.SCREEN_VIEW,
                screenView(AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_SETTINGS),
            ),
        )
        assertEquals(
            UsageMetric.ANNOUNCEMENT_REMINDER_OPEN,
            usageMetricForEvent(
                AnalyticsEvents.SCREEN_VIEW,
                screenView(AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_UNITS),
            ),
        )
        assertEquals(
            UsageMetric.PERSONAL_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCREEN_VIEW, screenView(AnalyticsScreenNames.PERSONAL)),
        )
        assertEquals(
            UsageMetric.SUBJECT_TREND_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCREEN_VIEW, screenView(AnalyticsScreenNames.SUBJECT_TREND)),
        )
        assertEquals(UsageMetric.GRADE_QUERY, usageMetricForEvent(AnalyticsEvents.GRADE_QUERY, success))
        assertEquals(UsageMetric.SCHEDULE_OPEN, usageMetricForEvent(AnalyticsEvents.SCHEDULE_OPEN, emptyMap()))
        assertEquals(
            UsageMetric.SCHEDULE_OPEN,
            usageMetricForEvent(
                AnalyticsEvents.FEATURE_OPEN,
                mapOf(AnalyticsParams.FEATURE to AnalyticsValues.FEATURE_SCHEDULE),
            ),
        )
        assertEquals(
            UsageMetric.SUBJECT_TREND_OPEN,
            usageMetricForEvent(
                AnalyticsEvents.FEATURE_OPEN,
                mapOf(AnalyticsParams.FEATURE to AnalyticsValues.FEATURE_SUBJECT_TREND),
            ),
        )
        assertEquals(
            UsageMetric.SCORE_SIMULATOR_OPEN,
            usageMetricForEvent(AnalyticsEvents.SCORE_SIMULATOR_USED, emptyMap()),
        )
        assertEquals(UsageMetric.GRADE_EXPORT, usageMetricForEvent(AnalyticsEvents.EXPORT_GRADES, success))
        assertEquals(
            UsageMetric.GRADE_REMINDER_START,
            usageMetricForEvent(AnalyticsEvents.GRADE_REMINDER_START, success),
        )
        assertEquals(
            UsageMetric.ANNOUNCEMENT_REMINDER_START,
            usageMetricForEvent(AnalyticsEvents.ANNOUNCEMENT_REMINDER_START, success),
        )

        assertNull(usageMetricForEvent(AnalyticsEvents.GRADE_QUERY, failure))
        assertNull(usageMetricForEvent(AnalyticsEvents.EXPORT_GRADES, failure))
        assertNull(usageMetricForEvent(AnalyticsEvents.GRADE_REMINDER_START, failure))
        assertNull(usageMetricForEvent(AnalyticsEvents.ANNOUNCEMENT_REMINDER_START, failure))
        assertNull(
            usageMetricForEvent(
                AnalyticsEvents.FEATURE_OPEN,
                mapOf(AnalyticsParams.FEATURE to AnalyticsValues.FEATURE_SETTINGS),
            ),
        )
        assertNull(usageMetricForEvent(AnalyticsEvents.LOGOUT, emptyMap()))
    }

    private fun screenView(screenName: String): Map<String, Any?> =
        mapOf(AnalyticsParams.SCREEN_NAME to screenName)
}
