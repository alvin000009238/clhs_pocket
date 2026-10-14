package com.clhs.score.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class SchoolCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    private var generation = 0L

    internal fun forCurrentGeneration(): CookieJar = synchronized(this) {
        val expectedGeneration = generation
        object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(this@SchoolCookieJar) {
                    if (generation == expectedGeneration) this@SchoolCookieJar.saveFromResponse(url, cookies)
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this@SchoolCookieJar) {
                if (generation == expectedGeneration) this@SchoolCookieJar.loadForRequest(url) else emptyList()
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            cookies.forEach { newCookie ->
                this.cookies.removeAll { it.hasSameIdentity(newCookie) }
                if (newCookie.expiresAt > now) {
                    this.cookies.add(newCookie)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(this) {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt <= now }
            return cookies.filter { it.matches(url) }
        }
    }

    fun snapshot(): Map<String, String> = synchronized(this) {
        cookies
            .filter { it.expiresAt > System.currentTimeMillis() }
            .associate { it.name to it.value }
    }

    fun replace(cookieValues: Map<String, String>, domain: String = "shcloud2.k12ea.gov.tw") {
        synchronized(this) {
            generation++
            cookies.clear()
            cookieValues.forEach { (name, value) ->
                if (name.isNotBlank()) {
                    cookies.add(
                        Cookie.Builder()
                            .hostOnlyDomain(domain)
                            .path("/")
                            .name(name)
                            .value(value)
                            .build(),
                    )
                }
            }
        }
    }

    fun clear() {
        synchronized(this) {
            generation++
            cookies.clear()
        }
    }

    private fun Cookie.hasSameIdentity(other: Cookie): Boolean =
        name == other.name && domain == other.domain && path == other.path
}
