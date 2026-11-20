package com.clhs.score.shared.repo

import com.clhs.score.data.AnnouncementFilterOptions
import com.clhs.score.data.AttendanceRecord
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeReport
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordHistoryStatistic
import com.clhs.score.data.RecordSemester
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleFetchResult
import com.clhs.score.data.ScheduleScope
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.schoolToday
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ScheduleWeekOption
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementDetail
import com.clhs.score.data.SchoolAnnouncementPage
import com.clhs.score.data.SchoolCalendarSnapshot
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.YouBikeStation
import com.clhs.score.domain.overview.WeatherSnapshot
import com.clhs.score.shared.net.CwaKeyValidation

/**
 * The surface Swift calls. Every function declares its exceptions, so a failure reaches Swift as an
 * `NSError` (classify it with [PocketFailure.from]) instead of terminating the process, and every
 * function runs off the calling thread and honours its [CancelToken].
 */
class PocketApi(private val services: PocketServices) {
    // Session

    @Throws(Exception::class)
    suspend fun restoreSession(token: CancelToken?): AuthenticatedSession? = pocketCall(token) { services.school.restoreSession() }

    @Throws(Exception::class)
    suspend fun loginWithCookies(studentNo: String, cookies: Map<String, String>, token: CancelToken?): AuthenticatedSession =
        pocketCall(token) { services.school.loginWithCookies(studentNo, cookies) }

    @Throws(Exception::class)
    suspend fun logout(token: CancelToken?) = pocketCall(token) { services.account.logout() }

    @Throws(Exception::class)
    suspend fun deleteAllData(token: CancelToken?) = pocketCall(token) { services.account.deleteAllData() }

    @Throws(Exception::class)
    suspend fun hasPendingCleanup(token: CancelToken?): Boolean = pocketCall(token) { services.hasPendingCleanup() }

    // School data

    @Throws(Exception::class)
    suspend fun loadStructure(session: AuthenticatedSession, forceRefresh: Boolean, token: CancelToken?): List<YearTermOption> =
        pocketCall(token) { services.school.loadStructure(session, forceRefresh) }

    @Throws(Exception::class)
    suspend fun fetchGrades(
        session: AuthenticatedSession, yearValue: String, examValue: String, forceRefresh: Boolean, token: CancelToken?,
    ): GradeReport = pocketCall(token) { services.school.fetchGrades(session, yearValue, examValue, forceRefresh) }

    @Throws(Exception::class)
    suspend fun cachedStudentInfo(session: AuthenticatedSession, token: CancelToken?): StudentInfo? =
        pocketCall(token) { services.school.loadCachedStudentInfo(session) }

    @Throws(Exception::class)
    suspend fun fetchStudentInfo(session: AuthenticatedSession, token: CancelToken?): StudentInfo =
        pocketCall(token) { services.school.fetchStudentInfo(session) }

    @Throws(Exception::class)
    suspend fun recordSemesters(session: AuthenticatedSession, token: CancelToken?): List<RecordSemester> =
        pocketCall(token) { services.school.fetchRecordSemesters(session) }

    @Throws(Exception::class)
    suspend fun attendanceHistory(session: AuthenticatedSession, token: CancelToken?): List<RecordHistoryStatistic> =
        pocketCall(token) { services.school.fetchAttendanceHistory(session) }

    @Throws(Exception::class)
    suspend fun meritHistory(session: AuthenticatedSession, token: CancelToken?): List<RecordHistoryStatistic> =
        pocketCall(token) { services.school.fetchMeritHistory(session) }

    @Throws(Exception::class)
    suspend fun attendance(session: AuthenticatedSession, semester: RecordSemester, token: CancelToken?): AttendanceRecord =
        pocketCall(token) { services.school.fetchAttendance(session, semester) }

    @Throws(Exception::class)
    suspend fun merits(session: AuthenticatedSession, semester: RecordSemester, token: CancelToken?): List<MeritRecord> =
        pocketCall(token) { services.school.fetchMerits(session, semester) }

    @Throws(Exception::class)
    suspend fun scheduleYears(session: AuthenticatedSession, token: CancelToken?): List<ScheduleYearTermOption> =
        pocketCall(token) { services.school.getScheduleYears(session) }

    @Throws(Exception::class)
    suspend fun scheduleClasses(session: AuthenticatedSession, year: String, term: String, token: CancelToken?): List<ScheduleClassOption> =
        pocketCall(token) { services.school.getScheduleClasses(session, year, term) }

    @Throws(Exception::class)
    suspend fun scheduleWeeks(session: AuthenticatedSession, year: String, term: String, token: CancelToken?): List<ScheduleWeekOption> =
        pocketCall(token) { services.school.getScheduleWeeks(session, year, term) }

    @Throws(Exception::class)
    suspend fun fetchSchedule(
        session: AuthenticatedSession, yearValue: String, year: String, term: String, classNo: String,
        scope: ScheduleScope, token: CancelToken?,
    ): ScheduleFetchResult = pocketCall(token) { services.school.fetchSchedule(session, yearValue, year, term, classNo, scope) }

    /** The weekly timetable [weekOffset] weeks from the current school week, as Android queries it by date. */
    @Throws(Exception::class)
    suspend fun fetchScheduleWeek(
        session: AuthenticatedSession, yearValue: String, year: String, term: String, classNo: String,
        weekOffset: Int, token: CancelToken?,
    ): ScheduleFetchResult = pocketCall(token) {
        services.school.fetchSchedule(
            session, yearValue, year, term, classNo, ScheduleScope.CURRENT_WEEK, schoolToday().shiftDays(7L * weekOffset),
        )
    }

