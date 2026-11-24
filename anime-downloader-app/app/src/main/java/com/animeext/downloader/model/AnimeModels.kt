package com.animeext.downloader.model

import java.io.File

data class SiteCategory(
    val label: String,
    val path: String
)

data class SiteProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val fallbackUrls: List<String> = emptyList(),
    val theme: String = "wordpress",
    val latestPath: String = "/",
    val searchPath: String = "/?s={query}",
    val fallbackSearchPath: String? = null,
    val pagePath: String = "/page/{page}/",
    val supportsEmbeddedPlayer: Boolean = false,
    val categories: List<SiteCategory> = emptyList()
)

data class AnimeCard(
    val siteId: String,
    val siteName: String,
    val title: String,
    val url: String,
    val posterUrl: String? = null
)

data class StreamOption(
    val quality: String = "Unknown",
    val size: String? = null,
    val hostLabel: String = "Link",
    val url: String,
    val isDirectVideo: Boolean = false,
    val referer: String? = null
)

data class EpisodeEntry(
    val label: String,
    val episodeNumber: Int? = null,
    val season: Int = 1,
    val streams: List<StreamOption> = emptyList()
)

data class SeriesDetail(
    val siteId: String,
    val siteName: String,
    val title: String,
    val url: String,
    val posterUrl: String? = null,
    val synopsis: String? = null,
    val genres: List<String> = emptyList(),
    val isCompleted: Boolean = false,
    val isDubbed: Boolean = false,
    val watchUrl: String? = null,
    val episodes: List<EpisodeEntry> = emptyList()
)

data class ResolvedTarget(
    val url: String,
    val referer: String? = null,
    val cookies: Map<String, String> = emptyMap(),
    val userAgent: String,
    val headers: Map<String, String> = emptyMap(),
    val fileNameHint: String? = null,
    val isHls: Boolean = false,
    val isZip: Boolean = false,
    val trace: List<String> = emptyList()
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    EXTRACTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadTask(
    val id: String,
    val seriesTitle: String,
    val episodeLabel: String,
    val url: String,
    val fileName: String,
    val destinationPath: String,
    val headers: Map<String, String> = emptyMap(),
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val extractedFiles: List<String> = emptyList()
)

data class LocalMediaItem(
    val seriesFolder: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
) {
    val file: File get() = File(filePath)
}
