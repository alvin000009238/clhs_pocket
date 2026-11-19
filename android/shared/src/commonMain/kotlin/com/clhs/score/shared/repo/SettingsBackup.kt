package com.clhs.score.shared.repo

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.BackgroundPause
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ThemeMode
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.normalizedSubjectOverrides
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetDefinitions
import com.clhs.score.domain.overview.OverviewWidgetInstance
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupSettings(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val amoledBlack: Boolean,
    val startOnSchedule: Boolean,
    val profileAvatarShapeIndex: Int,
    val notificationsEnabled: Boolean,
    val preferExactCourseAlarms: Boolean,
    val developerEnabled: Boolean,
    val weatherSource: WeatherSource,
    val backgroundPauseEnabled: Boolean,
    val backgroundPauseStartMinute: Int,
    val backgroundPauseEndMinute: Int,
) {
    fun validate() {
        require(profileAvatarShapeIndex in 0..3) { "頭像形狀超出範圍" }
        BackgroundPause(backgroundPauseEnabled, backgroundPauseStartMinute, backgroundPauseEndMinute)
    }
}

@Serializable
data class BackupAnnouncementPreferences(
    val enabled: Boolean,
    val intervalMinutes: Int,
    val selectedUnitIds: Set<String>,
) {
    fun validate() {
        require(intervalMinutes in ANNOUNCEMENT_CHECK_INTERVALS_MINUTES) { "公告檢查間隔不支援" }
        require(selectedUnitIds.size in 1..100 && selectedUnitIds.all { it.length <= 20 }) { "公告單位數量或長度不符" }
        require(normalizeAnnouncementUnitIds(selectedUnitIds) == selectedUnitIds) { "公告單位格式不符" }
    }
}

/** The JSON document Android's `SettingsTransferCodec` writes and reads; field names are the contract. */
@Serializable
data class SettingsBackupDocument(
    val app: String,
    val version: Int,
    val settings: BackupSettings,
    val overviewOrderVersion: Int,
    val overview: List<OverviewWidgetInstance>,
    val announcements: BackupAnnouncementPreferences,
    val remindBeforeClass: Boolean,
    /** null means the exporter had no unlocked account; an empty list explicitly clears overrides. */
    val subjects: List<ScheduleSubjectOverride>?,
)

object SettingsBackupCodec {
    const val MAX_BYTES = 1_048_576
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun encode(document: SettingsBackupDocument): String {
        validate(document)
        return json.encodeToString(SettingsBackupDocument.serializer(), document)
            .also { require(it.encodeToByteArray().size <= MAX_BYTES) { "設定超過 1 MiB" } }
    }

    fun decode(text: String): SettingsBackupDocument {
        require(text.length <= MAX_BYTES && text.encodeToByteArray().size <= MAX_BYTES) { "設定超過 1 MiB" }
        checkDepth(text)
        return try {
            json.decodeFromString(SettingsBackupDocument.serializer(), text.removePrefix("\uFEFF")).also(::validate)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("設定內容格式或值不符，請確認是 CLHS Pocket 匯出的 JSON", error)
        }
    }

    private fun checkDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    require(depth <= 32) { "設定 JSON 巢狀層數過多" }
                }
                '}', ']' -> depth--
            }
        }
    }

    fun validate(document: SettingsBackupDocument) {
        require(document.app == "clhs-pocket") { "不是 CLHS Pocket 設定" }
        require(document.version == 1) { "不支援此備份版本" }
        document.settings.validate()
        document.announcements.validate()
        require(document.overviewOrderVersion in 1..2) { "不支援此首頁版本" }
        require(document.overview.size <= 100) { "首頁元件過多" }
        require(document.overview.map { it.id }.distinct().size == document.overview.size) { "首頁身份重複" }
        document.overview.forEach {
            require(it.id.isNotBlank() && it.id.length <= 128 && it.type.isNotBlank() && it.type.length <= 80)
            require(it.configuration.length <= 16_384 && it.configVersion in 1..1000)
            require(it.size.columns in 1..8 && it.size.rows in 1..20)
            OverviewWidgetDefinitions[it.type]?.let { definition ->
                require(it.size in definition.sizes) { "首頁元件尺寸不符" }
                if (it.configVersion == 1) {
                    val config = requireNotNull(it.config()) { "首頁元件設定不符" }
                    if (config is OverviewWidgetConfig.Weather) {
                        require(config.location.countryCode == "TW") { "天氣地點只支援臺灣" }
                        config.location.cwaDataset?.let { dataset ->
                            require(Regex("F-D0047-[0-9]{3}").matches(dataset)) { "氣象資料集格式不符" }
                        }
                    }
                }
            }
        }
        require(
            document.overview.filter { OverviewWidgetDefinitions[it.type]?.multiple == false }
                .groupBy { it.type }.values.all { it.size == 1 },
        ) { "單一首頁元件重複" }
        document.subjects?.let { subjects ->
            require(subjects.size <= 500) { "自訂科目過多" }
            require(subjects.all { it.originalSubjectName.length in 1..80 }) { "原始科目名稱長度不符" }
            require(subjects.map { it.originalSubjectName.trim() }.distinct().size == subjects.size) { "自訂科目重複" }
            require(subjects.normalizedSubjectOverrides().size == subjects.size) { "自訂科目沒有自訂內容" }
        }
    }
}

