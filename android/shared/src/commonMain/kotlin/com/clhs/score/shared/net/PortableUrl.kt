package com.clhs.score.shared.net

/** Minimal absolute http(s) URL: enough to resolve redirects, match cookies and build requests. */
class PortableUrl private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    /** Encoded path, always starting with `/`. */
    val encodedPath: String,
    /** Encoded query without `?`, or null. */
    val encodedQuery: String?,
    val hasUserInfo: Boolean,
) {
    val isHttps: Boolean get() = scheme == "https"
    val defaultPort: Int get() = if (isHttps) 443 else 80
    val origin: String get() = "$scheme://$host" + if (port == defaultPort) "" else ":$port"

    override fun toString(): String = origin + encodedPath + (encodedQuery?.let { "?$it" } ?: "")

    fun resolve(reference: String): PortableUrl? {
        val value = reference.trim().substringBefore('#')
        if (value.isEmpty()) return this
        SCHEME.matchAt(value, 0)?.let { return parse(value) }
        if (value.startsWith("//")) return parse("$scheme:$value")
        val pathPart = value.substringBefore('?')
        val query = if ('?' in value) value.substringAfter('?') else null
        val path = when {
            pathPart.isEmpty() -> encodedPath
            pathPart.startsWith("/") -> pathPart
            else -> encodedPath.substringBeforeLast('/') + "/" + pathPart
        }
        return PortableUrl(
            scheme, host, port, normalizePath(path),
            if (pathPart.isEmpty() && query == null) encodedQuery else query?.let(::encodeUnsafe), hasUserInfo = false,
        )
    }

    fun withPath(path: String, query: List<Pair<String, String>> = emptyList()): PortableUrl = PortableUrl(
        scheme, host, port,
        normalizePath(encodedPath.trimEnd('/') + "/" + path.trimStart('/').split('/').joinToString("/") { encodeComponent(it) }),
        query.takeIf { it.isNotEmpty() }?.joinToString("&") { (name, value) -> "${encodeComponent(name)}=${encodeComponent(value)}" },
        hasUserInfo = false,
    )

    /** Appends [segments] as literal path segments: `/`, `?`, `%` and dot segments inside them are escaped. */
    fun withPathSegments(vararg segments: String): PortableUrl = PortableUrl(
        scheme, host, port,
        encodedPath.trimEnd('/') + segments.joinToString("") { segment ->
            "/" + encodeComponent(segment).let { if (it == "." || it == "..") it.replace(".", "%2E") else it }
        },
        null,
        hasUserInfo = false,
    )

    companion object {
        private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:")

        fun parse(text: String): PortableUrl? {
            val value = text.trim().substringBefore('#')
            val scheme = value.substringBefore("://", "").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = value.substring(scheme.length + 3)
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            val hasUserInfo = '@' in authority
            val hostPort = authority.substringAfterLast('@')
            if (hostPort.isEmpty() || hostPort.startsWith("[")) return null
            val host = hostPort.substringBefore(':').lowercase()
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '\\' || it == '%' }) return null
            val port = if (':' in hostPort) {
                hostPort.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else if (scheme == "https") 443 else 80
            val tail = rest.substring(authorityEnd)
            val path = tail.substringBefore('?').ifEmpty { "/" }
            val query = if ('?' in tail) encodeUnsafe(tail.substringAfter('?')) else null
            return PortableUrl(scheme, host, port, normalizePath(path), query, hasUserInfo)
        }

        private fun normalizePath(path: String): String {
            val output = ArrayList<String>()
            val segments = path.split('/')
            for ((index, segment) in segments.withIndex()) {
                when (segment) {
                    "." -> if (index == segments.lastIndex) output.add("")
                    ".." -> {
                        if (output.size > 1) output.removeAt(output.lastIndex)
                        if (index == segments.lastIndex) output.add("")
                    }
                    else -> output.add(encodeUnsafe(segment))
                }
            }
            return output.joinToString("/").let { if (it.startsWith("/")) it else "/$it" }
        }

        /** Percent-encodes everything outside RFC 3986 unreserved characters, as UTF-8. */
        fun encodeComponent(value: String, spaceAsPlus: Boolean = false): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                when {
                    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~" -> append(char)
                    char == ' ' && spaceAsPlus -> append('+')
                    else -> appendPercent(code)
                }
            }
        }

        /** Leaves an already encoded path segment or query alone and escapes only characters that are illegal on the wire. */
        private fun encodeUnsafe(segment: String): String = buildString {
            for (byte in segment.encodeToByteArray()) {
                val code = byte.toInt() and 0xFF
                if (code <= 0x20 || code >= 0x7F || code.toChar() in "\"<>`{}^|\\") appendPercent(code) else append(code.toChar())
            }
        }

        private fun StringBuilder.appendPercent(code: Int) {
            append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
        }

        private const val HEX = "0123456789ABCDEF"

        /** `application/x-www-form-urlencoded` body in insertion order. */
        fun formEncode(fields: List<Pair<String, String>>): String = fields.joinToString("&") { (name, value) ->
            "${encodeComponent(name, spaceAsPlus = true)}=${encodeComponent(value, spaceAsPlus = true)}"
        }

        /** Strict percent-decoding as UTF-8; `+` is kept literally. Throws on malformed input. */
        fun percentDecode(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            var index = 0
            while (index < value.length) {
                if (value[index] == '%') {
                    require(index + 2 <= value.lastIndex) { "Incomplete escape" }
                    val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                        ?: throw IllegalArgumentException("Invalid escape")
                    bytes.add(code.toByte())
                    index += 3
                } else {
                    val end = value.indexOf('%', index).let { if (it < 0) value.length else it }
                    value.substring(index, end).encodeToByteArray().forEach(bytes::add)
                    index = end
                }
            }
            return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    }
}
