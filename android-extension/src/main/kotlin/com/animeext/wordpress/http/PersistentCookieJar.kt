package com.animeext.wordpress.http

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

interface CookieStorage {
    fun loadAll(): List<StoredCookie>
    fun saveAll(cookies: List<StoredCookie>)
}

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean
) {
    fun toOkHttpCookie(): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
        if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }

    companion object {
        fun fromOkHttpCookie(cookie: Cookie): StoredCookie = StoredCookie(
            name = cookie.name,
            value = cookie.value,
            expiresAt = cookie.expiresAt,
            domain = cookie.domain,
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly
        )
    }
}

class InMemoryCookieStorage : CookieStorage {
    private var snapshot: List<StoredCookie> = emptyList()
    override fun loadAll(): List<StoredCookie> = snapshot
    override fun saveAll(cookies: List<StoredCookie>) {
        snapshot = cookies
    }
}

class SharedPrefsCookieStorage(
    private val prefs: SharedPreferences,
    private val key: String = "persisted_cookies_v1"
) : CookieStorage {
    private val json = Json { ignoreUnknownKeys = true }

    constructor(context: Context) : this(
        context.getSharedPreferences("anime_ext_cookies", Context.MODE_PRIVATE)
    )

    override fun loadAll(): List<StoredCookie> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredCookie>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun saveAll(cookies: List<StoredCookie>) {
        prefs.edit().putString(key, json.encodeToString(cookies)).apply()
    }
}

/**
 * Persistent OkHttp CookieJar with Android WebView CookieManager synchronization.
 */
class PersistentCookieJar(
    private val storage: CookieStorage = InMemoryCookieStorage()
) : CookieJar {
    private val store = ConcurrentHashMap<String, Cookie>()

    init {
        val now = System.currentTimeMillis()
        storage.loadAll()
            .filter { it.expiresAt > now }
            .forEach { stored ->
                val cookie = stored.toOkHttpCookie()
                store[cookieKey(cookie)] = cookie
            }
    }

    private fun cookieKey(cookie: Cookie): String =
        "${cookie.domain}|${cookie.path}|${cookie.name}"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            val key = cookieKey(cookie)
            if (cookie.expiresAt <= now) {
                store.remove(key)
            } else {
                store[key] = cookie
            }
        }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matched = mutableListOf<Cookie>()
        val expiredKeys = mutableListOf<String>()

        for ((key, cookie) in store) {
            if (cookie.expiresAt <= now) {
                expiredKeys.add(key)
            } else if (cookie.matches(url)) {
                matched.add(cookie)
            }
        }

        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { store.remove(it) }
            persist()
        }

        return matched.sortedByDescending { it.path.length }
    }

    fun getCookieHeader(url: HttpUrl): String =
        loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }

    fun syncToWebView(url: HttpUrl) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        for (cookie in loadForRequest(url)) {
            cookieManager.setCookie(url.toString(), cookie.toString())
        }
        cookieManager.flush()
    }

    fun syncFromWebView(url: HttpUrl) {
        val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return
        val parsed = raw.split(";")
            .mapNotNull { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .hostOnlyDomain(url.host)
                    .path("/")
                    .build()
            }
        if (parsed.isNotEmpty()) {
            saveFromResponse(url, parsed)
        }
    }

    fun clear() {
        store.clear()
        persist()
    }

    private fun persist() {
        storage.saveAll(store.values.map { StoredCookie.fromOkHttpCookie(it) })
    }
}
