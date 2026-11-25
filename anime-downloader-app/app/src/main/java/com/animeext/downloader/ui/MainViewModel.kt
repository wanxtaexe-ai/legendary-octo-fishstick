package com.animeext.downloader.ui

import android.app.Application
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeext.downloader.AnimeVaultApp
import com.animeext.downloader.model.AnimeCard
import com.animeext.downloader.model.DownloadTask
import com.animeext.downloader.model.LocalMediaItem
import com.animeext.downloader.model.ResolvedTarget
import com.animeext.downloader.model.SeriesDetail
import com.animeext.downloader.model.SiteCategory
import com.animeext.downloader.model.SiteProfile
import com.animeext.downloader.model.StreamOption
import com.animeext.downloader.network.DomainCatalog
import com.animeext.downloader.player.PlayerActivity
import com.animeext.downloader.resolver.FastPathResult
import com.animeext.downloader.resolver.GateSandboxState
import com.animeext.downloader.resolver.SandboxResolutionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab {
    BROWSE,
    SEARCH,
    DOWNLOADS,
    LIBRARY
}

data class ResolverUiState(
    val active: Boolean = false,
    val title: String = "",
    val wantDownload: Boolean = true,
    val trace: List<String> = emptyList(),
    val statusMessage: String = "",
    val resolvedTargets: List<ResolvedTarget> = emptyList(),
    val error: String? = null,
    val blockedCount: Int = 0
)

