package com.animeext.wordpress.resolver

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.animeext.wordpress.http.ExtensionHttpClient
import com.animeext.wordpress.model.QualityLabel
import com.animeext.wordpress.model.ResolvedStream
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream

/**
 * Pure-Kotlin state machine for interactive WebView media resolution.
 * Inspects navigation URLs, intercepted network requests, and injected DOM hooks
 * to detect Pixeldrain, Gofile, or direct .mp4/.m3u8 streams.
 */
class WebViewSessionController(
    val initialUrl: String,
    private val baseHeaders: Map<String, String> = emptyMap(),
    private val expectedQuality: QualityLabel = QualityLabel.UNKNOWN,
    var adFilterEnabled: Boolean = true,
    private val onResolved: (ResolvedStream) -> Unit = {},
    private val onCancelled: () -> Unit = {}
) {
    enum class Status { PENDING, RESOLVED, CANCELLED }

    var status: Status = Status.PENDING
        private set
    var currentUrl: String = initialUrl
        private set
    var resolvedStream: ResolvedStream? = null
        private set
    var blockedHijacksCount: Int = 0
        private set

    val history = mutableListOf(initialUrl)
    val detectedCandidates = mutableListOf<ResolvedStream>()

    fun onPageStarted(url: String): ResolvedStream? {
        if (status != Status.PENDING) return null
        currentUrl = url
        if (history.lastOrNull() != url) {
            history.add(url)
        }
        return inspectUrl(url, source = "navigation")
    }

    /**
     * Evaluates a navigation request from WebViewClient.shouldOverrideUrlLoading.
     * Blocks intent:// / market:// / tg:// hijacks, captures direct media hosts,
     * and prevents main-frame ad redirects from pulling the user off the chain page.
     */
    fun shouldOverrideUrlLoading(url: String, isForMainFrame: Boolean = true): Boolean {
        if (status != Status.PENDING) return false
        if (MediaHostPatterns.isExternalHijackScheme(url)) {
            blockedHijacksCount++
            return true
        }
        if (inspectUrl(url, source = "navigation-override") != null) {
            return true
        }
        if (adFilterEnabled && isForMainFrame && MediaHostPatterns.isAdHost(url)) {
            blockedHijacksCount++
            return true
        }
        return false
    }

    fun onPopupAttempt(url: String?): Boolean {
        if (status != Status.PENDING) return false
        if (!url.isNullOrBlank()) {
            inspectUrl(url, source = "popup-window")
        }
        blockedHijacksCount++
        return false
    }

    fun onDownloadTriggered(
        url: String,
        mimeType: String = "",
        contentDisposition: String = ""
    ): ResolvedStream? {
        if (status != Status.PENDING || url.isBlank()) return null
        inspectUrl(url, source = "download-listener", mimeTypeHint = mimeType)?.let { return it }

        if (contentDisposition.contains("attachment", ignoreCase = true) ||
            mimeType.startsWith("video/", ignoreCase = true)
        ) {
            val stream = ResolvedStream(
                url = url,
                originalUrl = url,
                hostType = "download",
                mimeType = mimeType.ifBlank { "video/mp4" },
                quality = expectedQuality,
                headers = baseHeaders + mapOf("Referer" to currentUrl),
                resolvedVia = "webview-download-listener"
            )
            detectedCandidates.add(stream)
            complete(stream)
            return stream
        }
        return null
    }

    /**
     * Evaluates a sub-resource or navigation request from WebViewClient.shouldInterceptRequest.
     * Returns Pair(shouldBlockAdRequest, resolvedStreamOrNull).
     */
    fun shouldInterceptRequest(
        url: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): Pair<Boolean, ResolvedStream?> {
        if (status != Status.PENDING) return false to resolvedStream
        if (adFilterEnabled && MediaHostPatterns.isAdHost(url)) {
            blockedHijacksCount++
            return true to null
        }
        if (MediaHostPatterns.isAdOrNoiseUrl(url)) {
            return false to null
        }
        val hit = inspectUrl(url, source = "network-intercept", requestHeaders = requestHeaders)
        return false to hit
    }

    fun onDomMediaDetected(
        url: String,
        mimeType: String = "",
        qualityHint: String = ""
    ): ResolvedStream? {
        if (status != Status.PENDING) return null
        return inspectUrl(
            url = url,
            source = "dom-hook",
            mimeTypeHint = mimeType,
            qualityHint = qualityHint
        )
    }

    fun manualResolve(url: String): ResolvedStream {
        val match = MediaHostPatterns.matchDirectMediaHost(url)
        val stream = ResolvedStream(
            url = match?.streamUrl ?: url,
            originalUrl = url,
            hostType = match?.hostType ?: "manual",
            mimeType = match?.mimeType ?: "video/*",
            quality = expectedQuality,
            headers = baseHeaders + mapOf("Referer" to currentUrl),
            resolvedVia = "webview-manual"
        )
        complete(stream)
        return stream
    }

    fun cancel() {
        if (status != Status.PENDING) return
        status = Status.CANCELLED
        onCancelled()
    }

    private fun inspectUrl(
        url: String,
        source: String,
        requestHeaders: Map<String, String> = emptyMap(),
        mimeTypeHint: String = "",
        qualityHint: String = ""
    ): ResolvedStream? {
        val match = MediaHostPatterns.matchDirectMediaHost(url) ?: return null
        val quality = QualityLabel.fromRaw(qualityHint).takeIf { it != QualityLabel.UNKNOWN }
            ?: expectedQuality
        val candidate = ResolvedStream(
            url = match.streamUrl,
            originalUrl = match.originalUrl,
            hostType = match.hostType,
            mimeType = mimeTypeHint.ifBlank { match.mimeType },
            quality = quality,
            headers = baseHeaders + mapOf("Referer" to currentUrl) + requestHeaders,
            resolvedVia = "webview-$source"
        )
        detectedCandidates.add(candidate)

        if (match.isDirectStream || match.hostType == "pixeldrain" || match.hostType == "gofile") {
            complete(candidate)
            return candidate
        }
        return null
    }

    private fun complete(stream: ResolvedStream) {
        if (status != Status.PENDING) return
        status = Status.RESOLVED
        resolvedStream = stream
        onResolved(stream)
    }
}

