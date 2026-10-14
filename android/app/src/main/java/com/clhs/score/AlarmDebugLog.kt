package com.clhs.score

import android.content.Context
import android.os.SystemClock
import android.util.Log

/** Debug-only operational metadata. Callers must not pass timetable text or private identifiers. */
internal object AlarmDebugLog {
    fun event(context: Context, feature: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val values = fields.joinToString(" ") { (key, value) -> "$key=${value ?: "none"}" }
        Log.d(
            "AlarmReliability",
            "feature=$feature event=$event package=${context.applicationContext.packageName} " +
                "rtc=${System.currentTimeMillis()} elapsedRealtime=${SystemClock.elapsedRealtime()} $values",
        )
    }
}
