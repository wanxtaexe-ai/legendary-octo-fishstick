import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const DEFAULT_LOCAL_CONFIG_PATH = path.resolve(__dirname, '../../../domains.json');

export class DomainConfigManager {
  /**
   * @param {object} [options]
   * @param {string} [options.remoteUrl] Remote URL to fetch domains.json from (e.g. raw GitHub URL)
   * @param {string} [options.localPath] Local fallback domains.json file path
   * @param {Function} [options.fetcher] Custom fetch implementation (defaults to globalThis.fetch)
   * @param {number} [options.cacheTtlMs] Cache TTL in milliseconds (default: 1 hour)
   */
  constructor(options = {}) {
    this.remoteUrl = options.remoteUrl || null;
    this.localPath = options.localPath || DEFAULT_LOCAL_CONFIG_PATH;
    this.fetcher = options.fetcher || globalThis.fetch;
    this.cacheTtlMs = options.cacheTtlMs ?? 3600_000;
    this._config = null;
    this._lastLoadedAt = 0;
  }

  /**
   * Load configuration from remote URL if configured, falling back to local domains.json.
   * @param {object} [options]
   * @param {boolean} [options.forceRefresh]
   */
  async load({ forceRefresh = false } = {}) {
    const now = Date.now();
    if (!forceRefresh && this._config && now - this._lastLoadedAt < this.cacheTtlMs) {
      return this._config;
    }

    let loaded = null;
    if (this.remoteUrl && typeof this.fetcher === 'function') {
      try {
        const res = await this.fetcher(this.remoteUrl, {
          headers: { Accept: 'application/json' },
        });
        if (res.ok) {
          loaded = await res.json();
        }
      } catch {
        // Fall back to local config if remote fetch fails or is offline
      }
    }

    if (!loaded) {
      const raw = await fs.readFile(this.localPath, 'utf8');
      loaded = JSON.parse(raw);
    }

    this._validateConfig(loaded);
    this._config = loaded;
    this._lastLoadedAt = now;
    return this._config;
  }

  _validateConfig(config) {
    if (!config || typeof config !== 'object') {
      throw new Error('Invalid domains.json: expected JSON object');
    }
    if (!config.sites || typeof config.sites !== 'object') {
      throw new Error('Invalid domains.json: missing "sites" map');
    }
    for (const [id, site] of Object.entries(config.sites)) {
      if (!site.baseUrl || typeof site.baseUrl !== 'string') {
        throw new Error(`Invalid domains.json: site "${id}" is missing baseUrl`);
      }
    }
  }

  getConfigSync() {
    if (!this._config) {
      throw new Error('DomainConfigManager not loaded yet. Call load() first.');
    }
    return this._config;
  }

  async getSiteConfig(siteId) {
    const config = await this.load();
    const site = config.sites[siteId];
    if (!site) {
      throw new Error(`Unknown site id: ${siteId}`);
    }
    return site;
  }

  async getBaseUrl(siteId) {
    const site = await this.getSiteConfig(siteId);
    return site.baseUrl.replace(/\/+$/, '');
  }

  /**
   * Dynamically override a site's baseUrl in-memory (or after loading remote config)
   * without recompiling the extension.
   */
  async updateBaseUrl(siteId, newBaseUrl) {
    const config = await this.load();
    if (!config.sites[siteId]) {
      throw new Error(`Unknown site id: ${siteId}`);
    }
    config.sites[siteId].baseUrl = newBaseUrl.replace(/\/+$/, '');
    return config.sites[siteId].baseUrl;
  }

  async getDefaultUserAgent() {
    const config = await this.load();
    return (
      config.defaultUserAgent ||
      'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
    );
  }

  async buildLatestUrl(siteId, page = 1) {
    const site = await this.getSiteConfig(siteId);
    const base = site.baseUrl.replace(/\/+$/, '');
    if (page <= 1) {
      const latestPath = site.latestPath || '/';
      return `${base}${latestPath.startsWith('/') ? latestPath : `/${latestPath}`}`;
    }
    const pagePath = (site.pagePath || '/page/{page}/').replace('{page}', String(page));
    return `${base}${pagePath.startsWith('/') ? pagePath : `/${pagePath}`}`;
  }

  async buildSearchUrl(siteId, query, page = 1, { useFallback = false } = {}) {
    const site = await this.getSiteConfig(siteId);
    const base = site.baseUrl.replace(/\/+$/, '');
    const encodedQuery = encodeURIComponent(query.trim());
    const template =
      useFallback && site.fallbackSearchPath
        ? site.fallbackSearchPath
        : site.searchPath || '/?s={query}';
    const searchPart = template.replace('{query}', encodedQuery);

    if (page > 1 && searchPart.startsWith('/?')) {
      return `${base}/page/${page}/${searchPart}`;
    }
    if (page > 1 && !searchPart.includes('page=')) {
      const sep = searchPart.includes('?') ? '&' : '?';
      return `${base}${searchPart}${sep}page=${page}`;
    }
    return `${base}${searchPart.startsWith('/') ? searchPart : `/${searchPart}`}`;
  }

  async getDirectMediaHosts() {
    const config = await this.load();
    return config.directMediaHosts || [];
  }

  async getRedirectorHosts() {
    const config = await this.load();
    return config.redirectorHosts || [];
  }
}
