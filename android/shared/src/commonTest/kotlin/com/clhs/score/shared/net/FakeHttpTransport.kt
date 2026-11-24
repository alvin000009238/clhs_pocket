package com.clhs.score.shared.net

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** Scripted transport: answers from [handler], or parks the call until the test releases it. */
internal class FakeHttpTransport(
    private val handler: (HttpRequest) -> HttpResponse = { error("Unexpected request ${it.url}") },
) : HttpTransport {
    class Pending(val request: HttpRequest, private val callback: HttpCallback) {
        var cancelled = false
        var finished = false
        fun respond(response: HttpResponse) {
            finished = true
            callback.onResponse(response)
        }

        fun fail(kind: HttpFailureKind) {
            finished = true
            callback.onFailure(HttpFailure(kind, kind.name))
        }
    }

    val requests = ArrayList<HttpRequest>()
    val pending = ArrayList<Pending>()

    /** When true, calls are parked in [pending] instead of being answered. */
    var hold = false
    var maxInFlight = 0
        private set

    override fun start(request: HttpRequest, callback: HttpCallback): HttpCall {
        requests.add(request)
        if (hold) {
            val call = Pending(request, callback)
            pending.add(call)
            maxInFlight = maxOf(maxInFlight, pending.count { !it.cancelled && !it.finished })
            return object : HttpCall {
                override fun cancel() {
                    call.cancelled = true
                }
            }
        }
        try {
            callback.onResponse(handler(request))
        } catch (error: HttpTransportException) {
            callback.onFailure(HttpFailure(error.kind, error.message.orEmpty()))
        }
        return object : HttpCall {
            override fun cancel() = Unit
        }
    }

    fun path(index: Int): String = requireNotNull(PortableUrl.parse(requests[index].url)).encodedPath
}

internal fun HttpRequest.header(name: String): String? =
    headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

internal fun response(
    body: String = "",
    status: Int = 200,
    headers: List<HttpHeader> = emptyList(),
    setCookies: List<HttpSetCookie> = emptyList(),
) = HttpResponse(status, headers, setCookies, body)

internal fun cookie(name: String, value: String, expiresAt: Long? = null) =
    HttpSetCookie(name, value, domain = null, path = "/", expiresAtEpochMillis = expiresAt, secure = true)

/** Client whose work runs on the test scheduler, so parked calls can be observed deterministically. */
internal fun TestScope.schoolClient(transport: HttpTransport, now: () -> Long = { 1_000L }): PortableSchoolClient =
    PortableSchoolClient(transport, SCHOOL_BASE_URL, now, StandardTestDispatcher(testScheduler))
