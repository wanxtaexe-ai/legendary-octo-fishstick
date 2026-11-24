import {
  extractEmbeddedVideoSources,
  getAdStubResponse,
  isAdOrNoiseUrl,
  isExternalHijackScheme,
  isKnownAdHost,
  matchDirectMediaHost,
} from './mediaHostMatcher.js';

/**
 * JavaScript injected into Android WebView onPageFinished to passively observe
 * dynamically created <video>/<source> tags, fetch/XHR requests, and media links
 * while the user completes interactive steps (timers, captchas, redirect buttons).
 */
export const WEBVIEW_INTERCEPT_SCRIPT = `
(function() {
  if (window.__animeExtHooked) return;
  window.__animeExtHooked = true;

  var MEDIA_RE = /\\.(mp4|m3u8|mkv|webm)(\\?.*)?$|pixeldrain\\.(com|dev)\\/(u|api\\/file)\\/|gofile\\.io\\/(d|download)\\//i;

  function report(url, mimeType, quality) {
    if (!url || typeof url !== 'string') return;
    if (MEDIA_RE.test(url) && window.AndroidStreamBridge && typeof window.AndroidStreamBridge.onMediaUrlDetected === 'function') {
      window.AndroidStreamBridge.onMediaUrlDetected(url, mimeType || '', quality || '');
    }
  }

  // Hook fetch
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(input) {
      try {
        var reqUrl = typeof input === 'string' ? input : (input && input.url);
        report(reqUrl);
      } catch (e) {}
      return origFetch.apply(this, arguments);
    };
  }

  // Hook XMLHttpRequest
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    try { report(url); } catch (e) {}
    return origOpen.apply(this, arguments);
  };

  // Scan existing and future <video> / <source> / <a> elements
  function scanDom() {
    document.querySelectorAll('video, video source, a[href]').forEach(function(el) {
      var candidate = el.src || el.getAttribute('src') || el.getAttribute('href');
      if (candidate) report(candidate, el.type || '', el.getAttribute('label') || '');
    });
  }

  scanDom();
  if (typeof MutationObserver !== 'undefined' && document.documentElement) {
    var observer = new MutationObserver(function() { scanDom(); });
    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
  }
})();
`.trim();

/**
 * Interactive WebView resolver session controller.
 * Models the Android WebViewClient lifecycle (`shouldInterceptRequest`, `shouldOverrideUrlLoading`,
 * DOM bridge callbacks, and manual handoff) so interactive resolution logic is testable and reusable.
 */
export class WebViewResolverSession {
  /**
   * @param {object} params
   * @param {string} params.initialUrl
   * @param {Record<string, string>} [params.headers]
   * @param {string} [params.expectedQuality]
   * @param {number} [params.timeoutMs]
   */
  constructor({
    initialUrl,
    headers = {},
    expectedQuality = 'Unknown',
    timeoutMs = 120_000,
    adFilterEnabled = true,
  }) {
    this.initialUrl = initialUrl;
    this.currentUrl = initialUrl;
    this.headers = headers;
    this.expectedQuality = expectedQuality;
    this.timeoutMs = timeoutMs;
    this.adFilterEnabled = adFilterEnabled;
    this.blockedHijacksCount = 0;
    this.history = [initialUrl];
    this.detectedCandidates = [];
    this.status = 'pending'; // 'pending' | 'resolved' | 'cancelled' | 'timeout'
    this.resolvedStream = null;
    this._resolvePromise = null;
    this._rejectPromise = null;
  }

  setAdFilterEnabled(enabled) {
    this.adFilterEnabled = Boolean(enabled);
  }

  /**
   * Called when WebView starts loading a new page (navigation step completed by user or redirect).
   */
  onPageStarted(url) {
    if (this.status !== 'pending') return null;
    this.currentUrl = url;
    if (this.history[this.history.length - 1] !== url) {
      this.history.push(url);
    }
    return this.inspectUrl(url, 'navigation');
  }

  /**
   * Models WebViewClient.shouldOverrideUrlLoading:
   * - Blocks intent://, market://, tg:// external app hijacks.
   * - Captures direct media host navigations immediately.
   * - Blocks main-frame hijacks to known ad networks so user stays on current chain page.
   */
  shouldOverrideUrlLoading(url, { isForMainFrame = true } = {}) {
    if (this.status !== 'pending') {
      return { overrideLoad: false, reason: 'not-pending', resolvedStream: this.resolvedStream };
    }

    if (isExternalHijackScheme(url)) {
      this.blockedHijacksCount++;
      return { overrideLoad: true, reason: 'blocked-external-scheme', resolvedStream: null };
    }

    const directHit = this.inspectUrl(url, 'navigation-override');
    if (directHit) {
      return { overrideLoad: true, reason: 'media-detected', resolvedStream: directHit };
    }

    if (this.adFilterEnabled && isForMainFrame && isKnownAdHost(url)) {
      this.blockedHijacksCount++;
      return { overrideLoad: true, reason: 'blocked-ad-redirect', resolvedStream: null };
    }

    return { overrideLoad: false, reason: 'allow-navigation', resolvedStream: null };
  }

