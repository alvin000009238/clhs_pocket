package com.clhs.score.data

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.edit
import com.clhs.score.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

enum class LocalDataCategory(
    val key: String,
    val label: String,
    val isClearable: Boolean = true,
) {
    GradeCache("grade_cache", "成績快取"),
    Settings("settings", "設定資料", isClearable = false),
    Session("session", "登入資料"),
    WebView("webview", "WebView 資料"),
    Cache("cache", "Cache"),
    NoBackupWebView("no_backup_webview", "No backup WebView 資料"),
}

data class StorageEntry(
    val category: LocalDataCategory,
    val bytes: Long,
) {
    val key: String = category.key
    val label: String = category.label
    val isClearable: Boolean = category.isClearable
}

data class StorageDiagnostics(
    val generatedAt: String,
    val entries: List<StorageEntry>,
) {
    val totalBytes: Long = entries.sumOf { it.bytes }
}

data class LocalDataCleanupResult(
    val removedBytes: Long,
    val storageAfterCleanup: StorageDiagnostics,
)

data class ErrorDiagnosticContext(
    val isLoggedIn: Boolean,
    val loginErrorMessage: String?,
    val gradesErrorMessage: String?,
)

data class DiagnosticEvent(
    val timestamp: String,
    val area: String,
    val message: String,
)

class DeveloperDiagnostics(context: Context) {
    private val appContext = context.applicationContext

    suspend fun collectStorageDiagnostics(): StorageDiagnostics = withContext(Dispatchers.IO) {
        buildStorageDiagnostics()
    }

    suspend fun clearLocalData(
        categories: Set<LocalDataCategory> = LocalDataCategory.entries.filter { it.isClearable }.toSet(),
    ): LocalDataCleanupResult = withContext(Dispatchers.IO) {
        val before = buildStorageDiagnostics().totalBytes
        val clearableCategories = categories.filter { it.isClearable }.toSet()

        if (LocalDataCategory.GradeCache in clearableCategories) {
            GradeCacheStore(appContext).clearAll()
        }
        if (LocalDataCategory.Session in clearableCategories) {
            SessionStore(appContext).clear()
        }
        if (LocalDataCategory.WebView in clearableCategories) {
            clearWebViewData()
            appContext.dataDir.resolve("app_webview").deleteRecursively()
        }
        if (LocalDataCategory.Cache in clearableCategories) {
            appContext.cacheDir.deleteContents()
        }
        if (LocalDataCategory.NoBackupWebView in clearableCategories) {
            appContext.noBackupFilesDir.resolve(".webview").deleteRecursively()
        }

        val after = buildStorageDiagnostics()
        LocalDataCleanupResult(
            removedBytes = (before - after.totalBytes).coerceAtLeast(0L),
            storageAfterCleanup = after,
        )
    }

