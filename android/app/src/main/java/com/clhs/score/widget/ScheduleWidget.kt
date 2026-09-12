package com.clhs.score.widget

import kotlinx.coroutines.CancellationException
import com.clhs.score.data.SessionCleanupFailure
import com.clhs.score.data.SessionCleanupException
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.clhs.score.BuildConfig
import com.clhs.score.data.AppSettings
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.displayItems
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.ThemeMode
import com.clhs.score.ui.theme.AmoledDarkColors
import com.clhs.score.ui.theme.DarkColors
import com.clhs.score.ui.theme.LightColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime

val WidgetShowTeacherKey = booleanPreferencesKey("widget_show_teacher")
val WidgetShowClassroomKey = booleanPreferencesKey("widget_show_classroom")
val WidgetShowTimeKey = booleanPreferencesKey("widget_show_time")
val WidgetAfterLastClassKey = booleanPreferencesKey("widget_after_last_class")
val WidgetScheduleReportKey = stringPreferencesKey("widget_schedule_report")
val WidgetThemeModeKey = stringPreferencesKey("widget_theme_mode")
val WidgetDynamicColorKey = booleanPreferencesKey("widget_dynamic_color")
val WidgetAmoledBlackKey = booleanPreferencesKey("widget_amoled_black")
private val WidgetJson = Json { ignoreUnknownKeys = true }
private const val ScheduleWidgetPreviewRevision = 5
private const val ScheduleWidgetPreviewPreferences = "schedule_widget_preview"
private const val ScheduleWidgetPreviewSignatureKey = "published_signature"
private const val ScheduleWidgetPreviewLastAttemptKey = "last_attempt"
private const val ScheduleWidgetPreviewRetryIntervalMillis = 60 * 60 * 1_000L

internal data class WidgetScheduleSelection(
    val date: LocalDate,
    val items: List<ScheduleItem>,
)

internal data class ScheduleWidgetPreferences(
    val showTeacher: Boolean = true,
    val showClassroom: Boolean = true,
    val showTime: Boolean = true,
    val afterLastClass: Boolean = false,
)

internal data class WidgetScheduleSections(
    val current: ScheduleItem?,
    val upcoming: List<ScheduleItem>,
    val completed: List<ScheduleItem>,
) {
    val prioritized: List<ScheduleItem>
        get() = listOfNotNull(current) + upcoming
}

internal fun classifyWidgetScheduleItems(
    items: List<ScheduleItem>,
    selectionDate: LocalDate,
    today: LocalDate,
    currentMinutes: Int,
): WidgetScheduleSections {
    val sortedItems = items.sortedBy { it.period }
    if (selectionDate != today) {
        return WidgetScheduleSections(
            current = null,
            upcoming = sortedItems,
            completed = emptyList(),
        )
    }

    var current: ScheduleItem? = null
    val upcoming = mutableListOf<ScheduleItem>()
    val completed = mutableListOf<ScheduleItem>()
    sortedItems.forEach { item ->
        val periodTime = PERIOD_TIMES.getOrNull(item.period - 1)
        val startMinutes = periodTime?.start?.toMinutesOrNull()
        val endMinutes = periodTime?.end?.toMinutesOrNull()
        when {
            startMinutes == null || endMinutes == null -> upcoming += item
            currentMinutes in startMinutes until endMinutes && current == null -> current = item
            currentMinutes >= endMinutes -> completed += item
            else -> upcoming += item
        }
    }
    return WidgetScheduleSections(current, upcoming, completed)
}

private fun String.toMinutesOrNull(): Int? {
    val hour = substringBefore(":").toIntOrNull() ?: return null
    val minute = substringAfter(":", missingDelimiterValue = "").toIntOrNull() ?: return null
    return hour * 60 + minute
}

internal fun selectWidgetSchedule(
    items: List<ScheduleItem>,
    today: LocalDate,
    currentMinutes: Int,
    afterLastClass: Boolean,
    validFrom: LocalDate = today,
): WidgetScheduleSelection {
    val selectionDate = maxOf(today, validFrom)
    val selectedDateItems = items.filter { it.dayOfWeek == selectionDate.dayOfWeek.value }.sortedBy { it.period }
    val shouldAdvance = if (afterLastClass && selectionDate == today) {
        val endMinutes = selectedDateItems.maxOfOrNull { it.period }
            ?.let { PERIOD_TIMES.getOrNull(it - 1)?.end?.toMinutesOrNull() }
        endMinutes != null && currentMinutes >= endMinutes
    } else false

    if (selectedDateItems.isNotEmpty() && !shouldAdvance) {
        return WidgetScheduleSelection(selectionDate, selectedDateItems)
    }

    for (offset in 1L..7L) {
        val date = selectionDate.plusDays(offset)
        val nextItems = items.filter { it.dayOfWeek == date.dayOfWeek.value }.sortedBy { it.period }
        if (nextItems.isNotEmpty()) return WidgetScheduleSelection(date, nextItems)
    }
    return WidgetScheduleSelection(selectionDate, emptyList())
}

