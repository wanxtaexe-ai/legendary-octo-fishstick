# Reusable Ad-Chain Resolver Architecture for Yumeiro

This document addresses the Yumeiro architecture quest: building a **dedicated, provider-agnostic, reusable chained-ad resolver** (`yumeiro-adchain`) rather than per-website bypasses, grounded in empirical protocol analysis and `siamform/yumeiro`'s existing primitives (`SafeWebView`, `AdShield`, `AdFilter`, `BrowserCheck`, `BrowserCheckHost`, `HeadlessCapture`, `StreamResolver`, `LinkHosts`, `WebDownloads`, and `OnlinePlayback`).

---

## 1. Provider Research & Redirect Chain Mechanics

### A. ShrinkMe (`shrinkme.io` / `shrinkme.click`)
Observed 3-stage chain (verified via `asirselvin446-commits/shrinkme-bypass` and live inspection):
1. **Stage 1 — Verification Anchor (`#div-human-verification`)**: Holds `data-link` / slug and `recaptchaCallback(...)`; moves to a safelink blog hop (e.g. `themezon.net/link.php?link=<slug>`).
2. **Stage 2 — WP Safelink (`themezon.net` / `newwpsafelink`)**: Two-step blog traversal:
   - Article 1: `<form name="tp">` with hidden `newwpsafelink` (Base64 JSON containing `delay`, `linkr`, `second_safelink_url`, or `newsafelink`) and `redirect_to=random`.
   - Article 2: `#nextPage a` / `#tp-snp2` destination anchor.
3. **Stage 3 — AdLinkFly CakePHP Destination (`mrproblogger.com` / `/links/go`)**:
   - Presents `form#go-link` containing `ad_form_data`, `_csrfToken`, and `_Token[fields]`, paired with a 5–15s countdown timer.
   - Submitting `POST /links/go` with `X-Requested-With: XMLHttpRequest` + session cookies returns JSON `{"status":"success","url":"<destination>"}`.