  /**
   * Models WebChromeClient.onCreateWindow for target="_blank" / window.open() popunders.
   */
  onPopupAttempt(url) {
    if (this.status !== 'pending') {
      return { allowPopup: false, resolvedStream: this.resolvedStream };
    }
    if (url) {
      const mediaHit = this.inspectUrl(url, 'popup-window');
      if (mediaHit) {
        return { allowPopup: false, resolvedStream: mediaHit };
      }
    }
    this.blockedHijacksCount++;
    return { allowPopup: false, reason: 'blocked-popunder', resolvedStream: null };
  }

  /**
   * Models WebView.setDownloadListener for attachment-triggered media hosts (Pixeldrain/Gofile/MP4).
   */
  onDownloadTriggered(url, mimeType = '', contentDisposition = '') {
    if (this.status !== 'pending' || !url) return null;
    const hit = this.inspectUrl(url, 'download-listener', {}, mimeType);
    if (hit) return hit;

    if (/attachment/i.test(contentDisposition) || /^video\//i.test(mimeType)) {
      const stream = {
        url,
        originalUrl: url,
        hostType: 'download',
        mimeType: mimeType || 'video/mp4',
        quality: this.expectedQuality,
        headers: {
          ...this.headers,
          Referer: this.currentUrl,
        },
        resolvedVia: 'webview-download-listener',
      };
      this.detectedCandidates.push(stream);
      this._complete(stream);
      return stream;
    }
    return null;
  }

  /**
   * Called from WebViewClient.shouldInterceptRequest(view, request).
   * Returns `{ blockRequest: boolean, stubResponse?: { mimeType, body }, resolvedStream?: object }`.
   */
  shouldInterceptRequest(url, requestHeaders = {}) {
    if (this.status !== 'pending') {
      return { blockRequest: false, resolvedStream: this.resolvedStream };
    }

    if (this.adFilterEnabled && isKnownAdHost(url)) {
      this.blockedHijacksCount++;
      return {
        blockRequest: true,
        stubResponse: getAdStubResponse(url),
        resolvedStream: null,
      };
    }

    if (isAdOrNoiseUrl(url)) {
      return { blockRequest: false, resolvedStream: null };
    }

    const match = this.inspectUrl(url, 'network-intercept', requestHeaders);
    return { blockRequest: false, resolvedStream: match };
  }

  /**
   * Called from injected WEBVIEW_INTERCEPT_SCRIPT via JavascriptInterface.
   */
  onDomMediaDetected(url, mimeType = '', quality = '') {
    if (this.status !== 'pending') return null;
    return this.inspectUrl(url, 'dom-hook', {}, mimeType, quality);
  }

  /**
   * User clicks "Play Current Video" or manually selects a detected media URL in WebView UI.
   */
  manualResolve(url, quality = this.expectedQuality) {
    const matched = matchDirectMediaHost(url);
    const stream = {
      url: matched ? matched.streamUrl : url,
      originalUrl: url,
      hostType: matched ? matched.hostType : 'manual',
      mimeType: matched ? matched.mimeType : 'video/*',
      quality: quality || this.expectedQuality,
      headers: {
        ...this.headers,
        Referer: this.currentUrl,
      },
      resolvedVia: 'webview-manual',
    };
    this._complete(stream);
    return stream;
  }

  cancel() {
    if (this.status !== 'pending') return;
    this.status = 'cancelled';
    if (this._rejectPromise) {
      this._rejectPromise(new Error('WebView resolution cancelled by user'));
    }
  }

  inspectUrl(url, source = 'unknown', requestHeaders = {}, mimeTypeHint = '', qualityHint = '') {
    const matched = matchDirectMediaHost(url);
    if (!matched) return null;

    const candidate = {
      url: matched.streamUrl,
      originalUrl: matched.originalUrl,
      hostType: matched.hostType,
      mimeType: mimeTypeHint || matched.mimeType,
      quality: qualityHint || this.expectedQuality,
      headers: {
        ...this.headers,
        Referer: this.currentUrl,
        ...requestHeaders,
      },
      resolvedVia: `webview-${source}`,
    };

    this.detectedCandidates.push(candidate);

    // Auto-handoff to native player when a direct stream or Pixeldrain/Gofile target is reached
    if (matched.isDirectStream || matched.hostType === 'pixeldrain' || matched.hostType === 'gofile') {
      this._complete(candidate);
      return candidate;
    }

    return null;
  }

