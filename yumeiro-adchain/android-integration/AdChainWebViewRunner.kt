package dev.asfakul.yumeiro.adchain.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.asfakul.yumeiro.adchain.AdChainDomScripts
import dev.asfakul.yumeiro.adchain.AdFilterPolicy
import dev.asfakul.yumeiro.adchain.DestinationValidator
import dev.asfakul.yumeiro.adchain.HumanChallengeKind
import dev.asfakul.yumeiro.adchain.InMemorySessionCookieJar
import dev.asfakul.yumeiro.adchain.ValidatedDestination
import dev.asfakul.yumeiro.adchain.WebViewChainDriver
import dev.asfakul.yumeiro.adchain.WebViewChainOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Production Android WebView + minimal-interruption human-gate driver for Yumeiro.
 *
 * Bridges directly into Yumeiro's existing primitives:
 * - `SafeWebView.harden` (blocks `file://`, `content://`, disables `X-Requested-With`, catches `onRenderProcessGone`)
 * - `AdFilterPolicy` (blocks ad networks without breaking Turnstile/reCAPTCHA/hCaptcha or first-party `/links/go`)
 * - Bidirectional `CookieManager` <-> `InMemorySessionCookieJar` synchronization (`flush()` before handing off)
 * - Minimal-interruption human verification callback: keeps the live WebView session intact and surfaces
 *   it to `BrowserCheckHost` only when `human_challenge` appears, resuming automatically once cleared.
 */
