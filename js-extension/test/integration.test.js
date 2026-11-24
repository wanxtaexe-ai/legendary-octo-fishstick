import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DomainConfigManager } from '../src/config/domainConfigManager.js';
import { CookieJar } from '../src/http/cookieJar.js';
import { HttpClient } from '../src/http/httpClient.js';
import { SourceRegistry } from '../src/sources/index.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES_DIR = path.join(__dirname, 'fixtures');
const LOCAL_CONFIG = path.resolve(__dirname, '../../domains.json');

async function loadFixture(name) {
  return fs.readFile(path.join(FIXTURES_DIR, name), 'utf8');
}

test('End-to-end integration test across all 5 WordPress sources via live local HTTP server', async () => {
  const fixtures = {
    'lordsanime-home': await loadFixture('lordsanime-home.html'),
    'lordsanime-search': await loadFixture('lordsanime-search.html'),
    'lordsanime-detail': await loadFixture('lordsanime-detail.html'),
    'tpxsub-home': await loadFixture('tpxsub-home.html'),
    'tpxsub-search': await loadFixture('tpxsub-search.html'),
    'tpxsub-detail': await loadFixture('tpxsub-detail.html'),
    'ybxanime-home': await loadFixture('ybxanime-home.html'),
    'ybxanime-search': await loadFixture('ybxanime-search.html'),
    'ybxanime-detail': await loadFixture('ybxanime-detail.html'),
    'hindisubbedacademy-home': await loadFixture('hindisubbedacademy-home.html'),
    'hindisubbedacademy-search': await loadFixture('hindisubbedacademy-search.html'),
    'hindisubbedacademy-detail': await loadFixture('hindisubbedacademy-detail.html'),
    'animevilla-home': await loadFixture('animevilla-home.html'),
    'animevilla-search': await loadFixture('animevilla-search.html'),
    'animevilla-detail': await loadFixture('animevilla-detail.html'),
    'animevilla-watch': await loadFixture('animevilla-watch.html'),
  };

  let serverBaseUrl = '';

  const rewriteUrls = (html, siteId) =>
    html
      .replaceAll('https://www.lordsanime.com', `${serverBaseUrl}/${siteId}`)
      .replaceAll('https://www.tpxsub.com', `${serverBaseUrl}/${siteId}`)
      .replaceAll('https://ybxanime.com', `${serverBaseUrl}/${siteId}`)
      .replaceAll('https://hindisubbedacademy.com', `${serverBaseUrl}/${siteId}`)
      .replaceAll('https://animevilla.org', `${serverBaseUrl}/${siteId}`);

  const server = http.createServer((req, res) => {
    const parsed = new URL(req.url, serverBaseUrl);
    const parts = parsed.pathname.split('/').filter(Boolean);
    const siteId = parts[0];
    const subPath = '/' + parts.slice(1).join('/');

    if (!siteId || !['lordsanime', 'tpxsub', 'ybxanime', 'hindisubbedacademy', 'animevilla'].includes(siteId)) {
      res.writeHead(404);
      res.end('Unknown site');
      return;
    }

    // Set a session cookie to verify cookie persistence
    res.setHeader('Set-Cookie', `site_session=${siteId}_ok; Path=/`);

    // Kiranime ?s= error simulation to verify automatic fallback to /search/?keyword=
    if ((siteId === 'hindisubbedacademy' || siteId === 'animevilla') && parsed.searchParams.has('s')) {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<html><body><p>Something went wrong.</p></body></html>');
      return;
    }

    if (parsed.searchParams.has('s') || subPath.startsWith('/search')) {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(rewriteUrls(fixtures[`${siteId}-search`], siteId));
      return;
    }

    if (subPath.startsWith('/watch/')) {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(rewriteUrls(fixtures['animevilla-watch'], siteId));
      return;
    }

    if (subPath === '/' || subPath === '/homepage' || subPath === '/homepage/') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(rewriteUrls(fixtures[`${siteId}-home`], siteId));
      return;
    }

    // Detail page
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(rewriteUrls(fixtures[`${siteId}-detail`], siteId));
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  serverBaseUrl = `http://127.0.0.1:${port}`;

  try {
    const configManager = new DomainConfigManager({ localPath: LOCAL_CONFIG });
    await configManager.load();

    // Hot-update all 5 baseUrls to point to our mock HTTP server without recompiling
    const siteIds = ['lordsanime', 'tpxsub', 'ybxanime', 'hindisubbedacademy', 'animevilla'];
    for (const id of siteIds) {
      await configManager.updateBaseUrl(id, `${serverBaseUrl}/${id}`);
    }

    const cookieJar = new CookieJar();
    const httpClient = new HttpClient({ cookieJar });
    const registry = new SourceRegistry({ configManager, httpClient });

    for (const id of siteIds) {
      const source = registry.getSource(id);

      // 1. Latest updates
      const latest = await source.getLatestUpdates();
      assert.ok(latest.items.length >= 1, `${id}: expected latest items`);

      // 2. Search
      const search = await source.search('anime');
      assert.ok(search.items.length >= 1, `${id}: expected search items`);

      // 3. Detail & episodes
      const detail = await source.getSeriesDetail(search.items[0].url);
      assert.ok(detail.title.length > 0, `${id}: expected detail title`);
      assert.ok(detail.episodes.length >= 2, `${id}: expected episodes`);
      assert.ok(
        detail.episodes.some((ep) => ep.streams.length > 0),
        `${id}: expected stream links`
      );
    }

    // 4. Verify AnimeVilla automatically extracted embedded watch streams
    const animeVillaDetail = await registry
      .getSource('animevilla')
      .getSeriesDetail(`${serverBaseUrl}/animevilla/anime/one-punch-man-hindi-dubbed-hindi-dub/`);
    assert.ok(animeVillaDetail.embeddedStreams.some((s) => s.url.endsWith('.m3u8')));

    // 5. Verify cookies persisted from HTTP responses
    assert.ok(cookieJar.getCookieHeader(serverBaseUrl).includes('site_session='));

    // 6. Verify WebView handoff for a redirector episode link
    const tpxDetail = await registry
      .getSource('tpxsub')
      .getSeriesDetail(`${serverBaseUrl}/tpxsub/moribito-guardian-of-the-spirit-season-01-e01/`);
    const mir1080 = tpxDetail.episodes[0].streams.find((s) => s.quality === '1080p');
    const resolved = await registry.getSource('tpxsub').resolveEpisodeStream(mir1080, {
      interactiveHandler: async (session) => {
        session.onPageStarted('https://links.tpxsub.com/verify');
        session.shouldInterceptRequest('https://pixeldrain.com/u/MoribitoEp1FHD');
      },
    });
    assert.equal(resolved.status, 'resolved');
    assert.equal(resolved.stream.url, 'https://pixeldrain.com/api/file/MoribitoEp1FHD');
  } finally {
    server.close();
  }
});