    suspend fun buildErrorReport(context: ErrorDiagnosticContext): String = withContext(Dispatchers.IO) {
        val storage = buildStorageDiagnostics()
        buildString {
            appendLine("CLHS Pocket 診斷包")
            appendLine("產生時間: ${storage.generatedAt}")
            appendLine("App ID: ${BuildConfig.APPLICATION_ID}")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("裝置: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("登入狀態: ${if (context.isLoggedIn) "已登入" else "未登入"}")
            appendLine("登入錯誤: ${context.loginErrorMessage.toDiagnosticLine()}")
            appendLine("成績錯誤: ${context.gradesErrorMessage.toDiagnosticLine()}")
            appendLine()
            appendLine("儲存空間")
            storage.entries.forEach { entry ->
                appendLine("- ${entry.label}: ${entry.bytes.toReadableSize()}")
            }
            appendLine("- 合計: ${storage.totalBytes.toReadableSize()}")
            appendLine()
            appendLine("近期診斷事件")
            val events = recentEvents(appContext)
            if (events.isEmpty()) {
                appendLine("- 無")
            } else {
                events.forEach { event ->
                    appendLine("- ${event.timestamp} [${event.area}] ${event.message.sanitizeDiagnosticText()}")
                }
            }
        }
    }

    private fun buildStorageDiagnostics(): StorageDiagnostics {
        val dataDir = appContext.dataDir
        val entries = listOf(
            StorageEntry(
                LocalDataCategory.GradeCache,
                dataDir.resolve("files/datastore/grade_cache.preferences_pb").safeSize(),
            ),
            StorageEntry(
                LocalDataCategory.Settings,
                dataDir.resolve("files/datastore/app_settings.preferences_pb").safeSize(),
            ),
            StorageEntry(
                LocalDataCategory.Session,
                dataDir.resolve("files/datastore/session_storage.pb").safeSize() +
                    dataDir.resolve("shared_prefs/score_biometric_session.xml").safeSize() +
                    dataDir.resolve("shared_prefs/score_session.xml").safeSize(),
            ),
            StorageEntry(LocalDataCategory.WebView, dataDir.resolve("app_webview").safeSize()),
            StorageEntry(LocalDataCategory.Cache, appContext.cacheDir.safeSize()),
            StorageEntry(
                LocalDataCategory.NoBackupWebView,
                appContext.noBackupFilesDir.resolve(".webview").safeSize(),
            ),
        )
        return StorageDiagnostics(
            generatedAt = Instant.now().toString(),
            entries = entries,
        )
    }

    private fun clearWebViewData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    private fun File.deleteContents() {
        listFiles()?.forEach { child -> child.deleteRecursively() }
    }

    private fun File.safeSize(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { file -> runCatching { file.length() }.getOrDefault(0L) }
    }

    companion object {
        private const val DIAGNOSTICS_PREFS = "developer_diagnostics"
        private const val EVENTS_KEY = "recent_events"
        private const val MAX_EVENTS = 20
        private const val SEPARATOR = "\u001F"

        fun recordEvent(context: Context, area: String, message: String) {
            val appContext = context.applicationContext
            val nextEvent = DiagnosticEvent(
                timestamp = Instant.now().toString(),
                area = area.sanitizeDiagnosticText(),
                message = message.sanitizeDiagnosticText(),
            )
            val prefs = appContext.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            val updated = (recentEvents(appContext) + nextEvent).takeLast(MAX_EVENTS)
            prefs.edit {
                putString(EVENTS_KEY, updated.joinToString("\n") { it.serialize() })
            }
        }

        fun recentEvents(context: Context): List<DiagnosticEvent> {
            val prefs = context.applicationContext.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            return prefs.getString(EVENTS_KEY, null)
                ?.lineSequence()
                ?.mapNotNull { it.deserializeEvent() }
                ?.toList()
                .orEmpty()
        }

        private fun DiagnosticEvent.serialize(): String =
            listOf(timestamp, area, message).joinToString(SEPARATOR)

        private fun String.deserializeEvent(): DiagnosticEvent? {
            val parts = split(SEPARATOR)
            if (parts.size != 3) return null
            return DiagnosticEvent(parts[0], parts[1], parts[2])
        }

    }
}

fun defaultSelectedLocalDataCategories(): Set<LocalDataCategory> = emptySet()

internal fun String?.toDiagnosticLine(): String =
    this?.sanitizeDiagnosticText()
        ?.takeIf { it.isNotBlank() }
        ?: "無"

internal fun String.sanitizeDiagnosticText(): String {
    val singleLine = replace("\n", " ")
        .replace("\r", " ")
        .replace("\u001F", " ")
        .trim()
    return singleLine
        .replace(SensitiveUrlRegex, "[url]")
        .replace(SensitiveAssignmentRegex) { match ->
            val key = match.groups["key"]?.value ?: "secret"
            "$key=[redacted]"
        }
        .replace(SensitiveHeaderRegex) { match ->
            val key = match.groups["key"]?.value ?: "secret"
            "$key: [redacted]"
        }
        .replace(StudentLikeNumberRegex, "[number]")
        .take(MaxDiagnosticTextLength)
}

private const val MaxDiagnosticTextLength = 240
private val SensitiveUrlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val SensitiveAssignmentRegex = Regex(
    pattern = """(?<key>[A-Za-z0-9_.-]*(?:cookie|session|token|password|passwd|pwd|authorization|student[_-]?no)[A-Za-z0-9_.-]*)\s*=\s*[^\s;]+""",
    option = RegexOption.IGNORE_CASE,
)
private val SensitiveHeaderRegex = Regex(
    pattern = """(?<key>cookie|set-cookie|authorization|api[_-]?token|student[_-]?no)\s*:\s*[^\s;]+""",
    option = RegexOption.IGNORE_CASE,
)
private val StudentLikeNumberRegex = Regex("""\b\d{5,}\b""")

fun Long.toReadableSize(): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        "%.2f %s".format(value, units[unitIndex])
    }
}
