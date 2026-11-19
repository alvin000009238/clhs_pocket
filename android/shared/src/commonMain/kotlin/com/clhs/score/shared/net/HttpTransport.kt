package com.clhs.score.shared.net

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpHeader(val name: String, val value: String)

/**
 * One wire request. The transport sends exactly this: it must not add cookies, credentials or
 * cached responses of its own, and must not follow redirects.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<HttpHeader>,
    /** UTF-8 request body, or null. */
    val body: String?,
    val timeoutMillis: Long,
    /** The transport fails with [HttpFailureKind.TOO_LARGE] instead of buffering more than this. */
    val maxResponseBytes: Long,
)

/** A `Set-Cookie` header already split and parsed by the platform. */
class HttpSetCookie(
    val name: String,
    val value: String,
    /** Null when the header had no `Domain` attribute (host-only cookie). */
    val domain: String?,
    val path: String?,
    /** Null for a session cookie. */
    val expiresAtEpochMillis: Long?,
    val secure: Boolean,
)

class HttpResponse(
    val statusCode: Int,
    val headers: List<HttpHeader>,
    val setCookies: List<HttpSetCookie>,
    /** Response body decoded as UTF-8. */
    val body: String,
) {
    fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    val isSuccessful: Boolean get() = statusCode in 200..299
}

enum class HttpFailureKind { CONNECTION, TIMEOUT, TOO_LARGE, CANCELLED, INVALID_REQUEST }

class HttpFailure(val kind: HttpFailureKind, val message: String)

class HttpTransportException(val kind: HttpFailureKind, message: String) : Exception(message)

interface HttpCall {
    fun cancel()
}

/** Exactly one of [onResponse] / [onFailure] must be called, once, from any thread. */
interface HttpCallback {
    fun onResponse(response: HttpResponse)
    fun onFailure(failure: HttpFailure)
}

/**
 * Platform HTTP boundary. iOS implements it with an ephemeral `URLSession`; tests use a scripted fake.
 * Cookie handling, redirects, concurrency limits and response validation stay in shared code.
 */
interface HttpTransport {
    fun start(request: HttpRequest, callback: HttpCallback): HttpCall
}

internal suspend fun HttpTransport.execute(request: HttpRequest): HttpResponse =
    suspendCancellableCoroutine { continuation: CancellableContinuation<HttpResponse> ->
        val call = start(
            request,
            object : HttpCallback {
                override fun onResponse(response: HttpResponse) {
                    if (continuation.isActive) continuation.resume(response)
                }

                override fun onFailure(failure: HttpFailure) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(HttpTransportException(failure.kind, failure.message))
                    }
                }
            },
        )
        continuation.invokeOnCancellation { call.cancel() }
    }
