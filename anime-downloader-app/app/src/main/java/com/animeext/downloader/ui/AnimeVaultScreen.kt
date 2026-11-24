package com.animeext.downloader.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.animeext.downloader.model.SeriesDetail
import com.animeext.downloader.model.StreamOption
import com.animeext.downloader.resolver.GateSandboxState

private val VaultColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    secondary = Color(0xFF22D3EE),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF131822),
    surfaceVariant = Color(0xFF1C2331),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeVaultScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val localMedia by vm.localMedia.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(ui.statusBanner) {
        val msg = ui.statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearBanner()
        }
    }

    MaterialTheme(colorScheme = VaultColors) {
        Scaffold(
            containerColor = VaultColors.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF10151F),
                        titleContentColor = Color.White
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("AnimeVault", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (ui.dohEnabled) "DoH 1.1.1.1 · Zero-Perm Sandbox" else "System DNS · Zero-Perm Sandbox",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF10151F)) {
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.BROWSE,
                        onClick = { vm.selectTab(MainTab.BROWSE) },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Browse") }
                    )
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.SEARCH,
                        onClick = { vm.selectTab(MainTab.SEARCH) },
                        icon = { Icon(Icons.Default.Search, null) },
                        label = { Text("Search") }
                    )
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.DOWNLOADS,
                        onClick = { vm.selectTab(MainTab.DOWNLOADS) },
                        icon = { Icon(Icons.Default.Download, null) },
                        label = {
                            val activeCount = downloads.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.EXTRACTING }
                            Text(if (activeCount > 0) "Queue ($activeCount)" else "Queue")
                        }
                    )
                    NavigationBarItem(
                        selected = ui.currentTab == MainTab.LIBRARY,
                        onClick = { vm.selectTab(MainTab.LIBRARY) },
                        icon = { Icon(Icons.Default.Folder, null) },
                        label = { Text("Library") }
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

        // Series Detail Sheet
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

        // Resolver Trace + Multi-target Picker
        if (ui.resolver.active && !ui.sandboxState.visibleToUser) {
            ResolverTraceDialog(
                state = ui.resolver,
                onDismiss = { vm.dismissResolver() },
                onPickTarget = { target ->
                    vm.pickResolvedTarget(ui.resolver.title, "Download", target, ui.resolver.wantDownload)
                }
            )
        }

        // Hardened Gate Sandbox WebView (visible ONLY when human verification is needed)
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
private fun BrowseTab(ui: MainUiState, vm: MainViewModel) {
    val activeSite = ui.sites.getOrNull(ui.selectedSiteIndex)
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(ui.sites) { index, site ->
                FilterChip(
                    selected = index == ui.selectedSiteIndex,
                    onClick = { vm.selectSite(index) },
                    label = { Text(site.name) }
                )
            }
        }

        if (activeSite != null && activeSite.categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(activeSite.categories) { idx, cat ->
                    AssistChip(
                        onClick = { vm.selectCategory(idx) },
                        label = {
                            Text(
                                cat.label,
                                color = if (idx == ui.selectedCategoryIndex) Color(0xFF22D3EE) else Color(0xFFD1D5DB)
                            )
                        }
                    )
                }
            }
        }

        if (ui.browseLoading && ui.browseCards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (ui.browseError != null && ui.browseCards.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(ui.browseError, color = Color(0xFFFCA5A5))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.loadBrowsePage(reset = true) }) {
                    Icon(Icons.Default.Refresh, null)
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
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            } else {
                                OutlinedButton(onClick = { vm.loadBrowsePage(reset = false) }) {
                                    Text("Load More (Page ${ui.browsePage + 1})")
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
            placeholder = { Text("Search Naruto, Solo Leveling, Bleach...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
            trailingIcon = {
                IconButton(onClick = { vm.runSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = ui.searchAllSites,
                onClick = { vm.toggleSearchAllSites(true) },
                label = { Text("All 5 Sites") }
            )
            val siteName = ui.sites.getOrNull(ui.selectedSiteIndex)?.name ?: "Current Site"
            FilterChip(
                selected = !ui.searchAllSites,
                onClick = { vm.toggleSearchAllSites(false) },
                label = { Text(siteName) }
            )
        }

        Spacer(Modifier.height(8.dp))
        when {
            ui.searchLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            ui.searchError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(ui.searchError, color = Color(0xFF9CA3AF))
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(Color(0xFF1F2937))
            ) {
                if (!card.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = card.posterUrl,
                        contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (showSiteBadge) {
                    Surface(
                        color = Color(0xCC0B0E14),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(6.dp).align(Alignment.TopStart)
                    ) {
                        Text(
                            text = card.siteName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF22D3EE),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun DownloadsTab(downloads: List<DownloadTask>, vm: MainViewModel) {
    if (downloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No downloads yet. Pick any episode to download!", color = Color(0xFF9CA3AF))
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(downloads, key = { it.id }) { task ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("${task.seriesTitle} · ${task.episodeLabel}", fontWeight = FontWeight.SemiBold)
                    Text(task.fileName, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                    Spacer(Modifier.height(6.dp))

                    if (task.totalBytes > 0) {
                        val progress = (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.EXTRACTING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusText = when (task.status) {
                            DownloadStatus.DOWNLOADING -> "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)} · ${formatBytes(task.speedBytesPerSec)}/s"
                            DownloadStatus.EXTRACTING -> "Auto-extracting ZIP batch..."
                            DownloadStatus.COMPLETED -> if (task.extractedFiles.isNotEmpty()) "Extracted ${task.extractedFiles.size} file(s)" else "Completed (${formatBytes(task.downloadedBytes)})"
                            DownloadStatus.FAILED -> "Failed: ${task.errorMessage ?: "Unknown"}"
                            else -> task.status.name
                        }
                        Text(statusText, style = MaterialTheme.typography.labelMedium, color = Color(0xFF22D3EE), modifier = Modifier.weight(1f))

                        Row {
                            if (task.status == DownloadStatus.DOWNLOADING) {
                                IconButton(onClick = { vm.pauseDownload(task.id) }) {
                                    Icon(Icons.Default.Pause, "Pause")
                                }
                            } else if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                                IconButton(onClick = { vm.resumeDownload(task.id) }) {
                                    Icon(Icons.Default.PlayArrow, "Resume")
                                }
                            }
                            if (task.status == DownloadStatus.COMPLETED) {
                                val playPath = task.extractedFiles.firstOrNull { !it.endsWith(".srt") && !it.endsWith(".ass") }
                                    ?: task.destinationPath
                                IconButton(onClick = { vm.launchBuiltInPlayer(playPath, "${task.seriesTitle} · ${task.episodeLabel}") }) {
                                    Icon(Icons.Default.PlayArrow, "Play")
                                }
                            }
                            IconButton(onClick = { vm.cancelDownload(task.id) }) {
                                Icon(Icons.Default.Delete, "Cancel")
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
            Text("Local offline library is empty.", color = Color(0xFF9CA3AF))
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
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.seriesFolder, style = MaterialTheme.typography.labelSmall, color = Color(0xFF22D3EE))
                        Text(item.fileName, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                    }
                    IconButton(onClick = { vm.launchBuiltInPlayer(item.filePath, item.fileName) }) {
                        Icon(Icons.Default.PlayArrow, "Play")
                    }
                    TextButton(onClick = { vm.openExternalPlayer(item) }) {
                        Text("Ext")
                    }
                    IconButton(onClick = { vm.deleteLocalFile(item) }) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                }
            }
        }
    }
}

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
            color = Color(0xFF0B0E14),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF10151F)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(series.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(series.siteName, style = MaterialTheme.typography.labelSmall, color = Color(0xFF22D3EE))
                    }
                }

                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!series.posterUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = series.posterUrl,
                                        contentDescription = series.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(108.dp).aspectRatio(0.72f).clip(RoundedCornerShape(10.dp))
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    if (series.genres.isNotEmpty()) {
                                        Text(
                                            series.genres.take(4).joinToString(" · "),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF818CF8)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    series.synopsis?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1D5DB), maxLines = 6, overflow = TextOverflow.Ellipsis)
                                    }
                                    error?.let {
                                        Text(it, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        if (series.episodes.isEmpty()) {
                            item {
                                Text("No episode/download links detected on this page.", color = Color(0xFF9CA3AF))
                            }
                        } else {
                            items(series.episodes) { ep ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(ep.label, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(8.dp))
                                        ep.streams.forEach { opt ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    val meta = listOfNotNull(opt.quality, opt.size).joinToString(" · ")
                                                    Text(opt.hostLabel, fontWeight = FontWeight.Medium)
                                                    if (meta.isNotBlank()) {
                                                        Text(meta, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                                                    }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    OutlinedButton(
                                                        onClick = { onAction(ep.label, opt, false) },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Stream")
                                                    }
                                                    Button(
                                                        onClick = { onAction(ep.label, opt, true) },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                    ) {
                                                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Get")
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
    onDismiss: () -> Unit,
    onPickTarget: (com.animeext.downloader.model.ResolvedTarget) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.statusMessage, color = Color(0xFF22D3EE), style = MaterialTheme.typography.bodyMedium)
                if (state.error == null && state.resolvedTargets.isEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.error?.let {
                    Text(it, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                }
                if (state.blockedCount > 0) {
                    Text("Shield blocked ${state.blockedCount} ad/unsafe request(s)", color = Color(0xFF10B981), style = MaterialTheme.typography.labelSmall)
                }
                LazyColumn(modifier = Modifier.height(140.dp)) {
                    items(state.trace) { step ->
                        Text("• $step", style = MaterialTheme.typography.labelSmall, color = Color(0xFFD1D5DB))
                    }
                }
                if (state.resolvedTargets.isNotEmpty()) {
                    state.resolvedTargets.forEachIndexed { idx, t ->
                        Button(
                            onClick = { onPickTarget(t) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(t.fileNameHint ?: "File #${idx + 1}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun HardenedSandboxDialog(state: GateSandboxState) {
    Dialog(
        onDismissRequest = state.onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color(0xFF0B0E14), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141A24))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shield, null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Hardened Gate Sandbox", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(
                            state.currentUrl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    Surface(
                        color = Color(0xFF7F1D1D),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Blocked: ${state.blockedCount}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = state.onCancel) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                }

                Surface(color = Color(0xFF1E293B), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Zero permissions · Popups & .apk downloads blocked · Complete verification below",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF22D3EE),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        val wv = state.webView!!
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv
                    }
                )
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
        onDismissRequest = onDismiss,
        title = { Text("Security & Download Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Cloudflare DoH (1.1.1.1)", fontWeight = FontWeight.Medium)
                        Text("Bypass ISP DNS blocks", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                    }
                    Switch(checked = dohEnabled, onCheckedChange = onDohChanged)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-Extract Batch ZIPs", fontWeight = FontWeight.Medium)
                        Text("Unpack episodes with Zip-Slip guard", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
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
            TextButton(onClick = onDismiss) { Text("Done") }
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
