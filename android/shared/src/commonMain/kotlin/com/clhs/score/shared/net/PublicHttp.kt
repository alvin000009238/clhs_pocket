package com.clhs.score.shared.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Failure of a public (unauthenticated) data source. [statusCode] is set for HTTP errors. */
class PublicDataException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * HTTP for public data. It has no cookie jar and no access to the school session by construction:
 * the only headers it sends are the ones a caller passes, and `Set-Cookie` responses are discarded.
 */
internal class PublicHttp(private val transport: HttpTransport) {
    suspend fun get(
        url: PortableUrl,
        label: String,
        maxBytes: Long,
        headers: List<HttpHeader> = emptyList(),
        maxRedirects: Int = 0,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): String = request("GET", url, null, label, maxBytes, headers, maxRedirects, timeoutMillis)

    suspend fun postForm(
        url: PortableUrl,
        form: List<Pair<String, String>>,
        label: String,
        maxBytes: Long,
    ): String = request(
        "POST", url, PortableUrl.formEncode(form), label, maxBytes,
        listOf(HttpHeader("Content-Type", "application/x-www-form-urlencoded")), 0, DEFAULT_TIMEOUT_MILLIS,
    )

    private suspend fun request(
        method: String,
        url: PortableUrl,
        body: String?,
        label: String,
        maxBytes: Long,
        headers: List<HttpHeader>,
        maxRedirects: Int,
        timeoutMillis: Long,
    ): String = withContext(Dispatchers.Default) {
        var current = url
        var redirects = 0
        while (true) {
            val response = try {
                transport.execute(HttpRequest(method, current.toString(), headers, body, timeoutMillis, maxBytes))
            } catch (error: HttpTransportException) {
                throw when (error.kind) {
                    HttpFailureKind.CANCELLED -> CancellationException("Request cancelled")
                    HttpFailureKind.TOO_LARGE -> PublicDataException("$label response is too large", cause = error)
                    else -> PublicDataException("$label request failed", cause = error)
                }
            }
            val location = response.header("Location")
            if (response.statusCode in 300..399 && location != null && redirects < maxRedirects && method == "GET") {
                val next = current.resolve(location)
                // Credentials never travel on a public request, so only the scheme needs guarding.
                if (next == null || !next.isHttps) throw PublicDataException("$label redirect is not allowed")
                current = next
                redirects++
                continue
            }
            if (!response.isSuccessful) {
                throw PublicDataException("$label request failed with HTTP ${response.statusCode}", response.statusCode)
            }
            return@withContext response.body
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}
