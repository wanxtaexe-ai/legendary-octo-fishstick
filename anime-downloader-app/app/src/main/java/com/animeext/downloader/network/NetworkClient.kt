package com.animeext.downloader.network

import android.util.Log
import com.animeext.downloader.model.SiteCategory
import com.animeext.downloader.model.SiteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class InMemorySessionCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host.lowercase()
        val list = store.getOrPut(host) { mutableListOf() }
        synchronized(list) {
            val now = System.currentTimeMillis()
            for (c in cookies) {
                list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.expiresAt > now) {
                    list.add(c)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Cookie>()
        for ((_, list) in store) {
            synchronized(list) {
                val it = list.iterator()
                while (it.hasNext()) {
                    val c = it.next()
                    if (c.expiresAt <= now) {
                        it.remove()
                    } else if (c.matches(url)) {
                        out.add(c)
                    }
                }
            }
        }
        return out
    }

    fun seedCookies(url: HttpUrl, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val parsed = cookies.map { (k, v) ->
            Cookie.Builder()
                .name(k)
                .value(v)
                .domain(url.host)
                .path("/")
                .build()
        }
        saveFromResponse(url, parsed)
    }

    fun snapshotForUrl(url: String): Map<String, String> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyMap()
        return loadForRequest(httpUrl).associate { it.name to it.value }
    }

    fun clear() {
        store.clear()
    }
}

data class HttpResponseData(
    val code: Int,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
    val cookies: Map<String, String>
)

class NetworkClient {
    companion object {
        const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    val cookieJar = InMemorySessionCookieJar()

    @Volatile
    var dohEnabled: Boolean = true

    private val bootstrapClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cloudflareDoh: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1")
        )
        .includeIPv6(false)
        .build()

    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (dohEnabled) {
                runCatching { cloudflareDoh.lookup(hostname) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true
    ): HttpResponseData = withContext(Dispatchers.IO) {
        val client = if (followRedirects) {
            httpClient
        } else {
            httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val bodyStr = resp.body?.string().orEmpty()
            val headerMap = LinkedHashMap<String, String>()
            for (i in 0 until resp.headers.size) {
                headerMap[resp.headers.name(i).lowercase()] = resp.headers.value(i)
            }
            HttpResponseData(
                code = resp.code,
                url = finalUrl,
                body = bodyStr,
                headers = headerMap,
                cookies = cookieJar.snapshotForUrl(finalUrl)
            )
        }
    }

    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponseData = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
        for ((k, v) in fields) {
            form.add(k, v)
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .post(form.build())
            .header("User-Agent", CHROME_UA)
            .header("Accept", "application/json, text/html, */*; q=0.8")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val bodyStr = resp.body?.string().orEmpty()
            val headerMap = LinkedHashMap<String, String>()
            for (i in 0 until resp.headers.size) {
                headerMap[resp.headers.name(i).lowercase()] = resp.headers.value(i)
            }
            HttpResponseData(
                code = resp.code,
                url = finalUrl,
                body = bodyStr,
                headers = headerMap,
                cookies = cookieJar.snapshotForUrl(finalUrl)
            )
        }
    }
}

object DomainCatalog {
    const val REMOTE_DOMAINS_URL =
        "https://raw.githubusercontent.com/wanxtaexe-ai/legendary-octo-fishstick/main/domains.json"

