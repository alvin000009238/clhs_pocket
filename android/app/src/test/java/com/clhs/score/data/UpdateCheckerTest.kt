package com.clhs.score.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun newerReleaseReturnsValidatedDownloadUrl() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://github.com/alvin000009238/clhs_pocket/releases/tag/v1.2.0",
                  "body": "Bug fixes",
                  "assets": [
                    {
                      "name": "clhs-score.apk",
                      "digest": "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                      "browser_download_url": "https://github.com/alvin000009238/clhs_pocket/releases/download/v1.2.0/app.apk"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = checker().check("1.1.9")

        result as UpdateResult.NewVersion
        assertEquals("1.2.0", result.versionName)
        assertEquals("Bug fixes", result.releaseNotes)
        assertEquals(
            "https://github.com/alvin000009238/clhs_pocket/releases/download/v1.2.0/app.apk",
            result.apkAsset?.downloadUrl,
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            result.apkAsset?.sha256,
        )
    }

    @Test
    fun sameVersionReturnsUpToDate() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://github.com/alvin000009238/clhs_pocket/releases/tag/v1.2.0",
                  "body": "",
                  "assets": []
                }
                """.trimIndent(),
            ),
        )

        assertEquals(UpdateResult.UpToDate, checker().check("1.2.0"))
    }

    @Test
    fun debugVersionSuffixDoesNotBreakComparison() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v1.2.4",
                  "html_url": "https://github.com/alvin000009238/clhs_pocket/releases/tag/v1.2.4",
                  "body": "",
                  "assets": []
                }
                """.trimIndent(),
            ),
        )

        assertTrue(checker().check("1.0-debug") is UpdateResult.NewVersion)
    }

    @Test
    fun invalidReleaseLinksReturnErrorInsteadOfActionableUpdate() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "javascript:alert(1)",
                  "body": "",
                  "assets": [
                    {
                      "name": "clhs-score.apk",
                      "digest": "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                      "browser_download_url": "file:///tmp/app.apk"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = checker().check("1.1.9")

        result as UpdateResult.Error
        assertEquals("更新連結格式不正確", result.message)
    }

    @Test
    fun invalidApkUrlFallsBackToValidHtmlUrl() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://github.com/alvin000009238/clhs_pocket/releases/tag/v1.2.0",
                  "body": "",
                  "assets": [
                    {
                      "name": "clhs-score.apk",
                      "digest": "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                      "browser_download_url": "intent://download"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = checker().check("1.1.9")

        result as UpdateResult.NewVersion
        assertNull(result.apkAsset)
        assertTrue(result.htmlUrl.startsWith("https://github.com/"))
    }

    @Test
    fun cleartextReleaseLinksAreRejected() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "http://github.com/alvin000009238/clhs_pocket/releases/tag/v1.2.0",
                  "body": "",
                  "assets": [
                    {
                      "name": "clhs-score.apk",
                      "digest": "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                      "browser_download_url": "http://github.com/alvin000009238/clhs_pocket/releases/download/v1.2.0/app.apk"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = checker().check("1.1.9")

        result as UpdateResult.Error
        assertEquals("更新連結格式不正確", result.message)
    }

    @Test
    fun malformedReleaseVersionIsRejected() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v1.bad.3",
                  "html_url": "https://github.com/alvin000009238/clhs_pocket/releases/tag/v1.bad.3",
                  "body": "",
                  "assets": []
                }
                """.trimIndent(),
            ),
        )

        val result = checker().check("1.2.0")

        result as UpdateResult.Error
        assertEquals("版本格式不正確", result.message)
    }

    @Test
    fun missingMalformedOrUnsupportedDigestFallsBackToValidHtmlUrl() = runTest {
        val digestFields = listOf(
            "",
            "\"digest\": \"sha256:1234\",",
            "\"digest\": \"sha512:${"a".repeat(128)}\",",
        )

        digestFields.forEach { digestField ->
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "tag_name": "v1.2.0",
                      "html_url": "https://github.com/alvin000009238/clhs_pocket/releases/tag/v1.2.0",
                      "body": "",
                      "assets": [
                        {
                          "name": "clhs-score.apk",
                          $digestField
                          "browser_download_url": "https://github.com/alvin000009238/clhs_pocket/releases/download/v1.2.0/app.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            val result = checker().check("1.1.9")

            result as UpdateResult.NewVersion
            assertNull(result.apkAsset)
            assertTrue(result.htmlUrl.startsWith("https://github.com/"))
        }
    }

    @Test
    fun draftAndPrereleaseAreIgnored() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "tag_name": "v9.0.0",
                  "draft": true,
                  "prerelease": false,
                  "assets": []
                },
                {
                  "tag_name": "v8.0.0-beta",
                  "draft": false,
                  "prerelease": true,
                  "assets": []
                },
                {
                  "tag_name": "v1.2.0",
                  "draft": false,
                  "prerelease": false,
                  "html_url": "https://github.com/alvin000009238/clhs_pocket/releases/tag/v1.2.0",
                  "body": "Stable",
                  "assets": []
                }
                """.trimIndent(),
            ),
        )

        val result = checker().check("1.1.9")

        result as UpdateResult.NewVersion
        assertEquals("1.2.0", result.versionName)
    }

    private fun checker(): UpdateChecker =
        UpdateChecker(
            client = OkHttpClient(),
            latestReleaseUrl = server.url("/release/latest").toString(),
        )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("[$body]")
}