/**
 * Interactive in-app WebView handler allowing the user to complete intermediate redirect,
 * countdown, or captcha steps in a browser UI while passively listening for final direct
 * media host URLs (Pixeldrain, Gofile, .mp4, .m3u8) to hand off to the native video player.
 */
class WebViewResolverDialogFragment : DialogFragment() {
    private var webView: WebView? = null
    private lateinit var controller: WebViewSessionController
    private var httpClient: ExtensionHttpClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        val ctx = requireContext()
        val initialUrl = requireArguments().getString(ARG_URL).orEmpty()
        val qualityName = requireArguments().getString(ARG_QUALITY) ?: QualityLabel.UNKNOWN.name
        val quality = runCatching { QualityLabel.valueOf(qualityName) }.getOrDefault(QualityLabel.UNKNOWN)

        val headers = httpClient?.buildHeadersForUrl(initialUrl) ?: emptyMap()
        controller = WebViewSessionController(
            initialUrl = initialUrl,
            baseHeaders = headers,
            expectedQuality = quality,
            onResolved = { stream ->
                mainHandler.post {
                    syncCookiesBack(controller.currentUrl)
                    pendingDeferred?.complete(stream)
                    pendingDeferred = null
                    dismissAllowingStateLoss()
                }
            },
            onCancelled = {
                mainHandler.post {
                    pendingDeferred?.cancel()
                    pendingDeferred = null
                    dismissAllowingStateLoss()
                }
            }
        )

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val urlText = TextView(ctx).apply {
            text = initialUrl
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            setPadding(24, 16, 24, 8)
        }

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }

        val actionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
        }

        val backButton = Button(ctx).apply {
            text = "Back"
            setOnClickListener {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                }
            }
        }

        val filterToggleButton = Button(ctx).apply {
            text = "Filter: ON"
            setOnClickListener {
                controller.adFilterEnabled = !controller.adFilterEnabled
                text = if (controller.adFilterEnabled) "Filter: ON" else "Filter: OFF"
                webView?.reload()
            }
        }

        val handoffButton = Button(ctx).apply {
            text = "Play Video"
            setOnClickListener {
                val latestCandidate = controller.detectedCandidates.lastOrNull()?.url
                    ?: webView?.url
                    ?: initialUrl
                controller.manualResolve(latestCandidate)
            }
        }

        val closeButton = Button(ctx).apply {
            text = "Cancel"
            setOnClickListener { controller.cancel() }
        }

        actionBar.addView(backButton)
        actionBar.addView(filterToggleButton)
        actionBar.addView(handoffButton)
        actionBar.addView(closeButton)

        val wv = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = httpClient?.userAgent ?: userAgentString
            }

            initialUrl.toHttpUrlOrNull()?.let { httpUrl ->
                httpClient?.cookieJar?.syncToWebView(httpUrl)
            }

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onMediaUrlDetected(url: String, mimeType: String, detectedQuality: String) {
                        controller.onDomMediaDetected(url, mimeType, detectedQuality)
                    }
                },
                "AndroidStreamBridge"
            )

            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                controller.onDownloadTriggered(url, mimetype.orEmpty(), contentDisposition.orEmpty())
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val hitUrl = view?.hitTestResult?.extra
                    return controller.onPopupAttempt(hitUrl)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val reqUrl = request?.url?.toString() ?: return false
                    return controller.shouldOverrideUrlLoading(reqUrl, request.isForMainFrame)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!url.isNullOrBlank()) {
                        urlText.text = "[Blocked: ${controller.blockedHijacksCount}] $url"
                        controller.onPageStarted(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(WEBVIEW_INTERCEPT_SCRIPT, null)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    val reqHeaders = request.requestHeaders ?: emptyMap()
                    val (blockAd, _) = controller.shouldInterceptRequest(reqUrl, reqHeaders)
                    if (blockAd) {
                        val (mime, body) = MediaHostPatterns.getAdStubResponse(reqUrl)
                        return WebResourceResponse(
                            mime,
                            "utf-8",
                            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            loadUrl(initialUrl, headers)
        }

        webView = wv
        root.addView(actionBar)
        root.addView(urlText)
        root.addView(progressBar)
        root.addView(wv)
        return root
    }

    private fun syncCookiesBack(url: String) {
        url.toHttpUrlOrNull()?.let { httpUrl ->
            httpClient?.cookieJar?.syncFromWebView(httpUrl)
        }
    }

    override fun onDestroyView() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_URL = "arg_url"
        private const val ARG_QUALITY = "arg_quality"

        @Volatile
        private var pendingDeferred: CompletableDeferred<ResolvedStream>? = null

        const val WEBVIEW_INTERCEPT_SCRIPT = """
            (function() {
              if (window.__animeExtHooked) return;
              window.__animeExtHooked = true;
              var MEDIA_RE = /\.(mp4|m3u8|mkv|webm)(\?.*)?$|pixeldrain\.(com|dev)\/(u|api\/file)\/|gofile\.io\/(d|download)\//i;
              function report(url, mimeType, quality) {
                if (!url || typeof url !== 'string') return;
                if (MEDIA_RE.test(url) && window.AndroidStreamBridge) {
                  window.AndroidStreamBridge.onMediaUrlDetected(url, mimeType || '', quality || '');
                }
              }
              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function(input) {
                  try { report(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
                  return origFetch.apply(this, arguments);
                };
              }
              var origOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) {
                try { report(url); } catch (e) {}
                return origOpen.apply(this, arguments);
              };
              function scanDom() {
                document.querySelectorAll('video, video source, a[href]').forEach(function(el) {
                  var c = el.src || el.getAttribute('src') || el.getAttribute('href');
                  if (c) report(c, el.type || '', el.getAttribute('label') || '');
                });
              }
              scanDom();
              if (typeof MutationObserver !== 'undefined' && document.documentElement) {
                new MutationObserver(function() { scanDom(); })
                  .observe(document.documentElement, { childList: true, subtree: true, attributes: true });
              }
            })();
        """

        suspend fun resolveInteractively(
            fragmentManager: FragmentManager,
            httpClient: ExtensionHttpClient,
            initialUrl: String,
            expectedQuality: QualityLabel = QualityLabel.UNKNOWN
        ): ResolvedStream {
            val deferred = CompletableDeferred<ResolvedStream>()
            pendingDeferred = deferred
            val fragment = WebViewResolverDialogFragment().apply {
                this.httpClient = httpClient
                arguments = Bundle().apply {
                    putString(ARG_URL, initialUrl)
                    putString(ARG_QUALITY, expectedQuality.name)
                }
            }
            fragment.show(fragmentManager, "WebViewResolverDialog")
            return deferred.await()
        }
    }
}