internal fun widgetScheduleDayLabel(selectionDate: LocalDate, today: LocalDate): String = when (selectionDate) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    else -> arrayOf("週一", "週二", "週三", "週四", "週五", "週六", "週日")[selectionDate.dayOfWeek.value - 1]
}

internal fun widgetScheduleDateLabel(date: LocalDate): String =
    "${date.monthValue} 月 ${date.dayOfMonth} 日"

internal fun widgetScheduleShortDateLabel(date: LocalDate): String =
    "${date.monthValue}/${date.dayOfMonth}"

fun getWidgetColorProviders(context: Context, settings: AppSettings) = run {
    val dynamicColor = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    
    val light = if (dynamicColor) {
        dynamicLightColorScheme(context)
    } else {
        LightColors
    }
    
    val dark = if (dynamicColor) {
        val base = dynamicDarkColorScheme(context)
        if (settings.amoledBlack) {
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainer = Color(0xFF0A0A0A),
                surfaceContainerHigh = Color(0xFF141414),
            )
        } else base
    } else {
        if (settings.amoledBlack) AmoledDarkColors else DarkColors
    }

    when (settings.themeMode) {
        ThemeMode.LIGHT -> ColorProviders(light = light, dark = light)
        ThemeMode.DARK -> ColorProviders(light = dark, dark = dark)
        ThemeMode.SYSTEM -> ColorProviders(light = light, dark = dark)
    }
}

class ScheduleWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(276.dp, 203.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cacheStore = GradeCacheStore(context)
        val settingsRepository = SettingsRepository(context)

        val report = cacheStore.loadWidgetScheduleReport()
        WidgetUpdateReceiver.scheduleNextUpdate(context, report)
        val reportStr = report?.let { WidgetJson.encodeToString(it) }
        val appSettings = settingsRepository.settings.first()
        val legacyPreferences = cacheStore.loadLegacyWidgetPreferences()

        updateAppWidgetState(context, id) { state ->
            state.syncScheduleWidgetState(reportStr, appSettings, legacyPreferences)
        }

        provideContent {
            val colors = getWidgetColorProviders(context, currentWidgetSettings(appSettings))
            GlanceTheme(colors = colors) {
                ScheduleWidgetContent()
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val settings = SettingsRepository(context).settings.first()
        val previewNow = LocalDateTime.of(1989, 6, 4, 11, 11)
        val report = FakeScheduleData.report(
            yearValue = "1132",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            today = previewNow.toLocalDate(),
        ).let { previewReport ->
            previewReport.copy(
                items = previewReport.items
                    .filter { it.dayOfWeek == 2 }
                    .map { it.copy(dayOfWeek = previewNow.dayOfWeek.value) },
            )
        }
        provideContent {
            GlanceTheme(colors = getWidgetColorProviders(context, settings)) {
                ScheduleWidgetContent(report, ScheduleWidgetPreferences(), previewNow)
            }
        }
    }
}

suspend fun syncAllScheduleWidgets(
    context: Context,
    settings: AppSettings? = null,
) = withContext(Dispatchers.IO) {
    val cacheStore = GradeCacheStore(context)
    val report = cacheStore.loadWidgetScheduleReport()
    val reportStr = report?.let { WidgetJson.encodeToString(it) }
    val appSettings = settings ?: SettingsRepository(context).settings.first()
    val legacyPreferences = cacheStore.loadLegacyWidgetPreferences()

    val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(ScheduleWidget::class.java)
    val widget = ScheduleWidget()
    if (glanceIds.isNotEmpty()) {
        WidgetUpdateReceiver.scheduleNextUpdate(context, report)
    }
    var refreshFailed = false
    glanceIds.forEach { glanceId ->
        try {
            updateAppWidgetState(context, glanceId) { state ->
                state.syncScheduleWidgetState(reportStr, appSettings, legacyPreferences)
            }
            widget.update(context, glanceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            refreshFailed = true
        }
    }
    if (refreshFailed) {
        throw SessionCleanupException(
            setOf(SessionCleanupFailure.WIDGET_REFRESH_FAILED),
        )
    }
    cacheStore.clearLegacyWidgetPreferences()
}

internal fun shouldRefreshScheduleWidgetPreview(
    hasGeneratedPreview: Boolean,
    publishedSignature: String?,
    currentSignature: String,
    lastAttemptMillis: Long,
    nowMillis: Long,
): Boolean = (!hasGeneratedPreview || publishedSignature != currentSignature) &&
    (lastAttemptMillis == 0L || nowMillis - lastAttemptMillis >= ScheduleWidgetPreviewRetryIntervalMillis)

suspend fun refreshScheduleWidgetPreview(context: Context, settings: AppSettings) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

    val component = ComponentName(context, ScheduleWidgetReceiver::class.java)
    val providerInfo = AppWidgetManager.getInstance(context).installedProviders
        .firstOrNull { it.provider == component }
    val hasGeneratedPreview = providerInfo
        ?.generatedPreviewCategories
        ?.and(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0
    val preferences = context.getSharedPreferences(
        ScheduleWidgetPreviewPreferences,
        Context.MODE_PRIVATE,
    )
    val currentSignature = buildString {
        append(BuildConfig.VERSION_CODE)
        append(':')
        append(ScheduleWidgetPreviewRevision)
        append(':')
        append(settings.themeMode)
        append(':')
        append(settings.dynamicColor)
        append(':')
        append(settings.amoledBlack)
    }
    val nowMillis = System.currentTimeMillis()
    if (!shouldRefreshScheduleWidgetPreview(
            hasGeneratedPreview = hasGeneratedPreview,
            publishedSignature = preferences.getString(ScheduleWidgetPreviewSignatureKey, null),
            currentSignature = currentSignature,
            lastAttemptMillis = preferences.getLong(ScheduleWidgetPreviewLastAttemptKey, 0L),
            nowMillis = nowMillis,
        )
    ) {
        return
    }

    preferences.edit { putLong(ScheduleWidgetPreviewLastAttemptKey, nowMillis) }
    runCatching {
        GlanceAppWidgetManager(context).setWidgetPreviews(ScheduleWidgetReceiver::class)
    }.onSuccess { result ->
        if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_RATE_LIMITED) {
            Log.w("ScheduleWidget", "Widget preview refresh was rate limited")
        } else {
            preferences.edit {
                putString(ScheduleWidgetPreviewSignatureKey, currentSignature)
            }
        }
    }.onFailure { Log.w("ScheduleWidget", "Failed to publish widget preview", it) }
}

internal suspend fun syncScheduleWidget(
    context: Context,
    appWidgetId: Int,
    preferences: ScheduleWidgetPreferences? = null,
) = withContext(Dispatchers.IO) {
    val cacheStore = GradeCacheStore(context)
    val report = cacheStore.loadWidgetScheduleReport()
    val reportStr = report?.let { WidgetJson.encodeToString(it) }
    val appSettings = SettingsRepository(context).settings.first()
    val legacyPreferences = cacheStore.loadLegacyWidgetPreferences()
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

    updateAppWidgetState(context, glanceId) { state ->
        state.syncScheduleWidgetState(reportStr, appSettings, legacyPreferences, preferences)
    }
    WidgetUpdateReceiver.scheduleNextUpdate(context, report)
}

internal suspend fun loadScheduleWidgetPreferences(
    context: Context,
    appWidgetId: Int,
): ScheduleWidgetPreferences {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
    return ScheduleWidgetPreferences(
        showTeacher = state[WidgetShowTeacherKey] ?: true,
        showClassroom = state[WidgetShowClassroomKey] ?: true,
        showTime = state[WidgetShowTimeKey] ?: true,
        afterLastClass = state[WidgetAfterLastClassKey] ?: false,
    )
}

internal suspend fun saveScheduleWidgetPreferences(
    context: Context,
    appWidgetId: Int,
    preferences: ScheduleWidgetPreferences,
) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    updateAppWidgetState(context, glanceId) { state ->
        state[WidgetShowTeacherKey] = preferences.showTeacher
        state[WidgetShowClassroomKey] = preferences.showClassroom
        state[WidgetShowTimeKey] = preferences.showTime
        state[WidgetAfterLastClassKey] = preferences.afterLastClass
    }
}

