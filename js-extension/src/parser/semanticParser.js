import * as cheerio from 'cheerio';

const NOISE_URL_FRAGMENTS = [
  'facebook.com/sharer',
  'x.com/intent',
  'twitter.com/intent',
  'linkedin.com/share',
  'pinterest.com/pin',
  'reddit.com/submit',
  'api.whatsapp.com',
  'telegram.me/share',
  't.me/share',
  'vk.com/share',
  'tumblr.com/',
  'mailto:',
  'wp-login.php',
  '#respond',
  'tutorial-video',
];

const PROMO_HOST_FRAGMENTS = [
  't.me/',
  'telegram.me/',
  'telegram.dog/',
  'animeyuga.com',
  'animevilla.in/tutorial',
];

/**
 * Resolve a potentially relative URL safely against baseUrl.
 */
export function resolveUrl(rawUrl, baseUrl) {
  if (!rawUrl) return null;
  const trimmed = String(rawUrl).trim();
  if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('javascript:')) {
    return null;
  }
  try {
    return new URL(trimmed, baseUrl).toString();
  } catch {
    return null;
  }
}

export function isNoiseLink(url, text = '') {
  if (!url) return true;
  const lowerUrl = url.toLowerCase();
  const lowerText = text.toLowerCase();
  if (NOISE_URL_FRAGMENTS.some((frag) => lowerUrl.includes(frag))) return true;
  if (PROMO_HOST_FRAGMENTS.some((frag) => lowerUrl.includes(frag))) return true;
  if (
    lowerText.includes('join our telegram') ||
    lowerText.includes('join telegram') ||
    lowerText.includes('watch tutorial') ||
    lowerText.includes('report your complaint') ||
    lowerText.includes('report link')
  ) {
    return true;
  }
  return false;
}

/**
 * Extract best image URL from an <img> element, skipping lazy SVG placeholders.
 */
export function extractImageSrc($, imgEl, baseUrl) {
  if (!imgEl || imgEl.length === 0) return null;
  const el = $(imgEl).first();
  const candidates = [
    el.attr('data-src'),
    el.attr('data-lazy-src'),
    el.attr('data-original'),
    el.attr('data-url'),
    el.attr('srcset')?.split(',')[0]?.trim().split(/\s+/)[0],
    el.attr('src'),
  ];

  for (const candidate of candidates) {
    if (!candidate) continue;
    const trimmed = candidate.trim();
    if (!trimmed || trimmed.startsWith('data:image/svg+xml') || trimmed.startsWith('data:image/gif')) {
      continue;
    }
    const resolved = resolveUrl(trimmed, baseUrl);
    if (resolved) return resolved;
  }
  return null;
}

/**
 * Normalize quality strings into standard labels: 1080p, 720p, 480p, 360p, or Unknown.
 */
export function normalizeQualityLabel(rawText) {
  if (!rawText) return 'Unknown';
  const text = String(rawText);
  if (/\b2160p\b|\b4k\b|\buhd\b/i.test(text)) return '2160p';
  if (/\b1080p\b|\bfhd\b|\bfull\s*hd\b/i.test(text)) return '1080p';
  if (/\b720p\b|\bhd\b/i.test(text)) return '720p';
  if (/\b480p\b|\bsd\b/i.test(text)) return '480p';
  if (/\b360p\b/i.test(text)) return '360p';
  return 'Unknown';
}

export function extractFileSize(rawText) {
  if (!rawText) return null;
  const match = String(rawText).match(/(\d+(?:\.\d+)?\s*(?:MB|GB|KB))/i);
  return match ? match[1].replace(/\s+/g, '').toUpperCase() : null;
}

/**
 * Parse catalog items (latest updates or search results) using semantic HTML selectors.
 */