    private val defaultProfiles = listOf(
        SiteProfile(
            id = "tpxsub",
            name = "TpXAnime",
            baseUrl = "https://www.tpxsub.com",
            theme = "wordpress",
            latestPath = "/",
            searchPath = "/?s={query}",
            pagePath = "/page/{page}/",
            categories = listOf(
                SiteCategory("Latest Updates", "/"),
                SiteCategory("Anime Series", "/category/anime/"),
                SiteCategory("Recent Page 2", "/page/2/"),
                SiteCategory("Archive Page 3", "/page/3/")
            )
        ),
        SiteProfile(
            id = "animevilla",
            name = "AnimeVilla",
            baseUrl = "https://animevilla.org",
            theme = "kiranime",
            latestPath = "/homepage/",
            searchPath = "/?s={query}",
            fallbackSearchPath = "/search/?keyword={query}",
            pagePath = "/anime/page/{page}/",
            supportsEmbeddedPlayer = true,
            categories = listOf(
                SiteCategory("Latest Updates", "/homepage/"),
                SiteCategory("Ongoing", "/status/ongoing/"),
                SiteCategory("Completed", "/status/completed/"),
                SiteCategory("Movies", "/status/anime-movies/"),
                SiteCategory("All Anime", "/anime/")
            )
        ),
        SiteProfile(
            id = "hindisubbedacademy",
            name = "HindiSubbedAcademy",
            baseUrl = "https://hindisubbedacademy.com",
            theme = "kiranime",
            latestPath = "/homepage/",
            searchPath = "/?s={query}",
            fallbackSearchPath = "/search/?keyword={query}",
            pagePath = "/anime/page/{page}/",
            supportsEmbeddedPlayer = true,
            categories = listOf(
                SiteCategory("Latest Updates", "/homepage/"),
                SiteCategory("Ongoing", "/status/ongoing/"),
                SiteCategory("Completed", "/status/completed/"),
                SiteCategory("Movies", "/status/anime-movies/"),
                SiteCategory("All Anime", "/anime/")
            )
        ),
        SiteProfile(
            id = "lordsanime",
            name = "LordsAnime",
            baseUrl = "https://www.lordsanime.com",
            theme = "wordpress",
            latestPath = "/",
            searchPath = "/?s={query}",
            pagePath = "/page/{page}/",
            categories = listOf(
                SiteCategory("Latest Updates", "/"),
                SiteCategory("Hindi Subbed", "/category/hindisubbed/"),
                SiteCategory("Recent Page 2", "/page/2/"),
                SiteCategory("Archive Page 3", "/page/3/")
            )
        ),
        SiteProfile(
            id = "ybxanime",
            name = "YBXAnime",
            baseUrl = "https://ybxanime.com",
            theme = "wordpress",
            latestPath = "/",
            searchPath = "/?s={query}",
            pagePath = "/page/{page}/",
            categories = listOf(
                SiteCategory("Latest Updates", "/"),
                SiteCategory("Action", "/category/action/"),
                SiteCategory("Recent Page 2", "/page/2/"),
                SiteCategory("Archive Page 3", "/page/3/")
            )
        )
    )

    private val mutex = Mutex()
    private var cachedProfiles: List<SiteProfile>? = null
    private var lastFetchMs: Long = 0L

    fun defaults(): List<SiteProfile> = defaultProfiles

    suspend fun getProfiles(network: NetworkClient): List<SiteProfile> = mutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedProfiles
        if (existing != null && (now - lastFetchMs) < 3_600_000L) {
            return@withLock existing
        }

        val fetched = runCatching {
            val resp = network.get(REMOTE_DOMAINS_URL)
            if (resp.code !in 200..299) return@runCatching null
            val root = JSONObject(resp.body)
            val sitesObj = root.optJSONObject("sites") ?: return@runCatching null
            defaultProfiles.map { def ->
                val remote = sitesObj.optJSONObject(def.id) ?: return@map def
                if (!remote.optBoolean("enabled", true)) return@map def
                val base = remote.optString("baseUrl", def.baseUrl).trimEnd('/')
                val fallbacks = mutableListOf<String>()
                val fbArr = remote.optJSONArray("fallbackUrls")
                if (fbArr != null) {
                    for (i in 0 until fbArr.length()) {
                        fbArr.optString(i).trimEnd('/').takeIf { it.startsWith("http") }?.let { fallbacks.add(it) }
                    }
                }
                def.copy(
                    name = remote.optString("name", def.name).ifBlank { def.name },
                    baseUrl = base.ifBlank { def.baseUrl },
                    fallbackUrls = fallbacks,
                    theme = remote.optString("theme", def.theme),
                    latestPath = remote.optString("latestPath", def.latestPath),
                    searchPath = remote.optString("searchPath", def.searchPath),
                    fallbackSearchPath = remote.optString("fallbackSearchPath", "").ifBlank { def.fallbackSearchPath },
                    pagePath = remote.optString("pagePath", def.pagePath),
                    supportsEmbeddedPlayer = remote.optBoolean("supportsEmbeddedPlayer", def.supportsEmbeddedPlayer)
                )
            }
        }.onFailure {
            Log.w("DomainCatalog", "Using bundled domain profiles: ${it.message}")
        }.getOrNull()

        if (!fetched.isNullOrEmpty()) {
            cachedProfiles = fetched
            lastFetchMs = now
            return@withLock fetched
        }
        defaultProfiles
    }
}