internal fun MutablePreferences.syncScheduleWidgetState(
    reportStr: String?,
    settings: AppSettings,
    legacyPreferences: Triple<Boolean, Boolean, Boolean>,
    preferences: ScheduleWidgetPreferences? = null,
) {
    preferences?.let { selected ->
        this[WidgetShowTeacherKey] = selected.showTeacher
        this[WidgetShowClassroomKey] = selected.showClassroom
        this[WidgetShowTimeKey] = selected.showTime
        this[WidgetAfterLastClassKey] = selected.afterLastClass
    }
    if (this[WidgetShowTeacherKey] == null) this[WidgetShowTeacherKey] = legacyPreferences.first
    if (this[WidgetShowClassroomKey] == null) this[WidgetShowClassroomKey] = legacyPreferences.second
    if (this[WidgetShowTimeKey] == null) this[WidgetShowTimeKey] = legacyPreferences.third
    this[WidgetThemeModeKey] = settings.themeMode.name
    this[WidgetDynamicColorKey] = settings.dynamicColor
    this[WidgetAmoledBlackKey] = settings.amoledBlack
    if (reportStr != null) {
        this[WidgetScheduleReportKey] = reportStr
    } else {
        remove(WidgetScheduleReportKey)
    }
}

@Composable
private fun currentWidgetSettings(fallback: AppSettings): AppSettings {
    val themeMode = currentState(key = WidgetThemeModeKey)
        ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
        ?: fallback.themeMode
    return fallback.copy(
        themeMode = themeMode,
        dynamicColor = currentState(key = WidgetDynamicColorKey) ?: fallback.dynamicColor,
        amoledBlack = currentState(key = WidgetAmoledBlackKey) ?: fallback.amoledBlack,
    )
}

@Composable
fun ScheduleWidgetContent() {
    val showTeacher = currentState(key = WidgetShowTeacherKey) ?: true
    val showClassroom = currentState(key = WidgetShowClassroomKey) ?: true
    val showTime = currentState(key = WidgetShowTimeKey) ?: true
    val afterLastClass = currentState(key = WidgetAfterLastClassKey) ?: false
    val reportStr = currentState(key = WidgetScheduleReportKey)
    val report = reportStr?.let {
        try { WidgetJson.decodeFromString<ScheduleReport>(it) }
        catch(e: Exception) { null } 
    }
    ScheduleWidgetContent(
        report = report,
        preferences = ScheduleWidgetPreferences(
            showTeacher = showTeacher,
            showClassroom = showClassroom,
            showTime = showTime,
            afterLastClass = afterLastClass,
        ),
    )
}