data class MainUiState(
    val currentTab: MainTab = MainTab.BROWSE,
    val sites: List<SiteProfile> = DomainCatalog.defaults(),
    val selectedSiteIndex: Int = 0,
    val selectedCategoryIndex: Int = 0,
    val browseCards: List<AnimeCard> = emptyList(),
    val browsePage: Int = 1,
    val browseHasNext: Boolean = false,
    val browseLoading: Boolean = false,
    val browseError: String? = null,

    val searchQuery: String = "",
    val searchAllSites: Boolean = true,
    val searchResults: List<AnimeCard> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,

    val selectedSeries: SeriesDetail? = null,
    val seriesLoading: Boolean = false,
    val seriesError: String? = null,

    val resolver: ResolverUiState = ResolverUiState(),
    val sandboxState: GateSandboxState = GateSandboxState(webView = null),

    val dohEnabled: Boolean = true,
    val autoExtractZip: Boolean = true,
    val statusBanner: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AnimeVaultApp

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    val downloads: StateFlow<List<DownloadTask>> = app.downloadEngine.tasks
    val localMedia: StateFlow<List<LocalMediaItem>> = app.downloadEngine.localFiles

    init {
        viewModelScope.launch {
            val profiles = DomainCatalog.getProfiles(app.networkClient)
            _ui.update { it.copy(sites = profiles) }
            loadBrowsePage(reset = true)
        }
    }

    fun selectTab(tab: MainTab) {
        _ui.update { it.copy(currentTab = tab) }
        if (tab == MainTab.LIBRARY) {
            app.downloadEngine.refreshLocalLibrary()
        }
    }

    fun selectSite(index: Int) {
        _ui.update {
            it.copy(
                selectedSiteIndex = index.coerceIn(0, (it.sites.size - 1).coerceAtLeast(0)),
                selectedCategoryIndex = 0
            )
        }
        loadBrowsePage(reset = true)
    }

    fun selectCategory(index: Int) {
        _ui.update { it.copy(selectedCategoryIndex = index) }
        loadBrowsePage(reset = true)
    }

    fun loadBrowsePage(reset: Boolean) {
        val snapshot = _ui.value
        val site = snapshot.sites.getOrNull(snapshot.selectedSiteIndex) ?: return
        val category: SiteCategory = site.categories.getOrNull(snapshot.selectedCategoryIndex)
            ?: SiteCategory("Latest", site.latestPath)
        val nextPage = if (reset) 1 else snapshot.browsePage + 1

        viewModelScope.launch {
            _ui.update {
                it.copy(
                    browseLoading = true,
                    browseError = null,
                    browseCards = if (reset) emptyList() else it.browseCards
                )
            }
            val res = runCatching { app.catalogRepository.fetchCatalogPage(site, category.path, nextPage) }
            res.onSuccess { pageRes ->
                _ui.update { state ->
                    val merged = if (reset) pageRes.cards else (state.browseCards + pageRes.cards).distinctBy { it.url }
                    state.copy(
                        browseCards = merged,
                        browsePage = nextPage,
                        browseHasNext = pageRes.hasNextPage,
                        browseLoading = false,
                        browseError = if (merged.isEmpty()) "No titles returned by ${site.name}" else null
                    )
                }
            }.onFailure { err ->
                _ui.update {
                    it.copy(
                        browseLoading = false,
                        browseError = "Failed to load ${site.name}: ${err.message}"
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _ui.update { it.copy(searchQuery = query) }
    }

    fun toggleSearchAllSites(all: Boolean) {
        _ui.update { it.copy(searchAllSites = all) }
    }

    fun runSearch() {
        val state = _ui.value
        val q = state.searchQuery.trim()
        if (q.isEmpty()) return

        viewModelScope.launch {
            _ui.update { it.copy(searchLoading = true, searchError = null, searchResults = emptyList()) }
            val results = if (state.searchAllSites) {
                app.catalogRepository.searchAllSites(state.sites, q)
            } else {
                val site = state.sites.getOrNull(state.selectedSiteIndex)
                if (site != null) runCatching { app.catalogRepository.searchSite(site, q) }.getOrDefault(emptyList()) else emptyList()
            }
            _ui.update {
                it.copy(
                    searchResults = results,
                    searchLoading = false,
                    searchError = if (results.isEmpty()) "No matches for \"$q\"" else null
                )
            }
        }
    }

    fun openSeries(card: AnimeCard) {
        val site = _ui.value.sites.firstOrNull { it.id == card.siteId } ?: _ui.value.sites.first()
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    seriesLoading = true,
                    seriesError = null,
                    selectedSeries = SeriesDetail(
                        siteId = card.siteId,
                        siteName = card.siteName,
                        title = card.title,
                        url = card.url,
                        posterUrl = card.posterUrl
                    )
                )
            }
            runCatching { app.catalogRepository.fetchSeriesDetail(site, card.url) }
                .onSuccess { detail ->
                    _ui.update { it.copy(selectedSeries = detail, seriesLoading = false) }
                }
                .onFailure { err ->
                    _ui.update { it.copy(seriesLoading = false, seriesError = err.message ?: "Failed to load series") }
                }
        }
    }

    fun closeSeries() {
        _ui.update { it.copy(selectedSeries = null, seriesError = null) }
    }

    fun resolveAndAct(seriesTitle: String, episodeLabel: String, option: StreamOption, wantDownload: Boolean) {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    resolver = ResolverUiState(
                        active = true,
                        title = "$seriesTitle · $episodeLabel (${option.quality})",
                        wantDownload = wantDownload,
                        statusMessage = "Resolving clean link...",
                        trace = listOf("Starting ${option.hostLabel}: ${option.url.take(70)}")
                    )
                )
            }

            when (val fast = app.httpFastPath.resolve(option.url, option.referer, wantDownload = wantDownload, sourceLinkIndex = option.sourceLinkIndex)) {
                is FastPathResult.Resolved -> {
                    handleResolvedTargets(seriesTitle, episodeLabel, wantDownload, fast.targets, fast.trace, 0)
                }
                is FastPathResult.NeedsHardenedWebView -> {
                    _ui.update {
                        it.copy(
                            resolver = it.resolver.copy(
                                trace = fast.trace,
                                statusMessage = "Running zero-permission gate sandbox..."
                            )
                        )
                    }
                    val sandboxRes = app.webViewResolver.resolveGate(
                        gateUrl = fast.gateUrl,
                        referer = fast.referer,
                        initialTrace = fast.trace,
                        sourceLinkTarget = fast.sourceLinkTarget,
                        sourceLinkIndex = fast.sourceLinkIndex,
                        onSandboxStateChanged = { sbState ->
                            _ui.update { st ->
                                st.copy(
                                    sandboxState = sbState,
                                    resolver = st.resolver.copy(blockedCount = sbState.blockedCount)
                                )
                            }
                        }
                    )
                    when (sandboxRes) {
                        is SandboxResolutionResult.Captured -> {
                            handleResolvedTargets(
                                seriesTitle,
                                episodeLabel,
                                wantDownload,
                                listOf(sandboxRes.target),
                                sandboxRes.target.trace,
                                sandboxRes.blockedCount
                            )
                        }
                        is SandboxResolutionResult.Cancelled -> {
                            _ui.update {
                                it.copy(
                                    resolver = it.resolver.copy(
                                        trace = sandboxRes.trace,
                                        statusMessage = "Cancelled",
                                        error = "Gate verification cancelled",
                                        blockedCount = sandboxRes.blockedCount
                                    )
                                )
                            }
                        }
                        is SandboxResolutionResult.Failed -> {
                            _ui.update {
                                it.copy(
                                    resolver = it.resolver.copy(
                                        trace = sandboxRes.trace,
                                        statusMessage = "Failed",
                                        error = sandboxRes.reason,
                                        blockedCount = sandboxRes.blockedCount
                                    )
                                )
                            }
                        }
                    }
                }
                is FastPathResult.Failed -> {
                    _ui.update {
                        it.copy(
                            resolver = it.resolver.copy(
                                trace = fast.trace,
                                statusMessage = "Failed",
                                error = fast.reason
                            )
                        )
                    }
                }
            }
        }
    }

    private fun handleResolvedTargets(
        seriesTitle: String,
        episodeLabel: String,
        wantDownload: Boolean,
        targets: List<ResolvedTarget>,
        trace: List<String>,
        blockedCount: Int
    ) {
        if (targets.isEmpty()) {
            _ui.update {
                it.copy(
                    resolver = it.resolver.copy(
                        trace = trace,
                        error = "No playable or downloadable URL found",
                        blockedCount = blockedCount
                    )
                )
            }
            return
        }

        if (targets.size == 1) {
            val chosen = targets.first()
            if ((wantDownload || chosen.megaKeyHex != null) && !chosen.isHls) {
                app.downloadEngine.enqueueDownload(seriesTitle, episodeLabel, chosen)
                val msg = if (chosen.megaKeyHex != null && !wantDownload) {
                    "Mega.nz is AES-encrypted — downloading & decrypting to Library!"
                } else {
                    "Queued download: $seriesTitle · $episodeLabel"
                }
                _ui.update {
                    it.copy(
                        resolver = ResolverUiState(active = false),
                        statusBanner = msg,
                        currentTab = MainTab.DOWNLOADS,
                        selectedSeries = null
                    )
                }
            } else {
                // Play stream via built-in Media3 player
                _ui.update { it.copy(resolver = ResolverUiState(active = false)) }
                launchBuiltInPlayer(chosen.url, "$seriesTitle · $episodeLabel", chosen.headers)
            }
        } else {
            _ui.update {
                it.copy(
                    resolver = it.resolver.copy(
                        trace = trace,
                        statusMessage = "Found ${targets.size} files — pick one:",
                        resolvedTargets = targets,
                        blockedCount = blockedCount
                    )
                )
            }
        }
    }

    fun pickResolvedTarget(seriesTitle: String, episodeLabel: String, target: ResolvedTarget, wantDownload: Boolean) {
        if ((wantDownload || target.megaKeyHex != null) && !target.isHls) {
            app.downloadEngine.enqueueDownload(seriesTitle, episodeLabel, target)
            _ui.update {
                it.copy(
                    resolver = ResolverUiState(active = false),
                    statusBanner = "Queued download: ${target.fileNameHint ?: episodeLabel}",
                    currentTab = MainTab.DOWNLOADS,
                    selectedSeries = null
                )
            }
        } else {
            _ui.update { it.copy(resolver = ResolverUiState(active = false)) }
            launchBuiltInPlayer(target.url, "$seriesTitle · $episodeLabel", target.headers)
        }
    }

    fun dismissResolver() {
        _ui.update {
            it.copy(
                resolver = ResolverUiState(active = false),
                sandboxState = GateSandboxState(webView = null)
            )
        }
    }

    fun launchBuiltInPlayer(uriOrPath: String, title: String, headers: Map<String, String> = emptyMap()) {
        val intent = PlayerActivity.createIntent(app, uriOrPath, title, headers).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    }

    fun openExternalPlayer(item: LocalMediaItem) {
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", item.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure {
            _ui.update { st -> st.copy(statusBanner = "No external video player found; use built-in player") }
        }
    }

    fun pauseDownload(taskId: String) = app.downloadEngine.pauseDownload(taskId)
    fun resumeDownload(taskId: String) = app.downloadEngine.resumeDownload(taskId)
    fun cancelDownload(taskId: String) = app.downloadEngine.cancelDownload(taskId)
    fun deleteLocalFile(item: LocalMediaItem) = app.downloadEngine.deleteLocalFile(item)

    fun setDohEnabled(enabled: Boolean) {
        app.networkClient.dohEnabled = enabled
        _ui.update { it.copy(dohEnabled = enabled) }
    }

    fun setAutoExtractZip(enabled: Boolean) {
        app.downloadEngine.autoExtractZip = enabled
        _ui.update { it.copy(autoExtractZip = enabled) }
    }

    fun wipeWebViewAndSessionData() {
        app.networkClient.cookieJar.clear()
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { CookieManager.getInstance().flush() }
        runCatching { WebStorage.getInstance().deleteAllData() }
        _ui.update { it.copy(statusBanner = "All WebView cookies, storage & session data wiped") }
    }

    fun clearBanner() {
        _ui.update { it.copy(statusBanner = null) }
    }
}