### B. SRNKY / ShrinkEarn (`srnky.com`, `tpi.li`, `oii.la`, `clksz.com`)
Observed live on `shortsync.epdexx.com/go/... -> https://srnky.com/ljpaf` and studied across `nOneCode4u/bypass-shortlinks`, `gongchandang49/bypass-all-shortlinks-debloated` (#102), and `Fyphost/Adlinkfly-Bypasser-V2`:
- **Genuinely interactive verification**: `srnky.com` renders `"Please click on below captcha box to proceed to the destination page"` backed by reCAPTCHA / Cloudflare Turnstile, plus rotating blog hops (`Continue` -> `Your Link Is Almost Ready` -> `/links/go`).
- **Critical failure mode of naive automation**: Dispatching synthetic DOM `.click()` events before/during Turnstile triggers `Event.isTrusted === false`, which `tpi.li` / Turnstile explicitly rejects—causing infinite page reload loops.
- **Exception**: When ShrinkEarn links carry a Base64-encoded `?url=` query parameter directly on the referring site, decoding it immediately avoids the shortener chain altogether.

### C. ShortXLinks / Generic AdLinkFly + Server Timers
Studied via `stav3ng3r/shortlink-bypass`:
- Replays HTTP 3xx -> `<meta http-equiv="refresh">` -> literal JS `location` assignments -> `newwpsafelink` Base64 JSON -> `form[name="tp"]` / `go` gate forms -> `/links/go` XHR POST.
- Enforces server-side timestamp checks: requesting the final step prematurely returns HTTP 200 with `"Too Early"`, requiring bounded delay + retry using the same session `CookieJar`.

### D. Token Wrappers (`links.tpxsub.com`, `?safe=`, `?shortid=`, `?url=`)
- `links.tpxsub.com/redirect/main2.php?url=...` encodes destinations offline via reversed base-36 character pairs offset by a repeating 40-char SHA-1 key (`7eea12b58ee6bd4833706e6d5a9468a865315209`).
- Other wrappers pass plain URLs or Base64 in `?url=`, `?safe=`, `?shortid=`, or `?link=`.

---

## 2. Android WebView Limitations & Mitigations

| Area | Limitation in Android `WebView` | Mitigation in `yumeiro-adchain` |
|---|---|---|
| **`shouldInterceptRequest`** | Runs on a background WebView IO thread; **does not expose POST bodies**, cannot inspect HTTP `302 Location` headers directly, and over-broad host/regex blocking breaks CAPTCHA widgets or anti-adblock checks (`gtag` bait checks). | Keep ad-blocking strictly scoped via `AdFilterPolicy`: explicitly allow `challenges.cloudflare.com`, `recaptcha.net`, `gstatic.com`, `hcaptcha.com`, and same-site `/links/go` APIs while returning empty `204` responses only to known ad/pop-under/VAST hosts. |
| **Cookie Synchronization** | Chromium `CookieManager` writes to SQLite asynchronously across IPC; OkHttp's `CookieJar` does not share cookies with WebView unless explicitly bridged, and `cf_clearance` / `_csrfToken` are bound to **both** cookie and `User-Agent`. | `AdChainWebViewRunner` seeds OkHttp cookies into `CookieManager` prior to `loadUrl`, calls `CookieManager.flush()` before reading cookies back into `InMemorySessionCookieJar` / `BrowserCheck.Clearance`, and propagates the exact `WebView` `User-Agent` to `StreamRequest`. |
| **JavaScript Injection** | `evaluateJavascript` executes *after* page scripts unless `WebViewCompat.addDocumentStartJavaScript` is available (requires `WebViewFeature.DOCUMENT_START_SCRIPT`). Globally monkeypatching `setTimeout` breaks legitimate player/CSRF logic; synthetic `.click()` fails `Event.isTrusted` on Turnstile. | Use `DOCUMENT_START_SCRIPT` only for `document.hidden = false`, `window.open` suppression, and full-screen click-catcher removal (skipping CAPTCHA iframes). Use `INSPECT_STAGE_JS` solely as a read-only DOM inspector and `fetch('/links/go')` dispatcher; **never** synthesize clicks on CAPTCHAs. |
| **Download Handling** | `WebView.setDownloadListener` fires on `Content-Disposition: attachment` without exposing active cookies or `Referer`, and `blob:` / `data:` URIs cannot be fetched by OkHttp/Media3. | Capture `url`, `userAgent`, `contentDisposition`, `mimeType`, current `web.url` (`Referer`), and `CookieManager.getCookie(url)` inside `DownloadListener` (matching `WebDownloads.requestFor`), reject `blob:`/`data:` gracefully, and validate destinations before handing off to `StreamDownloadAsk`. |

---

## 3. Reusable System Design (`yumeiro-adchain`)

Located in `yumeiro-adchain/`:

1. **`AdProviderRecognizer`** (`src/main/kotlin/.../AdProviderRecognizer.kt`)
   - Classifies pages into structural provider families (`TOKEN_WRAPPER`, `WP_SAFELINK`, `ADLINKFLY`, `COUNTDOWN_INTERSTITIAL`, `HUMAN_CHALLENGE`, `MEDIA_OR_CLOUD_HOST`, `GENERIC_HTML`) instead of per-site scripts.
2. **`AdChainHttpFastPath`** (`src/main/kotlin/.../AdChainHttpFastPath.kt`)
   - Walks up to 12 hops purely over OkHttp with an isolated `InMemorySessionCookieJar` (3xx, meta-refresh, literal JS redirects, `newwpsafelink`, `form[name=tp]`, bounded `Too Early` wait, and `/links/go` XHR POST). Zero WebView overhead when no JS/CAPTCHA gate is required.
3. **`AdFilterPolicy` & `AdChainDomScripts`**
   - Protects the WebView from pop-unders, `intent://` escapes, and transparent overlays without breaking verification iframes or first-party chain forms.
4. **`AdChainSessionCoordinator` + `AdChainWebViewRunner`** (`android-integration/AdChainWebViewRunner.kt`)
   - **Minimal-Interruption Human Verification**: When `HUMAN_CHALLENGE` (`cf-turnstile`, `g-recaptcha`, `h-captcha`, `srnky.com`) is detected, the coordinator never pretends to auto-solve it. Instead, it hands the **already-loaded, ad-shielded `WebView`** (preserving cookies and intermediate hops) to `BrowserCheckHost`, lets the user tap once, and resumes automatically once solved.
5. **`DestinationValidator`** (`src/main/kotlin/.../DestinationValidator.kt`)
   - Strips tracking wrappers (`google.com/url`, `l.facebook.com/l.php`), rejects unsafe schemes (`intent:`, `market:`, `javascript:`, `blob:`), rejects ad/parked/unresolved shortener domains, normalizes cloud file hosts (`Pixeldrain`, `GoFile`, `Drive`, `Dropbox`, `OneDrive`), and routes validated outputs cleanly into Yumeiro's `StreamResolver` -> `OnlinePlayback` (built-in player) or `WebDownloads`.

---

## 4. Integration Map into `siamform/yumeiro`

| Yumeiro File | Integration Role |
|---|---|
| `online/web/AdShield.kt` & `AdFilter.kt` | Reuse `AdFilterPolicy.decideSubresource` & `decideMainFrameNavigation` so verification hosts remain allowlisted while ad-chains stay blocked. |
| `online/web/SafeWebView.kt` | Reuse `SafeWebView.harden` + `AdChainDomScripts.DOCUMENT_START_JS`. |
| `ui/screens/online/BrowserCheckHost.kt` | Accept a suspended live `WebView` session from `AdChainWebViewRunner` for `HUMAN_CHALLENGE` states (`Manual` mode), resuming chain resolution upon clearance instead of discarding intermediate progress. |
| `stream/StreamResolver.kt` & `LinkHosts.kt` | Add `AdChainSessionCoordinator` before falling back to `Resolution.NeedsBrowser` when `WebPageExtractor` sees a shortener/ad-gate pattern. |
| `online/web/WebDownloads.kt` & `OnlinePlayback.kt` | Route validated `DirectMedia` / `CloudFileHost` results with synced `cookies`, `referer`, and `userAgent` straight to `OnlinePlayback.request(...)` or `StreamDownloadAsk.ask(...)`. |