@Composable
private fun ScheduleWidgetContent(
    report: ScheduleReport?,
    preferences: ScheduleWidgetPreferences,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val items = report?.displayItems().orEmpty()
    val currentTotalMinutes = now.hour * 60 + now.minute
    val today = now.toLocalDate()
    val validFrom = report
        ?.takeIf { it.scope == ScheduleScope.CURRENT_WEEK }
        ?.weekStartDate
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: today
    val selection = selectWidgetSchedule(
        items,
        today,
        currentTotalMinutes,
        preferences.afterLastClass,
        validFrom,
    )
    val isExpiredCurrentWeek = report?.scope == ScheduleScope.CURRENT_WEEK && !report.isValidOn(selection.date)
    val sections = classifyWidgetScheduleItems(
        items = selection.items,
        selectionDate = selection.date,
        today = today,
        currentMinutes = currentTotalMinutes,
    )
    val widgetSize = LocalSize.current
    val isShort = widgetSize.height < 160.dp
    val isNarrow = widgetSize.width < 220.dp
    val isCompact = widgetSize.height < 260.dp
    val prioritizedItems = if (isShort) sections.prioritized.take(1) else sections.prioritized

    val context = LocalContext.current
    val intent = Intent(
        Intent.ACTION_VIEW,
        "scoreapp://schedule".toUri(),
        context,
        com.clhs.score.MainActivity::class.java
    ).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    Scaffold(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity(intent)),
        backgroundColor = GlanceTheme.colors.background,
        horizontalPadding = if (isShort) 8.dp else 16.dp,
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(top = if (isShort) 8.dp else 12.dp, bottom = if (isShort) 4.dp else 8.dp),
        ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = widgetScheduleDayLabel(selection.date, today),
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = GlanceTheme.colors.onBackground,
                        ),
                    )
                    Text(
                        text = if (isShort || isNarrow) {
                            widgetScheduleShortDateLabel(selection.date)
                        } else {
                            widgetScheduleDateLabel(selection.date)
                        },
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                        ),
                        modifier = GlanceModifier
                            .defaultWeight()
                            .padding(start = 8.dp),
                    )
                    if (!isShort &&
                        !isNarrow &&
                        !isExpiredCurrentWeek &&
                        report?.scope == ScheduleScope.CURRENT_WEEK &&
                        !report.weekNo.isNullOrBlank()
                    ) {
                        Text(
                            text = "第 ${report.weekNo} 週",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GlanceTheme.colors.onPrimaryContainer,
                            ),
                            modifier = GlanceModifier
                                .background(GlanceTheme.colors.primaryContainer)
                                .cornerRadius(12.dp)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                if (report == null) {
                    WidgetStatusText("點擊此處查詢課表")
                } else if (isExpiredCurrentWeek) {
                    WidgetStatusText("課表已過期\n點擊此處更新")
                } else if (selection.items.isEmpty()) {
                    WidgetStatusText("今日無排課")
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        if (sections.prioritized.isEmpty() && sections.completed.isNotEmpty()) {
                            item(itemId = -1L) {
                                WidgetStatusText(
                                    text = "今日課程已結束",
                                    modifier = GlanceModifier.padding(bottom = if (isCompact) 0.dp else 8.dp),
                                )
                            }
                        }
                        items(prioritizedItems, itemId = { it.period.toLong() }) { item ->
                            ScheduleItemRow(
                                item = item,
                                isCurrent = item == sections.current,
                                isCompleted = false,
                                showTeacher = preferences.showTeacher && !isShort && !isNarrow,
                                showClassroom = preferences.showClassroom && !isShort && !isNarrow,
                                showTime = preferences.showTime,
                                isSmall = isShort,
                            )
                        }
                        if (!isCompact && sections.completed.isNotEmpty()) {
                            item(itemId = -2L) {
                                Text(
                                    text = "已下課",
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GlanceTheme.colors.onSurfaceVariant,
                                    ),
                                    modifier = GlanceModifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(sections.completed, itemId = { 100L + it.period }) { item ->
                                ScheduleItemRow(
                                    item = item,
                                    isCurrent = false,
                                    isCompleted = true,
                                    showTeacher = preferences.showTeacher,
                                    showClassroom = preferences.showClassroom,
                                    showTime = preferences.showTime,
                                    isSmall = false,
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun WidgetStatusText(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        modifier = modifier,
    )
}

@Composable
private fun ScheduleItemRow(
    item: ScheduleItem,
    isCurrent: Boolean,
    isCompleted: Boolean,
    showTeacher: Boolean,
    showClassroom: Boolean,
    showTime: Boolean,
    isSmall: Boolean,
) {
    val rowModifier = if (isCurrent) {
        GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = if (isSmall) 4.dp else 6.dp)
    } else {
        GlanceModifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .cornerRadius(0.dp)
            .padding(horizontal = 8.dp, vertical = if (isSmall) 4.dp else 6.dp)
    }
    val primaryTextColor = when {
        isCompleted -> GlanceTheme.colors.onSurfaceVariant
        isCurrent -> GlanceTheme.colors.onPrimaryContainer
        else -> GlanceTheme.colors.primary
    }
    val subjectColor = when {
        isCompleted -> GlanceTheme.colors.onSurfaceVariant
        isCurrent -> GlanceTheme.colors.onPrimaryContainer
        else -> GlanceTheme.colors.onBackground
    }
    val secondaryText = buildList {
        if (showTeacher && item.teacherName.isMeaningfulWidgetText()) add(item.teacherName)
        if (showClassroom && item.classroom.isMeaningfulWidgetText()) add(item.classroom)
    }.joinToString(" • ")

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.padding(end = 10.dp)) {
            Text(
                text = "第 ${item.period} 節",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCurrent) 13.sp else 12.sp,
                    color = primaryTextColor,
                ),
            )
            if (showTime) {
                PERIOD_TIMES.getOrNull(item.period - 1)?.singleLine?.let { periodTime ->
                    Text(
                        text = periodTime,
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = primaryTextColor,
                        ),
                    )
                }
            }
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            if (item.subjectName.isMeaningfulWidgetText()) {
                Text(
                    text = item.subjectName,
                    style = TextStyle(
                        color = subjectColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCurrent) 16.sp else 14.sp,
                    ),
                )
            }
            if (secondaryText.isNotEmpty()) {
                Text(
                    text = secondaryText,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
                    modifier = GlanceModifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun String.isMeaningfulWidgetText(): Boolean = isNotBlank() && this != "null"


