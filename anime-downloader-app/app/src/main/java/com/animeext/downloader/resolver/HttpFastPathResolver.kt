package com.animeext.downloader.resolver

import com.animeext.downloader.model.ResolvedTarget
import com.animeext.downloader.network.NetworkClient
import com.animeext.downloader.parser.Base64Codec
import com.animeext.downloader.parser.SemanticParser
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed class FastPathResult {
    data class Resolved(val targets: List<ResolvedTarget>, val trace: List<String>) : FastPathResult()
    data class NeedsHardenedWebView(
        val gateUrl: String,
        val referer: String?,
        val trace: List<String>,
        val reason: String,
        val sourceLinkTarget: String? = null,
        val sourceLinkIndex: Int = -1
    ) : FastPathResult()
    data class Failed(val reason: String, val trace: List<String>) : FastPathResult()
}

class HttpFastPathResolver(private val network: NetworkClient) {

    companion object {
        private const val TPX_REDIRECT_KEY = "7eea12b58ee6bd4833706e6d5a9468a865315209"
        private const val HSA_AES_KEY = "kiemtienmua911ca"
        private const val MAX_HOPS = 8

        private val deadHosts = listOf(
            "asurasever.cloud",
            "hindidubbedanime.com"
        )

        private val challengeMarkers = listOf(
            "g-recaptcha",
            "cf-turnstile",
            "h-captcha",
            "challenges.cloudflare.com/turnstile",
            "please click on below captcha box",
            "adcopy_challenge"
        )

        private val interstitialMarkers = listOf(
            "please wait",
            "just a moment",
            "redirecting",
            "verifying",
            "safelink",
            "too early",
            "almost ready"
        )

        private val interactiveGateHints = listOf(
            "hsalinks.",
            "shortsync.epdexx.com",
            "srnky.com",
            "shrinkearn.com",
            "7k29m4n8q",
            "shrinkme.",
            "tpi.li",
            "oii.la",
            "clksz.com"
        )

        fun decodeTpxRedirectUrl(url: String): String? {
            if (!url.contains("links.tpxsub.com", ignoreCase = true) && !url.contains("main2.php", ignoreCase = true)) {
                return null
            }
            val rawParam = Regex("[?&]url=([^&#]+)").find(url)?.groupValues?.get(1) ?: return null
            val encoded = runCatching { URLDecoder.decode(rawParam, "UTF-8") }.getOrDefault(rawParam).trim()
            if (encoded.length < 16 || encoded.length % 2 != 0) return null

            val sb = StringBuilder(encoded.length / 2)
            for (idx in 0 until encoded.length / 2) {
                val pair = encoded.substring(idx * 2, idx * 2 + 2)
                val reversed = "${pair[1]}${pair[0]}"
                val num = reversed.toIntOrNull(36) ?: return null
                val ch = num - TPX_REDIRECT_KEY[idx % TPX_REDIRECT_KEY.length].code
                if (ch < 32 || ch > 126) return null
                sb.append(ch.toChar())
            }
            val decoded = sb.toString()
            return if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        }

        fun unwrapTokenUrl(url: String): String? {
            decodeTpxRedirectUrl(url)?.let { return it }
            val query = url.substringAfter('?', "")
            if (query.isBlank()) return null

            val paramNames = listOf("url", "safe", "shortid", "link", "target", "dest", "id")
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=').lowercase()
                if (key !in paramNames) continue
                val raw = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                    .getOrDefault(pair.substringAfter('=', "")).trim()
                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    return raw
                }
                if (raw.length >= 12) {
                    Base64Codec.decodeToUtf8(raw)?.trim()?.let { decoded ->
                        if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded
                    }
                }
            }
            return null
        }

        fun normalizePixeldrainUrl(url: String): String? {
            val m = Regex("https?://(?:www\\.)?pixeldrain\\.(?:com|net|dev|in)/(?:u|api/file)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
                .find(url) ?: return null
            return "https://pixeldrain.com/api/file/${m.groupValues[1]}"
        }

        fun normalizeTpxPlayerUrl(url: String): String {
            if (!url.contains("tpxbeta.", ignoreCase = true)) return url
            val vid = Regex("[?&]v=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
                ?: Regex("#([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
                ?: return url
            return "https://beta.tpxsub.com/server3/player.php?v=$vid"
        }

        fun isKnownDeadUrl(url: String): Boolean {
            val lower = url.lowercase()
            return deadHosts.any { lower.contains(it) }
        }
    }

    suspend fun resolve(
        startUrl: String,
        initialReferer: String?,
        wantDownload: Boolean = true,
        sourceLinkIndex: Int = -1
    ): FastPathResult {
        val trace = mutableListOf<String>()
        val candidateStart = DownloadAndSchemeGuard.unwrapIntentUrl(startUrl) ?: startUrl
        val safety = DownloadAndSchemeGuard.inspectUrl(candidateStart)
        if (safety != DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS) {
            trace.add("Blocked unsafe URL scheme or executable: $startUrl")
            return FastPathResult.Failed("Blocked unsafe URL", trace)
        }

        val unwrapped = unwrapTokenUrl(candidateStart) ?: candidateStart
        if (unwrapped != candidateStart) {
            trace.add("Decoded wrapper -> ${summarizeUrl(unwrapped)}")
        }
        val normalizedStart = normalizeTpxPlayerUrl(unwrapped)
        if (isKnownDeadUrl(normalizedStart)) {
            trace.add("Known dead/parked host: ${summarizeUrl(normalizedStart)}")
            return FastPathResult.Failed("Link host is dead or parked", trace)
        }

        // Direct media or archive URL
        if (DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(normalizedStart)) {
            trace.add("Direct media/archive URL ready")
            return FastPathResult.Resolved(
                listOf(buildResolvedTarget(normalizedStart, initialReferer, trace = trace)),
                trace
            )
        }

        // LordsAnime puts raw las.epdexx.com links in HTML and rewrites them client-side to
        // shortsync.epdexx.com/go/...; opening las.epdexx.com directly triggers error.epdexx.com.
        if (normalizedStart.contains("epdexx.com/", ignoreCase = true) &&
            !normalizedStart.contains("shortsync.", ignoreCase = true) &&
            !initialReferer.isNullOrBlank() &&
            initialReferer.contains("lordsanime.", ignoreCase = true)
        ) {
            trace.add("Loading LordsAnime post so its shortener script wraps epdexx link...")
            return FastPathResult.NeedsHardenedWebView(
                gateUrl = initialReferer,
                referer = initialReferer,
                trace = trace,
                reason = "epdexx_needs_shortener_wrap",
                sourceLinkTarget = normalizedStart,
                sourceLinkIndex = sourceLinkIndex
            )
        }

        // Direct host extractors
        extractFromKnownHost(normalizedStart, initialReferer, trace, wantDownload)?.let { return it }

        // Interactive shorteners/gates must execute directly inside the browser sandbox so
        // referrer headers, cookies, and anti-bypass checks remain intact end-to-end.
        if (interactiveGateHints.any { normalizedStart.lowercase().contains(it) }) {
            trace.add("Interactive shortener gate -> opening hardened sandbox directly")
            return FastPathResult.NeedsHardenedWebView(
                normalizedStart,
                initialReferer,
                trace,
                "interactive_gate_direct"
            )
        }

        // Multi-hop HTTP ad-chain traversal
        trace.add("Tracing HTTP chain: ${summarizeUrl(normalizedStart)}")
        val chainResult = traverseHttpChain(normalizedStart, initialReferer ?: normalizedStart, trace)
        if (chainResult != null) {
            return chainResult
        }

        return FastPathResult.Failed("No downloadable media found", trace)
    }

    private suspend fun extractFromKnownHost(
        url: String,
        referer: String?,
        trace: MutableList<String>,
        wantDownload: Boolean
    ): FastPathResult? {
        val lower = url.lowercase()

        // 1. Pixeldrain
        normalizePixeldrainUrl(url)?.let { pdApi ->
            trace.add("Pixeldrain API normalized -> $pdApi")
            return FastPathResult.Resolved(
                listOf(buildResolvedTarget(pdApi, url, fileNameHint = null, trace = trace)),
                trace
            )
        }

        // 1b. Mega.nz (pure-HTTP API + AES-128-CTR decryption; never open in WebView)
        if (lower.contains("mega.nz/") || lower.contains("mega.co.nz/")) {
            trace.add("Querying Mega.nz API...")
            return resolveMegaNz(url, trace)
        }

        // 2. Mirrored.to
        if (lower.contains("mirrored.to/files/")) {
            trace.add("Resolving Mirrored.to mirrors...")
            val mirroredTargets = resolveMirroredTo(url, trace, wantDownload)
            if (mirroredTargets.isNotEmpty()) {
                return FastPathResult.Resolved(mirroredTargets, trace)
            }
        }

        // 3. StreamTape
        if (lower.contains("streamtape.") || lower.contains("shavetape.") || lower.contains("watchadsontape.")) {
            trace.add("Extracting StreamTape video link...")
            val st = resolveStreamTape(url, referer, trace)
            if (st != null) {
                return FastPathResult.Resolved(listOf(st), trace)
            }
        }

        // 3b. KrakenFiles
        if (lower.contains("krakenfiles.com/")) {
            trace.add("Extracting KrakenFiles link...")
            val kf = resolveKrakenFiles(url, referer, trace)
            if (kf != null) {
                return FastPathResult.Resolved(listOf(kf), trace)
            }
        }

        // 4. GoFile
        if (lower.contains("gofile.io/d/")) {
            trace.add("Querying GoFile API...")
            val gf = resolveGoFile(url, trace)
            if (gf.isNotEmpty()) {
                return FastPathResult.Resolved(gf, trace)
            }
        }

        // 5. HSAStream / P2P
        if (extractHsaStreamId(url) != null) {
            trace.add("Decrypting HSAStream/P2P player...")
            val host = runCatching { URI(referer ?: url).host }.getOrNull() ?: "animevilla.org"
            val hsa = resolveHsaStream(url, host, trace)
            if (hsa.isNotEmpty()) {
                return FastPathResult.Resolved(hsa, trace)
            }
        }

        // 6. AbyssPlayer
        if (isAbyssHost(url)) {
            trace.add("Decrypting AbyssPlayer AES-CTR...")
            val abyss = resolveAbyssPlayer(url, referer ?: "https://abysscdn.com/", trace)
            if (abyss.isNotEmpty()) {
                return FastPathResult.Resolved(abyss, trace)
            }
        }

        // 7. TpX intermediate player
        if (lower.contains("beta.tpxsub.com/server3/player") || lower.contains("tpxbeta.")) {
            trace.add("Inspecting TpX player page...")
            val tpxTargets = resolveTpxPlayerPage(url, referer, trace, wantDownload)
            if (tpxTargets.isNotEmpty()) {
                return FastPathResult.Resolved(tpxTargets, trace)
            }
        }

        // 7b. GDIndex (/0:/) directory or file (used by LordsAnime LAS links)
        if (lower.contains("/0:/") || lower.contains("/1:/")) {
            trace.add("Querying Drive Index (/0:/)...")
            val gdTargets = resolveGdIndex(url, referer, trace)
            if (gdTargets.isNotEmpty()) {
                return FastPathResult.Resolved(gdTargets, trace)
            }
        }

        // 7c. HubCloud (/drive/... or hubcdn.fans)
        if (DownloadAndSchemeGuard.isHubCloudHost(url)) {
            trace.add("Extracting HubCloud mirrors...")
            val hcTargets = resolveHubCloud(url, referer, trace)
            if (hcTargets.isNotEmpty()) {
                return FastPathResult.Resolved(hcTargets, trace)
            }
        }

        // 8. Kiranime /watch/ page
        if (lower.contains("/watch/")) {
            trace.add("Inspecting watch page embeds...")
            val resp = runCatching { network.get(url, referer = referer) }.getOrNull()
            if (resp != null) {
                val embedded = SemanticParser.extractEmbeddedMedia(resp.body, resp.url)
                val out = mutableListOf<ResolvedTarget>()
                embedded.directMediaUrls.forEach { m ->
                    out.add(buildResolvedTarget(m, resp.url, trace = trace))
                }
                for (iframe in embedded.iframeUrls) {
                    val sub = extractFromKnownHost(iframe, resp.url, trace, wantDownload)
                    if (sub is FastPathResult.Resolved) {
                        out.addAll(sub.targets)
                    }
                }
                if (out.isNotEmpty()) return FastPathResult.Resolved(out, trace)
            }
        }

        return null
    }

    private suspend fun traverseHttpChain(
        startUrl: String,
        initialReferer: String,
        trace: MutableList<String>
    ): FastPathResult? {
        val visited = HashSet<String>()
        var method = "GET"
        var currentUrl = startUrl
        var referer = initialReferer
        var postFields: Map<String, String>? = null
        var ajax = false
        var lastDelaySec = 0
        var retries = 0

        for (hop in 1..MAX_HOPS) {
            val visitKey = "$method|$currentUrl|${postFields?.hashCode() ?: 0}"
            if (!visited.add(visitKey)) {
                trace.add("Redirect loop detected; escalating to hardened sandbox")
                return FastPathResult.NeedsHardenedWebView(currentUrl, referer, trace, "redirect_loop")
            }

            val resp = runCatching {
                if (method == "POST") {
                    val headers = if (ajax) {
                        val uri = URI(currentUrl)
                        mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Origin" to "${uri.scheme}://${uri.authority}"
                        )
                    } else {
                        emptyMap()
                    }
                    network.postForm(currentUrl, postFields ?: emptyMap(), referer = referer, headers = headers)
                } else {
                    network.get(currentUrl, referer = referer)
                }
            }.getOrNull() ?: run {
                trace.add("HTTP request failed at hop $hop; escalating to sandbox")
                return FastPathResult.NeedsHardenedWebView(currentUrl, referer, trace, "http_error")
            }

            val landed = resp.url
            val effectiveReferer = if (landed != currentUrl) currentUrl else referer
            if (isKnownDeadUrl(landed)) {
                trace.add("Gate redirected to parked/dead host: ${summarizeUrl(landed)}")
                return FastPathResult.Failed("Shortener gate landed on dead host", trace)
            }
            if (interactiveGateHints.any { landed.lowercase().contains(it) }) {
                trace.add("Landed on interactive shortener -> opening hardened sandbox")
                return FastPathResult.NeedsHardenedWebView(landed, effectiveReferer, trace, "interactive_gate_hop")
            }

            val contentType = resp.headers["content-type"].orEmpty().lowercase()
            if (contentType.startsWith("video/") || contentType.contains("application/zip") ||
                DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(landed, contentType)
            ) {
                trace.add("Reached downloadable media at hop $hop")
                return FastPathResult.Resolved(listOf(buildResolvedTarget(landed, referer, trace = trace)), trace)
            }

            unwrapTokenUrl(landed)?.let { unwrapped ->
                if (unwrapped != landed) {
                    trace.add("Unwrapped hop $hop -> ${summarizeUrl(unwrapped)}")
                    extractFromKnownHost(unwrapped, landed, trace, true)?.let { return it }
                    if (DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(unwrapped)) {
                        return FastPathResult.Resolved(listOf(buildResolvedTarget(unwrapped, landed, trace = trace)), trace)
                    }
                }
            }

            extractFromKnownHost(landed, effectiveReferer, trace, true)?.let { return it }

            val body = resp.body
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{")) {
                val jsonUrl = runCatching {
                    val obj = JSONObject(trimmed)
                    listOf("url", "link", "destination", "target", "redirect")
                        .firstNotNullOfOrNull { k -> obj.optString(k, "").takeIf { it.startsWith("http") } }
                }.getOrNull()
                if (jsonUrl != null) {
                    trace.add("JSON endpoint yielded -> ${summarizeUrl(jsonUrl)}")
                    extractFromKnownHost(jsonUrl, landed, trace, true)?.let { return it }
                    return FastPathResult.Resolved(listOf(buildResolvedTarget(jsonUrl, landed, trace = trace)), trace)
                }
            }

            val lower = body.lowercase()
            val isCfIuam = resp.code in setOf(403, 503) &&
                (lower.contains("just a moment") || lower.contains("cf-chl-") || lower.contains("/cdn-cgi/challenge-platform/"))
            val challenge = challengeMarkers.firstOrNull { lower.contains(it) }
                ?: if (isCfIuam) "cloudflare_challenge" else null
            if (challenge != null) {
                trace.add("Human challenge ($challenge) -> opening hardened gate sandbox")
                return FastPathResult.NeedsHardenedWebView(landed, effectiveReferer, trace, "human_challenge:$challenge")
            }

            if (lower.contains("too early") && retries < 1 && lastDelaySec in 1..15) {
                retries++
                visited.remove(visitKey)
                trace.add("Waiting ${lastDelaySec + 1}s for gate timer...")
                delay((lastDelaySec + 1) * 1000L)
                method = "GET"
                currentUrl = landed
                postFields = null
                ajax = false
                continue
            }

            val doc = Jsoup.parse(body, landed)
            var nextActionSet = false
            for (form in doc.select("form")) {
                val inputs = LinkedHashMap<String, String>()
                for (inp in form.select("input[name]")) {
                    val type = inp.attr("type").lowercase()
                    if (type in setOf("submit", "button", "image", "reset", "file")) continue
                    inputs[inp.attr("name")] = inp.attr("value")
                }

                val safeB64 = inputs["newwpsafelink"]
                if (!safeB64.isNullOrBlank()) {
                    val safeJson = Base64Codec.decodeToUtf8(safeB64)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (safeJson != null) {
                        val d = safeJson.optInt("delay", 0)
                        if (d > 0) lastDelaySec = maxOf(lastDelaySec, d)
                        val target = listOf("linkr", "second_safelink_url", "newsafelink")
                            .firstNotNullOfOrNull { k -> safeJson.optString(k, "").takeIf { it.startsWith("http") } }
                        if (target != null) {
                            trace.add("Decoded WP Safelink payload -> ${summarizeUrl(target)}")
                            method = "GET"
                            currentUrl = target
                            referer = landed
                            postFields = null
                            ajax = false
                            nextActionSet = true
                            break
                        }
                    }
                }

                val formAction = SemanticParser.resolveUrl(form.attr("action").ifBlank { landed }, landed) ?: landed
                val formMethod = form.attr("method").ifBlank { "get" }.uppercase()
                if (inputs.containsKey("ad_form_data") || form.id() == "go-link" || formAction.trimEnd('/').endsWith("/links/go")) {
                    trace.add("Submitting AdLinkFly /links/go form...")
                    method = "POST"
                    currentUrl = formAction
                    referer = landed
                    postFields = inputs
                    ajax = true
                    nextActionSet = true
                    break
                }

                if (form.attr("name") == "tp" || inputs.containsKey("go")) {
                    trace.add("Submitting safelink form...")
                    if (formMethod == "POST") {
                        method = "POST"
                        currentUrl = formAction
                        referer = landed
                        postFields = inputs
                        ajax = false
                    } else {
                        val qs = inputs.entries.joinToString("&") { (k, v) ->
                            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                        }
                        val sep = if (formAction.contains("?")) "&" else "?"
                        method = "GET"
                        currentUrl = if (qs.isNotBlank()) "$formAction$sep$qs" else formAction
                        referer = landed
                        postFields = null
                        ajax = false
                    }
                    nextActionSet = true
                    break
                }
            }
            if (nextActionSet) continue

            val metaTarget = doc.selectFirst("meta[http-equiv~=refresh i]")
                ?.attr("content")
                ?.let { Regex("url\\s*=\\s*['\"]?([^'\";\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?.let { SemanticParser.resolveUrl(it, landed) }
            if (!metaTarget.isNullOrBlank() && metaTarget != landed) {
                trace.add("Following meta refresh -> ${summarizeUrl(metaTarget)}")
                method = "GET"
                currentUrl = metaTarget
                referer = landed
                postFields = null
                ajax = false
                continue
            }

            val jsTarget = Regex(
                "(?:window|document|top)\\.location(?:\\.href)?\\s*=\\s*['\"](https?://[^'\"]+)['\"]",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)?.replace("\\/", "/")
            if (!jsTarget.isNullOrBlank() && jsTarget != landed) {
                trace.add("Following JS redirect -> ${summarizeUrl(jsTarget)}")
                method = "GET"
                currentUrl = jsTarget
                referer = landed
                postFields = null
                ajax = false
                continue
            }

            if (interstitialMarkers.any { lower.contains(it) }) {
                val nextHref = doc.select("#nextPage a, a.get-link, #btn-get-link, #get-link-btn, a[href]")
                    .firstOrNull { a ->
                        val href = a.attr("href").trim()
                        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", true)) return@firstOrNull false
                        val idOrClass = "${a.id()} ${a.className()}".lowercase()
                        val text = a.text().trim().lowercase()
                        idOrClass.contains("get-link") || idOrClass.contains("tp-snp2") ||
                            text in setOf("continue", "proceed", "get link", "go to link", "click here to continue")
                    }
                    ?.let { SemanticParser.resolveUrl(it.attr("href"), landed) }
                if (!nextHref.isNullOrBlank() && nextHref != landed) {
                    trace.add("Advancing gate anchor -> ${summarizeUrl(nextHref)}")
                    method = "GET"
                    currentUrl = nextHref
                    referer = landed
                    postFields = null
                    ajax = false
                    continue
                }
            }

            if (interactiveGateHints.any { landed.lowercase().contains(it) } ||
                lower.contains("countdown") || lower.contains("timer") || lower.contains("cloudflare")
            ) {
                trace.add("Interactive JS gate detected -> opening hardened sandbox")
                return FastPathResult.NeedsHardenedWebView(landed, effectiveReferer, trace, "interactive_js_gate")
            }

            // Last check: maybe landed page itself is an unknown HTML gate or download page
            trace.add("Page needs interactive browser evaluation -> opening hardened sandbox")
            return FastPathResult.NeedsHardenedWebView(landed, effectiveReferer, trace, "unrecognized_gate")
        }

        return FastPathResult.NeedsHardenedWebView(currentUrl, referer, trace, "max_hops")
    }

    private suspend fun resolveMirroredTo(
        mirroredUrl: String,
        trace: MutableList<String>,
        wantDownload: Boolean
    ): List<ResolvedTarget> {
        val fileId = Regex("mirrored\\.to/files/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(mirroredUrl)?.groupValues?.get(1) ?: return emptyList()
        val basePageUrl = "https://www.mirrored.to/files/$fileId/"
        val firstResp = runCatching { network.get(basePageUrl) }.getOrNull() ?: return emptyList()
        val firstDoc = Jsoup.parse(firstResp.body, basePageUrl)
        val dlListPath = firstDoc.selectFirst("a[href*=\"hash=\"]")?.attr("href")
            ?: firstDoc.selectFirst("a[href*=\"dl=0\"]")?.attr("href")
        val mirrorsUrl = SemanticParser.resolveUrl(dlListPath, basePageUrl) ?: basePageUrl
        val mirrorsHtml = if (mirrorsUrl == basePageUrl) {
            firstResp.body
        } else {
            runCatching { network.get(mirrorsUrl, referer = basePageUrl).body }.getOrNull().orEmpty()
        }
        if (mirrorsHtml.isBlank()) return emptyList()

        val priority = listOf("pixeldrain", "streamtape", "gofile", "krakenfiles", "mediafire", "usersdrive")
        val mirrorsDoc = Jsoup.parse(mirrorsHtml, mirrorsUrl)
        val ranked = mutableListOf<Pair<Int, String>>()
        for (row in mirrorsDoc.select("tr")) {
            val hint = "${row.text()} ${row.html()}".lowercase()
            val link = row.selectFirst("a[href*=/getlink/]") ?: continue
            val resolved = SemanticParser.resolveUrl(link.attr("href"), mirrorsUrl) ?: continue
            val rank = priority.indexOfFirst { hint.contains(it) }
            ranked.add((if (rank >= 0) rank else 99) to resolved)
        }
        ranked.sortBy { it.first }

        val results = mutableListOf<ResolvedTarget>()
        for ((_, getLinkUrl) in ranked.take(5)) {
            val finalHtml = runCatching { network.get(getLinkUrl, referer = mirrorsUrl).body }.getOrNull().orEmpty()
            if (finalHtml.isBlank()) continue
            val finalDoc = Jsoup.parse(finalHtml, getLinkUrl)
            val candidates = linkedSetOf<String>()
            finalDoc.select("code").map { it.text().trim() }.filter { it.startsWith("http") }.forEach { candidates.add(it) }
            finalDoc.select("a[href]").mapNotNull { SemanticParser.resolveUrl(it.attr("href"), getLinkUrl) }
                .filter { u -> !u.contains("mirrored.to", true) && priority.any { u.lowercase().contains(it) } }
                .forEach { candidates.add(it) }

            for (target in candidates) {
                normalizePixeldrainUrl(target)?.let { pd ->
                    trace.add("Mirrored -> Pixeldrain: $pd")
                    results.add(buildResolvedTarget(pd, target, trace = trace))
                } ?: run {
                    if (target.contains("streamtape.", true)) {
                        resolveStreamTape(target, getLinkUrl, trace)?.let { results.add(it) }
                    } else if (target.contains("krakenfiles.", true)) {
                        resolveKrakenFiles(target, getLinkUrl, trace)?.let { results.add(it) }
                    } else if (target.contains("gofile.io/d/", true)) {
                        results.addAll(resolveGoFile(target, trace))
                    } else if (wantDownload && DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(target)) {
                        results.add(buildResolvedTarget(target, getLinkUrl, trace = trace))
                    }
                }
            }
            if (results.isNotEmpty()) break
        }
        return results
    }

    private suspend fun resolveStreamTape(
        url: String,
        referer: String?,
        trace: MutableList<String>
    ): ResolvedTarget? {
        val vid = Regex("/[ve]/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: return null
        val embedUrl = "https://streamtape.com/e/$vid"
        val html = runCatching { network.get(embedUrl, referer = referer).body }.getOrNull().orEmpty()
        if (html.isBlank()) return null

        val robotLine = Regex("""getElementById\(['"](?:ro)?botlink['"]\)\.innerHTML\s*=\s*([^;\n]+)""")
            .find(html)?.groupValues?.get(1)
        val direct = if (robotLine != null) {
            evalStreamTapeExpr(robotLine)
        } else {
            val robotMatch = Regex("""(?:ro)?botlink['"]?\s*[=:]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            val tokenMatch = Regex("""token\s*[=:]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!robotMatch.isNullOrBlank() && !tokenMatch.isNullOrBlank()) "$robotMatch&token=$tokenMatch" else null
        } ?: return null

        val streamUrl = when {
            direct.startsWith("//") -> "https:$direct"
            direct.startsWith("http") -> direct
            else -> "https://$direct"
        }.let { if (it.contains("stream=1")) it else "$it&stream=1" }

        trace.add("StreamTape resolved -> ${summarizeUrl(streamUrl)}")
        return buildResolvedTarget(streamUrl, embedUrl, fileNameHint = "streamtape_$vid.mp4", trace = trace)
    }

    private fun evalStreamTapeExpr(expr: String): String? {
        val sb = StringBuilder()
        val parts = expr.split('+')
        for (part in parts) {
            val p = part.trim()
            val literal = Regex("""^['"]([^'"]+)['"]$""").find(p)?.groupValues?.get(1)
            if (literal != null) {
                sb.append(literal)
                continue
            }
            val subMatch = Regex("""^\(?['"]([^'"]+)['"]\)?((?:\.substring\(\d+\))+)$""").find(p)
            if (subMatch != null) {
                var str = subMatch.groupValues[1]
                Regex("""\.substring\((\d+)\)""").findAll(subMatch.groupValues[2]).forEach { m ->
                    val offset = m.groupValues[1].toIntOrNull() ?: 0
                    str = if (offset in 0..str.length) str.substring(offset) else ""
                }
                sb.append(str)
            }
        }
        val out = sb.toString().trim()
        return out.takeIf { it.contains("streamtape", true) || it.contains("get_video", true) }
    }

    private suspend fun resolveGoFile(url: String, trace: MutableList<String>): List<ResolvedTarget> {
        val contentId = Regex("gofile\\.io/d/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return emptyList()
        val acctResp = runCatching { network.postForm("https://api.gofile.io/accounts", emptyMap()) }.getOrNull() ?: return emptyList()
        val token = runCatching { JSONObject(acctResp.body).optJSONObject("data")?.optString("token", "") }
            .getOrNull().orEmpty()
        if (token.isBlank()) return emptyList()

        val contentsResp = runCatching {
            network.get(
                "https://api.gofile.io/contents/$contentId?wt=4fd6sg89d7s6",
                headers = mapOf("Authorization" to "Bearer $token")
            )
        }.getOrNull() ?: return emptyList()

        val children = runCatching {
            JSONObject(contentsResp.body).optJSONObject("data")?.optJSONObject("children")
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<ResolvedTarget>()
        val keys = children.keys()
        while (keys.hasNext()) {
            val child = children.optJSONObject(keys.next()) ?: continue
            val link = child.optString("link", "")
            val name = child.optString("name", "").ifBlank { null }
            if (link.startsWith("http") && DownloadAndSchemeGuard.inspectUrl(link) == DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS) {
                trace.add("GoFile file -> ${name ?: summarizeUrl(link)}")
                out.add(
                    buildResolvedTarget(
                        url = link,
                        referer = "https://gofile.io/",
                        extraHeaders = mapOf("Authorization" to "Bearer $token", "Cookie" to "accountToken=$token"),
                        fileNameHint = name,
                        trace = trace
                    )
                )
            }
        }
        return out
    }

    private fun extractHsaStreamId(url: String): String? {
        val lower = url.lowercase()
        val matchHost = listOf("hsastream.", "rpmhub.", "rpmstream.", "rpmshare.", "upns.", "uns.bio", "p2pplay.", "vidstack.", "strp2p.")
            .any { lower.contains(it) }
        if (!matchHost) return null
        return Regex("#([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: Regex("/e/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: Regex("[?&](?:id|v|t)=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
    }

    private suspend fun resolveHsaStream(embedUrl: String, refererHost: String, trace: MutableList<String>): List<ResolvedTarget> {
        val videoId = extractHsaStreamId(embedUrl) ?: return emptyList()
        val uri = runCatching { URI(embedUrl) }.getOrNull()
        val host = uri?.host ?: "hsastream.com"
        val scheme = uri?.scheme ?: "https"
        val ref = "$scheme://$host/"
        val reqHeaders = mapOf("Origin" to "$scheme://$host", "Referer" to embedUrl)
        val endpoints = listOf(
            "$scheme://$host/api/v1/video?id=$videoId&w=1920&h=1080&r=$refererHost",
            "$scheme://$host/api/v1/info?id=$videoId&w=1920&h=1080&r=$refererHost"
        )

        val out = mutableListOf<ResolvedTarget>()
        val seen = hashSetOf<String>()
        for (apiUrl in endpoints) {
            val body = runCatching { network.get(apiUrl, referer = ref, headers = reqHeaders).body.trim() }.getOrNull().orEmpty()
            if (body.isBlank()) continue
            val candidates = decodeP2pPayload(body, videoId)
            for (dec in candidates) {
                for (key in listOf("source", "cfNative", "hls", "cfStream", "ggStream", "httpStream", "videoSource", "cf")) {
                    val m = Regex("\"$key\"\\s*:\\s*\"(https?://[^\"]+)\"").find(dec) ?: continue
                    val streamUrl = m.groupValues[1]
                    if ((key == "cf" || key == "cfNative") && streamUrl.endsWith(".txt", true)) continue
                    if (seen.add(streamUrl)) {
                        trace.add("HSAStream ($key) -> ${summarizeUrl(streamUrl)}")
                        out.add(buildResolvedTarget(streamUrl, ref, trace = trace))
                    }
                }
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    private fun decodeP2pPayload(rawBody: String, videoId: String): List<String> {
        if (rawBody.startsWith("{")) return listOf(rawBody.replace("\\/", "/"))
        val cleaned = rawBody.replace(Regex("[^0-9a-fA-F]"), "")
        if (cleaned.length < 32 || cleaned.length % 2 != 0) return emptyList()
        val bytes = ByteArray(cleaned.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val ivs = listOf(
            deriveHsaStreamIv(videoId),
            "1234567890oiuytr".toByteArray(StandardCharsets.UTF_8),
            "0123456789abcdef".toByteArray(StandardCharsets.UTF_8)
        )
        return ivs.mapNotNull { iv ->
            runCatching {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(HSA_AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES"), IvParameterSpec(iv.copyOf(16)))
                String(cipher.doFinal(bytes), StandardCharsets.UTF_8).replace("\\/", "/")
            }.getOrNull()?.takeIf { it.contains("http") || it.contains("\"source\"") }
        }
    }

    private fun deriveHsaStreamIv(videoId: String): ByteArray {
        val o = "https:"
        val d = "$o//"
        val t = o.length * d.length
        val sb = StringBuilder()
        for (z in 1..9) sb.append((z + t).toChar())
        val h = 3 * (videoId.firstOrNull()?.code ?: 0)
        val le = 111 + o.length
        val l = le + 4
        val x = o[1].code
        val te = x - 2
        sb.append(t.toChar()).append(111.toChar()).append(h.toChar())
            .append(le.toChar()).append(l.toChar()).append(x.toChar()).append(te.toChar())
        return sb.toString().toByteArray(StandardCharsets.ISO_8859_1).copyOf(16)
    }

    private fun isAbyssHost(url: String): Boolean {
        val lower = url.lowercase()
        return listOf("abyssplayer.", "abysssplayer.", "abysscdn.", "playhydrax.", "hydraxcdn.", "embedplayabyss.", "abyss.to", "short.icu")
            .any { lower.contains(it) }
    }

    private suspend fun resolveAbyssPlayer(embedUrl: String, referer: String, trace: MutableList<String>): List<ResolvedTarget> {
        val vid = Regex("[?&]v=([A-Za-z0-9_-]+)").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("abyss(?:s?player)?\\.(?:com|to)/([A-Za-z0-9_-]{5,})", RegexOption.IGNORE_CASE)
                .find(embedUrl)?.groupValues?.get(1)
        val candidates = buildList {
            if (!vid.isNullOrBlank()) add("https://abysscdn.com/?v=$vid" to "https://abysscdn.com/")
            add(embedUrl to referer)
        }
        val out = mutableListOf<ResolvedTarget>()
        for ((targetUrl, ref) in candidates) {
            val html = runCatching {
                network.get(targetUrl, referer = ref, headers = mapOf("Origin" to "https://playhydrax.com")).body
            }.getOrNull().orEmpty()
            if (html.isBlank()) continue

            Regex("https?://[^\\s\"'<>\\\\]+\\.m3u8[^\\s\"'<>\\\\]*", RegexOption.IGNORE_CASE)
                .find(html)?.value?.let { m3u8 ->
                    out.add(buildResolvedTarget(m3u8, ref, trace = trace))
                    return out
                }

            val encryptedDatas = Regex("(?:const|var)\\s+datas\\s*=\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1) ?: continue
            val metadata = decryptAbyssOffline(encryptedDatas) ?: decryptAbyssOnlineFallback(encryptedDatas) ?: continue
            val sourcesArr = metadata.optJSONObject("mp4")?.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val src = sourcesArr.optJSONObject(i) ?: continue
                    val directFile = src.optString("file", "").replace("\\/", "/")
                    val base = src.optString("url", "").trimEnd('/')
                    val path = src.optString("path", "").trimStart('/')
                    val streamUrl = when {
                        directFile.startsWith("http") -> directFile
                        base.startsWith("http") && path.isNotBlank() -> "$base/$path".replace("\\/", "/")
                        else -> ""
                    }
                    if (streamUrl.startsWith("http")) {
                        trace.add("Abyss MP4 -> ${summarizeUrl(streamUrl)}")
                        out.add(buildResolvedTarget(streamUrl, "https://abysscdn.com/", trace = trace))
                    }
                }
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    private fun decryptAbyssOffline(encodedDatas: String): JSONObject? {
        return runCatching {
            val decodedBytes = Base64Codec.decodeToBytes(encodedDatas) ?: return null
            val decodedJson = JSONObject(String(decodedBytes, StandardCharsets.ISO_8859_1))
            val userId = decodedJson.optString("user_id", "")
            val slug = decodedJson.optString("slug", "")
            val md5Id = decodedJson.optString("md5_id", "")
            val mediaStr = decodedJson.optString("media", "")
            if (slug.isBlank() || mediaStr.isBlank()) return null

            val seed = "$userId:$slug:$md5Id"
            val md5Hex = MessageDigest.getInstance("MD5").digest(seed.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val keyBytes = md5Hex.toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes.copyOf(16)))
            JSONObject(String(cipher.doFinal(mediaStr.toByteArray(StandardCharsets.ISO_8859_1)), StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private suspend fun decryptAbyssOnlineFallback(encodedDatas: String): JSONObject? {
        return runCatching {
            val resp = network.postJson(
                url = "https://enc-dec.app/api/dec-abyss",
                jsonBody = JSONObject().put("text", encodedDatas).toString(),
                referer = "https://playhydrax.com/",
                headers = mapOf("Origin" to "https://playhydrax.com")
            )
            val result = JSONObject(resp.body).optJSONObject("result") ?: return null
            val sources = result.optJSONArray("sources") ?: return null
            JSONObject().put("mp4", JSONObject().put("sources", sources))
        }.getOrNull()
    }

    private suspend fun resolveTpxPlayerPage(
        playerUrl: String,
        referer: String?,
        trace: MutableList<String>,
        wantDownload: Boolean
    ): List<ResolvedTarget> {
        val resp = runCatching { network.get(playerUrl, referer = referer) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ResolvedTarget>()
        val embedded = SemanticParser.extractEmbeddedMedia(resp.body, resp.url)
        embedded.directMediaUrls.forEach { out.add(buildResolvedTarget(it, resp.url, trace = trace)) }
        for (iframe in embedded.iframeUrls) {
            val sub = extractFromKnownHost(iframe, resp.url, trace, wantDownload)
            if (sub is FastPathResult.Resolved) out.addAll(sub.targets)
        }
        val doc = Jsoup.parse(resp.body, resp.url)
        for (a in doc.select("a[href]")) {
            val href = SemanticParser.resolveUrl(a.attr("href"), resp.url) ?: continue
            val unwrapped = unwrapTokenUrl(href) ?: href
            if (unwrapped.contains("openx.xyz", true) || unwrapped.contains("tpxsub.com", true)) continue
            val sub = extractFromKnownHost(unwrapped, resp.url, trace, wantDownload)
            if (sub is FastPathResult.Resolved) out.addAll(sub.targets)
        }
        return out
    }

    private fun buildResolvedTarget(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        fileNameHint: String? = null,
        trace: List<String> = emptyList()
    ): ResolvedTarget {
        val cookies = network.cookieJar.snapshotForUrl(url)
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = NetworkClient.CHROME_UA
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        headers.putAll(extraHeaders)
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return ResolvedTarget(
            url = url,
            referer = referer,
            cookies = cookies,
            userAgent = NetworkClient.CHROME_UA,
            headers = headers,
            fileNameHint = fileNameHint,
            isHls = path.endsWith(".m3u8") || path.endsWith(".m3u"),
            isZip = path.endsWith(".zip") || path.endsWith(".rar") || path.endsWith(".7z"),
            trace = trace.toList()
        )
    }

    private suspend fun resolveMegaNz(url: String, trace: MutableList<String>): FastPathResult {
        val modern = Regex("mega\\.(?:co\\.)?nz/file/([A-Za-z0-9_-]+)#([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(url)
        val legacy = Regex("mega\\.(?:co\\.)?nz/#!([A-Za-z0-9_-]+)!([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(url)
        val match = modern ?: legacy
        if (match == null) {
            trace.add("Unsupported Mega.nz URL format (folder or missing key)")
            return FastPathResult.Failed("Unsupported Mega.nz link — try Mir or PL1", trace)
        }
        val fileId = match.groupValues[1]
        val keyStr = match.groupValues[2]
        val rawKey = Base64Codec.decodeToBytes(keyStr)
        if (rawKey == null || rawKey.size < 32) {
            trace.add("Invalid Mega decryption key")
            return FastPathResult.Failed("Invalid Mega.nz key", trace)
        }

        val aesKey = ByteArray(16) { i -> (rawKey[i].toInt() xor rawKey[i + 16].toInt()).toByte() }
        val nonce = rawKey.copyOfRange(16, 24)
        val aesKeyHex = aesKey.joinToString("") { "%02x".format(it) }
        val nonceHex = nonce.joinToString("") { "%02x".format(it) }

        val resp = runCatching {
            network.postJson(
                url = "https://g.api.mega.co.nz/cs?id=1",
                jsonBody = """[{"a":"g","g":1,"p":"$fileId"}]""",
                referer = "https://mega.nz/"
            )
        }.getOrNull() ?: run {
            trace.add("Mega API network request failed")
            return FastPathResult.Failed("Mega.nz API unreachable — try Mir or PL1", trace)
        }

        val arr = runCatching { org.json.JSONArray(resp.body.trim()) }.getOrNull()
        val firstObj = arr?.optJSONObject(0)
        if (firstObj == null) {
            val errCode = arr?.optInt(0, -1) ?: -1
            trace.add("Mega API error code: $errCode")
            val msg = when (errCode) {
                -9, -11 -> "Mega file removed or expired — try Mir or PL1"
                -18 -> "Mega temporary bandwidth limit — try Mir or PL1"
                else -> "Mega API error ($errCode) — try Mir or PL1"
            }
            return FastPathResult.Failed(msg, trace)
        }

        val rawDlUrl = firstObj.optString("g", "")
        if (!rawDlUrl.startsWith("http")) {
            trace.add("Mega API returned no download URL")
            return FastPathResult.Failed("Mega.nz quota/error — try Mir or PL1", trace)
        }
        val dlUrl = if (rawDlUrl.startsWith("http://")) {
            "https://${rawDlUrl.removePrefix("http://")}"
        } else {
            rawDlUrl
        }

        val attrB64 = firstObj.optString("at", "")
        val fileName = decryptMegaFileName(attrB64, aesKey) ?: "mega_$fileId.mp4"
        trace.add("Mega.nz ready -> $fileName (AES-CTR)")
        val target = buildResolvedTarget(dlUrl, "https://mega.nz/", fileNameHint = fileName, trace = trace).copy(
            megaKeyHex = aesKeyHex,
            megaNonceHex = nonceHex
        )
        return FastPathResult.Resolved(listOf(target), trace)
    }

    private fun decryptMegaFileName(attrB64: String, aesKey: ByteArray): String? {
        if (attrB64.isBlank()) return null
        return runCatching {
            val encBytes = Base64Codec.decodeToBytes(attrB64) ?: return null
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            val plain = String(cipher.doFinal(encBytes), StandardCharsets.UTF_8).trimEnd('\u0000')
            if (!plain.startsWith("MEGA{")) return null
            JSONObject(plain.removePrefix("MEGA")).optString("n", "").ifBlank { null }
        }.getOrNull()
    }

    private suspend fun resolveKrakenFiles(
        url: String,
        referer: String?,
        trace: MutableList<String>
    ): ResolvedTarget? {
        val resp = runCatching { network.get(url, referer = referer) }.getOrNull() ?: return null
        val doc = Jsoup.parse(resp.body, resp.url)
        val form = doc.selectFirst("form#dl-form, form[action*=/download/]") ?: return null
        val action = SemanticParser.resolveUrl(form.attr("action"), resp.url) ?: return null
        val token = form.selectFirst("input[name=token]")?.attr("value").orEmpty()
        if (token.isBlank()) return null
        val postResp = runCatching {
            network.postForm(
                url = action,
                fields = mapOf("token" to token),
                referer = resp.url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
        }.getOrNull() ?: return null
        val dlUrl = runCatching { JSONObject(postResp.body).optString("url", "") }.getOrNull().orEmpty()
        if (!dlUrl.startsWith("http")) return null
        trace.add("KrakenFiles resolved -> ${summarizeUrl(dlUrl)}")
        return buildResolvedTarget(dlUrl, resp.url, trace = trace)
    }

    private suspend fun resolveGdIndex(
        url: String,
        referer: String?,
        trace: MutableList<String>
    ): List<ResolvedTarget> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val origin = "${uri.scheme}://${uri.authority}"
        val folderUrl = if (url.endsWith("/")) url else "$url/"
        val resp = runCatching {
            network.postJson(
                url = folderUrl,
                jsonBody = """{"password":"","page_token":null,"page_index":0}""",
                referer = referer ?: url,
                headers = mapOf("Origin" to origin, "X-Requested-With" to "XMLHttpRequest")
            )
        }.getOrNull() ?: return emptyList()

        val raw = resp.body.trim()
        val jsonObj = runCatching { JSONObject(raw) }.getOrNull()
            ?: decodeGdIndexPayload(raw)
            ?: return emptyList()

        val files = jsonObj.optJSONObject("data")?.optJSONArray("files") ?: return emptyList()
        val out = mutableListOf<ResolvedTarget>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name", "").trim()
            val mime = f.optString("mimeType", "").lowercase()
            if (name.isBlank() || mime.contains("folder")) continue
            val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20")
            val fileUrl = "$folderUrl$encodedName"
            if (DownloadAndSchemeGuard.inspectUrl(fileUrl) == DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS) {
                trace.add("Index file -> $name")
                out.add(buildResolvedTarget(fileUrl, folderUrl, fileNameHint = name, trace = trace))
            }
        }
        return out
    }

    internal suspend fun resolveHubCloud(
        url: String,
        referer: String?,
        trace: MutableList<String>
    ): List<ResolvedTarget> {
        val resp = runCatching { network.get(url, referer = referer) }.getOrNull() ?: return emptyList()
        if (resp.code !in 200..299 || resp.body.isBlank()) return emptyList()

        val firstDoc = Jsoup.parse(resp.body, resp.url)
        val pageFileName = firstDoc.selectFirst(".card-header, h4, h3, title")
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.contains("hubcloud", true) &&
                    it.contains('.') &&
                    it.length <= 120
            }

        // Step 1: Check if HubCloud /drive/<id> points to a generator link (hubcloud.php / gamerxyt / hubcdn)
        var mirrorDoc = firstDoc
        var mirrorBaseUrl = resp.url
        val genHref = firstDoc.select("a#download[href], a[href*=\"hubcloud.php\"], a[href*=\"gamerxyt\"], a[href]")
            .firstOrNull { a ->
                val txt = a.text().trim().lowercase()
                val href = SemanticParser.resolveUrl(a.attr("href"), resp.url).orEmpty()
                val host = AdFilterPolicy.hostOf(href)
                href.startsWith("http") &&
                    (host == null || !AdFilterPolicy.isAdHost(host)) &&
                    (
                        a.id().equals("download", true) ||
                            href.contains("hubcloud.php", true) ||
                            href.contains("gamerxyt", true) ||
                            txt.contains("generate direct download link")
                        )
            }
            ?.let { SemanticParser.resolveUrl(it.attr("href"), resp.url) }
            ?: Regex("""(?:var|let|const)\s+url\s*=\s*['"](https?://[^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(resp.body)?.groupValues?.get(1)

        if (!genHref.isNullOrBlank() && genHref != resp.url) {
            trace.add("HubCloud generator -> ${summarizeUrl(genHref)}")
            val genResp = runCatching { network.get(genHref, referer = resp.url) }.getOrNull()
            if (genResp != null && genResp.code in 200..299 && genResp.body.isNotBlank()) {
                mirrorDoc = Jsoup.parse(genResp.body, genResp.url)
                mirrorBaseUrl = genResp.url
            }
        }

        // Step 2: Collect direct mirrors (Pixeldrain, GoFile, FSL, HubCDN, ZipDisk, 10Gbps, direct media/zip)
        val out = mutableListOf<ResolvedTarget>()
        val seenUrls = hashSetOf<String>()
        for (a in mirrorDoc.select("a[href]")) {
            val resolved = SemanticParser.resolveUrl(a.attr("href"), mirrorBaseUrl) ?: continue
            if (!resolved.startsWith("http") || resolved == mirrorBaseUrl || resolved == resp.url) continue
            if (DownloadAndSchemeGuard.inspectUrl(resolved) != DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS) continue
            val host = AdFilterPolicy.hostOf(resolved)
            if (host != null && AdFilterPolicy.isAdHost(host)) continue
            val lowerResolved = resolved.lowercase()
            if (lowerResolved.contains("t.me/") || lowerResolved.contains("telegram.") ||
                lowerResolved.contains("/drive/") || lowerResolved.contains("login") ||
                lowerResolved.contains("register")
            ) {
                continue
            }

            val btnText = a.text().replace(Regex("\\s+"), " ").trim()
            val lowerBtn = btnText.lowercase()

            val pdApi = normalizePixeldrainUrl(resolved)
            if (pdApi != null) {
                if (seenUrls.add(pdApi)) {
                    trace.add("HubCloud -> Pixeldrain")
                    out.add(
                        buildResolvedTarget(pdApi, mirrorBaseUrl, fileNameHint = pageFileName, trace = trace)
                            .copy(label = "Pixeldrain${pageFileName?.let { " · $it" } ?: ""}")
                    )
                }
                continue
            }

            if (lowerResolved.contains("gofile.io/d/")) {
                val gf = resolveGoFile(resolved, trace)
                gf.filter { seenUrls.add(it.url) }.forEach {
                    out.add(it.copy(label = "GoFile · ${it.fileNameHint ?: pageFileName ?: "Download"}"))
                }
                continue
            }

            val looksLikeMirrorButton = DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget(resolved) ||
                listOf("fsl", "10gbps", "zipdisk", "hubcdn", "buzzheavier", "direct", "gpdl", "download", "server")
                    .any { lowerBtn.contains(it) || lowerResolved.contains(it) }

            if (looksLikeMirrorButton && seenUrls.add(resolved)) {
                val mirrorName = btnText.ifBlank { host ?: "Direct Mirror" }
                trace.add("HubCloud mirror ($mirrorName) -> ${summarizeUrl(resolved)}")
                out.add(
                    buildResolvedTarget(resolved, mirrorBaseUrl, fileNameHint = pageFileName, trace = trace)
                        .copy(label = "$mirrorName${pageFileName?.let { " · $it" } ?: ""}")
                )
            }
        }

        return out
    }

    private fun decodeGdIndexPayload(raw: String): JSONObject? {
        if (raw.length < 10) return null
        val candidates = listOf(
            raw,
            raw.reversed(),
            if (raw.length > 48) raw.drop(24).dropLast(24).reversed() else raw
        )
        for (c in candidates) {
            val decoded = Base64Codec.decodeToUtf8(c) ?: continue
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj != null && obj.has("data")) return obj
        }
        return null
    }

    private fun summarizeUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.take(48)
        val host = uri.host ?: return url.take(48)
        val path = (uri.path ?: "").take(28)
        return "$host$path"
    }
}
