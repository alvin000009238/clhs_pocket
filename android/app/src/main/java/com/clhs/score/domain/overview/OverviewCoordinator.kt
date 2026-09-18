package com.clhs.score.domain.overview

import com.clhs.score.data.GradeReminderRepository
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.NetworkSchoolCalendarRepository
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleChange
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.displayChanges
import com.clhs.score.data.displayItems
import com.clhs.score.data.SchoolAnnouncementPage
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SchoolCalendarSnapshot
import com.clhs.score.data.parseYearTermOrNull
import com.clhs.score.data.refreshAt
import com.clhs.score.data.refreshTargetDateAt
import com.clhs.score.data.shouldRefreshAt
import com.clhs.score.data.WeatherRepository
import com.clhs.score.data.WeatherRepositoryState
import com.clhs.score.data.WeatherConfiguration
import com.clhs.score.data.WeatherSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

class OverviewCoordinator(
    private val scheduleRepository: ScheduleRepository,
    private val calendarRepository: NetworkSchoolCalendarRepository,
    private val announcementsRepository: NetworkSchoolAnnouncementsRepository,
    private val reminderRepository: GradeReminderRepository,
    private val weatherRepository: WeatherRepository? = null,
    private val weatherConfiguration: Flow<WeatherConfiguration> = flowOf(WeatherConfiguration(WeatherSource.OPEN_METEO)),
    private val nowProvider: () -> LocalDateTime = LocalDateTime::now,
    private val instantProvider: () -> Instant = Instant::now,
) {
    fun observe(): Flow<OverviewState> = merge(
        scheduleUpdates(),
        calendarUpdates(),
        announcementUpdates(),
        gradeUpdates(),
        weatherUpdates(),
        clockUpdates(),
    ).runningFold(OverviewAccumulator()) { current, update ->
        current.apply(update)
    }.map { accumulator ->
        buildState(accumulator, nowProvider())
    }.distinctUntilChanged()

    private fun scheduleUpdates(): Flow<OverviewUpdate> = flow {
        emit(OverviewUpdate.SourceStarted(OverviewSource.Schedule))
        var cachedReport: ScheduleReport? = null
        try {
            val snapshot = scheduleRepository.getLatestScheduleSnapshot()
            cachedReport = snapshot?.report
            emit(OverviewUpdate.Schedule(snapshot?.report, refreshing = false, failed = false))
            if (snapshot != null) {
                val initialNow = nowProvider()
                if (!scheduleNeedsRefresh(snapshot.report, snapshot.fetchedAtMillis, initialNow)) {
                    val waitMillis = scheduleRefreshDelayMillis(snapshot.report, initialNow)
                    if (waitMillis > 0) delay(waitMillis.milliseconds)
                }
                if (scheduleNeedsRefresh(snapshot.report, snapshot.fetchedAtMillis, nowProvider())) {
                    emit(OverviewUpdate.Schedule(snapshot.report, refreshing = true, failed = false))
                    val yearTerm = parseYearTermOrNull(snapshot.report.yearTermValue)
                    if (yearTerm == null) {
                        emit(OverviewUpdate.Schedule(snapshot.report, refreshing = false, failed = true))
                        return@flow
                    }
                    val refreshed = scheduleRepository.fetchSchedule(
                        yearValue = snapshot.report.yearTermValue,
                        year = yearTerm.first,
                        term = yearTerm.second,
                        classNo = snapshot.report.classNo,
                        scope = ScheduleScope.CURRENT_WEEK,
                        targetDate = snapshot.report.refreshTargetDateAt(nowProvider()),
                    )
                    emit(OverviewUpdate.Schedule(refreshed, refreshing = false, failed = false))
                }
            }
        } catch (error: Exception) {
            error.rethrowCancellation()
            emit(OverviewUpdate.Schedule(cachedReport, refreshing = false, failed = true))
        }
    }

    private fun calendarUpdates(): Flow<OverviewUpdate> = flow {
        emit(OverviewUpdate.SourceStarted(OverviewSource.Calendar))
        var cached: SchoolCalendarSnapshot? = null
        try {
            cached = calendarRepository.loadCached()
            emit(OverviewUpdate.Calendar(cached, refreshing = false, failed = false))
            emit(OverviewUpdate.Calendar(cached, refreshing = true, failed = false))
            val current = calendarRepository.load(forceRefresh = false)
            emit(OverviewUpdate.Calendar(current, refreshing = false, failed = false))
        } catch (error: Exception) {
            error.rethrowCancellation()
            emit(OverviewUpdate.Calendar(cached, refreshing = false, failed = true))
        }
    }

    private fun announcementUpdates(): Flow<OverviewUpdate> = flow {
        emit(OverviewUpdate.SourceStarted(OverviewSource.Announcements))
        var cached: SchoolAnnouncementPage? = null
        try {
            cached = announcementsRepository.loadCached()
            emit(OverviewUpdate.Announcements(cached, refreshing = false, failed = false))
            val stale = cached == null || Duration.between(cached.fetchedAt, instantProvider()).let {
                it.isNegative || it > ANNOUNCEMENT_MAX_AGE
            }
            if (stale) {
                emit(OverviewUpdate.Announcements(cached, refreshing = true, failed = false))
                val current = announcementsRepository.loadPage(0)
                emit(OverviewUpdate.Announcements(current, refreshing = false, failed = false))
            }
        } catch (error: Exception) {
            error.rethrowCancellation()
            emit(OverviewUpdate.Announcements(cached, refreshing = false, failed = true))
        }
    }

    private fun gradeUpdates(): Flow<OverviewUpdate> =
        reminderRepository.state.map { OverviewUpdate.Grades(it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun weatherUpdates(): Flow<OverviewUpdate> {
        val repository = weatherRepository ?: return flowOf(
            OverviewUpdate.Weather(WeatherRepositoryState(null, isRefreshing = false)),
        )
        return weatherConfiguration.distinctUntilChanged()
            .flatMapLatest { repository.observe(it.source) }
            .map(OverviewUpdate::Weather)
    }

    private fun clockUpdates(): Flow<OverviewUpdate> = flow {
        while (true) {
            emit(OverviewUpdate.Clock)
            val now = nowProvider()
            delay(overviewClockDelayMillis(now).milliseconds)
        }
    }

    private fun buildState(accumulator: OverviewAccumulator, now: LocalDateTime): OverviewState {
        val scheduleItems = accumulator.schedule?.let { scheduleOverviewItems(it, now) }
            ?: if (
                OverviewSource.Schedule in accumulator.completedSources &&
                OverviewSource.Schedule !in accumulator.failedSources
            ) {
                listOf(scheduleSetupOverviewItem())
            } else {
                emptyList()
            }
        val calendarItems = accumulator.calendar?.let { calendarOverviewItems(it, now) }.orEmpty()
        val gradeItems = gradeOverviewItems(accumulator.grades)
        val announcementItems = accumulator.announcements?.let(::announcementOverviewItems).orEmpty()
        val sorted = (scheduleItems + calendarItems + gradeItems + announcementItems)
            .sortedWith(
                compareBy<OverviewItem> { it.priority.ordinal }
                    .thenBy { it.order }
                    .thenBy { it.eventTime ?: LocalDateTime.MAX },
            )
        val completed = accumulator.completedSources.size
        return OverviewState(
            calendarEvents = accumulator.calendar?.feed?.events.orEmpty().sortedBy(SchoolCalendarEvent::start),
            now = now,
            context = overviewContext(accumulator.schedule, now),
            items = sorted,
            isInitialLoading = completed < OverviewSource.entries.size && sorted.isEmpty(),
            isRefreshing = accumulator.refreshingSources.isNotEmpty(),
            isScheduleRefreshing = OverviewSource.Schedule in accumulator.refreshingSources,
            hasPartialFailure = accumulator.failedSources.isNotEmpty(),
            weather = accumulator.weather.toOverviewWeatherState(),
            hero = accumulator.schedule?.let { scheduleOverviewHero(it, now) },
        )
    }

    private companion object {
        val ANNOUNCEMENT_MAX_AGE: Duration = Duration.ofMinutes(30)
    }
}

internal fun scheduleNeedsRefresh(
    report: ScheduleReport,
    fetchedAtMillis: Long?,
    now: LocalDateTime,
): Boolean {
    if (report.scope == ScheduleScope.CURRENT_WEEK) {
        val start = report.weekStartDate
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?: return true
        val end = report.weekEndDate
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?: return true
        if (end < start) return true
        val today = now.toLocalDate()
        if (today < start) return false
        return today > end || report.shouldRefreshAt(now)
    }
    val fetchedAt = fetchedAtMillis?.let(Instant::ofEpochMilli) ?: return true
    val age = Duration.between(fetchedAt, now.atZone(TAIPEI_ZONE).toInstant())
    return age.isNegative || age > Duration.ofHours(6)
}

internal fun scheduleRefreshDelayMillis(
    report: ScheduleReport,
    now: LocalDateTime,
): Long = report.refreshAt()
    ?.let { Duration.between(now, it).toMillis() }
    ?.takeIf { it > 0 }
    ?: 0L

internal fun overviewClockDelayMillis(now: LocalDateTime): Long {
    val nextSecond = now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
    return Duration.between(now, nextSecond).toMillis().coerceAtLeast(100L)
}

internal fun overviewContext(report: ScheduleReport?, now: LocalDateTime): OverviewContext {
    val todayItems = report?.itemsForDate(now.toLocalDate()).orEmpty()
    if (todayItems.isEmpty()) return OverviewContext.NonSchoolDay
    if (todayItems.any { it.contains(now.toLocalTime()) }) return OverviewContext.InClass
    if (todayItems.any { it.startsAfter(now.toLocalTime()) }) return OverviewContext.BetweenClasses
    return if (now.toLocalTime() >= LocalTime.of(18, 0)) {
        OverviewContext.Evening
    } else {
        OverviewContext.AfterSchool
    }
}

internal fun scheduleOverviewItems(report: ScheduleReport, now: LocalDateTime): List<OverviewItem> {
    val today = now.toLocalDate()
    val todayItems = report.itemsForDate(today)
    val current = todayItems.firstOrNull { it.contains(now.toLocalTime()) }
    val upcoming = todayItems.filter { it.startsAfter(now.toLocalTime()) }.take(3)
    val result = mutableListOf<OverviewItem>()

    val displayDate = scheduleOverviewHero(report, now)?.date?.toLocalDate()
    report.displayChanges()
        .filter { displayDate != null && report.isValidOn(displayDate) && it.dayOfWeek == displayDate.dayOfWeek.value }
        .sortedBy(ScheduleChange::period)
        .forEach { change -> result += change.toOverviewItem(requireNotNull(displayDate)) }

    current?.let { item ->
        result += item.toCourseOverviewItem(
            date = today,
            priority = OverviewPriority.P0,
            kind = OverviewItemKind.CurrentClass,
            titlePrefix = "正在上",
            order = 10,
        )
    }
    upcoming.forEachIndexed { index, item ->
        result += item.toCourseOverviewItem(
            date = today,
            priority = if (index == 0) OverviewPriority.P0 else OverviewPriority.P1,
            kind = OverviewItemKind.UpcomingClass,
            titlePrefix = if (index == 0) "下一節" else "接下來",
            order = 20 + index,
        )
    }

    if (current == null && upcoming.isEmpty()) {
        report.nextSchoolDate(today.plusDays(1))?.let { nextDate ->
            val first = report.itemsForDate(nextDate).first()
            val count = report.itemsForDate(nextDate).size
            result += OverviewItem(
                id = "next-school-day-$nextDate",
                priority = OverviewPriority.P1,
                kind = OverviewItemKind.UpcomingClass,
                title = "${nextSchoolDayLabel(today, nextDate)} · ${nextDate.dayOfWeek.localizedName()}",
                supportingText = "第一節 ${first.subjectName}，共 $count 節課",
                label = nextDate.format(DateTimeFormatter.ofPattern("M/d")),
                icon = "calendar_today",
                destination = OverviewDestination.Schedule,
                eventTime = nextDate.atStartOfDay(),
                order = 30,
            )
        }
    }
    return result
}

internal fun scheduleOverviewHero(report: ScheduleReport, now: LocalDateTime): OverviewHero? {
    val today = now.toLocalDate()
    val todayItems = report.itemsForDate(today)
    val current = todayItems.firstOrNull { it.contains(now.toLocalTime()) }
    val upcoming = todayItems.firstOrNull { it.startsAfter(now.toLocalTime()) }
    val item = current ?: upcoming
    if (item != null) {
        val periodTime = PERIOD_TIMES.getOrNull(item.period - 1)
        return OverviewHero(
            date = today.atStartOfDay(),
            headline = if (current != null) "正在上課" else "接下來",
            courseLabel = "第 ${item.period} 節",
            courseName = item.subjectName,
            trailingText = periodTime?.singleLine,
            courseProgress = if (current != null && periodTime != null) {
                val start = LocalTime.parse(periodTime.start)
                val end = LocalTime.parse(periodTime.end)
                (Duration.between(start, now.toLocalTime()).toMillis().toFloat() /
                    Duration.between(start, end).toMillis()).coerceIn(0f, 1f)
            } else null,
            supportingText = listOf(item.classroom, item.teacherName)
                .filter(String::isNotBlank)
                .joinToString(" · "),
        )
    }
    val nextDate = report.nextSchoolDate(today.plusDays(1)) ?: return null
    val nextItems = report.itemsForDate(nextDate)
    val first = nextItems.first()
    return OverviewHero(
        date = nextDate.atStartOfDay(),
        headline = nextSchoolDayLabel(today, nextDate),
        courseLabel = "第一節",
        courseName = first.subjectName,
        trailingText = "${nextItems.size} 節課",
        supportingText = listOf(first.classroom, first.teacherName)
            .filter(String::isNotBlank)
            .joinToString(" · "),
    )
}

private fun nextSchoolDayLabel(today: LocalDate, date: LocalDate): String =
    when (val days = ChronoUnit.DAYS.between(today, date)) {
        1L -> "明天"
        2L -> "後天"
        else -> "$days 天後"
    }

internal fun scheduleSetupOverviewItem() = OverviewItem(
    id = "schedule-setup",
    priority = OverviewPriority.P1,
    kind = OverviewItemKind.ScheduleSetup,
    title = "尚未有可用的課表",
    supportingText = "完成一次學期與班級選擇後，總覽就能顯示目前與接下來的課程。",
    label = "需要設定",
    icon = "calendar_today",
    destination = OverviewDestination.Schedule,
    order = 35,
)

internal fun calendarOverviewItems(
    snapshot: SchoolCalendarSnapshot,
    now: LocalDateTime,
): List<OverviewItem> {
    val end = now.plusDays(14)
    return snapshot.feed.events
        .asSequence()
        .filter { it.endExclusive.isAfter(now) && it.start.isBefore(end) }
        .sortedBy(SchoolCalendarEvent::start)
        .map { event ->
            val daysAway = ChronoUnit.DAYS.between(now.toLocalDate(), event.start.toLocalDate())
            val isOngoing = !event.start.isAfter(now) && event.endExclusive.isAfter(now)
            val isKeyEvent = IMPORTANT_EVENT_WORDS.any { event.title.contains(it) }
            OverviewItem(
                id = "calendar-${event.id}",
                priority = if (daysAway <= 1 || isKeyEvent) OverviewPriority.P1 else OverviewPriority.P3,
                kind = OverviewItemKind.CalendarEvent,
                title = event.title,
                supportingText = listOfNotNull(
                    calendarTimeText(event, now),
                    event.location?.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                label = when {
                    isOngoing -> "進行中"
                    daysAway == 0L -> "今天"
                    daysAway == 1L -> "明天"
                    else -> event.start.format(DateTimeFormatter.ofPattern("M/d"))
                },
                icon = "calendar_today",
                destination = OverviewDestination.CalendarEvent(event.id),
                eventTime = if (isOngoing) now else event.start,
                order = 40,
            )
        }
        .toList()
}

internal fun gradeOverviewItems(state: GradeReminderState?): List<OverviewItem> {
    val changeSet = state?.latestChangeSet?.takeIf { it.hasChanges } ?: return emptyList()
    return listOf(
        OverviewItem(
            id = "grade-${changeSet.yearValue}-${changeSet.examValue}-${changeSet.checkedAtMillis}",
            priority = OverviewPriority.P2,
            kind = OverviewItemKind.GradeUpdate,
            title = "${changeSet.examName}有 ${changeSet.changes.size} 項更新",
            supportingText = "查看最新成績與排名變化",
            label = "新成績",
            icon = "science",
            destination = OverviewDestination.GradeExam(changeSet.yearValue, changeSet.examValue),
            order = 50,
        ),
    )
}

internal fun announcementOverviewItems(page: SchoolAnnouncementPage): List<OverviewItem> =
    page.announcements
        .asSequence()
        .filter { it.isPinned }
        .map { announcement ->
            OverviewItem(
                id = "announcement-${announcement.id}",
                priority = OverviewPriority.P2,
                kind = OverviewItemKind.Announcement,
                title = announcement.title,
                supportingText = listOf(announcement.unit, announcement.date)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                label = "置頂公告",
                icon = "campaign",
                destination = OverviewDestination.Announcement(
                    announcement.id,
                    announcement.category,
                    announcement.externalUrl,
                ),
                order = 60,
            )
        }
        .toList()

private enum class OverviewSource { Schedule, Calendar, Announcements, Grades }

private sealed interface OverviewUpdate {
    data class SourceStarted(val source: OverviewSource) : OverviewUpdate
    data class Schedule(val report: ScheduleReport?, val refreshing: Boolean, val failed: Boolean) : OverviewUpdate
    data class Calendar(val snapshot: SchoolCalendarSnapshot?, val refreshing: Boolean, val failed: Boolean) : OverviewUpdate
    data class Announcements(val page: SchoolAnnouncementPage?, val refreshing: Boolean, val failed: Boolean) : OverviewUpdate
    data class Grades(val state: GradeReminderState) : OverviewUpdate
    data class Weather(val state: WeatherRepositoryState) : OverviewUpdate
    data object Clock : OverviewUpdate
}

private data class OverviewAccumulator(
    val schedule: ScheduleReport? = null,
    val calendar: SchoolCalendarSnapshot? = null,
    val announcements: SchoolAnnouncementPage? = null,
    val grades: GradeReminderState? = null,
    val weather: WeatherRepositoryState? = null,
    val completedSources: Set<OverviewSource> = emptySet(),
    val refreshingSources: Set<OverviewSource> = emptySet(),
    val failedSources: Set<OverviewSource> = emptySet(),
) {
    fun apply(update: OverviewUpdate): OverviewAccumulator = when (update) {
        is OverviewUpdate.SourceStarted -> copy(refreshingSources = refreshingSources + update.source)
        is OverviewUpdate.Schedule -> sourceResult(
            source = OverviewSource.Schedule,
            refreshing = update.refreshing,
            failed = update.failed,
        ).copy(schedule = update.report)
        is OverviewUpdate.Calendar -> sourceResult(
            source = OverviewSource.Calendar,
            refreshing = update.refreshing,
            failed = update.failed,
        ).copy(calendar = update.snapshot)
        is OverviewUpdate.Announcements -> sourceResult(
            source = OverviewSource.Announcements,
            refreshing = update.refreshing,
            failed = update.failed,
        ).copy(announcements = update.page)
        is OverviewUpdate.Grades -> sourceResult(
            source = OverviewSource.Grades,
            refreshing = false,
            failed = false,
        ).copy(grades = update.state)
        is OverviewUpdate.Weather -> copy(weather = update.state)
        OverviewUpdate.Clock -> this
    }

    private fun sourceResult(
        source: OverviewSource,
        refreshing: Boolean,
        failed: Boolean,
    ): OverviewAccumulator = copy(
        completedSources = completedSources + source,
        refreshingSources = if (refreshing) refreshingSources + source else refreshingSources - source,
        failedSources = if (failed) failedSources + source else failedSources - source,
    )
}

private fun WeatherRepositoryState?.toOverviewWeatherState(): OverviewWeatherState = when {
    this == null -> OverviewWeatherState.Loading
    snapshot != null -> OverviewWeatherState.Available(snapshot, isRefreshing)
    else -> OverviewWeatherState.Unavailable(needsCwaApiKey)
}

private fun ScheduleReport.itemsForDate(date: LocalDate): List<ScheduleItem> {
    if (!isValidOn(date)) return emptyList()
    return displayItems().filter { it.dayOfWeek == date.dayOfWeek.value }.sortedBy(ScheduleItem::period)
}

private fun ScheduleReport.nextSchoolDate(start: LocalDate): LocalDate? =
    (0L..7L)
        .map(start::plusDays)
        .firstOrNull { itemsForDate(it).isNotEmpty() }

private fun ScheduleItem.contains(time: LocalTime): Boolean {
    val periodTime = PERIOD_TIMES.getOrNull(period - 1) ?: return false
    val start = runCatching { LocalTime.parse(periodTime.start) }.getOrNull() ?: return false
    val end = runCatching { LocalTime.parse(periodTime.end) }.getOrNull() ?: return false
    return !time.isBefore(start) && time.isBefore(end)
}

private fun ScheduleItem.startsAfter(time: LocalTime): Boolean {
    val start = PERIOD_TIMES.getOrNull(period - 1)?.start
        ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        ?: return false
    return start.isAfter(time)
}

private fun ScheduleItem.toCourseOverviewItem(
    date: LocalDate,
    priority: OverviewPriority,
    kind: OverviewItemKind,
    titlePrefix: String,
    order: Int,
): OverviewItem {
    val periodTime = PERIOD_TIMES.getOrNull(period - 1)
    val details = listOfNotNull(
        periodTime?.singleLine,
        classroom.takeIf(String::isNotBlank),
        teacherName.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    return OverviewItem(
        id = "course-$date-$dayOfWeek-$period-$subjectName",
        priority = priority,
        kind = kind,
        title = "$titlePrefix $subjectName",
        supportingText = details,
        label = "第 $period 節",
        icon = "school",
        destination = OverviewDestination.Schedule,
        eventTime = periodTime?.start
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?.let(date::atTime),
        order = order,
    )
}

private fun ScheduleChange.toOverviewItem(date: LocalDate): OverviewItem {
    val effective = weekItem ?: semesterItem
    val title = when (type) {
        ScheduleChangeType.ADDED -> "第 $period 節新增 ${weekItem?.subjectName.orEmpty()}"
        ScheduleChangeType.REMOVED -> "第 $period 節停課：${semesterItem?.subjectName.orEmpty()}"
        ScheduleChangeType.MODIFIED -> "第 $period 節課程異動"
    }
    val supporting = when (type) {
        ScheduleChangeType.MODIFIED -> listOfNotNull(
            weekItem?.subjectName,
            weekItem?.classroom?.takeIf(String::isNotBlank),
            weekItem?.teacherName?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
        else -> listOfNotNull(
            effective?.classroom?.takeIf(String::isNotBlank),
            effective?.teacherName?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
    }
    return OverviewItem(
        id = "schedule-change-$date-$dayOfWeek-$period-$type",
        priority = OverviewPriority.P0,
        kind = OverviewItemKind.ScheduleChange,
        title = title,
        supportingText = supporting.ifBlank { "課表已更新" },
        label = "課程異動",
        icon = "notifications_active",
        destination = OverviewDestination.Schedule,
        order = 0,
    )
}

private fun calendarTimeText(event: SchoolCalendarEvent, now: LocalDateTime): String = when {
    !event.start.isAfter(now) && event.endExclusive.isAfter(now) && event.isAllDay ->
        "進行中 · 至 ${event.endExclusive.minusNanos(1).format(DateTimeFormatter.ofPattern("M/d"))}"
    !event.start.isAfter(now) && event.endExclusive.isAfter(now) ->
        "進行中 · ${event.endExclusive.format(DateTimeFormatter.ofPattern("HH:mm"))} 結束"
    event.isAllDay -> "全天"
    else -> event.start.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
}

private fun DayOfWeek.localizedName(): String =
    getDisplayName(TextStyle.FULL, Locale.TAIWAN)

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

private val IMPORTANT_EVENT_WORDS = listOf("段考", "期中", "期末", "放假", "補假", "活動")
private val TAIPEI_ZONE = java.time.ZoneId.of("Asia/Taipei")