export function parseCatalogCards(html, baseUrl, options = {}) {
  const $ = cheerio.load(html);
  const items = [];
  const seenUrls = new Set();
  const skipUrls = new Set((options.skipUrlPatterns || []).map((s) => s.toLowerCase()));

  const addItem = (item) => {
    if (!item || !item.url || !item.title) return;
    const normalizedUrl = item.url.replace(/\/+$/, '');
    const lower = normalizedUrl.toLowerCase();
    if (seenUrls.has(lower)) return;
    if (isNoiseLink(item.url, item.title)) return;
    if (Array.from(skipUrls).some((pat) => lower.includes(pat))) return;
    // Skip author/category/tag/page links
    if (/\/(author|category|genre|tag|status|type|studio|producer|licensor|page)\//i.test(lower)) {
      return;
    }
    seenUrls.add(lower);
    items.push(item);
  };

  // Strategy 1: Semantic <article> elements (standard WordPress theme posts)
  const articles = $('main article, [role="main"] article, article');
  articles.each((_, el) => {
    const $el = $(el);
    const linkEl = $el.find('h2 a, h3 a, h4 a, header a[href], a[rel="bookmark"]').first();
    const fallbackLink = linkEl.length ? linkEl : $el.find('a[href]').first();
    const url = resolveUrl(fallbackLink.attr('href'), baseUrl);
    const title = (linkEl.text() || fallbackLink.attr('title') || fallbackLink.text() || '')
      .replace(/\s+/g, ' ')
      .trim();

    if (!url || !title || title.length < 2) return;

    const thumbnail = extractImageSrc($, $el.find('img').first(), baseUrl);
    const dateText = ($el.find('time').first().attr('datetime') || $el.find('time').first().text() || '')
      .replace(/\s+/g, ' ')
      .trim() || null;
    const summary = ($el.find('p').first().text() || '')
      .replace(/\s+/g, ' ')
      .trim() || null;
    const genres = [];
    $el.find('a[rel="category tag"], a[href*="/category/"], a[href*="/genre/"]').each((__, g) => {
      const gt = $(g).text().replace(/\s+/g, ' ').trim();
      if (gt && !genres.includes(gt)) genres.push(gt);
    });

    addItem({ title, url, thumbnail, date: dateText, summary, genres });
  });

  // Strategy 2: Semantic list/section items containing heading links (e.g., <li> with <h2><a>)
  if (items.length === 0) {
    $('main li, section li, ul li, div').each((_, el) => {
      const $el = $(el);
      // Avoid matching huge wrapper divs with multiple headings
      const headings = $el.find('> h2 a, > h3 a, > header h2 a, > header h3 a');
      const targetHeading = headings.length ? headings.first() : $el.children('h2, h3').find('a').first();
      if (!targetHeading.length) return;

      const url = resolveUrl(targetHeading.attr('href'), baseUrl);
      const title = targetHeading.text().replace(/\s+/g, ' ').trim();
      if (!url || !title) return;

      const thumbnail = extractImageSrc($, $el.find('img').first(), baseUrl);
      const dateText = $el.find('time').first().text().replace(/\s+/g, ' ').trim() || null;
      const summary = $el.children('p').first().text().replace(/\s+/g, ' ').trim() || null;

      addItem({ title, url, thumbnail, date: dateText, summary, genres: [] });
    });
  }

  // Strategy 3: Kiranime / anime-slug catalog links (e.g., <a href="/anime/..."> with heading or img)
  if (items.length === 0) {
    $('a[href*="/anime/"]').each((_, el) => {
      const $a = $(el);
      const url = resolveUrl($a.attr('href'), baseUrl);
      if (!url) return;

      const container = $a.closest('article, li, section > div > div, div');
      const heading = $a.find('h2, h3, h4').first();
      const title = (
        heading.text() ||
        $a.attr('title') ||
        $a.text() ||
        container.find('h2, h3, h4').first().text() ||
        ''
      )
        .replace(/\s+/g, ' ')
        .trim();

      if (!title || /^(watch now|detail)$/i.test(title)) return;
      const img = $a.find('img').first().length
        ? $a.find('img').first()
        : container.find('img').first();
      const thumbnail = extractImageSrc($, img, baseUrl);

      addItem({ title, url, thumbnail, date: null, summary: null, genres: [] });
    });
  }

  return items;
}

/**
 * Parse pagination next page URL semantically.
 */
export function parseNextPageUrl(html, baseUrl) {
  const $ = cheerio.load(html);
  const candidates = [
    $('a[rel="next"]').first(),
    $('nav[aria-label*="pagination" i] a.next').first(),
    $('a.next.page-numbers').first(),
    $('a').filter((_, el) => /^\s*(next|next page|›|»)\s*$/i.test($(el).text())).first(),
  ];

  for (const el of candidates) {
    if (el && el.length) {
      const resolved = resolveUrl(el.attr('href'), baseUrl);
      if (resolved) return resolved;
    }
  }
  return null;
}

