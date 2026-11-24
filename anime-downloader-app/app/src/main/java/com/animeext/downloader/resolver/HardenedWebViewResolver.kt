package com.animeext.downloader.resolver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.animeext.downloader.model.ResolvedTarget
import com.animeext.downloader.network.InMemorySessionCookieJar
import com.animeext.downloader.network.NetworkClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

sealed class SandboxResolutionResult {
    data class Captured(val target: ResolvedTarget, val blockedCount: Int) : SandboxResolutionResult()
    data class Cancelled(val blockedCount: Int, val trace: List<String>) : SandboxResolutionResult()
    data class Failed(val reason: String, val blockedCount: Int, val trace: List<String>) : SandboxResolutionResult()
}

data class GateSandboxState(
    val webView: WebView?,
    val visibleToUser: Boolean = false,
    val currentUrl: String = "",
    val challengeKind: String? = null,
    val blockedCount: Int = 0,
    val onCancel: () -> Unit = {}
)

class HardenedWebViewResolver(
    private val context: Context,
    private val cookieJar: InMemorySessionCookieJar,
    private val httpFastPath: HttpFastPathResolver
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    suspend fun resolveGate(
        gateUrl: String,
        referer: String?,
        initialTrace: List<String>,
        onSandboxStateChanged: (GateSandboxState) -> Unit,
        timeoutMs: Long = 90_000L
    ): SandboxResolutionResult {
        val trace = initialTrace.toMutableList()
        val blockedCount = AtomicInteger(0)

        val capturedResult = withContext(Dispatchers.Main) {
            val result = CompletableDeferred<SandboxResolutionResult>()
            val cm = CookieManager.getInstance()
            val web = WebView(context)

            fun notifyUi(visible: Boolean, url: String, challengeKind: String? = null) {
                onSandboxStateChanged(
                    GateSandboxState(
                        webView = web,
                        visibleToUser = visible,
                        currentUrl = url,
                        challengeKind = challengeKind,
                        blockedCount = blockedCount.get(),
                        onCancel = {
                            if (!result.isCompleted) {
                                trace.add("User cancelled sandbox gate")
                                result.complete(SandboxResolutionResult.Cancelled(blockedCount.get(), trace))
                            }
                        }
                    )
                )
            }

            try {
                // Hardened zero-permission WebView configuration
                @Suppress("DEPRECATION")
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    setGeolocationEnabled(false)
                    saveFormData = false
                    databaseEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString = NetworkClient.CHROME_UA
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        safeBrowsingEnabled = true
                    }
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
                }

                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(web, true)
                seedCookiesIntoWebView(gateUrl, cm)

                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(web, AdChainDomScripts.DOCUMENT_START_JS, setOf("*"))
                }

                notifyUi(visible = false, url = gateUrl)

                fun completeWithDestination(destUrl: String, currentPageUrl: String) {
                    if (result.isCompleted) return
                    if (DownloadAndSchemeGuard.inspectUrl(destUrl) != DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS) {
                        blockedCount.incrementAndGet()
                        trace.add("Sandbox blocked unsafe destination: $destUrl")
                        notifyUi(visible = false, url = currentPageUrl)
                        return
                    }
                    runCatching { cm.flush() }
                    val rawCookie = runCatching { cm.getCookie(destUrl) }.getOrNull().orEmpty()
                    val cookieMap = rawCookie.split(';').mapNotNull { part ->
                        val name = part.substringBefore('=').trim()
                        val value = part.substringAfter('=', "").trim()
                        if (name.isBlank() || value.isBlank()) null else name to value
                    }.toMap()
                    destUrl.toHttpUrlOrNull()?.let { httpUrl ->
                        if (cookieMap.isNotEmpty()) cookieJar.seedCookies(httpUrl, cookieMap)
                    }
                    val headers = LinkedHashMap<String, String>()
                    headers["User-Agent"] = NetworkClient.CHROME_UA
                    if (currentPageUrl.startsWith("http")) headers["Referer"] = currentPageUrl
                    if (cookieMap.isNotEmpty()) {
                        headers["Cookie"] = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    }
                    val path = destUrl.substringBefore('?').substringBefore('#').lowercase()
                    trace.add("Sandbox captured clean destination (${blockedCount.get()} threats blocked)")
                    result.complete(
                        SandboxResolutionResult.Captured(
                            target = ResolvedTarget(
                                url = destUrl,
                                referer = currentPageUrl,
                                cookies = cookieMap,
                                userAgent = NetworkClient.CHROME_UA,
                                headers = headers,
                                isHls = path.endsWith(".m3u8") || path.endsWith(".m3u"),
                                isZip = path.endsWith(".zip") || path.endsWith(".rar") || path.endsWith(".7z"),
                                trace = trace.toList()
                            ),
                            blockedCount = blockedCount.get()
                        )
                    )
                }

                // Firewall on WebView downloads: strictly block APK/EXE & non-HTTP schemes
                web.setDownloadListener { url, _, _, mimeType, _ ->
                    if (result.isCompleted || url.isNullOrBlank()) return@setDownloadListener
                    val safety = DownloadAndSchemeGuard.inspectUrl(url)
                    if (safety != DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS ||
                        DownloadAndSchemeGuard.isBlockedMimeType(mimeType)
                    ) {
                        val count = blockedCount.incrementAndGet()
                        trace.add("BLOCKED dangerous download ($mimeType): $url")
                        notifyUi(visible = true, url = web.url ?: gateUrl, challengeKind = "blocked_$count")
                        return@setDownloadListener
                    }
                    completeWithDestination(url, web.url ?: gateUrl)
                }

                var humanSurfaceShown = false

                fun pollDomStage() {
                    if (result.isCompleted) return
                    web.evaluateJavascript(AdChainDomScripts.INSPECT_STAGE_JS) { rawJson ->
                        if (result.isCompleted || rawJson.isNullOrBlank() || rawJson == "null") return@evaluateJavascript
                        val unquoted = runCatching { JSONObject("{\"v\":$rawJson}").getString("v") }.getOrNull()
                            ?: return@evaluateJavascript
                        val obj = runCatching { JSONObject(unquoted) }.getOrNull() ?: return@evaluateJavascript
                        val stage = obj.optString("stage")
                        val currentUrl = web.url ?: gateUrl

                        when (stage) {
                            "direct_media", "next_anchor" -> {
                                val target = obj.optString("url")
                                if (DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(target) ||
                                    DownloadAndSchemeGuard.isKnownCloudFileHost(target)
                                ) {
                                    completeWithDestination(target, currentUrl)
                                } else if (target.startsWith("http") &&
                                    DownloadAndSchemeGuard.inspectUrl(target) == DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS
                                ) {
                                    web.loadUrl(target, refererHeader(currentUrl))
                                }
                            }
                            "adlinkfly_form" -> {
                                val action = obj.optString("action", "/links/go")
                                val fieldsJson = obj.optJSONObject("fields")?.toString() ?: "{}"
                                val js = """
                                    (function(){
                                      var fd = new FormData();
                                      var obj = $fieldsJson;
                                      Object.keys(obj).forEach(function(k){ fd.append(k, obj[k]); });
                                      fetch('$action', { method: 'POST', body: fd, headers: {'X-Requested-With':'XMLHttpRequest'} })
                                        .then(function(r){ return r.json(); })
                                        .then(function(d){ if(d && d.url){ window.location.href = d.url; } })
                                        .catch(function(){});
                                    })();
                                """.trimIndent()
                                web.evaluateJavascript(js, null)
                            }
                            "safelink_form" -> {
                                web.evaluateJavascript("document.querySelector('form[name=\"tp\"]')?.submit();", null)
                            }
                            "human_challenge" -> {
                                val kind = obj.optString("kind", "captcha")
                                if (!humanSurfaceShown) {
                                    humanSurfaceShown = true
                                    trace.add("Human check ($kind) surfaced in sandbox")
                                }
                                notifyUi(visible = true, url = currentUrl, challengeKind = kind)
                            }
                        }
                    }
                }

                // Zero-permission WebChromeClient: deny popups, permissions, geolocation, JS dialogs
                web.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        blockedCount.incrementAndGet()
                        return false
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        blockedCount.incrementAndGet()
                        request?.deny()
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?
                    ) {
                        blockedCount.incrementAndGet()
                        callback?.invoke(origin, false, false)
                    }

                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                        result?.cancel()
                        return true
                    }

                    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                        result?.cancel()
                        return true
                    }

                    override fun onJsPrompt(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        defaultValue: String?,
                        result: JsPromptResult?
                    ): Boolean {
                        result?.cancel()
                        return true
                    }
                }

                web.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        if (!result.isCompleted) {
                            val crashed = if (Build.VERSION.SDK_INT >= 26) detail?.didCrash() == true else false
                            result.complete(
                                SandboxResolutionResult.Failed("WebView renderer exited ($crashed)", blockedCount.get(), trace)
                            )
                        }
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val req = request ?: return null
                        val url = req.url?.toString() ?: return null
                        if (!req.isForMainFrame) {
                            val pageHost = AdFilterPolicy.hostOf(view?.url ?: gateUrl)
                            if (AdFilterPolicy.decideSubresource(url, pageHost) == AdFilterPolicy.SubresourceDecision.BLOCK_AD) {
                                blockedCount.incrementAndGet()
                                return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            }
                            if (DownloadAndSchemeGuard.isDirectVideoUrl(url) && !result.isCompleted) {
                                mainHandler.post {
                                    completeWithDestination(url, view?.url ?: gateUrl)
                                }
                            }
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val req = request ?: return false
                        if (!req.isForMainFrame) return false
                        val target = req.url?.toString() ?: return true

                        val decision = AdFilterPolicy.decideMainFrameNavigation(
                            currentUrl = view?.url,
                            targetUrl = target,
                            hasUserGesture = req.hasGesture(),
                            isRedirect = req.isRedirect
                        )
                        if (decision != AdFilterPolicy.NavigationDecision.ALLOW) {
                            blockedCount.incrementAndGet()
                            trace.add("Blocked ${decision.name}: ${target.take(64)}")
                            return true
                        }

                        if (DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(target) ||
                            DownloadAndSchemeGuard.isKnownCloudFileHost(target)
                        ) {
                            completeWithDestination(target, view?.url ?: gateUrl)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (result.isCompleted) return
                        val landed = url.orEmpty()
                        notifyUi(visible = humanSurfaceShown, url = landed)

                        if (DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(landed) ||
                            DownloadAndSchemeGuard.isKnownCloudFileHost(landed)
                        ) {
                            completeWithDestination(landed, landed)
                            return
                        }
                        pollDomStage()
                        mainHandler.postDelayed({ pollDomStage() }, 1500L)
                        mainHandler.postDelayed({ pollDomStage() }, 3500L)
                    }
                }

                web.loadUrl(gateUrl, refererHeader(referer))
                withTimeoutOrNull(timeoutMs) { result.await() }
                    ?: SandboxResolutionResult.Failed("Gate timed out after ${timeoutMs / 1000}s", blockedCount.get(), trace)
            } finally {
                onSandboxStateChanged(GateSandboxState(webView = null, visibleToUser = false))
                // Immediate session wipe so gate cookies/trackers never persist
                runCatching { cm.removeAllCookies(null) }
                runCatching { cm.flush() }
                runCatching { WebStorage.getInstance().deleteAllData() }
                runCatching { (web.parent as? android.view.ViewGroup)?.removeView(web) }
                runCatching { web.stopLoading() }
                runCatching { web.clearHistory() }
                runCatching { web.clearCache(true) }
                runCatching { web.destroy() }
            }
        }

        // If the sandbox landed on a cloud file host (e.g. Mirrored.to, StreamTape, GoFile), resolve to final binary URL!
        if (capturedResult is SandboxResolutionResult.Captured) {
            val captured = capturedResult.target
            if (!DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(captured.url) &&
                DownloadAndSchemeGuard.isKnownCloudFileHost(captured.url)
            ) {
                val post = httpFastPath.resolve(captured.url, captured.referer, wantDownload = true)
                if (post is FastPathResult.Resolved && post.targets.isNotEmpty()) {
                    val upgraded = post.targets.first().copy(
                        trace = captured.trace + post.trace
                    )
                    return SandboxResolutionResult.Captured(upgraded, capturedResult.blockedCount)
                }
            }
        }

        return capturedResult
    }

    private fun seedCookiesIntoWebView(url: String, cm: CookieManager) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        for (c in cookieJar.loadForRequest(httpUrl)) {
            cm.setCookie(url, "${c.name}=${c.value}; Path=${c.path}")
        }
        cm.flush()
    }

    private fun refererHeader(referer: String?): MutableMap<String, String> {
        val map = HashMap<String, String>()
        if (!referer.isNullOrBlank() && referer.startsWith("http")) {
            map["Referer"] = referer
        }
        return map
    }
}
