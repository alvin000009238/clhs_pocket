package com.clhs.score.reminders

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import com.clhs.score.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal enum class CourseAlarmPurpose { BOUNDARY, RECOVERY }
internal enum class CourseAlarmPrecision { INEXACT, EXACT }

internal data class AlarmScheduleState(
    val exactUserPreference: Boolean?,
    val exactSystemAccess: Boolean,
    val batteryOptimizationExempt: Boolean,
)

internal fun selectCourseAlarmPrecision(
    exactUserPreference: Boolean?,
    exactSystemAccess: Boolean,
    purpose: CourseAlarmPurpose,
    sdkInt: Int,
): CourseAlarmPrecision = if (
    purpose == CourseAlarmPurpose.BOUNDARY && exactUserPreference == true &&
        (sdkInt < Build.VERSION_CODES.S || exactSystemAccess)
) {
    CourseAlarmPrecision.EXACT
} else {
    CourseAlarmPrecision.INEXACT
}

internal fun exactAlarmAccessAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching {
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }.getOrDefault(false)

internal suspend fun readAlarmScheduleState(context: Context): AlarmScheduleState {
    val repository = SettingsRepository(context.applicationContext)
    val savedPreference = try {
        repository.settings.first().preferExactCourseAlarms
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
    val exactAccess = exactAlarmAccessAvailable(context)
    return AlarmScheduleState(
        exactUserPreference = savedPreference,
        exactSystemAccess = exactAccess,
        batteryOptimizationExempt = runCatching {
            BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        }.getOrDefault(false),
    )
}

internal fun reliabilityMode(exactEnabled: Boolean, batteryExempt: Boolean): String = when {
    exactEnabled && batteryExempt -> "排程可靠性良好"
    exactEnabled || batteryExempt -> "標準排程可靠性"
    else -> "使用系統預設排程"
}

internal fun batteryOptimizationStatus(exempt: Boolean): String =
    if (exempt) "已排除" else "仍受最佳化限制"
