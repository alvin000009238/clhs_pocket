@file:OptIn(ExperimentalAtomicApi::class)

package com.clhs.score.shared.net

import com.clhs.score.data.AuthenticatedSession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val expiresAtEpochMillis: Long?,
    val secure: Boolean,
) {
    fun isExpired(now: Long): Boolean = expiresAtEpochMillis != null && expiresAtEpochMillis <= now

    fun matches(url: PortableUrl): Boolean {
        val hostMatches = url.host == domain || (!hostOnly && url.host.endsWith(".$domain"))
        if (!hostMatches || (secure && !url.isHttps)) return false
        val requestPath = url.encodedPath
        return requestPath == path ||
            (requestPath.startsWith(path) && (path.endsWith("/") || requestPath[path.length] == '/'))
    }

    fun sameIdentity(other: StoredCookie): Boolean =
        name == other.name && domain == other.domain && path == other.path
}

/** One immutable authorization snapshot; every change produces a new generation. */
internal data class SchoolState(
    val generation: Long = 0L,
    val session: AuthenticatedSession? = null,
    val scheduleToken: String? = null,
    val cookies: List<StoredCookie> = emptyList(),
)

/**
 * Session, cookies and derived tokens of the school client behind one atomic reference.
 *
 * Android guards the same data with `sessionLock` and a synchronized cookie jar whose generation view
 * rejects old requests. Here every read and write names the generation it belongs to, so a request
 * chain that started under an earlier authorization can neither read the new cookies nor store its own.
 */
internal class SchoolSessionState {
    private val reference = AtomicReference(SchoolState())

    val current: SchoolState get() = reference.load()

    private inline fun update(transform: (SchoolState) -> SchoolState): SchoolState {
        while (true) {
            val previous = reference.load()
            val next = transform(previous)
            if (next === previous || reference.compareAndSet(previous, next)) return next
        }
    }

    /** Installs [session] with exactly [cookies] for [host] and starts a new generation. */
    fun replace(session: AuthenticatedSession?, cookies: Map<String, String>, host: String): Long = update { previous ->
        SchoolState(
            generation = previous.generation + 1,
            session = session,
            cookies = cookies.filterKeys(String::isNotBlank).map { (name, value) ->
                StoredCookie(name, value, host, hostOnly = true, path = "/", expiresAtEpochMillis = null, secure = false)
            },
        )
    }.generation

    /** Same as Android `prepareSession`: only a different session starts a new generation. */
    fun prepare(session: AuthenticatedSession, host: String): Long {
        val state = current
        if (state.session == session) return state.generation
        return update { previous ->
            if (previous.session == session) previous
            else SchoolState(
                generation = previous.generation + 1,
                session = session,
                cookies = session.cookies.filterKeys(String::isNotBlank).map { (name, value) ->
                    StoredCookie(name, value, host, hostOnly = true, path = "/", expiresAtEpochMillis = null, secure = false)
                },
            )
        }.generation
    }

    fun clear(): Long = update { SchoolState(generation = it.generation + 1) }.generation

    fun isCurrent(generation: Long): Boolean = current.generation == generation

    /** Returns false when [generation] is no longer current; nothing is stored then. */
    fun setSession(generation: Long, session: AuthenticatedSession): Boolean =
        update { if (it.generation == generation) it.copy(session = session) else it }.let {
            it.generation == generation && it.session == session
        }

    fun scheduleToken(generation: Long): String? = current.takeIf { it.generation == generation }?.scheduleToken

    fun setScheduleToken(generation: Long, token: String): Boolean =
        update { if (it.generation == generation) it.copy(scheduleToken = token) else it }.generation == generation

    fun cookiesFor(generation: Long, url: PortableUrl, now: Long): List<StoredCookie> {
        val state = current
        if (state.generation != generation) return emptyList()
        return state.cookies.filter { !it.isExpired(now) && it.matches(url) }
    }

    fun saveFromResponse(generation: Long, url: PortableUrl, received: List<HttpSetCookie>, now: Long) {
        if (received.isEmpty()) return
        val parsed = received.mapNotNull { it.toStoredCookie(url) }
        update { previous ->
            if (previous.generation != generation) return@update previous
            var cookies = previous.cookies
            for (cookie in parsed) {
                cookies = cookies.filterNot { it.sameIdentity(cookie) }
                if (!cookie.isExpired(now)) cookies = cookies + cookie
            }
            previous.copy(cookies = cookies)
        }
    }

    fun cookieSnapshot(now: Long): Map<String, String> =
        current.cookies.filter { !it.isExpired(now) }.associate { it.name to it.value }
}

private fun HttpSetCookie.toStoredCookie(url: PortableUrl): StoredCookie? {
    if (name.isBlank()) return null
    val attributeDomain = domain?.trim()?.trimStart('.')?.lowercase()?.takeIf(String::isNotEmpty)
    // A cookie may only widen to a parent domain of the host that set it.
    if (attributeDomain != null && url.host != attributeDomain && !url.host.endsWith(".$attributeDomain")) return null
    if (attributeDomain != null && '.' !in attributeDomain) return null
    val cookiePath = path?.takeIf { it.startsWith("/") }
        ?: url.encodedPath.substringBeforeLast('/').ifEmpty { "/" }
    return StoredCookie(
        name = name,
        value = value,
        domain = attributeDomain ?: url.host,
        hostOnly = attributeDomain == null,
        path = cookiePath,
        expiresAtEpochMillis = expiresAtEpochMillis,
        secure = secure,
    )
}
