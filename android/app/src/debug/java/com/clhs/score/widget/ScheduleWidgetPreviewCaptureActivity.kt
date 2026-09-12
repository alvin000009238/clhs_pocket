package com.clhs.score.widget

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.glance.appwidget.composeForPreview
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScheduleWidgetPreviewCaptureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!captureInProgress.compareAndSet(false, true)) {
            finishAndRemoveTask()
            return
        }
        window.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setFormat(PixelFormat.TRANSLUCENT)
            setGravity(Gravity.TOP or Gravity.START)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        scope.launch {
            try {
                runCatching { capture() }
                    .onFailure { error ->
                        File(checkNotNull(getExternalFilesDir(null)), ERROR_FILE_NAME)
                            .writeText(error.stackTraceToString())
                    }
            } finally {
                captureInProgress.set(false)
                finishAndRemoveTask()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun capture() {
        val settingsRepository = SettingsRepository(applicationContext)
        val originalSettings = settingsRepository.settings.first()

        try {
            settingsRepository.setThemeMode(ThemeMode.LIGHT)
            settingsRepository.setDynamicColor(false)
            settingsRepository.setAmoledBlack(false)

            val configuration = Configuration(resources.configuration).apply {
                densityDpi = DisplayMetrics.DENSITY_XHIGH
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_NO
            }
            val renderContext = createConfigurationContext(configuration)
            val component = ComponentName(renderContext, ScheduleWidgetReceiver::class.java)
            val providerInfo = AppWidgetManager.getInstance(renderContext).installedProviders
                .first { it.provider == component }
            val remoteViews = ScheduleWidget().composeForPreview(
                context = renderContext,
                widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                info = providerInfo,
            )
            val systemCornerRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderContext.resources.getDimension(
                    android.R.dimen.system_app_widget_background_radius,
                )
            } else {
                24 * renderContext.resources.displayMetrics.density
            }
            val hostView = AppWidgetHostView(renderContext).apply {
                setAppWidget(0, providerInfo)
                setPadding(0, 0, 0, 0)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, systemCornerRadius)
                    }
                }
                clipToOutline = true
                measure(exactly(OUTPUT_WIDTH), exactly(OUTPUT_HEIGHT))
                layout(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT)
                updateAppWidget(remoteViews)
            }
            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(hostView, FrameLayout.LayoutParams(OUTPUT_WIDTH, OUTPUT_HEIGHT))
            }
            setContentView(root)
            window.setLayout(OUTPUT_WIDTH, OUTPUT_HEIGHT)
            awaitHardwareFrame(hostView)
            delay(2.seconds)

            val bitmap = createBitmap(
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888,
            )
            copyWindow(bitmap)
            withContext(Dispatchers.IO) {
                val output = File(
                    checkNotNull(applicationContext.getExternalFilesDir(null)),
                    OUTPUT_FILE_NAME,
                )
                output.outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            }
            bitmap.recycle()
        } finally {
            settingsRepository.setThemeMode(originalSettings.themeMode)
            settingsRepository.setDynamicColor(originalSettings.dynamicColor)
            settingsRepository.setAmoledBlack(originalSettings.amoledBlack)
        }
    }

    private suspend fun awaitHardwareFrame(view: View) =
        suspendCancellableCoroutine { continuation ->
            view.post {
                view.postOnAnimation {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }

    private suspend fun copyWindow(bitmap: Bitmap) =
        suspendCancellableCoroutine { continuation ->
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("PixelCopy failed with result $result"),
                        )
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        val captureInProgress = AtomicBoolean(false)
        const val OUTPUT_WIDTH = 552
        const val OUTPUT_HEIGHT = 406
        const val OUTPUT_FILE_NAME = "schedule_widget_preview.png"
        const val ERROR_FILE_NAME = "schedule_widget_preview_error.txt"
    }
}
