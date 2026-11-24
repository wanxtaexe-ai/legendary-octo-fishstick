import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  parseCatalogCards,
  parseNextPageUrl,
  parseSeriesDetail,
  normalizeQualityLabel,
} from '../src/parser/semanticParser.js';
import { extractEmbeddedVideoSources } from '../src/resolver/mediaHostMatcher.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES_DIR = path.join(__dirname, 'fixtures');

async function readFixture(name) {
  return fs.readFile(path.join(FIXTURES_DIR, name), 'utf8');
}

test('normalizeQualityLabel maps FHD/HD/SD and pixel heights consistently', () => {
  assert.equal(normalizeQualityLabel('FHD[400MB]'), '1080p');
  assert.equal(normalizeQualityLabel('Full HD 1080p'), '1080p');
  assert.equal(normalizeQualityLabel('HD [200MB]'), '720p');
  assert.equal(normalizeQualityLabel('SD [99MB]'), '480p');
  assert.equal(normalizeQualityLabel('480p'), '480p');
  assert.equal(normalizeQualityLabel('360p'), '360p');
  assert.equal(normalizeQualityLabel('LAS Link'), 'Unknown');
});

test('lordsanime.com: parses latest, search, detail metadata, seasons & quality streams', async () => {
  const homeHtml = await readFixture('lordsanime-home.html');
  const latest = parseCatalogCards(homeHtml, 'https://www.lordsanime.com');
  assert.equal(latest.length, 2);
  assert.match(latest[0].title, /Komi Can’t Communicate/);
  // Skips SVG placeholder and reads data-src
  assert.equal(
    latest[0].thumbnail,
    'https://www.lordsanime.com/wp-content/uploads/2023/10/Photoleap_17_10_2023_19_47_51_8NU3e-390x220.jpg'
  );
  assert.equal(parseNextPageUrl(homeHtml, 'https://www.lordsanime.com'), 'https://www.lordsanime.com/page/2/');

  const searchHtml = await readFixture('lordsanime-search.html');
  const searchItems = parseCatalogCards(searchHtml, 'https://www.lordsanime.com');
  assert.equal(searchItems.length, 1);
  assert.ok(searchItems[0].genres.includes('Hindi Subbed'));

  const detailHtml = await readFixture('lordsanime-detail.html');
  const detail = parseSeriesDetail(
    detailHtml,
    'https://www.lordsanime.com/komi-cant-communicate-season-1-2-hindi-subbed-24-24-komi-san-wa-comyushou-desu-hindi-sub/'
  );
  assert.equal(detail.status, 'Completed');
  assert.ok(detail.genres.includes('Comedy'));
  assert.ok(detail.synopsis.includes('Hitohito Tadano'));
  assert.equal(detail.episodes.length, 3);
  assert.equal(detail.episodes[0].season, 1);
  assert.equal(detail.episodes[0].episodeRange, '01-06');
  assert.equal(detail.episodes[0].streams[0].quality, '720p');
  assert.equal(detail.episodes[1].streams[0].quality, '1080p');
  assert.equal(detail.episodes[1].streams[0].requiresWebView, false); // Pixeldrain
  assert.equal(detail.episodes[2].season, 2);
  assert.equal(detail.episodes[2].streams[0].quality, '480p');
});

test('tpxsub.com: parses latest, search, and per-episode 1080p/720p/480p quality tiers', async () => {
  const homeHtml = await readFixture('tpxsub-home.html');
  const latest = parseCatalogCards(homeHtml, 'https://www.tpxsub.com');
  assert.equal(latest.length, 2);
  assert.equal(latest[0].thumbnail, 'https://www.tpxsub.com/wp-content/uploads/2026/07/MORIBITO-470x264.jpg');

  const searchHtml = await readFixture('tpxsub-search.html');
  const search = parseCatalogCards(searchHtml, 'https://www.tpxsub.com');
  assert.equal(search.length, 1);
  assert.match(search[0].title, /Moribito/);

  const detailHtml = await readFixture('tpxsub-detail.html');
  const detail = parseSeriesDetail(detailHtml, 'https://www.tpxsub.com/moribito-guardian-of-the-spirit-season-01-e01/');
  assert.ok(detail.genres.includes('Fantasy'));
  assert.ok(detail.synopsis.includes('Prince Chagum'));
  assert.equal(detail.episodes.length, 2);

  const ep1 = detail.episodes[0];
  assert.equal(ep1.episodeNumber, 1);
  const qualities = ep1.streams.map((s) => s.quality);
  assert.ok(qualities.includes('1080p'));
  assert.ok(qualities.includes('720p'));
  assert.ok(qualities.includes('480p'));
  assert.equal(ep1.streams.find((s) => s.quality === '1080p').size, '400MB');
  assert.equal(ep1.streams.find((s) => s.quality === '720p').size, '200MB');
  assert.equal(ep1.streams.find((s) => s.quality === '480p').size, '99MB');
});

