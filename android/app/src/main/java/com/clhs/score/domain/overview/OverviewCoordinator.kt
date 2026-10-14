package com.clhs.score.domain.overview

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.NetworkSchoolCalendarRepository
import com.clhs.score.data.occurrenceOn
import com.clhs.score.data.SCHOOL_COURSE_ZONE
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
import com.clhs.score.data.SchoolAnnouncement
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
import com.clhs.score.data.YouBikeRepository
import com.clhs.score.data.YouBikeState
import com.clhs.score.data.YouBikeStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import java.time.Clock
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
    private val reminderState: Flow<GradeReminderState>,
    private val weatherRepository: WeatherRepository? = null,
    private val weatherConfiguration: Flow<WeatherConfiguration> = flowOf(WeatherConfiguration(WeatherSource.OPEN_METEO)),
    private val clock: Clock = Clock.systemUTC(),
    private val youBikeRepository: YouBikeRepository? = null,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(
        refreshes: Flow<Unit> = flowOf(Unit),
        session: AuthenticatedSession? = null,
        clockEnabled: Flow<Boolean> = flowOf(true),
        youBikeEnabled: Flow<Boolean> = clockEnabled,
        youBikeRefreshes: Flow<Unit> = flowOf(Unit),
        widgets: Flow<List<OverviewWidgetInstance>>? = null,
        weatherRefreshes: Flow<Unit> = emptyFlow(),
    ): Flow<OverviewState> {
        if (widgets == null) return observeLegacy(refreshes, session, clockEnabled, youBikeEnabled, youBikeRefreshes)
        val weatherStates = widgets.map { instances -> instances.filter { it.enabled }.mapNotNull { it.config() as? OverviewWidgetConfig.Weather }.distinct() }
            .distinctUntilChanged().flatMapLatest { configurations ->
                var latest: Map<String, OverviewWeatherState> = emptyMap()
                fun failedStates(): Map<String, OverviewWeatherState> = configurations.associate { config ->
                    val key = config.resourceKey()
                    key to when (val previous = latest[key]) {
                        is OverviewWeatherState.Available -> previous.copy(isRefreshing = false, failed = true)
                        else -> OverviewWeatherState.Unavailable()
                    }
                }
                combine(clockEnabled, weatherConfiguration) { visible, configuration -> visible to configuration }
                    .distinctUntilChanged().flatMapLatest { (visible, _) ->
                        if (!visible || configurations.isEmpty()) flowOf(latest.mapValues { (_, state) ->
                            if (state is OverviewWeatherState.Available) state.copy(isRefreshing = false) else state
                        } to emptySet<String>())
                        else weatherRefreshes.map { true }.onStart { emit(false) }.flatMapLatest { force ->
                            val sources = configurations.map { config ->
                                (weatherRepository?.observe(config.source, config.location, force)
                                    ?: flowOf(WeatherRepositoryState(null, false)))
                                    .map { config.resourceKey() to it.toOverviewWeatherState() }
                                    .onStart {
                                        val key = config.resourceKey()
                                        val previous = latest[key] as? OverviewWeatherState.Available
                                        emit(key to (previous?.copy(isRefreshing = true, failed = false) ?: OverviewWeatherState.Loading))
                                    }
                            }
                            combine(sources) { values ->
                                val states = values.toMap()
                                states to if (force) states.filterValues {
                                    it is OverviewWeatherState.Loading || (it is OverviewWeatherState.Available && it.isRefreshing)
                                }.keys else emptySet()
                            }
                        }
                    }.onEach { if (it.first.isNotEmpty()) latest = it.first }
                    .retryWhen { error, attempt ->
                        if (error !is IOException) return@retryWhen false
                        emit(failedStates() to emptySet())
                        delay((1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L))
                        true
                    }.catch { error ->
                        error.rethrowCancellation()
                        if (error !is Exception) throw error
                        emit(failedStates() to emptySet())
                    }
            }
        val stations = widgets.map { instances -> instances.filter { it.enabled }.mapNotNull {
            (it.config() as? OverviewWidgetConfig.YouBike)?.station
        }.distinctBy(YouBikeStation::id) }.distinctUntilChanged()
        val bikeStates = youBikeRepository?.observeStations(stations, youBikeEnabled, youBikeRefreshes) ?: flowOf(emptyMap())
        return combine(observeLegacy(refreshes, session, clockEnabled, youBikeEnabled, youBikeRefreshes, legacyPublic = false), widgets, weatherStates, bikeStates) { base, layout, weather, bikes ->
            base.copy(widgets = layout, weatherLocations = weather.first, manuallyRefreshingWeatherKeys = weather.second, youBikeStations = bikes,
                weather = weather.first.values.firstOrNull() ?: OverviewWeatherState.Loading,
                youBike = bikes[YouBikeStation.SCHOOL.id] ?: YouBikeState(),
                hasPartialFailure = base.hasPartialFailure || bikes.values.any { it.failed } || weather.first.values.any {
                    it is OverviewWeatherState.Unavailable || (it is OverviewWeatherState.Available && it.failed)
                })
        }.distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLegacy(
        refreshes: Flow<Unit> = flowOf(Unit),
        session: AuthenticatedSession? = null,
        clockEnabled: Flow<Boolean> = flowOf(true),
        youBikeEnabled: Flow<Boolean> = clockEnabled,
        youBikeRefreshes: Flow<Unit> = flowOf(Unit),
        legacyPublic: Boolean = true,
    ): Flow<OverviewState> = merge(
        refreshes.flatMapLatest {
            merge(
                if (session != null) scheduleUpdates(session) else flowOf(OverviewUpdate.Schedule(null, false, false)),
                calendarUpdates(),
                announcementUpdates(),
                if (legacyPublic) weatherUpdates() else emptyFlow(),
            )
        },
        if (session != null) gradeUpdates(session.studentNo) else flowOf(OverviewUpdate.Grades(GradeReminderState())),
        youBikeRepository?.takeIf { legacyPublic }?.observe(youBikeEnabled, youBikeRefreshes)?.map(OverviewUpdate::YouBike)
            ?: flowOf(OverviewUpdate.YouBike(YouBikeState())),
        clockEnabled.distinctUntilChanged().flatMapLatest { enabled ->
            if (enabled) clockUpdates() else emptyFlow()
        },
    ).runningFold(OverviewAccumulator(now = schoolNow())) { current, update ->
        current.apply(update).let { if (update == OverviewUpdate.Clock) it.copy(now = schoolNow()) else it }
    }.map { accumulator ->
        buildState(accumulator, requireNotNull(accumulator.now))
    }.distinctUntilChanged()

    private fun schoolNow(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), SCHOOL_ZONE)

    private fun scheduleUpdates(session: AuthenticatedSession): Flow<OverviewUpdate> = flow {
        emit(OverviewUpdate.SourceStarted(OverviewSource.Schedule))
        var cachedReport: ScheduleReport? = null
        try {
            val snapshot = scheduleRepository.getLatestScheduleSnapshot()
            cachedReport = snapshot?.report
            emit(OverviewUpdate.Schedule(snapshot?.report, refreshing = false, failed = false))
            if (snapshot != null) {
                val initialInstant = clock.instant()
                val initialNow = LocalDateTime.ofInstant(initialInstant, SCHOOL_ZONE)
                if (!scheduleNeedsRefresh(snapshot.report, snapshot.fetchedAtMillis, initialInstant)) {
                    val waitMillis = scheduleRefreshDelayMillis(snapshot.report, initialNow)
                    if (waitMillis > 0) delay(waitMillis.milliseconds)
                }
                if (scheduleNeedsRefresh(snapshot.report, snapshot.fetchedAtMillis, clock.instant())) {
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
                        targetDate = snapshot.report.refreshTargetDateAt(schoolNow()),
                        expectedSession = session,
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
            if (cached != null) emit(OverviewUpdate.Calendar(cached, refreshing = false, failed = false))
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
            if (cached != null) emit(OverviewUpdate.Announcements(cached, refreshing = false, failed = false))
            val stale = cached == null || Duration.between(cached.fetchedAt, clock.instant()).let {
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

    private fun gradeUpdates(studentNo: String): Flow<OverviewUpdate> =
        reminderState.map {
            OverviewUpdate.Grades(
                it.takeIf { state -> state.studentNo == studentNo &&
                    (state.latestChangeSet == null || state.latestChangeSet.studentNo == studentNo) }
                    ?: GradeReminderState(),
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun weatherUpdates(): Flow<OverviewUpdate> {
        val repository = weatherRepository ?: return flowOf(
            OverviewUpdate.Weather(WeatherRepositoryState(null, isRefreshing = false)),
        )
        return weatherConfiguration.distinctUntilChanged()
            .flatMapLatest { repository.observe(it.source) }
            .map<WeatherRepositoryState, OverviewUpdate>(OverviewUpdate::Weather)
            .retryWhen { error, attempt ->
                if (error !is IOException) return@retryWhen false
                emit(OverviewUpdate.WeatherConfigurationFailed)
                delay((1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L))
                true
            }.catch { error ->
                error.rethrowCancellation()
                if (error !is Exception) throw error
                emit(OverviewUpdate.WeatherConfigurationFailed)
            }
    }

    private fun clockUpdates(): Flow<OverviewUpdate> = flow {
        while (true) {
            emit(OverviewUpdate.Clock)
            val now = schoolNow()
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
        val calendarItems = calendarOverviewItems(accumulator.calendarEvents, now)
        val sorted = (scheduleItems + calendarItems + accumulator.gradeItems + accumulator.announcementItems)
            .sortedWith(
                compareBy<OverviewItem> { it.priority.ordinal }
                    .thenBy { it.order }
                    .thenBy { it.eventTime ?: LocalDateTime.MAX },
            )
        val completed = accumulator.completedSources.size
        return OverviewState(
            calendarEvents = accumulator.calendarEvents,
            announcements = accumulator.announcements,
            now = now,
            context = overviewContext(accumulator.schedule, now),
            items = sorted,
            isInitialLoading = completed < OverviewSource.entries.size && sorted.isEmpty(),
            isRefreshing = accumulator.refreshingSources.isNotEmpty(),
            isScheduleRefreshing = OverviewSource.Schedule in accumulator.refreshingSources,
            scheduleFailed = OverviewSource.Schedule in accumulator.failedSources,
            hasPartialFailure = accumulator.failedSources.isNotEmpty() || accumulator.weatherConfigurationFailed || accumulator.youBike.failed,
            weather = accumulator.weather.toOverviewWeatherState(),
            youBike = accumulator.youBike,
            hero = accumulator.schedule?.let { scheduleOverviewHero(it, now) },
            hasSchedule = accumulator.schedule != null,
            calendarLoading = OverviewSource.Calendar !in accumulator.completedSources,
            calendarFailed = OverviewSource.Calendar in accumulator.failedSources,
            announcementsLoading = OverviewSource.Announcements !in accumulator.completedSources,
            announcementsFailed = OverviewSource.Announcements in accumulator.failedSources,
        )
    }

    private companion object {
        val ANNOUNCEMENT_MAX_AGE: Duration = Duration.ofMinutes(30)
    }
}

internal fun scheduleNeedsRefresh(
    report: ScheduleReport,
    fetchedAtMillis: Long?,
    now: Instant,
): Boolean {
    val schoolNow = LocalDateTime.ofInstant(now, SCHOOL_ZONE)
    if (report.scope == ScheduleScope.CURRENT_WEEK) {
        val start = report.weekStartDate
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?: return true
        val end = report.weekEndDate
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?: return true
        if (end < start) return true
        val today = schoolNow.toLocalDate()
        if (today < start) return false
        return today > end || report.shouldRefreshAt(schoolNow)
    }
    val fetchedAt = fetchedAtMillis?.let(Instant::ofEpochMilli) ?: return true
    val age = Duration.between(fetchedAt, now)
    return age.isNegative || age > Duration.ofHours(6)
}

internal fun scheduleNeedsRefresh(
    report: ScheduleReport,
    fetchedAtMillis: Long?,
    now: LocalDateTime,
): Boolean = scheduleNeedsRefresh(
    report,
    fetchedAtMillis,
    now.atZone(SCHOOL_ZONE).toInstant(),
)

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
    if (todayItems.any { it.contains(now) }) return OverviewContext.InClass
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
    val current = todayItems.firstOrNull { it.contains(now) }
    val upcoming = todayItems.filter { it.startsAfter(now.toLocalTime()) }.take(3)
    val result = mutableListOf<OverviewItem>()

    val displayDate = scheduleOverviewHero(report, now)?.date?.toLocalDate()
    report.displayChanges()
        .sortedBy(ScheduleChange::period)
        .mapNotNull { change ->
            val removedToday = change.type == ScheduleChangeType.REMOVED && report.isValidOn(today) &&
                change.dayOfWeek == today.dayOfWeek.value &&
                change.semesterItem?.occurrenceOn(today)?.end?.isAfter(now.atZone(SCHOOL_ZONE).toInstant()) == true
            val date = if (removedToday) today else displayDate
            date?.takeIf { report.isValidOn(it) && change.dayOfWeek == it.dayOfWeek.value }
                ?.let { change.toOverviewItem(it) }
        }
        .forEach { result += it }

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
                supportingText = "第 ${first.period} 節 ${first.subjectName}，共 $count 節課",
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
    val current = todayItems.firstOrNull { it.contains(now) }
    val upcoming = todayItems.firstOrNull { it.startsAfter(now.toLocalTime()) }
    val item = current ?: upcoming
    if (item != null) {
        val periodTime = PERIOD_TIMES.getOrNull(item.period - 1)
        return OverviewHero(
            date = today.atStartOfDay(),
            headline = if (current != null) "正在上課" else "接下來",
            courseLabel = "第 ${item.period} 節",
            courseName = item.subjectName,
            startsAt = periodTime?.let { today.atTime(LocalTime.parse(it.start)) },
            endsAt = periodTime?.let { today.atTime(LocalTime.parse(it.end)) },
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
        courseLabel = "第 ${first.period} 節",
        courseName = first.subjectName,
        startsAt = PERIOD_TIMES.getOrNull(first.period - 1)?.let { nextDate.atTime(LocalTime.parse(it.start)) },
        endsAt = PERIOD_TIMES.getOrNull(first.period - 1)?.let { nextDate.atTime(LocalTime.parse(it.end)) },
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
    supportingText = "完成一次學期與班級選擇後，首頁就能顯示目前與接下來的課程。",
    label = "需要設定",
    icon = "calendar_today",
    destination = OverviewDestination.Schedule,
    order = 35,
)

internal fun calendarOverviewItems(
    snapshot: SchoolCalendarSnapshot,
    now: LocalDateTime,
): List<OverviewItem> = calendarOverviewItems(snapshot.feed.events.sortedBy(SchoolCalendarEvent::start), now)

/** Events are sorted when the source changes; clock ticks only apply time-dependent labels and filtering. */
private fun calendarOverviewItems(
    sortedEvents: List<SchoolCalendarEvent>,
    now: LocalDateTime,
): List<OverviewItem> {
    val end = now.plusDays(14)
    return sortedEvents
        .asSequence()
        .filter { it.endExclusive.isAfter(now) && it.start.isBefore(end) }
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
                displayDate = if (isOngoing) now.toLocalDate() else event.start.toLocalDate(),
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
        .map(::announcementOverviewItem)
        .toList()

internal fun announcementOverviewItem(announcement: SchoolAnnouncement): OverviewItem =
    OverviewItem(
        id = "announcement-${announcement.id}",
        priority = OverviewPriority.P2,
        kind = OverviewItemKind.Announcement,
        title = announcement.title,
        supportingText = listOf(announcement.unit, announcement.date).filter(String::isNotBlank).joinToString(" · "),
        label = if (announcement.isPinned) "置頂公告" else "公告",
        displayDate = runCatching { LocalDate.parse(announcement.date.replace('/', '-')) }.getOrNull(),
        icon = "campaign",
        destination = OverviewDestination.Announcement(announcement.id, announcement.category, announcement.externalUrl),
        order = 60,
    )

private enum class OverviewSource { Schedule, Calendar, Announcements, Grades }

private sealed interface OverviewUpdate {
    data class SourceStarted(val source: OverviewSource) : OverviewUpdate
    data class Schedule(val report: ScheduleReport?, val refreshing: Boolean, val failed: Boolean) : OverviewUpdate
    data class Calendar(val snapshot: SchoolCalendarSnapshot?, val refreshing: Boolean, val failed: Boolean) : OverviewUpdate
    data class Announcements(val page: SchoolAnnouncementPage?, val refreshing: Boolean, val failed: Boolean) : OverviewUpdate
    data class Grades(val state: GradeReminderState) : OverviewUpdate
    data class Weather(val state: WeatherRepositoryState) : OverviewUpdate
    data class YouBike(val state: YouBikeState) : OverviewUpdate
    data object WeatherConfigurationFailed : OverviewUpdate
    data object Clock : OverviewUpdate
}

private data class OverviewAccumulator(
    val now: LocalDateTime? = null,
    val schedule: ScheduleReport? = null,
    val calendarEvents: List<SchoolCalendarEvent> = emptyList(),
    val announcementItems: List<OverviewItem> = emptyList(),
    val announcements: List<SchoolAnnouncement> = emptyList(),
    val gradeItems: List<OverviewItem> = emptyList(),
    val weather: WeatherRepositoryState? = null,
    val youBike: YouBikeState = YouBikeState(),
    val weatherConfigurationFailed: Boolean = false,
    val completedSources: Set<OverviewSource> = emptySet(),
    val refreshingSources: Set<OverviewSource> = emptySet(),
    val failedSources: Set<OverviewSource> = emptySet(),
) {
    fun apply(update: OverviewUpdate): OverviewAccumulator = when (update) {
        // Rechecking a loaded schedule cache is not a network refresh.
        is OverviewUpdate.SourceStarted -> if (update.source == OverviewSource.Schedule && update.source in completedSources) this
            else copy(refreshingSources = refreshingSources + update.source)
        is OverviewUpdate.Schedule -> sourceResult(
            source = OverviewSource.Schedule,
            refreshing = update.refreshing,
            failed = update.failed,
        ).copy(schedule = update.report ?: schedule.takeIf {
            update.failed || OverviewSource.Schedule in completedSources
        })
        is OverviewUpdate.Calendar -> sourceResult(
            source = OverviewSource.Calendar,
            refreshing = update.refreshing,
            failed = update.failed,
        ).copy(calendarEvents = update.snapshot?.feed?.events?.sortedBy(SchoolCalendarEvent::start) ?: calendarEvents)
        is OverviewUpdate.Announcements -> sourceResult(
            source = OverviewSource.Announcements,
            refreshing = update.refreshing,
            failed = update.failed,
        ).copy(announcementItems = update.page?.let(::announcementOverviewItems) ?: announcementItems,
            announcements = update.page?.announcements ?: announcements)
        is OverviewUpdate.Grades -> sourceResult(
            source = OverviewSource.Grades,
            refreshing = false,
            failed = false,
        ).copy(gradeItems = gradeOverviewItems(update.state))
        is OverviewUpdate.Weather -> copy(weather = update.state, weatherConfigurationFailed = false)
        is OverviewUpdate.YouBike -> copy(youBike = update.state)
        OverviewUpdate.WeatherConfigurationFailed -> copy(
            weather = weather?.copy(isRefreshing = false) ?: WeatherRepositoryState(null, false),
            weatherConfigurationFailed = true,
        )
        OverviewUpdate.Clock -> this
    }

    private fun sourceResult(
        source: OverviewSource,
        refreshing: Boolean,
        failed: Boolean,
    ): OverviewAccumulator = copy(
        completedSources = if (refreshing) completedSources else completedSources + source,
        refreshingSources = if (refreshing) refreshingSources + source else refreshingSources - source,
        failedSources = if (failed) failedSources + source else failedSources - source,
    )
}

private fun WeatherRepositoryState?.toOverviewWeatherState(): OverviewWeatherState = when {
    this == null -> OverviewWeatherState.Loading
    snapshot != null -> OverviewWeatherState.Available(snapshot, isRefreshing, failed)
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

private fun ScheduleItem.contains(now: LocalDateTime): Boolean =
    occurrenceOn(now.toLocalDate())?.contains(now.atZone(SCHOOL_COURSE_ZONE).toInstant()) == true

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
    val effective = if (type == ScheduleChangeType.REMOVED) semesterItem else weekItem ?: semesterItem
    val action = when (type) {
        ScheduleChangeType.ADDED -> "新增課程"
        ScheduleChangeType.MODIFIED -> "調課"
        ScheduleChangeType.REMOVED -> "停課"
    }
    val details = listOfNotNull(
        effective?.subjectName?.takeIf(String::isNotBlank),
        effective?.classroom?.takeIf(String::isNotBlank),
        effective?.teacherName?.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    return OverviewItem(
        id = "schedule-change-$date-$dayOfWeek-$period-$type",
        priority = OverviewPriority.P0,
        kind = OverviewItemKind.ScheduleChange,
        title = "第 $period 節$action",
        supportingText = details.ifBlank { "課表已更新" },
        label = "課程異動",
        icon = "swap_vert",
        destination = OverviewDestination.Schedule,
        order = 0,
    )
}

private fun calendarTimeText(event: SchoolCalendarEvent, now: LocalDateTime): String = when {
    !event.start.isAfter(now) && event.endExclusive.isAfter(now) && event.isAllDay ->
        "進行中"
    !event.start.isAfter(now) && event.endExclusive.isAfter(now) ->
        "進行中 · ${event.endExclusive.format(DateTimeFormatter.ofPattern("HH:mm"))} 結束"
    event.isAllDay -> "全天 · 開始"
    else -> event.start.format(DateTimeFormatter.ofPattern("HH:mm")) + " 開始"
}

private fun DayOfWeek.localizedName(): String =
    getDisplayName(TextStyle.FULL, Locale.TAIWAN)

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

private val IMPORTANT_EVENT_WORDS = listOf("段考", "期中", "期末", "放假", "補假", "活動")