class AdChainWebViewRunner(
    private val context: Context,
    private val userAgent: String,
    private val humanGatePresenter: suspend (webView: WebView, gateUrl: String, kind: HumanChallengeKind?) -> Boolean,
    private val timeoutMs: Long = 35_000L
) : WebViewChainDriver {

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature", "RestrictedApi")
    override suspend fun runChainInWebView(
        startUrl: String,
        referer: String?,
        cookieJar: InMemorySessionCookieJar
    ): WebViewChainOutcome = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<WebViewChainOutcome>()
        val cm = CookieManager.getInstance()
        val web = WebView(context.applicationContext)

        try {
            web.settings.apply {
                allowFileAccess = false
                allowContentAccess = false
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = userAgent
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
            }
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(web, true)

            // Seed OkHttp cookies into Chromium CookieManager so session continuity is preserved
            syncOkHttpCookiesToWebView(startUrl, cookieJar, cm)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, AdChainDomScripts.DOCUMENT_START_JS, setOf("*"))
            }

            // Capture browser-initiated file downloads (e.g. direct attachment redirect)
            web.setDownloadListener { url, _, _, _, _ ->
                if (!result.isCompleted && url.startsWith("http", ignoreCase = true)) {
                    finishWithDestination(url, web.url ?: startUrl, cm, cookieJar, userAgent, result)
                }
            }

            var humanPromptShown = false

            fun pollDomStage() {
                if (result.isCompleted) return
                web.evaluateJavascript(AdChainDomScripts.INSPECT_STAGE_JS) { rawJson ->
                    if (result.isCompleted || rawJson.isNullOrBlank() || rawJson == "null") return@evaluateJavascript
                    val unquoted = runCatching { JSONObject("{\"v\":$rawJson}").getString("v") }.getOrNull() ?: return@evaluateJavascript
                    val stageObj = runCatching { JSONObject(unquoted) }.getOrNull() ?: return@evaluateJavascript
                    val stage = stageObj.optString("stage")
                    val currentUrl = web.url ?: startUrl

                    when (stage) {
                        "direct_media", "next_anchor" -> {
                            val target = stageObj.optString("url")
                            val validated = DestinationValidator.validate(target)
                            if (validated !is ValidatedDestination.Invalid && !DestinationValidator.isShortenerGateHost(target)) {
                                finishWithDestination(target, currentUrl, cm, cookieJar, userAgent, result)
                            } else if (target.startsWith("http")) {
                                web.loadUrl(target, refererHeader(currentUrl))
                            }
                        }
                        "adlinkfly_form" -> {
                            // Dispatch same-origin XHR POST inside WebView context with active cookies
                            val action = stageObj.optString("action", "/links/go")
                            val fieldsJson = stageObj.optJSONObject("fields")?.toString() ?: "{}"
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
                            if (!humanPromptShown) {
                                humanPromptShown = true
                                mainHandler.post {
                                    // Hand live WebView to BrowserCheckHost without losing state;
                                    // caller suspends on user completion or cancellation.
                                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                        val completed = humanGatePresenter(web, currentUrl, null)
                                        if (!completed && !result.isCompleted) {
                                            result.complete(WebViewChainOutcome.HumanVerificationCancelled(currentUrl, null))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            web.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    // Drop all pop-under windows cleanly
                    return false
                }
            }

            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val req = request ?: return null
                    val url = req.url?.toString() ?: return null
                    if (!req.isForMainFrame) {
                        val pageHost = AdFilterPolicy.hostOf(view?.url ?: startUrl)
                        if (AdFilterPolicy.decideSubresource(url, pageHost) == AdFilterPolicy.SubresourceDecision.BLOCK_AD) {
                            return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        val v = DestinationValidator.validate(url)
                        if (v is ValidatedDestination.DirectMedia && !result.isCompleted) {
                            mainHandler.post {
                                finishWithDestination(url, view?.url ?: startUrl, cm, cookieJar, userAgent, result)
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
                        return true
                    }
                    val validated = DestinationValidator.validate(target)
                    if (validated is ValidatedDestination.DirectMedia || validated is ValidatedDestination.CloudFileHost) {
                        finishWithDestination(target, view?.url ?: startUrl, cm, cookieJar, userAgent, result)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (result.isCompleted) return
                    val landed = url.orEmpty()
                    val validated = DestinationValidator.validate(landed)
                    if (validated is ValidatedDestination.DirectMedia || validated is ValidatedDestination.CloudFileHost) {
                        finishWithDestination(landed, landed, cm, cookieJar, userAgent, result)
                        return
                    }
                    pollDomStage()
                    mainHandler.postDelayed({ pollDomStage() }, 1500L)
                    mainHandler.postDelayed({ pollDomStage() }, 3500L)
                }
            }

            web.loadUrl(startUrl, refererHeader(referer))
            withTimeoutOrNull(timeoutMs) { result.await() }
                ?: WebViewChainOutcome.Failed(web.url ?: startUrl, "webview_timeout")
        } finally {
            runCatching { cm.flush() }
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
        }
    }

    private fun refererHeader(referer: String?): MutableMap<String, String> {
        val map = HashMap<String, String>()
        if (!referer.isNullOrBlank() && referer.startsWith("http")) {
            map["Referer"] = referer
        }
        return map
    }

    private fun syncOkHttpCookiesToWebView(url: String, jar: InMemorySessionCookieJar, cm: CookieManager) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        for (c in jar.loadForRequest(httpUrl)) {
            val header = "${c.name}=${c.value}; Path=${c.path}"
            cm.setCookie(url, header)
        }
        cm.flush()
    }

    private fun finishWithDestination(
        destinationUrl: String,
        referer: String,
        cm: CookieManager,
        jar: InMemorySessionCookieJar,
        ua: String,
        result: CompletableDeferred<WebViewChainOutcome>
    ) {
        if (result.isCompleted) return
        runCatching { cm.flush() }
        val rawCookie = runCatching { cm.getCookie(destinationUrl) }.getOrNull().orEmpty()
        val cookieMap = rawCookie.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }.toMap()
        destinationUrl.toHttpUrlOrNull()?.let { httpUrl ->
            if (cookieMap.isNotEmpty()) jar.seedCookies(httpUrl, cookieMap)
        }
        result.complete(WebViewChainOutcome.DestinationReached(destinationUrl, referer, cookieMap, ua))
    }
}
