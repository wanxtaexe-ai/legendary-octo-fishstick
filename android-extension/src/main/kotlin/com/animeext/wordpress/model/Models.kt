package com.animeext.wordpress.model

enum class QualityLabel(val display: String) {
    Q_2160P("2160p"),
    Q_1080P("1080p"),
    Q_720P("720p"),
    Q_480P("480p"),
    Q_360P("360p"),
    UNKNOWN("Unknown");

    companion object {
        fun fromRaw(raw: String?): QualityLabel {
            if (raw.isNullOrBlank()) return UNKNOWN
            return when {
                Regex("\\b(2160p|4k|uhd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> Q_2160P
                Regex("\\b(1080p|fhd|full\\s*hd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> Q_1080P
                Regex("\\b(720p|hd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> Q_720P
                Regex("\\b(480p|sd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> Q_480P
                Regex("\\b360p\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> Q_360P
                else -> UNKNOWN
            }
        }
    }
}

data class CatalogItem(
    val title: String,
    val url: String,
    val thumbnail: String? = null,
    val date: String? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList()
)

data class CatalogPage(
    val siteId: String,
    val page: Int,
    val url: String,
    val hasNextPage: Boolean,
    val nextPageUrl: String? = null,
    val items: List<CatalogItem>
)

data class EpisodeStream(
    val quality: QualityLabel,
    val rawQualityLabel: String,
    val size: String? = null,
    val hostLabel: String,
    val url: String,
    val isDirectStream: Boolean,
    val requiresWebView: Boolean
)

data class Episode(
    val episodeLabel: String,
    val episodeNumber: Int? = null,
    val episodeRange: String? = null,
    val season: Int = 1,
    val streams: List<EpisodeStream>
)

data class EmbeddedSource(
    val url: String,
    val quality: QualityLabel,
    val mimeType: String,
    val sourceType: String
)

data class EmbeddedIframe(
    val url: String,
    val label: String
)

data class SeriesDetail(
    val siteId: String,
    val title: String,
    val url: String,
    val poster: String? = null,
    val synopsis: String? = null,
    val genres: List<String> = emptyList(),
    val status: String = "Unknown",
    val metadata: Map<String, String> = emptyMap(),
    val watchUrl: String? = null,
    val episodes: List<Episode> = emptyList(),
    val embeddedStreams: List<EmbeddedSource> = emptyList(),
    val embeddedIframes: List<EmbeddedIframe> = emptyList()
)

data class ResolvedStream(
    val url: String,
    val originalUrl: String,
    val hostType: String,
    val mimeType: String,
    val quality: QualityLabel,
    val headers: Map<String, String>,
    val resolvedVia: String
)

sealed class ResolutionResult {
    data class Resolved(val stream: ResolvedStream, val method: String) : ResolutionResult()
    data class RequiresWebView(
        val initialUrl: String,
        val headers: Map<String, String>,
        val expectedQuality: QualityLabel
    ) : ResolutionResult()
}