    @Throws(Exception::class)
    suspend fun latestSchedule(session: AuthenticatedSession, token: CancelToken?): CachedSchedule? =
        pocketCall(token) { services.school.loadLatestSchedule(session) }

    @Throws(Exception::class)
    suspend fun subjectOverrides(session: AuthenticatedSession, token: CancelToken?): List<ScheduleSubjectOverride> =
        pocketCall(token) { services.school.loadSubjectOverrides(session) }

    @Throws(Exception::class)
    suspend fun saveSubjectOverrides(session: AuthenticatedSession, overrides: List<ScheduleSubjectOverride>, token: CancelToken?) =
        pocketCall(token) { services.school.saveSubjectOverrides(session, overrides) }

    // Public data

    @Throws(Exception::class)
    suspend fun cachedAnnouncements(token: CancelToken?): SchoolAnnouncementPage? = pocketCall(token) { services.announcements.loadCached() }

    @Throws(Exception::class)
    suspend fun announcements(pageIndex: Int, keyword: String, unitId: String, categoryId: String, token: CancelToken?): SchoolAnnouncementPage =
        pocketCall(token) { services.announcements.loadPage(pageIndex, keyword, unitId, categoryId) }

    @Throws(Exception::class)
    suspend fun cachedAnnouncementFilters(token: CancelToken?): AnnouncementFilterOptions =
        pocketCall(token) { services.announcements.loadCachedFilterOptions() }

    @Throws(Exception::class)
    suspend fun announcementFilters(token: CancelToken?): AnnouncementFilterOptions =
        pocketCall(token) { services.announcements.loadFilterOptions() }

    @Throws(Exception::class)
    suspend fun announcementDetail(id: String, categoryHint: String, token: CancelToken?): SchoolAnnouncementDetail =
        pocketCall(token) { services.announcements.loadDetail(id, categoryHint) }

    @Throws(Exception::class)
    suspend fun unreadAnnouncementIds(token: CancelToken?): Set<String> = pocketCall(token) { services.announcements.unreadIds() }

    @Throws(Exception::class)
    suspend fun markAnnouncementRead(id: String, token: CancelToken?) = pocketCall(token) { services.announcements.markRead(id) }

    @Throws(Exception::class)
    suspend fun markAnnouncementsRead(ids: Set<String>, token: CancelToken?) = pocketCall(token) { services.announcements.markAllRead(ids) }

    @Throws(Exception::class)
    suspend fun bookmarkedAnnouncements(token: CancelToken?): List<SchoolAnnouncement> = pocketCall(token) { services.announcements.bookmarks() }

    @Throws(Exception::class)
    suspend fun toggleAnnouncementBookmark(announcement: SchoolAnnouncement, token: CancelToken?): Boolean =
        pocketCall(token) { services.announcements.toggleBookmark(announcement) }

    @Throws(Exception::class)
    suspend fun cachedCalendar(token: CancelToken?): SchoolCalendarSnapshot? = pocketCall(token) { services.calendar.loadCached() }

    @Throws(Exception::class)
    suspend fun calendar(forceRefresh: Boolean, token: CancelToken?): SchoolCalendarSnapshot =
        pocketCall(token) { services.calendar.load(forceRefresh) }

    @Throws(Exception::class)
    suspend fun cachedWeather(source: WeatherSource, location: WeatherLocation, token: CancelToken?): WeatherSnapshot? =
        pocketCall(token) { services.weather.cached(source, location) }

    @Throws(Exception::class)
    suspend fun weather(source: WeatherSource, location: WeatherLocation, forceRefresh: Boolean, token: CancelToken?): WeatherLoad =
        pocketCall(token) { services.weather.load(source, location, forceRefresh) }

    @Throws(Exception::class)
    suspend fun hasCwaKey(token: CancelToken?): Boolean = pocketCall(token) { services.weather.hasCwaKey() }

    @Throws(Exception::class)
    suspend fun saveCwaKey(rawKey: String, token: CancelToken?): CwaKeyValidation = pocketCall(token) { services.weather.saveCwaKey(rawKey) }

    @Throws(Exception::class)
    suspend fun clearCwaKey(token: CancelToken?) = pocketCall(token) { services.weather.clearCwaKey() }

    @Throws(Exception::class)
    suspend fun youBike(stations: List<YouBikeStation>, forceRefresh: Boolean, token: CancelToken?): List<YouBikeStationLoad> =
        pocketCall(token) { services.youBike.load(stations, forceRefresh) }

    @Throws(Exception::class)
    suspend fun searchYouBikeStations(query: String, token: CancelToken?): List<YouBikeStation> =
        pocketCall(token) { services.youBike.searchStations(query) }

    // Settings

    @Throws(Exception::class)
    suspend fun loadSettings(token: CancelToken?): PocketSettings = pocketCall(token) { services.settings.load() }

    @Throws(Exception::class)
    suspend fun saveSettings(settings: PocketSettings, token: CancelToken?) = pocketCall(token) { services.settings.save(settings) }

    @Throws(Exception::class)
    suspend fun exportSettingsJson(session: AuthenticatedSession?, token: CancelToken?): String =
        pocketCall(token) { services.backup.exportJson(session) }

    /** Parses and validates a backup without applying it, for the import confirmation step. */
    @Throws(Exception::class)
    suspend fun inspectSettingsJson(text: String, token: CancelToken?): SettingsBackupDocument =
        pocketCall(token) { SettingsBackupCodec.decode(text) }

    @Throws(Exception::class)
    suspend fun restoreSettingsJson(text: String, includeSubjects: Boolean, session: AuthenticatedSession?, token: CancelToken?) =
        pocketCall(token) { services.backup.restoreJson(text, includeSubjects, session) }
}
