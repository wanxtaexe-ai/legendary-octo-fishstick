import * as cheerio from 'cheerio';
import { normalizeQualityLabel, resolveUrl } from '../parser/semanticParser.js';

const AD_AND_NOISE_HOSTS = [
  'doubleclick.net',
  'googlesyndication.com',
  'google-analytics.com',
  'googletagmanager.com',
  'facebook.net',
  'adservice.google.com',
  'popads.net',
  'popcash.net',
  'propellerads.com',
  'onclickads.net',
  'adsterra.com',
  'highperformanceformat.com',
  'effectiveratecpm.com',
  'profitablegate.com',
  'hilltopads.net',
  'admaven.com',
  'monetag.com',
  'exoclick.com',
  'juicyads.com',
  'trafficjunky.net',
  'mc.yandex.ru',
];

const EXTERNAL_HIJACK_SCHEMES = ['intent:', 'market:', 'tg:', 'whatsapp:', 'upi:', 'tel:'];

const NON_MEDIA_EXTENSIONS = /\.(css|js|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|eot|ts|vtt|srt)(\?.*)?$/i;
const DIRECT_VIDEO_EXTENSION = /\.(mp4|m3u8|mkv|webm)(\?.*)?$/i;
const PIXELDRAIN_RE = /^https?:\/\/(?:www\.)?pixeldrain\.(?:com|dev)\/(?:u|api\/file)\/([a-zA-Z0-9_-]+)/i;
const GOFILE_RE = /^https?:\/\/(?:[a-z0-9-]+\.)?gofile\.io\/(?:d|download)\/([a-zA-Z0-9_-]+)/i;
const ASURA_DIRECT_RE = /^https?:\/\/(?:www\.)?asurasever\.cloud\/.+\.(mp4|mkv|webm|m3u8)(\?.*)?$/i;

export function isExternalHijackScheme(url) {
  if (!url) return false;
  const lower = String(url).trim().toLowerCase();
  return EXTERNAL_HIJACK_SCHEMES.some((scheme) => lower.startsWith(scheme));
}

export function isKnownAdHost(url) {
  if (!url) return false;
  try {
    const { hostname } = new URL(url);
    const host = hostname.toLowerCase();
    return AD_AND_NOISE_HOSTS.some((adHost) => host === adHost || host.endsWith(`.${adHost}`));
  } catch {
    return false;
  }
}

export function getAdStubResponse(url) {
  const lower = String(url || '').toLowerCase();
  if (/\.js(\?|$)/.test(lower)) {
    return {
      mimeType: 'application/javascript',
      body: '/* ad-stub */ window.adsbygoogle = window.adsbygoogle || [];',
    };
  }
  if (/\.css(\?|$)/.test(lower)) {
    return { mimeType: 'text/css', body: '/* ad-stub */' };
  }
  return { mimeType: 'text/html', body: '<!doctype html><html><body></body></html>' };
}

export function isAdOrNoiseUrl(url) {
  if (!url) return true;
  const lower = String(url).toLowerCase();
  if (!lower.startsWith('http://') && !lower.startsWith('https://')) return true;
  if (NON_MEDIA_EXTENSIONS.test(lower)) return true;
  return isKnownAdHost(url);
}

/**
 * Match a URL against known direct media streams and file hosts (Pixeldrain, Gofile, .mp4/.m3u8).
 * @param {string} url
 * @returns {{ matched: boolean, hostType?: string, originalUrl?: string, streamUrl?: string, mimeType?: string, isDirectStream?: boolean } | null}
 */
export function matchDirectMediaHost(url) {
  if (!url || isAdOrNoiseUrl(url)) return null;

  const pixeldrainMatch = url.match(PIXELDRAIN_RE);
  if (pixeldrainMatch) {
    const fileId = pixeldrainMatch[1];
    return {
      matched: true,
      hostType: 'pixeldrain',
      originalUrl: url,
      streamUrl: `https://pixeldrain.com/api/file/${fileId}`,
      mimeType: 'video/mp4',
      isDirectStream: true,
    };
  }

  const gofileMatch = url.match(GOFILE_RE);
  if (gofileMatch) {
    const isDirectFile = /\/download\//i.test(url) && DIRECT_VIDEO_EXTENSION.test(url);
    return {
      matched: true,
      hostType: 'gofile',
      originalUrl: url,
      streamUrl: url,
      mimeType: isDirectFile ? inferMimeType(url) : 'text/html',
      isDirectStream: isDirectFile,
    };
  }

  if (ASURA_DIRECT_RE.test(url) || DIRECT_VIDEO_EXTENSION.test(url)) {
    return {
      matched: true,
      hostType: 'direct',
      originalUrl: url,
      streamUrl: url,
      mimeType: inferMimeType(url),
      isDirectStream: true,
    };
  }

  return null;
}

