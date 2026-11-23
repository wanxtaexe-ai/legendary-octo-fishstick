import fs from 'node:fs/promises';

/**
 * RFC-6265-compatible lightweight persistent CookieJar for Node.js fetch & WebView cookie sync.
 */
export class CookieJar {
  constructor(initialCookies = []) {
    /** @type {Map<string, { name: string, value: string, domain: string, path: string, expiresAt: number | null, secure: boolean, httpOnly: boolean, hostOnly: boolean }>} */
    this._cookies = new Map();
    for (const cookie of initialCookies) {
      this._putCookie(cookie);
    }
  }

  _cookieKey(domain, path, name) {
    return `${domain.toLowerCase()}|${path}|${name}`;
  }

  _putCookie(cookie) {
    const key = this._cookieKey(cookie.domain, cookie.path, cookie.name);
    if (cookie.expiresAt !== null && cookie.expiresAt <= Date.now()) {
      this._cookies.delete(key);
      return;
    }
    this._cookies.set(key, cookie);
  }

  /**
   * Parse and store Set-Cookie header(s) from an HTTP response.
   * @param {string} requestUrl
   * @param {Headers | Record<string, string | string[]>} headers
   */
  setCookiesFromHeaders(requestUrl, headers) {
    const setCookieValues = this._extractSetCookieHeaders(headers);
    if (setCookieValues.length === 0) return;

    const parsedUrl = new URL(requestUrl);
    const requestHost = parsedUrl.hostname.toLowerCase();
    const defaultPath = this._defaultCookiePath(parsedUrl.pathname);

    for (const rawHeader of setCookieValues) {
      const parsed = this._parseSetCookie(rawHeader, requestHost, defaultPath);
      if (parsed) {
        this._putCookie(parsed);
      }
    }
  }

  _extractSetCookieHeaders(headers) {
    if (!headers) return [];
    if (typeof headers.getSetCookie === 'function') {
      return headers.getSetCookie();
    }
    if (typeof headers.get === 'function') {
      const single = headers.get('set-cookie');
      return single ? this._splitCombinedSetCookie(single) : [];
    }
    const raw = headers['set-cookie'] || headers['Set-Cookie'];
    if (!raw) return [];
    return Array.isArray(raw) ? raw : this._splitCombinedSetCookie(String(raw));
  }

  _splitCombinedSetCookie(headerStr) {
    // Split on commas that start a new cookie (not commas inside Expires=Wed, 21 Oct ...)
    return headerStr.split(/,(?=\s*[^;=,\s]+=[^;]*)/).map((s) => s.trim()).filter(Boolean);
  }

  _defaultCookiePath(pathname) {
    if (!pathname || !pathname.startsWith('/')) return '/';
    const idx = pathname.lastIndexOf('/');
    return idx <= 0 ? '/' : pathname.slice(0, idx);
  }

  _parseSetCookie(rawHeader, requestHost, defaultPath) {
    const parts = rawHeader.split(';').map((p) => p.trim()).filter(Boolean);
    if (parts.length === 0) return null;

    const eqIdx = parts[0].indexOf('=');
    if (eqIdx <= 0) return null;

    const name = parts[0].slice(0, eqIdx).trim();
    const value = parts[0].slice(eqIdx + 1).trim();

    let domain = requestHost;
    let hostOnly = true;
    let path = defaultPath;
    let expiresAt = null;
    let secure = false;
    let httpOnly = false;

    for (let i = 1; i < parts.length; i++) {
      const attr = parts[i];
      const attrEq = attr.indexOf('=');
      const attrName = (attrEq === -1 ? attr : attr.slice(0, attrEq)).trim().toLowerCase();
      const attrVal = attrEq === -1 ? '' : attr.slice(attrEq + 1).trim();

      if (attrName === 'domain' && attrVal) {
        const cleaned = attrVal.replace(/^\./, '').toLowerCase();
        if (requestHost === cleaned || requestHost.endsWith(`.${cleaned}`)) {
          domain = cleaned;
          hostOnly = false;
        }
      } else if (attrName === 'path' && attrVal) {
        path = attrVal.startsWith('/') ? attrVal : '/';
      } else if (attrName === 'max-age' && attrVal) {
        const seconds = Number(attrVal);
        if (Number.isFinite(seconds)) {
          expiresAt = seconds <= 0 ? 0 : Date.now() + seconds * 1000;
        }
      } else if (attrName === 'expires' && attrVal && expiresAt === null) {
        const ts = Date.parse(attrVal);
        if (Number.isFinite(ts)) {
          expiresAt = ts;
        }
      } else if (attrName === 'secure') {
        secure = true;
      } else if (attrName === 'httponly') {
        httpOnly = true;
      }
    }

    return { name, value, domain, path, expiresAt, secure, httpOnly, hostOnly };
  }

  /**
   * Return matching Cookie header value for a given URL.
   * @param {string} targetUrl
   * @returns {string}
   */
  getCookieHeader(targetUrl) {
    const cookies = this.getCookiesForUrl(targetUrl);
    return cookies.map((c) => `${c.name}=${c.value}`).join('; ');
  }

  getCookiesForUrl(targetUrl) {
    const parsedUrl = new URL(targetUrl);
    const host = parsedUrl.hostname.toLowerCase();
    const reqPath = parsedUrl.pathname || '/';
    const isHttps = parsedUrl.protocol === 'https:';
    const now = Date.now();
    const matched = [];

    for (const [key, cookie] of this._cookies.entries()) {
      if (cookie.expiresAt !== null && cookie.expiresAt <= now) {
        this._cookies.delete(key);
        continue;
      }
      if (cookie.secure && !isHttps) continue;

      const domainMatches = cookie.hostOnly
        ? host === cookie.domain
        : host === cookie.domain || host.endsWith(`.${cookie.domain}`);
      if (!domainMatches) continue;

      const pathMatches =
        reqPath === cookie.path ||
        cookie.path === '/' ||
        (reqPath.startsWith(cookie.path) &&
          (cookie.path.endsWith('/') || reqPath[cookie.path.length] === '/'));
      if (!pathMatches) continue;

      matched.push(cookie);
    }

    matched.sort((a, b) => b.path.length - a.path.length);
    return matched;
  }

  setCookie(url, name, value) {
    const parsedUrl = new URL(url);
    this._putCookie({
      name,
      value,
      domain: parsedUrl.hostname.toLowerCase(),
      path: '/',
      expiresAt: null,
      secure: parsedUrl.protocol === 'https:',
      httpOnly: false,
      hostOnly: true,
    });
  }

  clear() {
    this._cookies.clear();
  }

  toJSON() {
    return Array.from(this._cookies.values());
  }

  static fromJSON(list) {
    return new CookieJar(Array.isArray(list) ? list : []);
  }

  async saveToFile(filePath) {
    await fs.writeFile(filePath, JSON.stringify(this.toJSON(), null, 2), 'utf8');
  }

  static async loadFromFile(filePath) {
    try {
      const raw = await fs.readFile(filePath, 'utf8');
      return CookieJar.fromJSON(JSON.parse(raw));
    } catch {
      return new CookieJar();
    }
  }
}
