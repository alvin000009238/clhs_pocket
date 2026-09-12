package com.clhs.score.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

class FirebaseAnalyticsLogger(
    context: Context,
) : AnalyticsLogger {
    private val analytics by lazy {
        FirebaseAnalytics.getInstance(context.applicationContext)
    }
    private val usageStatistics by lazy {
        UsageStatisticsStore(context)
    }

    override fun logEvent(name: String, parameters: Map<String, Any?>) {
        if (!isValidEventName(name)) {
            return
        }
        runCatching { usageStatistics.recordEvent(name, parameters) }
        runCatching {
            analytics.logEvent(name, AnalyticsParameterSanitizer.sanitize(parameters).toBundle())
        }
    }

    private fun Map<String, Any>.toBundle(): Bundle =
        Bundle().also { bundle ->
            forEach { (key, value) ->
                when (value) {
                    is Long -> bundle.putLong(key, value)
                    is Double -> bundle.putDouble(key, value)
                    is String -> bundle.putString(key, value)
                }
            }
        }

    private fun isValidEventName(name: String): Boolean =
        name.length in 1..40 && EVENT_NAME_PATTERN.matches(name)

    private companion object {
        val EVENT_NAME_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}