export function inferMimeType(url) {
  const lower = String(url).toLowerCase();
  if (/\.m3u8(\?|$)/.test(lower)) return 'application/x-mpegURL';
  if (/\.mp4(\?|$)/.test(lower)) return 'video/mp4';
  if (/\.mkv(\?|$)/.test(lower)) return 'video/x-matroska';
  if (/\.webm(\?|$)/.test(lower)) return 'video/webm';
  return 'video/*';
}

export function isRedirectorUrl(url, redirectorHosts = []) {
  if (!url) return false;
  const lower = url.toLowerCase();
  return redirectorHosts.some((host) => lower.includes(host.toLowerCase()));
}

/**
 * Extract embedded video sources (<video>, <source>, <iframe>, and inline HLS/MP4 script configs).
 */
export function extractEmbeddedVideoSources(html, baseUrl) {
  const $ = cheerio.load(html);
  const sources = [];
  const iframes = [];
  const seenUrls = new Set();

  const addSource = (rawUrl, qualityHint = 'Unknown', sourceType = 'embedded') => {
    const resolved = resolveUrl(rawUrl, baseUrl);
    if (!resolved || seenUrls.has(resolved) || isAdOrNoiseUrl(resolved)) return;
    seenUrls.add(resolved);
    const quality = normalizeQualityLabel(qualityHint);
    sources.push({
      url: resolved,
      quality,
      mimeType: inferMimeType(resolved),
      sourceType,
    });
  };

  // 1. Direct HTML5 <video> and <source> tags
  $('video').each((_, vid) => {
    const $vid = $(vid);
    const directSrc = $vid.attr('src');
    if (directSrc) {
      addSource(directSrc, $vid.attr('data-quality') || $vid.attr('title') || 'Unknown', 'video-tag');
    }
    $vid.find('source').each((__, srcEl) => {
      const $src = $(srcEl);
      addSource(
        $src.attr('src'),
        $src.attr('label') || $src.attr('size') || $src.attr('title') || 'Unknown',
        'video-source'
      );
    });
  });

  // 2. Embedded <iframe> players
  $('iframe[src], iframe[data-src]').each((_, frame) => {
    const $f = $(frame);
    const raw = $f.attr('src') || $f.attr('data-src');
    const resolved = resolveUrl(raw, baseUrl);
    if (!resolved || isAdOrNoiseUrl(resolved)) return;
    if (/youtube\.com|youtu\.be|facebook\.com|disqus\.com|recaptcha/i.test(resolved)) return;
    iframes.push({
      url: resolved,
      label: $f.attr('title') || $f.attr('name') || 'Embedded Player',
    });
  });

  // 3. Inline <script> blocks for direct .m3u8/.mp4 or quality-keyed JSON configs
  $('script').each((_, scriptEl) => {
    const code = $(scriptEl).html() || '';
    if (!code) return;

    // Pattern A: Quality-labeled objects e.g. { file: "...m3u8", label: "1080p" } or "720p": "https://...mp4"
    const keyedQualityRe = /["'](2160p|1080p|720p|480p|360p|FHD|HD|SD)["']\s*:\s*["'](https?:\/\/[^"']+\.(?:m3u8|mp4|mkv|webm)[^"']*)["']/gi;
    let m;
    while ((m = keyedQualityRe.exec(code)) !== null) {
      addSource(m[2], m[1], 'script-quality-map');
    }

    const objSourceRe = /(?:file|src|url)\s*:\s*["'](https?:\/\/[^"']+\.(?:m3u8|mp4|mkv|webm)[^"']*)["'][^}]{0,160}?(?:label|quality|res)\s*:\s*["']([^"']+)["']/gi;
    while ((m = objSourceRe.exec(code)) !== null) {
      addSource(m[1], m[2], 'script-player-config');
    }

    // Pattern B: Any direct .m3u8 / .mp4 URL in scripts
    const rawVideoUrlRe = /https?:\/\/[^\s"'<>\\]+\.(?:m3u8|mp4|mkv|webm)(?:\?[^\s"'<>\\]*)?/gi;
    while ((m = rawVideoUrlRe.exec(code)) !== null) {
      addSource(m[0], m[0], 'script-url');
    }
  });

  // 4. Data attributes on server/quality buttons
  $('[data-video], [data-stream], [data-url], [data-src]').each((_, el) => {
    const $el = $(el);
    const val = $el.attr('data-video') || $el.attr('data-stream') || $el.attr('data-url') || $el.attr('data-src');
    if (val && DIRECT_VIDEO_EXTENSION.test(val)) {
      addSource(val, $el.attr('data-quality') || $el.text(), 'data-attribute');
    }
  });

  return { sources, iframes };
}
