package com.clhs.score.analytics

object AnalyticsParameterSanitizer {
    private const val MAX_STRING_LENGTH = 80

    private val allowedKeys = setOf(
        AnalyticsParams.ACTION,
        AnalyticsParams.CACHED,
        AnalyticsParams.ENABLED,
        AnalyticsParams.ERROR_TYPE,
        AnalyticsParams.EXAM_COUNT_BUCKET,
        AnalyticsParams.FEATURE,
        AnalyticsParams.FAILURE_REASON,
        AnalyticsParams.LOCKED,
        AnalyticsParams.METHOD,
        AnalyticsParams.MODE,
        AnalyticsParams.REASON,
        AnalyticsParams.RESULT,
        AnalyticsParams.SCREEN,
        AnalyticsParams.SCREEN_CLASS,
        AnalyticsParams.SCREEN_NAME,
        AnalyticsParams.SELECTION_COUNT_BUCKET,
        AnalyticsParams.SHOW_CLASSROOM,
        AnalyticsParams.SHOW_TEACHER,
        AnalyticsParams.SHOW_TIME,
        AnalyticsParams.SOURCE,
        AnalyticsParams.SUBJECT_COUNT,
        AnalyticsParams.SUBJECT_COUNT_BUCKET,
        AnalyticsParams.SYSTEM_PERMISSION,
        AnalyticsParams.TRIGGER,
        AnalyticsParams.YEAR_COUNT,
    )

    fun sanitize(parameters: Map<String, Any?>): Map<String, Any> =
        buildMap {
            parameters.forEach { (key, rawValue) ->
                if (key !in allowedKeys || rawValue == null) {
                    return@forEach
                }
                val value = when (rawValue) {
                    is Boolean -> if (rawValue) 1L else 0L
                    is Byte -> rawValue.toLong()
                    is Short -> rawValue.toLong()
                    is Int -> rawValue.toLong()
                    is Long -> rawValue
                    is Float -> rawValue.toDouble()
                    is Double -> rawValue
                    is String -> rawValue.take(MAX_STRING_LENGTH)
                    else -> return@forEach
                }
                put(key, value)
            }
        }

    fun countBucket(count: Int): String = when {
        count <= 0 -> "0"
        count == 1 -> "1"
        count <= 3 -> "2_3"
        count <= 6 -> "4_6"
        count <= 10 -> "7_10"
        else -> "11_plus"
    }
}
