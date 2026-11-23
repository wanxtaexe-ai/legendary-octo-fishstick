import {
  parseCatalogCards,
  parseNextPageUrl,
  parseSeriesDetail,
} from './semanticParser.js';
import { extractEmbeddedVideoSources } from '../resolver/mediaHostMatcher.js';
import { resolveStream } from '../resolver/webViewResolver.js';

export class WordPressSource {
  /**
   * @param {object} params
   * @param {string} params.siteId
   * @param {import('../config/domainConfigManager.js').DomainConfigManager} params.configManager
   * @param {import('../http/httpClient.js').HttpClient} params.httpClient
   */
  constructor({ siteId, configManager, httpClient }) {
    this.siteId = siteId;
    this.configManager = configManager;
    this.httpClient = httpClient;
  }

  async getSiteConfig() {
    return this.configManager.getSiteConfig(this.siteId);
  }

  async getBaseUrl() {
    return this.configManager.getBaseUrl(this.siteId);
  }

  async getLatestUpdates(page = 1) {
    const url = await this.configManager.buildLatestUrl(this.siteId, page);
    const { html, url: finalUrl } = await this.httpClient.getHtml(url);
    return this.parseLatestResponse(html, finalUrl, page);
  }

  parseLatestResponse(html, baseUrl, page = 1) {
    const items = parseCatalogCards(html, baseUrl);
    const nextPageUrl = parseNextPageUrl(html, baseUrl);
    return {
      siteId: this.siteId,
      page,
      url: baseUrl,
      hasNextPage: Boolean(nextPageUrl),
      nextPageUrl,
      items,
    };
  }

  async search(query, page = 1) {
    if (!query || !query.trim()) {
      return {
        siteId: this.siteId,
        query: '',
        page,
        url: await this.getBaseUrl(),
        hasNextPage: false,
        nextPageUrl: null,
        items: [],
      };
    }

    const site = await this.getSiteConfig();
    const primaryUrl = await this.configManager.buildSearchUrl(this.siteId, query, page, {
      useFallback: false,
    });

    let result = null;
    try {
      const { html, url: finalUrl } = await this.httpClient.getHtml(primaryUrl);
      result = this.parseSearchResponse(html, finalUrl, query, page);
    } catch {
      result = { items: [] };
    }

    if (
      result.items.length === 0 &&
      site.fallbackSearchPath &&
      site.fallbackSearchPath !== site.searchPath
    ) {
      const fallbackUrl = await this.configManager.buildSearchUrl(this.siteId, query, page, {
        useFallback: true,
      });
      const { html, url: finalUrl } = await this.httpClient.getHtml(fallbackUrl);
      result = this.parseSearchResponse(html, finalUrl, query, page);
    }

    return result;
  }

  parseSearchResponse(html, baseUrl, query, page = 1) {
    const items = parseCatalogCards(html, baseUrl);
    const nextPageUrl = parseNextPageUrl(html, baseUrl);
    return {
      siteId: this.siteId,
      query,
      page,
      url: baseUrl,
      hasNextPage: Boolean(nextPageUrl),
      nextPageUrl,
      items,
    };
  }

  async getSeriesDetail(seriesUrl) {
    const { html, url: finalUrl } = await this.httpClient.getHtml(seriesUrl);
    const detail = this.parseDetailResponse(html, finalUrl);

    const site = await this.getSiteConfig();
    if (site.supportsEmbeddedPlayer && detail.watchUrl) {
      try {
        const watchData = await this.extractWatchStreams(detail.watchUrl, finalUrl);
        if (watchData.sources.length > 0) {
          detail.embeddedStreams = watchData.sources;
          detail.embeddedIframes = watchData.iframes;
        }
      } catch {
        // Keep parsed download/episode links even if watch page fetch fails
      }
    }

    return detail;
  }

  parseDetailResponse(html, pageUrl) {
    const detail = parseSeriesDetail(html, pageUrl);
    const embedded = extractEmbeddedVideoSources(html, pageUrl);
    return {
      siteId: this.siteId,
      ...detail,
      embeddedStreams: embedded.sources,
      embeddedIframes: embedded.iframes,
    };
  }

  async extractWatchStreams(watchUrl, referer = null) {
    const { html, url: finalUrl } = await this.httpClient.getHtml(watchUrl, {
      headers: referer ? { Referer: referer } : {},
    });
    const extracted = extractEmbeddedVideoSources(html, finalUrl);

    // Follow first non-ad iframe if no direct video source was on the outer page
    if (extracted.sources.length === 0 && extracted.iframes.length > 0) {
      for (const frame of extracted.iframes) {
        try {
          const inner = await this.httpClient.getHtml(frame.url, {
            headers: { Referer: finalUrl },
          });
          const innerExtracted = extractEmbeddedVideoSources(inner.html, inner.url);
          if (innerExtracted.sources.length > 0) {
            extracted.sources.push(...innerExtracted.sources);
          }
        } catch {
          // Ignore unreachable iframe and continue
        }
      }
    }

    return extracted;
  }

  async resolveEpisodeStream(streamLink, options = {}) {
    return resolveStream(streamLink, this.httpClient, options);
  }
}
