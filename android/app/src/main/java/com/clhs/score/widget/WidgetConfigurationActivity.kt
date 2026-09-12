package com.clhs.score.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.FirebaseAnalyticsLogger
import com.clhs.score.data.AppSettings
import com.clhs.score.data.SettingsRepository
import com.clhs.score.ui.schedule.WidgetSettingsScreen
import com.clhs.score.ui.theme.ScoreTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val launchConfiguration = mutableStateOf<WidgetConfigurationData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { launchConfiguration.value == null }
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val iconView = runCatching { splashScreenView.iconView }.getOrNull()
            if (iconView == null) {
                splashScreenView.remove()
                return@setOnExitAnimationListener
            }
            iconView.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { splashScreenView.remove() }
                .start()
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val intentExtras = intent?.extras
        if (intentExtras != null) {
            appWidgetId = intentExtras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )

        val settingsRepository = SettingsRepository(applicationContext)
        lifecycleScope.launch {
            try {
                val manager = GlanceAppWidgetManager(applicationContext)
                val glanceId = manager.getGlanceIdBy(appWidgetId)
                val sizes = runCatching { manager.getAppWidgetSizes(glanceId) }
                    .getOrDefault(emptyList())
                    .filterNot { it == DpSize.Zero }
                val previewSize = if (
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                ) {
                    sizes.maxByOrNull { it.width.value }
                } else {
                    sizes.minByOrNull { it.width.value }
                }
                launchConfiguration.value = WidgetConfigurationData(
                    settings = settingsRepository.settings.first(),
                    preferences = loadScheduleWidgetPreferences(applicationContext, appWidgetId),
                    previewSize = previewSize ?: DpSize(276.dp, 203.dp),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                finish()
            }
        }

        setContent {
            val configuration = launchConfiguration.value ?: return@setContent
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = configuration.settings
            )

            ScoreTheme(
                themeMode = settings.themeMode,
                amoledBlack = settings.amoledBlack,
                dynamicColor = settings.dynamicColor
            ) {
                WidgetSettingsScreen(
                    initialPreferences = configuration.preferences,
                    previewSize = configuration.previewSize,
                    onDismiss = {
                        finish()
                    },
                    onSaveCompleted = { preferences ->
                        withContext(Dispatchers.IO) {
                            saveScheduleWidgetPreferences(
                                applicationContext,
                                appWidgetId,
                                preferences,
                            )
                            syncScheduleWidget(applicationContext, appWidgetId, preferences)
                            val glanceAppWidgetManager = GlanceAppWidgetManager(applicationContext)
                            val glanceId = glanceAppWidgetManager.getGlanceIdBy(appWidgetId)
                            ScheduleWidget().update(applicationContext, glanceId)
                        }
                        FirebaseAnalyticsLogger(applicationContext).logEvent(
                            AnalyticsEvents.SCHEDULE_WIDGET_SETTINGS_SAVE,
                            mapOf(
                                AnalyticsParams.SHOW_TEACHER to preferences.showTeacher,
                                AnalyticsParams.SHOW_CLASSROOM to preferences.showClassroom,
                                AnalyticsParams.SHOW_TIME to preferences.showTime,
                            ),
                        )
                        val resultValue = Intent().apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        setResult(RESULT_OK, resultValue)
                        finish()
                    }
                )
            }
        }
    }

    private data class WidgetConfigurationData(
        val settings: AppSettings,
        val preferences: ScheduleWidgetPreferences,
        val previewSize: DpSize,
    )
}
