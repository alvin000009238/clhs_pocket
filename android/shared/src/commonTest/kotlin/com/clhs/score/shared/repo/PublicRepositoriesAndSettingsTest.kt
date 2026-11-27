package com.clhs.score.shared.repo

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ThemeMode
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.shared.net.CwaKeyValidation
import com.clhs.score.shared.net.FakeHttpTransport
import com.clhs.score.shared.net.PublicAnnouncementsClient
import com.clhs.score.shared.net.PublicCalendarClient
import com.clhs.score.shared.net.PublicFixtures
import com.clhs.score.shared.net.PublicWeatherClient
import com.clhs.score.shared.net.header
import com.clhs.score.shared.net.response
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.parseInstant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicRepositoriesAndSettingsTest {
    private val secure = FakeSecureStore()
    private val files = FakeFileStore()
    private var clock = parseInstant("2026-08-30T04:30:00Z").epochMillis()
    private val now = { clock }
    private val minute = 60_000L

    // Announcements

    @Test
    fun onlyTheDefaultFirstPageIsCachedAndItIsReadableOffline() = runTest {
        var body = PublicFixtures.RECORDED_LIST_JSON
        val transport = FakeHttpTransport { response(body) }
        val repository = AnnouncementsRepository(PublicAnnouncementsClient(transport), files, now)
        assertNull(repository.loadCached())
        val page = repository.loadPage(0)
        assertEquals(page.announcements, assertNotNull(repository.loadCached()).announcements)

        body = PublicFixtures.RECORDED_SEARCH_JSON
        assertEquals("search-result", repository.loadPage(0, keyword = "模擬考").announcements.single().id)
        assertEquals(page.announcements, assertNotNull(repository.loadCached()).announcements)
        // Public requests never carry school cookies.
        assertTrue(transport.requests.all { it.header("Cookie") == null })
    }

    @Test
    fun readStateStartsAtFirstUseAndBookmarksPersist() = runTest {
        val transport = FakeHttpTransport { response(PublicFixtures.RECORDED_LIST_JSON) }
        val repository = AnnouncementsRepository(PublicAnnouncementsClient(transport), files, now)
        // First use is 2026-08-30; everything in the fixture was published earlier, except undated items.
        val page = repository.loadPage(0)
        assertEquals(setOf("external"), repository.unreadIds())
        clock = parseInstant("2026-08-01T00:00:00Z").epochMillis()
        files.files.remove("${FileStore.SETTINGS}/announcement-read.json")
        repository.discover(page.announcements)
        assertEquals(setOf("45072", "45068", "external"), repository.unreadIds())
        repository.markRead("45072")
        repository.markAllRead(setOf("45068"))
        assertEquals(setOf("external"), AnnouncementsRepository(PublicAnnouncementsClient(transport), files, now).unreadIds())

        val first = page.announcements.first()
        assertTrue(repository.toggleBookmark(first))
        assertEquals(listOf(first.id), repository.bookmarks().map { it.id })
        assertFalse(repository.toggleBookmark(first))
        assertTrue(repository.bookmarks().isEmpty())
        // Bookmarks are never evicted: the oldest one may be the only copy of an announcement gone from the list.
        repeat(501) { repository.toggleBookmark(first.copy(id = "b$it")) }
        assertEquals(501, repository.bookmarks().size)
        assertEquals("b0", repository.bookmarks().last().id)
        files.failWrites += FileStore.SETTINGS
        assertFailsWith<StorageException> { repository.toggleBookmark(first) }
        // Public content still loads while reading state cannot be stored.
        assertEquals(page.announcements, repository.loadPage(0).announcements)
    }

    // Calendar

    @Test
    fun calendarIsServedFromCacheForSixHoursUnlessRefreshIsForced() = runTest {
        val transport = FakeHttpTransport { response(PublicFixtures.SAMPLE_ICS) }
        val repository = CalendarRepository(PublicCalendarClient(transport), files, now)
        val first = repository.load(forceRefresh = false)
        clock += 6 * 60 * minute
        assertEquals(first.feed, repository.load(forceRefresh = false).feed)
        assertEquals(1, transport.requests.size)
        clock += 1
        repository.load(forceRefresh = false)
        assertEquals(2, transport.requests.size)
        repository.load(forceRefresh = true)
        assertEquals(3, transport.requests.size)
        assertEquals(first.feed, assertNotNull(repository.loadCached()).feed)
    }

    // Weather

    @Test
    fun weatherIsFreshForThirtyMinutesAndStaleDataSurvivesAFailedRefresh() = runTest {
        var next = response(PublicFixtures.OPEN_METEO_RESPONSE)
        val transport = FakeHttpTransport { next }
        val repository = WeatherRepository(PublicWeatherClient(transport), files, secure, now)
        val first = repository.load(WeatherSource.OPEN_METEO)
        assertNotNull(first.snapshot)
        clock += 30 * minute
        assertTrue(repository.load(WeatherSource.OPEN_METEO).fromFreshCache)
        assertEquals(1, transport.requests.size)

        clock += 1
        next = response(status = 500)
        val failed = repository.load(WeatherSource.OPEN_METEO)
        assertTrue(failed.failed)
        assertNotNull(failed.snapshot)
        clock += 6 * 60 * minute
        assertNull(repository.load(WeatherSource.OPEN_METEO).snapshot)
        assertNull(repository.cached(WeatherSource.OPEN_METEO))
    }

    @Test
    fun cwaKeyIsStoredOnlyWhenValidAndOnlyInSecureStorage() = runTest {
        var next = response(status = 401)
        val transport = FakeHttpTransport { next }
        val repository = WeatherRepository(PublicWeatherClient(transport), files, secure, now)
        assertTrue(repository.load(WeatherSource.CWA).needsCwaApiKey)
        assertTrue(transport.requests.isEmpty())

        assertEquals(CwaKeyValidation.INVALID_AUTHORIZATION, repository.saveCwaKey("CWA-SECRET"))
        assertFalse(repository.hasCwaKey())
        next = response(PublicFixtures.CWA_RESPONSE)
        assertEquals(CwaKeyValidation.VALID, repository.saveCwaKey(" CWA-SECRET "))
        assertEquals(mapOf(WeatherRepository.CWA_KEY to "CWA-SECRET"), secure.values)

        assertNotNull(repository.load(WeatherSource.CWA).snapshot)
        val request = transport.requests.last()
        assertEquals("CWA-SECRET", request.header("Authorization"))
        assertFalse(request.url.contains("CWA-SECRET"))
        assertTrue(files.files.values.none { it.first.contains("CWA-SECRET") })

        secure.failDelete = true
        assertFailsWith<StorageException> { repository.clearCwaKey() }
        secure.failDelete = false
        repository.clearCwaKey()
        assertTrue(repository.load(WeatherSource.CWA, forceRefresh = true).needsCwaApiKey)
    }

    // Settings and backup

    private val androidBackup = """
        {
          "app": "clhs-pocket",
          "version": 1,
          "settings": {
            "themeMode": "DARK", "dynamicColor": true, "amoledBlack": true, "startOnSchedule": true,
            "profileAvatarShapeIndex": 2, "notificationsEnabled": true, "preferExactCourseAlarms": true,
            "developerEnabled": false, "weatherSource": "CWA", "backgroundPauseEnabled": true,
            "backgroundPauseStartMinute": 1320, "backgroundPauseEndMinute": 390
          },
          "overviewOrderVersion": 2,
          "overview": [],
          "announcements": {"enabled": true, "intervalMinutes": 180, "selectedUnitIds": ["425"]},
          "remindBeforeClass": true,
          "subjects": [{"originalSubjectName": "國語文", "customSubjectName": "國文"}]
        }
    """.trimIndent()

    private class Backup(val service: SettingsBackupService, val settings: SettingsStore, val cache: PrivateCache, val vault: SessionVault)

    private fun backup(): Backup {
        val settings = SettingsStore(files, now)
        val cache = PrivateCache(files, now)
        val vault = SessionVault(secure, files)
        return Backup(SettingsBackupService(settings, cache, vault), settings, cache, vault)
    }

    @Test
    fun androidBackupIsAppliedAndExportedAgainUnchanged() = runTest {
        val rig = backup()
        val session = rig.vault.establishSession(AuthenticatedSession("DEMO-001", "t", emptyMap()))
        rig.service.restoreJson(androidBackup, includeSubjects = true, expectedSession = session)

        val applied = rig.settings.load()
        assertEquals(ThemeMode.DARK, applied.themeMode)
        assertEquals(WeatherSource.CWA, applied.weatherSource)
        assertEquals(1320, applied.backgroundPauseStartMinute)
        assertEquals(setOf("425"), applied.announcementUnitIds)
        assertTrue(applied.remindBeforeClass && applied.dynamicColor && applied.overview.isEmpty())
        assertEquals("國文", rig.cache.loadSubjectOverrides("DEMO-001").single().customSubjectName)

        assertEquals(SettingsBackupCodec.decode(androidBackup), SettingsBackupCodec.decode(rig.service.exportJson(session)))
        // Without an unlocked account the export says nothing about subjects.
        assertNull(rig.service.export(null).subjects)
    }

    @Test
    fun importNeverTouchesSubjectsWithoutTheAuthorizedSessionAndKeepsDeviceOnlySettings() = runTest {
        val rig = backup()
        rig.settings.save(PocketSettings(appLockEnabled = true, hasCompletedOnboarding = true))
        rig.service.restoreJson(androidBackup, includeSubjects = false, expectedSession = null)
        assertTrue(files.files.keys.none { "subject-overrides" in it })
        assertTrue(rig.settings.load().appLockEnabled && rig.settings.load().hasCompletedOnboarding)

        assertFailsWith<IllegalArgumentException> { rig.service.restoreJson(androidBackup, includeSubjects = true, expectedSession = null) }
        val session = rig.vault.establishSession(AuthenticatedSession("DEMO-001", "t", emptyMap()))
        rig.vault.revoke()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            rig.service.restoreJson(androidBackup, includeSubjects = true, expectedSession = session)
        }
        assertTrue(rig.cache.loadSubjectOverrides("DEMO-001").isEmpty())
    }

    @Test
    fun failedImportRestoresThePreviousSubjectsAndSettings() = runTest {
        val rig = backup()
        val session = rig.vault.establishSession(AuthenticatedSession("DEMO-001", "t", emptyMap()))
        val previous = listOf(ScheduleSubjectOverride("數學", customTeacherName = "王老師"))
        rig.cache.saveSubjectOverrides("DEMO-001", previous) { true }
        rig.settings.save(PocketSettings(themeMode = ThemeMode.LIGHT))

        files.failWrites += "${FileStore.SETTINGS}/app-settings.json"
        val error = assertFailsWith<SettingsRestoreException> {
            rig.service.restoreJson(androidBackup, includeSubjects = true, expectedSession = session)
        }
        // The settings file cannot be written at all, so putting it back fails too and is reported.
        assertTrue(error.rollbackFailed)
        assertEquals(PocketFailureKind.STORAGE, PocketFailure.from(error).kind)
        assertEquals(previous, rig.cache.loadSubjectOverrides("DEMO-001"))
        files.failWrites.clear()
        assertEquals(ThemeMode.LIGHT, rig.settings.load().themeMode)
    }

    @Test
    fun malformedOrForeignBackupsAreRejected() {
        fun rejects(text: String) = assertFailsWith<IllegalArgumentException> { SettingsBackupCodec.decode(text) }
        rejects("not json")
        rejects(androidBackup.replace("clhs-pocket", "other-app"))
        rejects(androidBackup.replace("\"version\": 1", "\"version\": 2"))
        rejects(androidBackup.replace("\"intervalMinutes\": 180", "\"intervalMinutes\": 45"))
        rejects(androidBackup.replace("\"profileAvatarShapeIndex\": 2", "\"profileAvatarShapeIndex\": 9"))
        rejects(androidBackup.replace("\"425\"", "\"../x\""))
        rejects(androidBackup.replace("\"customSubjectName\": \"國文\"", "\"customSubjectName\": \" \""))
        rejects(androidBackup.replace("\"overview\": []", "\"overview\": ${"[".repeat(40)}${"]".repeat(40)}"))
        rejects(androidBackup.replace("\"remindBeforeClass\": true,", "\"remindBeforeClass\": true, \"unknown\": 1,"))
        rejects(androidBackup + " ".repeat(SettingsBackupCodec.MAX_BYTES))
        assertEquals(1, SettingsBackupCodec.decode("\uFEFF$androidBackup").subjects?.size)
    }

    @Test
    fun damagedSettingsFailClosedUntilResetAndInvalidValuesAreNotSaved() = runTest {
        val settings = SettingsStore(files, now)
        files.write("${FileStore.SETTINGS}/app-settings.json", "{broken", 0L)
        // A damaged file must not read as defaults, which would silently turn the app lock off.
        val error = assertFailsWith<SettingsUnreadableException> { settings.load() }
        assertEquals(PocketFailureKind.STORAGE, PocketFailure.from(error).kind)
        assertFailsWith<SettingsUnreadableException> { settings.update { it.copy(themeMode = ThemeMode.DARK) } }
        settings.clear()
        // Default home widgets get fresh ids, so compare everything else.
        assertEquals(PocketSettings(overview = emptyList()), settings.load().copy(overview = emptyList()))
        assertEquals(PocketSettings().overview.map { it.type }, settings.load().overview.map { it.type })

        assertFailsWith<IllegalArgumentException> { settings.save(PocketSettings(announcementIntervalMinutes = 7)) }
        files.failWrites += FileStore.SETTINGS
        assertFailsWith<StorageException> { settings.save(PocketSettings(appLockEnabled = true)) }
    }

    @Test
    fun overridesWriteThatFailsAfterReplacingTheFileIsRolledBack() = runTest {
        val rig = backup()
        val session = rig.vault.establishSession(AuthenticatedSession("DEMO-001", "t", emptyMap()))
        val previous = listOf(ScheduleSubjectOverride("數學", customTeacherName = "王老師"))
        rig.cache.saveSubjectOverrides("DEMO-001", previous) { true }
        rig.settings.save(PocketSettings(themeMode = ThemeMode.LIGHT))
        // The file is replaced, then setting its attributes fails.
        files.partialWriteOnce += "${FileStore.SETTINGS}/subject-overrides/"

        val error = assertFailsWith<SettingsRestoreException> {
            rig.service.restoreJson(androidBackup, includeSubjects = true, expectedSession = session)
        }
        assertFalse(error.rollbackFailed)
        assertEquals("設定匯入失敗，原本的設定已保留", PocketFailure.from(error).message)
        assertEquals(previous, rig.cache.loadSubjectOverrides("DEMO-001"))
        assertEquals(ThemeMode.LIGHT, rig.settings.load().themeMode)
    }

    @Test
    fun revocationDuringImportIsReportedAsAFailedRollback() = runTest {
        val rig = backup()
        val session = rig.vault.establishSession(AuthenticatedSession("DEMO-001", "t", emptyMap()))
        val previous = listOf(ScheduleSubjectOverride("數學", customTeacherName = "王老師"))
        rig.cache.saveSubjectOverrides("DEMO-001", previous) { true }
        // Logout revokes while the imported overrides are being written.
        files.onWrite["${FileStore.SETTINGS}/subject-overrides/"] = { rig.vault.revoke() }

        val error = assertFailsWith<SettingsRestoreException> {
            rig.service.restoreJson(androidBackup, includeSubjects = true, expectedSession = session)
        }
        assertTrue(error.rollbackFailed)
        assertEquals("設定匯入失敗，且部分設定無法還原", PocketFailure.from(error).message)
    }

    @Test
    fun weatherForALongNamedLocationGetsItsOwnCacheFile() = runTest {
        val transport = FakeHttpTransport { response(PublicFixtures.OPEN_METEO_RESPONSE) }
        val repository = WeatherRepository(PublicWeatherClient(transport), files, secure, now)
        val location = WeatherLocation(
            id = "custom", name = "桃園市中壢區中央西路二段一百號附近的一個很長的地點名稱",
            latitude = 24.957_123_456_789, longitude = 121.215_987_654_321,
            cwaDataset = "F-D0047-005", cwaLocationName = "中壢區中央西路二段一百號附近的一個很長的地點名稱",
        )
        assertNotNull(repository.load(WeatherSource.OPEN_METEO, location).snapshot)
        assertNotNull(repository.cached(WeatherSource.OPEN_METEO, location))
        assertNull(repository.cached(WeatherSource.OPEN_METEO))
    }
}
