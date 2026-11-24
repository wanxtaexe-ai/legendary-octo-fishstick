import { CookieJar } from './cookieJar.js';

export const DEFAULT_USER_AGENT =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

export class HttpClient {
  /**
   * @param {object} [options]
   * @param {string} [options.userAgent]
   * @param {CookieJar} [options.cookieJar]
   * @param {Function} [options.fetcher]
   * @param {number} [options.timeoutMs]
   * @param {Record<string, string>} [options.defaultHeaders]
   */
  constructor(options = {}) {
    this.userAgent = options.userAgent || DEFAULT_USER_AGENT;
    this.cookieJar = options.cookieJar || new CookieJar();
    this.fetcher = options.fetcher || globalThis.fetch;
    this.timeoutMs = options.timeoutMs ?? 20_000;
    this.defaultHeaders = {
      Accept:
        'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
      'Accept-Language': 'en-US,en;q=0.9,hi;q=0.8',
      'Cache-Control': 'no-cache',
      ...options.defaultHeaders,
    };
  }

  /**
   * Build request headers including User-Agent, Referer, and CookieJar cookies.
   */
  buildHeaders(url, extraHeaders = {}) {
    const headers = {
      'User-Agent': this.userAgent,
      ...this.defaultHeaders,
      ...extraHeaders,
    };

    const cookieHeader = this.cookieJar.getCookieHeader(url);
    if (cookieHeader) {
      headers.Cookie = headers.Cookie ? `${headers.Cookie}; ${cookieHeader}` : cookieHeader;
    }
    return headers;
  }

  /**
   * Execute HTTP request with manual redirect handling so cookies are persisted at every hop.
   * @param {string} url
   * @param {object} [options]
   */
  async request(url, options = {}) {
    const {
      method = 'GET',
      headers = {},
      body = undefined,
      maxRedirects = 6,
      followRedirects = true,
      timeoutMs = this.timeoutMs,
    } = options;

    let currentUrl = url;
    let currentMethod = method;
    let currentBody = body;
    const redirectChain = [url];

    for (let hop = 0; hop <= maxRedirects; hop++) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);

      let response;
      try {
        response = await this.fetcher(currentUrl, {
          method: currentMethod,
          headers: this.buildHeaders(currentUrl, headers),
          body: currentBody,
          redirect: 'manual',
          signal: controller.signal,
        });
      } finally {
        clearTimeout(timer);
      }

      this.cookieJar.setCookiesFromHeaders(currentUrl, response.headers);

      const isRedirect =
        response.status >= 300 && response.status < 400 && response.headers.get('location');
      if (isRedirect && followRedirects) {
        const location = response.headers.get('location');
        const nextUrl = new URL(location, currentUrl).toString();
        redirectChain.push(nextUrl);

        if (response.status === 301 || response.status === 302 || response.status === 303) {
          currentMethod = 'GET';
          currentBody = undefined;
        }
        headers.Referer = currentUrl;
        currentUrl = nextUrl;
        continue;
      }

      return {
        url: currentUrl,
        status: response.status,
        ok: response.ok,
        headers: response.headers,
        redirectChain,
        text: () => response.text(),
        json: () => response.json(),
      };
    }

    throw new Error(`Exceeded maxRedirects (${maxRedirects}) starting from ${url}`);
  }

  async getHtml(url, options = {}) {
    const res = await this.request(url, { ...options, method: 'GET' });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status} fetching ${res.url}`);
    }
    const html = await res.text();
    return { url: res.url, status: res.status, html, redirectChain: res.redirectChain };
  }

  async getJson(url, options = {}) {
    const res = await this.request(url, {
      ...options,
      method: 'GET',
      headers: { Accept: 'application/json', ...(options.headers || {}) },
    });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status} fetching JSON from ${res.url}`);
    }
    return res.json();
  }

  async postForm(url, params, options = {}) {
    const body = new URLSearchParams(params).toString();
    return this.request(url, {
      ...options,
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        ...(options.headers || {}),
      },
      body,
    });
  }
}