test('ybxanime.com: parses list-based latest/search cards and detail episode ranges', async () => {
  const homeHtml = await readFixture('ybxanime-home.html');
  const latest = parseCatalogCards(homeHtml, 'https://ybxanime.com');
  assert.equal(latest.length, 2);
  assert.match(latest[0].title, /Dragon Ball Super/);

  const searchHtml = await readFixture('ybxanime-search.html');
  const search = parseCatalogCards(searchHtml, 'https://ybxanime.com');
  assert.equal(search.length, 2);
  assert.equal(parseNextPageUrl(searchHtml, 'https://ybxanime.com'), 'https://ybxanime.com/page/2/?s=shy');

  const detailHtml = await readFixture('ybxanime-detail.html');
  const detail = parseSeriesDetail(detailHtml, 'https://ybxanime.com/shy-hindi-subbed/');
  assert.equal(detail.status, 'Completed');
  assert.ok(detail.genres.includes('Action'));
  assert.equal(detail.episodes.length, 2);
  assert.deepEqual(
    detail.episodes[0].streams.map((s) => s.quality),
    ['1080p', '720p']
  );
  assert.equal(detail.episodes[1].streams[0].quality, '480p');
});

test('hindisubbedacademy.com: parses Kiranime catalog, search, and detail episodes', async () => {
  const homeHtml = await readFixture('hindisubbedacademy-home.html');
  const latest = parseCatalogCards(homeHtml, 'https://hindisubbedacademy.com');
  assert.equal(latest.length, 2);
  assert.match(latest[0].url, /\/anime\/how-not-to-summon-a-demon-lord-hindi-subbed/);

  const searchHtml = await readFixture('hindisubbedacademy-search.html');
  const search = parseCatalogCards(searchHtml, 'https://hindisubbedacademy.com');
  assert.equal(search.length, 1);

  const detailHtml = await readFixture('hindisubbedacademy-detail.html');
  const detail = parseSeriesDetail(
    detailHtml,
    'https://hindisubbedacademy.com/anime/how-not-to-summon-a-demon-lord-hindi-subbed/'
  );
  assert.equal(detail.status, 'Completed');
  assert.ok(detail.genres.includes('Isekai'));
  assert.equal(detail.episodes.length, 2);
  assert.equal(detail.episodes[0].streams[0].quality, '720p');
  assert.equal(detail.episodes[1].streams[0].quality, '1080p');
});

test('animevilla.org: parses detail with inline multi-quality links and embedded watch streams', async () => {
  const homeHtml = await readFixture('animevilla-home.html');
  const latest = parseCatalogCards(homeHtml, 'https://animevilla.org');
  assert.equal(latest.length, 2);

  const detailHtml = await readFixture('animevilla-detail.html');
  const detail = parseSeriesDetail(
    detailHtml,
    'https://animevilla.org/anime/one-punch-man-hindi-dubbed-hindi-dub/'
  );
  assert.equal(detail.status, 'Completed');
  assert.equal(detail.watchUrl, 'https://animevilla.org/watch/one-punch-man-hindi-dubbed-hindi-dub/');
  assert.equal(detail.episodes.length, 2);
  // Verifies per-link quality extraction on the same line: "480p – [HSA DD] | 720p – [HSA DD]"
  assert.deepEqual(
    detail.episodes[0].streams.map((s) => s.quality),
    ['480p', '720p']
  );
  assert.equal(detail.episodes[1].streams[0].quality, '1080p');

  const watchHtml = await readFixture('animevilla-watch.html');
  const embedded = extractEmbeddedVideoSources(
    watchHtml,
    'https://animevilla.org/watch/one-punch-man-hindi-dubbed-hindi-dub/'
  );
  assert.ok(embedded.sources.some((s) => s.url.endsWith('.mp4') && s.quality === '720p'));
  assert.ok(embedded.sources.some((s) => s.url.endsWith('.m3u8') && s.quality === '1080p'));
  assert.equal(embedded.iframes.length, 1);
  assert.equal(embedded.iframes[0].url, 'https://player.animevilla.org/embed/opm-ep1');
});