  _complete(stream) {
    if (this.status !== 'pending') return;
    this.status = 'resolved';
    this.resolvedStream = stream;
    if (this._resolvePromise) {
      this._resolvePromise(stream);
    }
  }

  waitForResolution() {
    if (this.status === 'resolved') return Promise.resolve(this.resolvedStream);
    if (this.status !== 'pending') {
      return Promise.reject(new Error(`WebView session already ${this.status}`));
    }

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.status === 'pending') {
          this.status = 'timeout';
          reject(new Error(`WebView resolution timed out after ${this.timeoutMs}ms`));
        }
      }, this.timeoutMs);

      this._resolvePromise = (stream) => {
        clearTimeout(timer);
        resolve(stream);
      };
      this._rejectPromise = (err) => {
        clearTimeout(timer);
        reject(err);
      };
    });
  }
}

/**
 * Resolve a stream link:
 * 1. Direct media hosts (Pixeldrain, Gofile, .mp4/.m3u8) resolve immediately.
 * 2. Embedded players or HTTP redirects are probed with HttpClient.
 * 3. Intermediate redirectors / interactive gates launch or return a WebViewResolverSession.
 */
export async function resolveStream(streamLink, httpClient, options = {}) {
  const { interactiveHandler = null, referer = null } = options;

  // Step 1: Check if URL is already a direct media host
  const direct = matchDirectMediaHost(streamLink.url);
  if (direct && (direct.isDirectStream || direct.hostType === 'pixeldrain' || direct.hostType === 'gofile')) {
    return {
      status: 'resolved',
      method: 'direct',
      stream: {
        url: direct.streamUrl,
        originalUrl: direct.originalUrl,
        hostType: direct.hostType,
        mimeType: direct.mimeType,
        quality: streamLink.quality || 'Unknown',
        headers: httpClient.buildHeaders(direct.streamUrl, referer ? { Referer: referer } : {}),
        resolvedVia: 'direct-match',
      },
    };
  }

  // Step 2: Try probing the URL via HttpClient (captures cookies + checks direct redirect or embedded player)
  try {
    const response = await httpClient.request(streamLink.url, {
      method: 'GET',
      headers: referer ? { Referer: referer } : {},
    });

    const landedDirect = matchDirectMediaHost(response.url);
    if (landedDirect && landedDirect.isDirectStream) {
      return {
        status: 'resolved',
        method: 'http-redirect',
        stream: {
          url: landedDirect.streamUrl,
          originalUrl: landedDirect.originalUrl,
          hostType: landedDirect.hostType,
          mimeType: landedDirect.mimeType,
          quality: streamLink.quality || 'Unknown',
          headers: httpClient.buildHeaders(landedDirect.streamUrl, { Referer: streamLink.url }),
          resolvedVia: 'http-redirect',
        },
      };
    }

    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('text/html')) {
      const html = await response.text();
      const embedded = extractEmbeddedVideoSources(html, response.url);
      if (embedded.sources.length > 0) {
        const preferred =
          embedded.sources.find((s) => s.quality === streamLink.quality) || embedded.sources[0];
        return {
          status: 'resolved',
          method: 'embedded-extractor',
          stream: {
            url: preferred.url,
            originalUrl: preferred.url,
            hostType: 'embedded',
            mimeType: preferred.mimeType,
            quality: preferred.quality !== 'Unknown' ? preferred.quality : streamLink.quality || 'Unknown',
            headers: httpClient.buildHeaders(preferred.url, { Referer: response.url }),
            resolvedVia: preferred.sourceType,
          },
          allSources: embedded.sources,
        };
      }
    }
  } catch {
    // Fall through to interactive WebViewResolver
  }

  // Step 3: Hand off to interactive WebViewResolver
  const session = new WebViewResolverSession({
    initialUrl: streamLink.url,
    headers: httpClient.buildHeaders(streamLink.url, referer ? { Referer: referer } : {}),
    expectedQuality: streamLink.quality || 'Unknown',
  });

  if (typeof interactiveHandler === 'function') {
    const resolutionPromise = session.waitForResolution();
    await interactiveHandler(session);
    const resolvedStream = await resolutionPromise;
    return {
      status: 'resolved',
      method: 'webview-interactive',
      stream: resolvedStream,
    };
  }

  return {
    status: 'requires_webview',
    method: 'webview-interactive',
    session,
    injectedScript: WEBVIEW_INTERCEPT_SCRIPT,
  };
}