/** A restore failed. [rollbackFailed] means the previous values could not all be put back. */
class SettingsRestoreException(val rollbackFailed: Boolean, cause: Exception) : Exception("Settings restore failed", cause)

/**
 * Settings export and import in the Android backup format.
 *
 * Subject overrides belong to an account: they are exported only for [session] and imported only
 * when the caller asks for them and that same session is still the authorized one.
 */
class SettingsBackupService(
    private val settings: SettingsStore,
    private val cache: PrivateCache,
    private val vault: SessionVault,
) {
    fun export(session: AuthenticatedSession?): SettingsBackupDocument {
        session?.let(vault::validateSession)
        val current = settings.load()
        return SettingsBackupDocument(
            app = "clhs-pocket",
            version = 1,
            settings = BackupSettings(
                current.themeMode, current.dynamicColor, current.amoledBlack, current.startOnSchedule,
                current.profileAvatarShapeIndex, current.notificationsEnabled, current.preferExactCourseAlarms,
                current.developerEnabled, current.weatherSource, current.backgroundPauseEnabled,
                current.backgroundPauseStartMinute, current.backgroundPauseEndMinute,
            ),
            overviewOrderVersion = if (current.overviewLegacyOrder) 1 else 2,
            overview = current.overview,
            announcements = BackupAnnouncementPreferences(
                current.announcementReminderEnabled, current.announcementIntervalMinutes, current.announcementUnitIds,
            ),
            remindBeforeClass = current.remindBeforeClass,
            subjects = session?.let { cache.loadSubjectOverrides(it.studentNo) },
        ).also(SettingsBackupCodec::validate)
    }

    fun exportJson(session: AuthenticatedSession?): String = SettingsBackupCodec.encode(export(session))

    fun restoreJson(text: String, includeSubjects: Boolean, expectedSession: AuthenticatedSession?) =
        restore(SettingsBackupCodec.decode(text), includeSubjects, expectedSession)

    fun restore(document: SettingsBackupDocument, includeSubjects: Boolean, expectedSession: AuthenticatedSession?) {
        SettingsBackupCodec.validate(document)
        if (includeSubjects) {
            require(document.subjects != null && expectedSession != null) { "請先登入再匯入自訂科目" }
            vault.validateSession(expectedSession)
        }
        val previous = settings.load()
        val previousSubjects = if (includeSubjects) cache.loadSubjectOverrides(requireNotNull(expectedSession).studentNo) else null
        val undo = ArrayList<() -> Unit>()
        try {
            if (includeSubjects) {
                val session = requireNotNull(expectedSession)
                undo += { cache.saveSubjectOverrides(session.studentNo, requireNotNull(previousSubjects)) { vault.isAuthorized(session) } }
                cache.saveSubjectOverrides(session.studentNo, requireNotNull(document.subjects)) { vault.isAuthorized(session) }
                vault.validateSession(session)
            }
            undo += { settings.save(previous) }
            settings.save(
                previous.copy(
                    themeMode = document.settings.themeMode,
                    dynamicColor = document.settings.dynamicColor,
                    amoledBlack = document.settings.amoledBlack,
                    startOnSchedule = document.settings.startOnSchedule,
                    profileAvatarShapeIndex = document.settings.profileAvatarShapeIndex,
                    notificationsEnabled = document.settings.notificationsEnabled,
                    preferExactCourseAlarms = document.settings.preferExactCourseAlarms,
                    developerEnabled = document.settings.developerEnabled,
                    weatherSource = document.settings.weatherSource,
                    backgroundPauseEnabled = document.settings.backgroundPauseEnabled,
                    backgroundPauseStartMinute = document.settings.backgroundPauseStartMinute,
                    backgroundPauseEndMinute = document.settings.backgroundPauseEndMinute,
                    overview = document.overview,
                    overviewLegacyOrder = document.overviewOrderVersion == 1,
                    announcementReminderEnabled = document.announcements.enabled,
                    announcementIntervalMinutes = document.announcements.intervalMinutes,
                    announcementUnitIds = document.announcements.selectedUnitIds,
                    remindBeforeClass = document.remindBeforeClass,
                ),
            )
        } catch (error: Exception) {
            var rollbackFailed = false
            undo.asReversed().forEach { rollback ->
                try {
                    rollback()
                } catch (failure: Exception) {
                    rollbackFailed = true
                    error.addSuppressed(failure)
                }
            }
            if (error is CancellationException && !rollbackFailed) throw error
            throw SettingsRestoreException(rollbackFailed, error)
        }
    }
}
