package com.clhs.score.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class UpdateResult {
    data object UpToDate : UpdateResult()
    data class NewVersion(
        val versionName: String,
        val htmlUrl: String,
        val apkAsset: ApkAsset?,
        val releaseNotes: String,
    ) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

data class ApkAsset(
    val downloadUrl: String,
    val sha256: String,
)

internal fun isNewerVersion(remote: String, current: String): Boolean? {
    val remoteParts = remote.versionPartsOrNull() ?: return null
    val currentParts = current.substringBefore('-').versionPartsOrNull() ?: return null
    val maxLen = maxOf(remoteParts.size, currentParts.size)
    for (i in 0 until maxLen) {
        val r = remoteParts.getOrElse(i) { 0 }
        val c = currentParts.getOrElse(i) { 0 }
        if (r > c) return true
        if (r < c) return false
    }
    return false
}

private val VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+)*")

private fun String.versionPartsOrNull(): List<Int>? {
    if (!VERSION_PATTERN.matches(this)) return null
    return split('.').map { part -> part.toIntOrNull() ?: return null }
}

class UpdateChecker(
    private val client: OkHttpClient = defaultClient,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) {

    suspend fun check(currentVersionName: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(latestReleaseUrl)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateResult.Error("HTTP ${response.code}")
                }
                val body = response.body.string()
                val json = SchoolJson.parseToJsonElement(body).jsonArray
                    .firstOrNull { release ->
                        val item = release.jsonObject
                        item["draft"]?.jsonPrimitive?.booleanOrNull != true &&
                            item["prerelease"]?.jsonPrimitive?.booleanOrNull != true
                    }
                    ?.jsonObject
                    ?: return@withContext UpdateResult.Error("找不到穩定版本")
                val tagName = json["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val remoteVersion = tagName.removePrefix("v")
                val htmlUrl = json["html_url"]?.jsonPrimitive?.content.orEmpty().takeIfValidHttpsUrl()
                val releaseBody = json["body"]?.jsonPrimitive?.content.orEmpty()
                val apkAsset = json["assets"]?.jsonArray
                    ?.firstOrNull { asset ->
                        asset.jsonObject["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true
                    }?.jsonObject
                    ?.let { asset ->
                        val downloadUrl = asset["browser_download_url"]
                            ?.jsonPrimitive
                            ?.content
                            ?.takeIfValidHttpsUrl()
                        val sha256 = asset["digest"]
                            ?.jsonPrimitive
                            ?.content
                            ?.takeIfValidSha256()
                        if (downloadUrl != null && sha256 != null) {
                            ApkAsset(downloadUrl, sha256)
                        } else {
                            null
                        }
                    }

                val isNewer = isNewerVersion(remoteVersion, currentVersionName)
                    ?: return@withContext UpdateResult.Error("版本格式不正確")
                if (!isNewer) {
                    return@withContext UpdateResult.UpToDate
                }
                if (htmlUrl == null && apkAsset == null) {
                    return@withContext UpdateResult.Error("更新連結格式不正確")
                }
                UpdateResult.NewVersion(
                    versionName = remoteVersion,
                    htmlUrl = htmlUrl.orEmpty(),
                    apkAsset = apkAsset,
                    releaseNotes = releaseBody,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateResult.Error("暫時無法檢查更新")
        }
    }

    private fun String.takeIfValidHttpsUrl(): String? =
        takeIf { value -> value.toHttpUrlOrNull()?.isHttps == true }

    private fun String.takeIfValidSha256(): String? =
        takeIf { value ->
            value.length == SHA256_PREFIX.length + SHA256_HEX_LENGTH &&
                value.startsWith(SHA256_PREFIX) &&
                value.drop(SHA256_PREFIX.length).all { char ->
                    char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
                }
        }?.removePrefix(SHA256_PREFIX)?.lowercase()

    private companion object {
        const val SHA256_PREFIX = "sha256:"
        const val SHA256_HEX_LENGTH = 64
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/alvin000009238/clhs_pocket/releases?per_page=20"

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
