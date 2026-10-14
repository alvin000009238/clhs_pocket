package com.clhs.score.data

import com.clhs.score.domain.overview.OverviewWidgetDefinitions
import com.clhs.score.domain.overview.OverviewWidgetInstance
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

@Serializable
internal data class PortableSettings(
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

    companion object {
        fun from(value: AppSettings) = PortableSettings(value.themeMode, value.dynamicColor, value.amoledBlack,
            value.startOnSchedule, value.profileAvatarShapeIndex, value.notificationsEnabled,
            value.preferExactCourseAlarms, value.developerEnabled, value.weatherSource,
            value.backgroundPause.enabled, value.backgroundPause.startMinute, value.backgroundPause.endMinute)
    }
}

@Serializable
internal data class PortableAnnouncementPreferences(
    val enabled: Boolean,
    val intervalMinutes: Int,
    val selectedUnitIds: Set<String>,
) {
    fun validate() {
        require(intervalMinutes in ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES) { "公告檢查間隔不支援" }
        require(selectedUnitIds.size in 1..100 && selectedUnitIds.all { it.length <= 20 }) { "公告單位數量或長度不符" }
        require(withAnnouncementUnits(AnnouncementReminderState(), selectedUnitIds).selectedUnitIds == selectedUnitIds) {
            "公告單位格式不符"
        }
    }
    companion object {
        fun from(value: AnnouncementReminderState) = PortableAnnouncementPreferences(
            value.enabled, value.intervalMinutes, value.selectedUnitIds)
    }
}

@Serializable
internal data class SettingsTransferDocument(
    val app: String,
    val version: Int,
    val settings: PortableSettings,
    val overviewOrderVersion: Int,
    val overview: List<OverviewWidgetInstance>,
    val announcements: PortableAnnouncementPreferences,
    val remindBeforeClass: Boolean,
    // null means the exporter had no unlocked account; [] explicitly clears overrides.
    val subjects: List<ScheduleSubjectOverride>?,
)

internal object SettingsTransferCodec {
    const val MAX_BYTES = 1_048_576
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun encode(document: SettingsTransferDocument): String {
        validate(document)
        return json.encodeToString(document).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "設定超過 1 MiB" } }
    }

    fun decode(text: String): SettingsTransferDocument {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "設定超過 1 MiB" }
        checkDepth(text)
        return try {
            json.decodeFromString<SettingsTransferDocument>(text.removePrefix("\uFEFF")).also(::validate)
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
                '{', '[' -> { depth++; require(depth <= 32) { "設定 JSON 巢狀層數過多" } }
                '}', ']' -> depth--
            }
        }
    }

    fun read(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_BYTES) { "設定檔超過 1 MiB" }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("設定檔不是有效 UTF-8", error)
        }
    }

    fun validate(document: SettingsTransferDocument) {
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
                    if (config is com.clhs.score.domain.overview.OverviewWidgetConfig.Weather) {
                        require(config.location.countryCode == "TW") { "天氣地點只支援臺灣" }
                        config.location.cwaDataset?.let { dataset ->
                            require(Regex("F-D0047-[0-9]{3}").matches(dataset)) { "氣象資料集格式不符" }
                        }
                    }
                }
            }
        }
        require(document.overview.filter { OverviewWidgetDefinitions[it.type]?.multiple == false }
            .groupBy { it.type }.values.all { it.size == 1 }) { "單一首頁元件重複" }
        document.subjects?.let { subjects ->
            require(subjects.size <= 500) { "自訂科目過多" }
            require(subjects.all { it.originalSubjectName.length in 1..80 }) { "原始科目名稱長度不符" }
            require(subjects.map { it.originalSubjectName.trim() }.distinct().size == subjects.size) { "自訂科目重複" }
            require(subjects.normalizedSubjectOverrides().size == subjects.size) { "自訂科目沒有自訂內容" }
        }
    }
}
