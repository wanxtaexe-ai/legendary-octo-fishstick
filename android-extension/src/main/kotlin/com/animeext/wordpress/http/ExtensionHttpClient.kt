package com.animeext.wordpress.http

import com.animeext.wordpress.config.DomainsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HttpHtmlResponse(
    val url: String,
    val statusCode: Int,
    val html: String,
    val headers: Map<String, String>
)

/**
 * Shared OkHttp client configured with mobile Chrome User-Agent, Accept headers,
 * and PersistentCookieJar for WordPress catalog fetching and WebView handoff.
 */
class ExtensionHttpClient(
    val userAgent: String = DomainsConfig.DEFAULT_UA,
    val cookieJar: PersistentCookieJar = PersistentCookieJar()
) {
    private val defaultHeadersInterceptor = Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("User-Agent", userAgent)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
            .build()
        chain.proceed(request)
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(defaultHeadersInterceptor)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun buildHeadersForUrl(url: String, referer: String? = null): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8"
        )
        if (!referer.isNullOrBlank()) {
            headers["Referer"] = referer
        }
        url.toHttpUrlOrNull()?.let { httpUrl ->
            val cookieHeader = cookieJar.getCookieHeader(httpUrl)
            if (cookieHeader.isNotEmpty()) {
                headers["Cookie"] = cookieHeader
            }
        }
        return headers
    }

    suspend fun getHtml(url: String, referer: String? = null): HttpHtmlResponse =
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder().url(url).get()
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", referer)
            }
            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} fetching $url")
                }
                val finalUrl = response.request.url.toString()
                val body = response.body?.string().orEmpty()
                val headerMap = response.headers.toMultimap()
                    .mapValues { (_, values) -> values.joinToString(", ") }
                HttpHtmlResponse(
                    url = finalUrl,
                    statusCode = response.code,
                    html = body,
                    headers = headerMap
                )
            }
        }

    suspend fun probeUrl(url: String, referer: String? = null): HttpHtmlResponse =
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder().url(url).get()
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", referer)
            }
            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type").orEmpty()
                val html = if (contentType.contains("text/html", ignoreCase = true)) {
                    response.body?.string().orEmpty()
                } else {
                    ""
                }
                val headerMap = response.headers.toMultimap()
                    .mapValues { (_, values) -> values.joinToString(", ") }
                HttpHtmlResponse(
                    url = finalUrl,
                    statusCode = response.code,
                    html = html,
                    headers = headerMap
                )
            }
        }
}