/**
 * Parse series detail metadata using semantic elements (h1, article, .entry-content, label: value pairs).
 */
export function parseSeriesDetail(html, pageUrl) {
  const $ = cheerio.load(html);
  const root = $('article, main, [role="main"]').first().length
    ? $('article, main, [role="main"]').first()
    : $.root();
  const contentRoot = root.find('.entry-content').first().length
    ? root.find('.entry-content').first()
    : root;

  const title = (
    root.find('h1').first().text() ||
    $('h1').first().text() ||
    $('meta[property="og:title"]').attr('content') ||
    $('title').text() ||
    ''
  )
    .replace(/\s+/g, ' ')
    .trim();

  let poster = resolveUrl($('meta[property="og:image"]').attr('content'), pageUrl);
  if (!poster) {
    contentRoot.find('img').each((_, img) => {
      if (poster) return;
      const src = extractImageSrc($, img, pageUrl);
      if (src && !/\.gif(\?|$)/i.test(src) && !/avatar/i.test(src)) {
        poster = src;
      }
    });
  }

  // Extract key-value metadata from list items, paragraphs, or table rows
  const metadata = {};
  const labelPattern =
    /^(Name|Other Name|Japanese|English|Season|Genre|Genres|Myanimelist|Total Episodes|Episodes|Duration|Language|Quality|Size|Status|Type|Aired|Premiered|Studios?|Producers?|Licensors?|Rating)\s*:+\s*(.+)$/i;

  root.find('li, p, tr').each((_, el) => {
    const $el = $(el);
    if ($el.is('tr')) {
      const cells = $el.find('th, td');
      if (cells.length >= 2) {
        const key = $(cells[0]).text().replace(/[:\s]+$/, '').trim().toLowerCase();
        const val = $(cells[1]).text().replace(/\s+/g, ' ').trim();
        if (key && val) metadata[key] = val;
      }
      return;
    }
    const text = $el.text().replace(/\s+/g, ' ').trim();
    const match = text.match(labelPattern);
    if (match) {
      const key = match[1].toLowerCase().replace(/s$/, (m) => (match[1].toLowerCase() === 'status' ? 's' : ''));
      const normalizedKey = match[1].toLowerCase();
      metadata[normalizedKey] = match[2].trim();
      if (key && !metadata[key]) metadata[key] = match[2].trim();
    }
  });

  // Genres from links + metadata
  const genres = new Set();
  root.find('a[href*="/genre/"], a[rel="tag"], a[rel="category tag"]').each((_, el) => {
    const g = $(el).text().replace(/\s+/g, ' ').trim();
    if (g && g.length < 40) genres.add(g);
  });
  const genreMeta = metadata.genres || metadata.genre;
  if (genreMeta) {
    genreMeta
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .forEach((g) => genres.add(g));
  }

  // Synopsis extraction
  let synopsis = '';
  const synopsisHeading = root
    .find('h2, h3, h4, strong, b')
    .filter((_, el) => /^(synopsis|overview)\s*:?$/i.test($(el).text().trim()))
    .first();

  if (synopsisHeading.length) {
    const parentP = synopsisHeading.closest('p');
    if (parentP.length) {
      const inlineText = parentP.text().replace(/^synopsis\s*:?\s*/i, '').trim();
      if (inlineText.length > 20) synopsis = inlineText;
      let next = parentP.next();
      while (next.length && next.is('p') && !/^(episode|download|note|subbed by)/i.test(next.text().trim())) {
        const t = next.text().replace(/\s+/g, ' ').trim();
        if (t && !labelPattern.test(t) && !/\[Written by/i.test(t)) {
          synopsis = synopsis ? `${synopsis}\n${t}` : t;
        }
        next = next.next();
      }
    } else {
      let next = synopsisHeading.next();
      while (next.length && (next.is('p') || next.is('div'))) {
        const t = next.text().replace(/\s+/g, ' ').trim();
        if (t && t.length > 15) {
          synopsis = synopsis ? `${synopsis}\n${t}` : t;
        }
        if (synopsis.length > 120) break;
        next = next.next();
      }
    }
  }

  if (!synopsis) {
    const paragraphs = [];
    contentRoot.find('p').each((_, el) => {
      const t = $(el).text().replace(/\s+/g, ' ').trim();
      if (
        t.length > 60 &&
        !labelPattern.test(t) &&
        !/^(note:|episode|fhd|hd\s*[–-]|sd\s*[–-]|download)/i.test(t)
      ) {
        paragraphs.push(t);
      }
    });
    synopsis = paragraphs.slice(0, 2).join('\n');
  }

  // Status inference
  const statusText = (
    metadata.status ||
    metadata['total episodes'] ||
    metadata.episodes ||
    title ||
    ''
  ).toLowerCase();
  let status = 'Unknown';
  if (statusText.includes('completed') || statusText.includes('finished')) {
    status = 'Completed';
  } else if (statusText.includes('ongoing') || statusText.includes('airing')) {
    status = 'Ongoing';
  }

  // Watch page link (common in Kiranime sites)
  const watchLinkEl = root.find('a[href*="/watch/"]').first();
  const watchUrl = watchLinkEl.length ? resolveUrl(watchLinkEl.attr('href'), pageUrl) : null;

  const episodes = parseEpisodesAndQualities(html, pageUrl);

  return {
    title,
    url: pageUrl,
    poster,
    synopsis: synopsis || null,
    genres: Array.from(genres),
    status,
    metadata,
    watchUrl,
    episodes,
  };
}

/**
 * Parse episodes, quality tiers (480p, 720p, 1080p), and host/redirect links
 * from WordPress detail or watch pages.
 */
export function parseEpisodesAndQualities(html, baseUrl) {
  const $ = cheerio.load(html);
  const contentRoot = $('article .entry-content, .entry-content, article, main').first().length
    ? $('article .entry-content, .entry-content, article, main').first()
    : $.root();

  const episodesMap = new Map();
  let currentSeason = 1;
  let currentEpisodeKey = null;
  let currentQualityHint = 'Unknown';
  let currentSizeHint = null;

  const ensureEpisode = (label, seasonNum) => {
    const cleanLabel = label.replace(/\s+/g, ' ').trim();
    const key = `${seasonNum}|${cleanLabel.toLowerCase()}`;
    if (!episodesMap.has(key)) {
      const numMatch = cleanLabel.match(/(\d+(?:\s*-\s*\d+)?)/);
      const rangeStr = numMatch ? numMatch[1].replace(/\s+/g, '') : null;
      const singleNum = rangeStr && !rangeStr.includes('-') ? Number(rangeStr) : null;
      episodesMap.set(key, {
        episodeLabel: cleanLabel,
        episodeNumber: singleNum,
        episodeRange: rangeStr,
        season: seasonNum,
        streams: [],
      });
    }
    return key;
  };

  const addStream = (epKey, stream) => {
    const ep = episodesMap.get(epKey);
    if (!ep) return;
    if (ep.streams.some((s) => s.url === stream.url && s.quality === stream.quality)) return;
    ep.streams.push(stream);
  };

  const seasonHeaderRe = /^\s*(?:\[\s*)?Season\s*(\d+)(?:\s*\])?\s*$/i;
  const episodeHeaderRe = /\b(?:S(\d+)\s*)?(?:Episode|Ep\.?)\s*(\d+(?:\s*-\s*\d+)?)\b/i;

  // Iterate sequentially over block elements inside contentRoot
  const blocks = contentRoot.find('h1, h2, h3, h4, h5, p, li, div');
  blocks.each((_, el) => {
    const $el = $(el);
    // Avoid processing container divs that wrap paragraphs we will also visit
    if ($el.is('div') && $el.find('p, h2, h3, h4, li').length > 0) return;

    const fullText = $el.text().replace(/\s+/g, ' ').trim();
    if (!fullText) return;

    // 1. Detect season header
    const seasonMatch = fullText.match(seasonHeaderRe);
    if (seasonMatch) {
      currentSeason = Number(seasonMatch[1]);
      return;
    }

    // 2. Detect episode header
    const epMatch = fullText.match(episodeHeaderRe);
    const links = $el.find('a[href]');

    if (epMatch && links.length === 0) {
      if (epMatch[1]) currentSeason = Number(epMatch[1]);
      const normalizedEpLabel = `Episode ${epMatch[2].replace(/\s+/g, '')}`;
      currentEpisodeKey = ensureEpisode(normalizedEpLabel, currentSeason);
      currentQualityHint = 'Unknown';
      currentSizeHint = null;
      return;
    }

    // 3. Process links in this block
    if (links.length > 0) {
      // Check if block itself specifies an episode (e.g. "Episode 01 - 720p [Link]")
      if (epMatch) {
        if (epMatch[1]) currentSeason = Number(epMatch[1]);
        const normalizedEpLabel = `Episode ${epMatch[2].replace(/\s+/g, '')}`;
        currentEpisodeKey = ensureEpisode(normalizedEpLabel, currentSeason);
      }

      // Extract quality & size per-link by looking at text between previous <a> and current <a>
      const rawHtml = $el.html() || '';
      const anchorMatches = [];
      const anchorRe = /<a\b[^>]*>[\s\S]*?<\/a>/gi;
      let am;
      while ((am = anchorRe.exec(rawHtml)) !== null) {
        anchorMatches.push({ start: am.index, end: am.index + am[0].length });
      }

      links.each((linkIdx, linkEl) => {
        const $a = $(linkEl);
        const linkText = $a.text().replace(/\s+/g, ' ').trim();
        const resolvedUrl = resolveUrl($a.attr('href'), baseUrl);
        if (!resolvedUrl || isNoiseLink(resolvedUrl, linkText)) return;
        if (/^watch\s*now$/i.test(linkText)) return;

        // Ignore non-episode navigation links (genres, tags, author, status, etc.)
        if (/\/(genre|category|author|status|type|studio|producer|licensor|anime-type)\//i.test(resolvedUrl)) {
          return;
        }
        // Ignore self-post links / internal related article links unless they are shortlink/watch/redirect
        const isEpisodeOrDownloadLink =
          currentEpisodeKey !== null ||
          /\b(480p|720p|1080p|fhd|hd|sd|link|mir|pl1|mega|theta|hsa|pixeldrain|gofile)\b/i.test(
            linkText
          ) ||
          /\.(mp4|m3u8|mkv)(\?|$)/i.test(resolvedUrl) ||
          /(short|redirect|go\/|7k29m4n8q|pixeldrain|gofile|asurasever)/i.test(resolvedUrl);

        if (!isEpisodeOrDownloadLink) return;

        // If no episode header preceded this link (e.g. single movie/OVA page), create Episode 1 bucket
        if (!currentEpisodeKey) {
          currentEpisodeKey = ensureEpisode('Episode 1', currentSeason);
        }

        // Determine preceding segment text for this specific link
        let precedingText = '';
        if (anchorMatches[linkIdx]) {
          const prevEnd = linkIdx > 0 ? anchorMatches[linkIdx - 1].end : 0;
          const segHtml = rawHtml.slice(prevEnd, anchorMatches[linkIdx].start);
          precedingText = cheerio.load(segHtml).text().replace(/\s+/g, ' ').trim();
        }

        const segQuality = normalizeQualityLabel(precedingText);
        const segSize = extractFileSize(precedingText);
        if (segQuality !== 'Unknown') currentQualityHint = segQuality;
        if (segSize) currentSizeHint = segSize;

        const linkQuality = normalizeQualityLabel(linkText);
        const effectiveQuality =
          linkQuality !== 'Unknown'
            ? linkQuality
            : segQuality !== 'Unknown'
            ? segQuality
            : currentQualityHint;
        const effectiveSize = extractFileSize(linkText) || segSize || currentSizeHint;

        const isDirectMedia =
          /\.(mp4|m3u8|mkv|webm)(\?.*)?$/i.test(resolvedUrl) ||
          /pixeldrain\.(com|dev)|gofile\.io/i.test(resolvedUrl);

        addStream(currentEpisodeKey, {
          quality: effectiveQuality,
          rawQualityLabel:
            linkQuality !== 'Unknown'
              ? linkText
              : segQuality !== 'Unknown'
              ? precedingText
              : effectiveQuality,
          size: effectiveSize,
          hostLabel: linkText || 'Link',
          url: resolvedUrl,
          isDirectStream: /\.(mp4|m3u8|mkv|webm)(\?.*)?$/i.test(resolvedUrl),
          requiresWebView: !isDirectMedia,
        });
      });
    }
  });

  // Remove empty episodes that had no valid links
  return Array.from(episodesMap.values()).filter((ep) => ep.streams.length > 0);
}
