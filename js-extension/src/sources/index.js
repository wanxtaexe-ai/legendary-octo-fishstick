import { DomainConfigManager } from '../config/domainConfigManager.js';
import { HttpClient } from '../http/httpClient.js';
import { LordsAnimeSource } from './lordsAnime.js';
import { TpxSubSource } from './tpxSub.js';
import { YbxAnimeSource } from './ybxAnime.js';
import { HindiSubbedAcademySource } from './hindiSubbedAcademy.js';
import { AnimeVillaSource } from './animeVilla.js';

export class SourceRegistry {
  constructor({ configManager = new DomainConfigManager(), httpClient = new HttpClient() } = {}) {
    this.configManager = configManager;
    this.httpClient = httpClient;
    this.sources = new Map([
      ['lordsanime', new LordsAnimeSource({ configManager, httpClient })],
      ['tpxsub', new TpxSubSource({ configManager, httpClient })],
      ['ybxanime', new YbxAnimeSource({ configManager, httpClient })],
      ['hindisubbedacademy', new HindiSubbedAcademySource({ configManager, httpClient })],
      ['animevilla', new AnimeVillaSource({ configManager, httpClient })],
    ]);
  }

  getSource(siteId) {
    const source = this.sources.get(siteId);
    if (!source) {
      throw new Error(`Unsupported source id: ${siteId}`);
    }
    return source;
  }

  getAllSources() {
    return Array.from(this.sources.values());
  }

  async searchAll(query) {
    const entries = await Promise.allSettled(
      this.getAllSources().map(async (src) => ({
        siteId: src.siteId,
        result: await src.search(query),
      }))
    );
    return entries.map((entry, idx) => {
      const siteId = this.getAllSources()[idx].siteId;
      if (entry.status === 'fulfilled') return entry.value;
      return { siteId, error: entry.reason?.message || 'Search failed', result: null };
    });
  }
}
