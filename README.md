# Modular WordPress Anime Catalog & Stream Resolver Extension

A modular catalog, metadata parser, and interactive stream-resolution extension targeting five WordPress-based anime sites, implemented with both an **Android/Kotlin library (`android-extension/`)** and a **Node.js/JavaScript module & test suite (`js-extension/`)** backed by a shared remote configuration file (`domains.json`).

## Supported Domains

Configured dynamically via [`domains.json`](./domains.json):

| Site ID | Name | Base URL | Theme / Pattern |
| --- | --- | --- | --- |
| `lordsanime` | Lord's Anime | `https://www.lordsanime.com` | WordPress (`article`, `.entry-content`, `?s={query}`) |
| `tpxsub` | TpXAnime | `https://www.tpxsub.com` | WordPress (`article`, `.entry-content`, `?s={query}`) |
| `ybxanime` | YBX Anime | `https://ybxanime.com` | WordPress (`li`/`h2 a`, `.entry-content`, `?s={query}`) |
| `hindisubbedacademy` | Hindi Subbed Academy | `https://hindisubbedacademy.com` | WordPress / Kiranime (`?s={query}` with `/search/?keyword={query}` fallback) |
| `animevilla` | AnimeVilla | `https://animevilla.org` | WordPress / Kiranime (`?s={query}` + `/watch/` embedded player) |

## Architecture

1. **Remote Domain Config (`domains.json`)**
   - `DomainConfigManager` (Kotlin & JS) fetches `domains.json` from a remote URL with TTL caching and local fallback, allowing base URLs and search paths to be updated without recompiling.
2. **HTTP & Cookie Persistence**
   - **Kotlin (`android-extension`)**: `ExtensionHttpClient` (`OkHttpClient`) with mobile Chrome `User-Agent`, `Referer`, and `PersistentCookieJar` (`SharedPreferences` + `WebView` `CookieManager` sync).
   - **JavaScript (`js-extension`)**: `HttpClient` (`fetch`) with manual redirect tracking and `CookieJar` persistence across hops.
3. **Resilient Semantic Parsing**
   - Uses semantic selectors (`main`, `article`, `h2 a`, `h3 a`, `.entry-content`, `time`, `img` lazy-placeholder filtering) instead of brittle CSS class chains.
   - Extracts series metadata, genres, synopsis, and episode lists with normalized quality labels (`1080p`, `720p`, `480p`, `360p`) and file sizes.
4. **Interactive Stream Resolution (`WebViewResolver`)**
   - Extracts direct `<video>`, `<source>`, `<iframe>`, and HLS `.m3u8`/`.mp4` script configs on embedded player pages.
   - Detects direct media hosts (`Pixeldrain` `/u/{id}` -> `/api/file/{id}`, `Gofile`, `.mp4`, `.m3u8`).
   - Provides `WebViewResolverDialogFragment` (Kotlin) and `WebViewResolverSession` (JS) so users can complete intermediate redirect/timer steps in an interactive browser UI while `shouldInterceptRequest` + injected `WEBVIEW_INTERCEPT_SCRIPT` passively capture the final media URL for native player handoff.

## Running Tests

### JavaScript / Node.js Suite

```bash
npm install
npm test
```

### Android / Kotlin Suite (requires JDK 17 + Android SDK)

```bash
./gradlew :android-extension:testDebugUnitTest
```
