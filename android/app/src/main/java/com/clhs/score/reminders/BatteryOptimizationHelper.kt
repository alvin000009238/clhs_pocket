package com.clhs.score.reminders

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

object BatteryOptimizationHelper {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        }

    fun ignoreBatteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun openBatteryOptimizationRequest(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return true

        return runCatching {
            val request = requestIgnoreBatteryOptimizationsIntent(context)
            if (context !is Activity) request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(request)
            true
        }.getOrElse { requestError ->
            Log.w(TAG, "Unable to open battery optimization request", requestError)
            runCatching {
                val settings = ignoreBatteryOptimizationSettingsIntent()
                if (context !is Activity) settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
                true
            }.getOrElse { settingsError ->
                Log.w(TAG, "Unable to open battery optimization settings", settingsError)
                false
            }
        }
    }

    private const val TAG = "BatteryOptimization"
}
