import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DomainConfigManager } from '../src/config/domainConfigManager.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const LOCAL_CONFIG = path.resolve(__dirname, '../../domains.json');

test('DomainConfigManager loads all 5 WordPress sites from domains.json', async () => {
  const manager = new DomainConfigManager({ localPath: LOCAL_CONFIG });
  const config = await manager.load();

  assert.equal(config.version, 1);
  assert.ok(config.defaultUserAgent.includes('Mozilla/5.0'));

  const expectedIds = [
    'lordsanime',
    'tpxsub',
    'ybxanime',
    'hindisubbedacademy',
    'animevilla',
  ];
  for (const id of expectedIds) {
    const site = await manager.getSiteConfig(id);
    assert.equal(site.id, id);
    assert.equal(site.enabled, true);
    assert.ok(site.baseUrl.startsWith('https://'));
  }
});

test('DomainConfigManager fetches remote domains.json and updates baseUrl without recompiling', async () => {
  const remoteConfig = {
    version: 2,
    defaultUserAgent: 'CustomAgent/2.0',
    sites: {
      lordsanime: {
        id: 'lordsanime',
        name: "Lord's Anime Mirror",
        baseUrl: 'https://mirror.lordsanime.net',
        searchPath: '/?s={query}',
        latestPath: '/',
        enabled: true,
      },
    },
  };

  const mockFetcher = async () => ({
    ok: true,
    json: async () => remoteConfig,
  });

  const manager = new DomainConfigManager({
    remoteUrl: 'https://raw.githubusercontent.com/example/repo/main/domains.json',
    localPath: LOCAL_CONFIG,
    fetcher: mockFetcher,
  });

  const base = await manager.getBaseUrl('lordsanime');
  assert.equal(base, 'https://mirror.lordsanime.net');

  const searchUrl = await manager.buildSearchUrl('lordsanime', 'komi san');
  assert.equal(searchUrl, 'https://mirror.lordsanime.net/?s=komi%20san');

  await manager.updateBaseUrl('lordsanime', 'https://v3.lordsanime.org/');
  assert.equal(await manager.getBaseUrl('lordsanime'), 'https://v3.lordsanime.org');
});

test('DomainConfigManager falls back to local domains.json when remote fails', async () => {
  const failingFetcher = async () => {
    throw new Error('Network unreachable');
  };

  const manager = new DomainConfigManager({
    remoteUrl: 'https://example.invalid/domains.json',
    localPath: LOCAL_CONFIG,
    fetcher: failingFetcher,
  });

  assert.equal(await manager.getBaseUrl('tpxsub'), 'https://www.tpxsub.com');
  assert.equal(
    await manager.buildSearchUrl('animevilla', 'one punch', 1, { useFallback: true }),
    'https://animevilla.org/search/?keyword=one%20punch'
  );
});
