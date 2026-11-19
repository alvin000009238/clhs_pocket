package com.clhs.score.shared.net

/**
 * Small tolerant HTML tokenizer for the few school pages the app reads (hidden form tokens, `<select>`
 * options, announcement article fragments). It is not a general DOM: callers scan the token stream.
 */
internal sealed interface HtmlToken {
    class Text(val text: String) : HtmlToken
    class Start(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : HtmlToken
    class End(val name: String) : HtmlToken
}

internal val VOID_HTML_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
)
private val RAW_TEXT_TAGS = setOf("script", "style")

internal fun tokenizeHtml(source: String): List<HtmlToken> {
    val tokens = ArrayList<HtmlToken>()
    val text = StringBuilder()
    var index = 0
    fun flushText() {
        if (text.isNotEmpty()) {
            tokens.add(HtmlToken.Text(decodeHtmlEntities(text.toString())))
            text.clear()
        }
    }
    while (index < source.length) {
        val char = source[index]
        if (char != '<' || index + 1 >= source.length) {
            text.append(char)
            index++
            continue
        }
        val next = source[index + 1]
        when {
            source.startsWith("<!--", index) -> {
                flushText()
                val end = source.indexOf("-->", index + 4)
                index = if (end < 0) source.length else end + 3
            }
            next == '!' || next == '?' -> {
                flushText()
                val end = source.indexOf('>', index)
                index = if (end < 0) source.length else end + 1
            }
            next == '/' && index + 2 < source.length && source[index + 2].isLetter() -> {
                flushText()
                val end = source.indexOf('>', index).let { if (it < 0) source.length else it }
                val name = source.substring(index + 2, end).trim().takeWhile { !it.isWhitespace() }.lowercase()
                tokens.add(HtmlToken.End(name))
                index = minOf(end + 1, source.length)
            }
            next.isLetter() -> {
                flushText()
                var cursor = index + 1
                while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] != '>' && source[cursor] != '/') cursor++
                val name = source.substring(index + 1, cursor).lowercase()
                val attributes = LinkedHashMap<String, String>()
                var selfClosing = false
                while (cursor < source.length && source[cursor] != '>') {
                    val current = source[cursor]
                    if (current.isWhitespace()) {
                        cursor++
                        continue
                    }
                    if (current == '/') {
                        selfClosing = true
                        cursor++
                        continue
                    }
                    selfClosing = false
                    val nameStart = cursor
                    while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] !in "=>/") cursor++
                    val attributeName = source.substring(nameStart, cursor).lowercase()
                    while (cursor < source.length && source[cursor].isWhitespace()) cursor++
                    var value = ""
                    if (cursor < source.length && source[cursor] == '=') {
                        cursor++
                        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
                        if (cursor < source.length && (source[cursor] == '"' || source[cursor] == '\'')) {
                            val quote = source[cursor]
                            val end = source.indexOf(quote, cursor + 1).let { if (it < 0) source.length else it }
                            value = source.substring(cursor + 1, end)
                            cursor = minOf(end + 1, source.length)
                        } else {
                            val valueStart = cursor
                            while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] != '>') cursor++
                            value = source.substring(valueStart, cursor)
                        }
                    }
                    if (attributeName.isNotEmpty() && attributeName !in attributes) {
                        attributes[attributeName] = decodeHtmlEntities(value)
                    } else if (attributeName.isEmpty()) {
                        cursor++
                    }
                }
                index = minOf(cursor + 1, source.length)
                tokens.add(HtmlToken.Start(name, attributes, selfClosing))
                if (name in RAW_TEXT_TAGS && !selfClosing) {
                    val close = source.indexOf("</$name", index, ignoreCase = true)
                    val rawEnd = if (close < 0) source.length else close
                    if (rawEnd > index) tokens.add(HtmlToken.Text(source.substring(index, rawEnd)))
                    index = rawEnd
                }
            }
            else -> {
                text.append(char)
                index++
            }
        }
    }
    flushText()
    return tokens
}

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
)
private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z]{2,8});")

internal fun decodeHtmlEntities(value: String): String {
    if ('&' !in value) return value
    return ENTITY.replace(value) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePointText(body.substring(2).toIntOrNull(16))
            body.startsWith("#") -> codePointText(body.substring(1).toIntOrNull())
            else -> NAMED_ENTITIES[body]
        } ?: match.value
    }
}

private fun codePointText(codePoint: Int?): String? = when {
    codePoint == null || codePoint <= 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF -> null
    codePoint < 0x10000 -> codePoint.toChar().toString()
    else -> {
        val offset = codePoint - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
}

internal fun escapeHtmlText(value: String): String = buildString(value.length) {
    for (char in value) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\u00A0' -> append("&nbsp;")
            else -> append(char)
        }
    }
}

internal fun escapeHtmlAttribute(value: String): String = escapeHtmlText(value).replace("\"", "&quot;")

/** Value of the first element whose `name` attribute is [name]: its `value`, else its text. */
internal fun findNamedInputValue(html: String, name: String): String? {
    val tokens = tokenizeHtml(html)
    val index = tokens.indexOfFirst { it is HtmlToken.Start && it.attributes["name"] == name }
    if (index < 0) return null
    val start = tokens[index] as HtmlToken.Start
    val value = start.attributes["value"] ?: (tokens.getOrNull(index + 1) as? HtmlToken.Text)?.text
    return value?.trim()?.takeIf { it.isNotBlank() }
}

/** `(value, text)` of every `<option value>` inside a `<select>` carrying [selectClass]. */
internal fun selectOptions(html: String, selectClass: String): List<Pair<String, String>> {
    val result = ArrayList<Pair<String, String>>()
    var inside = false
    var pendingValue: String? = null
    val pendingText = StringBuilder()
    fun flush() {
        pendingValue?.let { result.add(it to pendingText.toString().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")) }
        pendingValue = null
        pendingText.clear()
    }
    for (token in tokenizeHtml(html)) {
        when (token) {
            is HtmlToken.Start -> when (token.name) {
                "select" -> {
                    flush()
                    inside = token.attributes["class"].orEmpty().split(Regex("\\s+")).contains(selectClass)
                }
                "option" -> {
                    flush()
                    if (inside) pendingValue = token.attributes["value"]
                }
            }
            is HtmlToken.End -> if (token.name == "option" || token.name == "select") {
                flush()
                if (token.name == "select") inside = false
            }
            is HtmlToken.Text -> if (pendingValue != null) pendingText.append(token.text)
        }
    }
    flush()
    return result
}
