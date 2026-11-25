package com.animeext.downloader.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.animeext.downloader.model.AnimeCard
import com.animeext.downloader.model.DownloadStatus
import com.animeext.downloader.model.DownloadTask
import com.animeext.downloader.model.LocalMediaItem
import com.animeext.downloader.model.ResolvedTarget
import com.animeext.downloader.model.SeriesDetail
import com.animeext.downloader.model.StreamOption
import com.animeext.downloader.resolver.GateSandboxState

private val ObsidianBg = Color(0xFF070A10)
private val ObsidianSurface = Color(0xFF0E1420)
private val ObsidianCard = Color(0xFF131B2B)
private val ObsidianBorder = Color(0xFF212D45)
private val SkyCyan = Color(0xFF38BDF8)
private val EmeraldShield = Color(0xFF10B981)
private val RoseError = Color(0xFFFB7185)
private val AmberWarn = Color(0xFFF59E0B)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextMuted = Color(0xFF94A3B8)

private val VaultColors = darkColorScheme(
    primary = SkyCyan,
    secondary = EmeraldShield,
    background = ObsidianBg,
    surface = ObsidianSurface,
    surfaceVariant = ObsidianCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeVaultScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val localMedia by vm.localMedia.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = ui.currentTab != MainTab.BROWSE && ui.selectedSeries == null && !ui.resolver.active) {
        vm.selectTab(MainTab.BROWSE)
    }

    LaunchedEffect(ui.statusBanner) {
        val msg = ui.statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearBanner()
        }
    }

    MaterialTheme(colorScheme = VaultColors) {
        Scaffold(
            containerColor = ObsidianBg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ObsidianSurface,
                        titleContentColor = TextPrimary
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = EmeraldShield.copy(alpha = 0.14f),
                                shape = CircleShape,
                                border = BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.4f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = EmeraldShield,
                                    modifier = Modifier.padding(7.dp).size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "AnimeVault",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (ui.dohEnabled) "DoH 1.1.1.1 · Hardened Sandbox" else "System DNS · Hardened Sandbox",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextMuted)
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = ObsidianSurface) {
                    val navColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SkyCyan,
                        selectedTextColor = SkyCyan,
                        indicatorColor = SkyCyan.copy(alpha = 0.14f),
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.BROWSE,
                        onClick = { vm.selectTab(MainTab.BROWSE) },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Browse") },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.SEARCH,
                        onClick = { vm.selectTab(MainTab.SEARCH) },
                        icon = { Icon(Icons.Default.Search, null) },
                        label = { Text("Search") },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.DOWNLOADS,
                        onClick = { vm.selectTab(MainTab.DOWNLOADS) },
                        icon = { Icon(Icons.Default.Download, null) },
                        label = {
                            val activeCount = downloads.count {
                                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.EXTRACTING
                            }
                            Text(if (activeCount > 0) "Queue ($activeCount)" else "Queue")
                        },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.LIBRARY,
                        onClick = { vm.selectTab(MainTab.LIBRARY) },
                        icon = { Icon(Icons.Default.Folder, null) },
                        label = { Text("Library") },
                        colors = navColors
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (ui.currentTab) {
                    MainTab.BROWSE -> BrowseTab(ui = ui, vm = vm)
                    MainTab.SEARCH -> SearchTab(ui = ui, vm = vm)
                    MainTab.DOWNLOADS -> DownloadsTab(downloads = downloads, vm = vm)
                    MainTab.LIBRARY -> LibraryTab(localMedia = localMedia, vm = vm)
                }
            }
        }

        // Series Detail Fullscreen Sheet
        ui.selectedSeries?.let { series ->
            SeriesDetailDialog(
                series = series,
                loading = ui.seriesLoading,
                error = ui.seriesError,
                onDismiss = { vm.closeSeries() },
                onAction = { epLabel, opt, wantDownload ->
                    vm.resolveAndAct(series.title, epLabel, opt, wantDownload)
                }
            )
        }

        // Resolver Progress + Mirror Picker Sheet
        if (ui.resolver.active && !ui.sandboxState.visibleToUser) {
            ResolverTraceDialog(
                state = ui.resolver,
                canOpenSandbox = ui.sandboxState.webView != null,
                onOpenSandbox = { ui.sandboxState.onForceShow() },
                onDismiss = { vm.dismissResolver() },
                onPickTarget = { target ->
                    vm.pickResolvedTarget(
                        ui.resolver.title,
                        target.fileNameHint ?: "Download",
                        target,
                        ui.resolver.wantDownload
                    )
                }
            )
        }

        // Hardened Gate Sandbox WebView (with native Back + in-UI Back + safe teardown)
        if (ui.sandboxState.visibleToUser && ui.sandboxState.webView != null) {
            HardenedSandboxDialog(state = ui.sandboxState)
        }

        if (showSettings) {
            SettingsDialog(
                dohEnabled = ui.dohEnabled,
                autoExtractZip = ui.autoExtractZip,
                onDohChanged = { vm.setDohEnabled(it) },
                onAutoExtractChanged = { vm.setAutoExtractZip(it) },
                onWipeSession = { vm.wipeWebViewAndSessionData() },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun VaultPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = SkyCyan
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.copy(alpha = 0.16f) else ObsidianCard,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.7f) else ObsidianBorder)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else TextMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun BrowseTab(ui: MainUiState, vm: MainViewModel) {
    val activeSite = ui.sites.getOrNull(ui.selectedSiteIndex)
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(ui.sites) { index, site ->
                VaultPill(
                    label = site.name,
                    selected = index == ui.selectedSiteIndex,
                    onClick = { vm.selectSite(index) }
                )
            }
        }

        if (activeSite != null && activeSite.categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(activeSite.categories) { idx, cat ->
                    VaultPill(
                        label = cat.label,
                        selected = idx == ui.selectedCategoryIndex,
                        onClick = { vm.selectCategory(idx) },
                        accent = EmeraldShield
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (ui.browseLoading && ui.browseCards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = SkyCyan)
                    Spacer(Modifier.height(10.dp))
                    Text("Loading ${activeSite?.name ?: "catalog"}...", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (ui.browseError != null && ui.browseCards.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = RoseError, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text(ui.browseError, color = RoseError)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.loadBrowsePage(reset = true) }) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(ui.browseCards, key = { it.url }) { card ->
                    AnimePosterCard(card = card, onClick = { vm.openSeries(card) })
                }

                if (ui.browseHasNext || ui.browseLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (ui.browseLoading) {
                                CircularProgressIndicator(color = SkyCyan, modifier = Modifier.size(28.dp))
                            } else {
                                OutlinedButton(
                                    onClick = { vm.loadBrowsePage(reset = false) },
                                    border = BorderStroke(1.dp, ObsidianBorder)
                                ) {
                                    Text("Load Page ${ui.browsePage + 1}", color = SkyCyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTab(ui: MainUiState, vm: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = { vm.updateSearchQuery(it) },
            placeholder = { Text("Search Naruto, Kizumonogatari, Solo Leveling...") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SkyCyan,
                unfocusedBorderColor = ObsidianBorder,
                focusedContainerColor = ObsidianCard,
                unfocusedContainerColor = ObsidianCard
            ),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
            trailingIcon = {
                IconButton(onClick = { vm.runSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = SkyCyan)
                }
            }
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VaultPill(
                label = "All 5 Sites",
                selected = ui.searchAllSites,
                onClick = { vm.toggleSearchAllSites(true) }
            )
            val siteName = ui.sites.getOrNull(ui.selectedSiteIndex)?.name ?: "Current Site"
            VaultPill(
                label = siteName,
                selected = !ui.searchAllSites,
                onClick = { vm.toggleSearchAllSites(false) }
            )
        }

        Spacer(Modifier.height(10.dp))
        when {
            ui.searchLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SkyCyan)
                }
            }
            ui.searchError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(ui.searchError, color = TextMuted)
                }
            }
            ui.searchResults.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Search across all 5 anime sources at once", color = TextMuted)
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(ui.searchResults, key = { "${it.siteId}_${it.url}" }) { card ->
                        AnimePosterCard(card = card, showSiteBadge = true, onClick = { vm.openSeries(card) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimePosterCard(
    card: AnimeCard,
    showSiteBadge: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianCard),
        border = BorderStroke(1.dp, ObsidianBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(Color(0xFF182235))
            ) {
                if (!card.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = card.posterUrl,
                        contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, ObsidianCard.copy(alpha = 0.92f))
                            )
                        )
                )
                if (showSiteBadge) {
                    Surface(
                        color = Color(0xD9070A10),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, SkyCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.padding(8.dp).align(Alignment.TopStart)
                    ) {
                        Text(
                            text = card.siteName,
                            style = MaterialTheme.typography.labelSmall,
                            color = SkyCyan,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Text(
                text = card.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun DownloadsTab(downloads: List<DownloadTask>, vm: MainViewModel) {
    if (downloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Download, null, tint = TextMuted, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("Download queue is empty", color = TextMuted)
            }
        }
        return
    }
    val videoExts = remember { setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts") }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(downloads, key = { it.id }) { task ->
            val playablePath = remember(task.extractedFiles, task.destinationPath) {
                task.extractedFiles.firstOrNull { p ->
                    p.substringAfterLast('.', "").lowercase() in videoExts
                } ?: task.destinationPath.takeIf { p ->
                    p.substringAfterLast('.', "").lowercase() in videoExts
                }
            }
            val pct = if (task.totalBytes > 0L) {
                ((task.downloadedBytes * 100L) / task.totalBytes).toInt().coerceIn(0, 100)
            } else null

            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                border = BorderStroke(1.dp, ObsidianBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("${task.seriesTitle} · ${task.episodeLabel}", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(task.fileName, style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))

                    if (task.totalBytes > 0) {
                        val progress = (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            color = if (task.status == DownloadStatus.FAILED) RoseError else SkyCyan,
                            trackColor = ObsidianBorder,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        )
                    } else if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.EXTRACTING) {
                        LinearProgressIndicator(
                            color = SkyCyan,
                            trackColor = ObsidianBorder,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (statusText, statusColor) = when (task.status) {
                            DownloadStatus.DOWNLOADING -> {
                                val pctPart = pct?.let { "$it% · " } ?: ""
                                "$pctPart${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)} · ${formatBytes(task.speedBytesPerSec)}/s" to SkyCyan
                            }
                            DownloadStatus.EXTRACTING -> "Verifying & unpacking ZIP..." to AmberWarn
                            DownloadStatus.COMPLETED -> {
                                if (task.extractedFiles.isNotEmpty()) {
                                    "Ready · ${task.extractedFiles.size} file(s)" to EmeraldShield
                                } else if (playablePath != null) {
                                    "Completed (${formatBytes(task.downloadedBytes)})" to EmeraldShield
                                } else {
                                    "Saved archive (${formatBytes(task.downloadedBytes)})" to AmberWarn
                                }
                            }
                            DownloadStatus.FAILED -> "Failed: ${task.errorMessage ?: "Unknown"}" to RoseError
                            DownloadStatus.PAUSED -> "Paused (${formatBytes(task.downloadedBytes)})" to AmberWarn
                            else -> task.status.name to TextMuted
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            modifier = Modifier.weight(1f)
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (task.status == DownloadStatus.DOWNLOADING) {
                                IconButton(onClick = { vm.pauseDownload(task.id) }) {
                                    Icon(Icons.Default.Pause, "Pause", tint = TextPrimary)
                                }
                            } else if (task.status == DownloadStatus.PAUSED) {
                                IconButton(onClick = { vm.resumeDownload(task.id) }) {
                                    Icon(Icons.Default.PlayArrow, "Resume", tint = SkyCyan)
                                }
                            } else if (task.status == DownloadStatus.FAILED) {
                                IconButton(onClick = { vm.resumeDownload(task.id) }) {
                                    Icon(Icons.Default.Refresh, "Retry", tint = AmberWarn)
                                }
                            }
                            if (task.status == DownloadStatus.COMPLETED && playablePath != null) {
                                IconButton(onClick = { vm.launchBuiltInPlayer(playablePath, "${task.seriesTitle} · ${task.episodeLabel}") }) {
                                    Icon(Icons.Default.PlayArrow, "Play", tint = EmeraldShield)
                                }
                            }
                            IconButton(onClick = { vm.cancelDownload(task.id) }) {
                                Icon(Icons.Default.Delete, "Remove", tint = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTab(localMedia: List<LocalMediaItem>, vm: MainViewModel) {
    if (localMedia.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Folder, null, tint = TextMuted, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("Offline library is empty", color = TextMuted)
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(localMedia, key = { it.filePath }) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                border = BorderStroke(1.dp, ObsidianBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.seriesFolder, style = MaterialTheme.typography.labelSmall, color = SkyCyan)
                        Text(item.fileName, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                        Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    IconButton(onClick = { vm.launchBuiltInPlayer(item.filePath, item.fileName) }) {
                        Icon(Icons.Default.PlayArrow, "Play", tint = EmeraldShield)
                    }
                    TextButton(onClick = { vm.openExternalPlayer(item) }) {
                        Text("Ext", color = SkyCyan)
                    }
                    IconButton(onClick = { vm.deleteLocalFile(item) }) {
                        Icon(Icons.Default.Delete, "Delete", tint = TextMuted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesDetailDialog(
    series: SeriesDetail,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAction: (episodeLabel: String, option: StreamOption, wantDownload: Boolean) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ObsidianBg,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(ObsidianSurface).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(series.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                        Text(series.siteName, style = MaterialTheme.typography.labelSmall, color = SkyCyan)
                    }
                }

                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SkyCyan)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!series.posterUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = series.posterUrl,
                                        contentDescription = series.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(108.dp).aspectRatio(0.72f).clip(RoundedCornerShape(12.dp))
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (series.genres.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            series.genres.take(4).forEach { g ->
                                                Surface(
                                                    color = ObsidianCard,
                                                    shape = RoundedCornerShape(6.dp),
                                                    border = BorderStroke(1.dp, ObsidianBorder)
                                                ) {
                                                    Text(
                                                        g,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = SkyCyan,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    series.synopsis?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                    }
                                    error?.let {
                                        Text(it, color = RoseError, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        if (series.episodes.isEmpty()) {
                            item {
                                Surface(
                                    color = ObsidianCard,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, ObsidianBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "No episode/download links detected on this page.",
                                        color = TextMuted,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        } else {
                            items(series.episodes) { ep ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                                    border = BorderStroke(1.dp, ObsidianBorder),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        Text(ep.label, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        Spacer(Modifier.height(8.dp))
                                        ep.streams.forEach { opt ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(opt.hostLabel, fontWeight = FontWeight.Medium, color = TextPrimary)
                                                    val meta = listOfNotNull(
                                                        opt.quality.takeIf { it != "Unknown" },
                                                        opt.size
                                                    ).joinToString(" · ")
                                                    if (meta.isNotBlank()) {
                                                        Text(meta, style = MaterialTheme.typography.labelSmall, color = SkyCyan)
                                                    }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    OutlinedButton(
                                                        onClick = { onAction(ep.label, opt, false) },
                                                        border = BorderStroke(1.dp, ObsidianBorder),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp), tint = SkyCyan)
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Stream", color = TextPrimary)
                                                    }
                                                    Button(
                                                        onClick = { onAction(ep.label, opt, true) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = SkyCyan, contentColor = ObsidianBg),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                    ) {
                                                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Get", fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResolverTraceDialog(
    state: ResolverUiState,
    canOpenSandbox: Boolean,
    onOpenSandbox: () -> Unit,
    onDismiss: () -> Unit,
    onPickTarget: (ResolvedTarget) -> Unit
) {
    AlertDialog(
        containerColor = ObsidianSurface,
        onDismissRequest = onDismiss,
        title = { Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.statusMessage, color = SkyCyan, style = MaterialTheme.typography.bodyMedium)
                if (state.error == null && state.resolvedTargets.isEmpty()) {
                    LinearProgressIndicator(
                        color = SkyCyan,
                        trackColor = ObsidianBorder,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    )
                }
                state.error?.let {
                    Text(it, color = RoseError, style = MaterialTheme.typography.bodySmall)
                }
                if (state.blockedCount > 0) {
                    Text(
                        "Shield blocked ${state.blockedCount} ad/redirect threat(s)",
                        color = EmeraldShield,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.fillMaxWidth().height(136.dp)
                ) {
                    LazyColumn(contentPadding = PaddingValues(8.dp)) {
                        items(state.trace) { step ->
                            Text("• $step", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
                if (state.resolvedTargets.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.resolvedTargets.forEachIndexed { idx, t ->
                            Button(
                                onClick = { onPickTarget(t) },
                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCard, contentColor = TextPrimary),
                                border = BorderStroke(1.dp, SkyCyan.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    t.label ?: t.fileNameHint ?: "Mirror #${idx + 1}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (canOpenSandbox) {
                TextButton(onClick = onOpenSandbox) {
                    Text("Open Sandbox", color = SkyCyan)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextMuted)
            }
        }
    )
}

@Composable
private fun HardenedSandboxDialog(state: GateSandboxState) {
    Dialog(
        onDismissRequest = {
            if (state.canGoBack) state.onGoBack() else state.onCancel()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Intercept hardware / gesture Back inside the Dialog window so ads never kick user out
        BackHandler(enabled = true) {
            if (state.canGoBack) {
                state.onGoBack()
            } else {
                state.onCancel()
            }
        }

        Surface(
            color = ObsidianBg,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = state.onGoBack,
                        enabled = state.canGoBack
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (state.canGoBack) SkyCyan else TextMuted.copy(alpha = 0.35f)
                        )
                    }
                    Icon(Icons.Default.Shield, null, tint = EmeraldShield, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Hardened Gate Sandbox",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            state.currentUrl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Surface(
                        color = Color(0xFF7F1D1D),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "Blocked: ${state.blockedCount}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = state.onReload) {
                        Icon(Icons.Default.Refresh, "Reload", tint = TextMuted)
                    }
                    IconButton(onClick = state.onCancel) {
                        Icon(Icons.Default.Close, "Close Sandbox", tint = RoseError)
                    }
                }

                Surface(color = ObsidianCard, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Popups, betting/checkout ads & .apk downloads blocked · Tap ← Back if an overlay opens",
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyCyan,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                val currentWebView = state.webView
                if (currentWebView != null) {
                    key(currentWebView) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                (currentWebView.parent as? ViewGroup)?.removeView(currentWebView)
                                currentWebView
                            },
                            onRelease = { wv ->
                                (wv.parent as? ViewGroup)?.removeView(wv)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    dohEnabled: Boolean,
    autoExtractZip: Boolean,
    onDohChanged: (Boolean) -> Unit,
    onAutoExtractChanged: (Boolean) -> Unit,
    onWipeSession: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = ObsidianSurface,
        onDismissRequest = onDismiss,
        title = { Text("Security & Download Settings", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Cloudflare DoH (1.1.1.1)", fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Bypass ISP DNS blocks", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Switch(checked = dohEnabled, onCheckedChange = onDohChanged)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-Extract Batch ZIPs", fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Unpack episodes with PKZIP + Zip-Slip check", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Switch(checked = autoExtractZip, onCheckedChange = onAutoExtractChanged)
                }
                Button(
                    onClick = onWipeSession,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Wipe Cookies & WebView Data")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = SkyCyan) }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}
