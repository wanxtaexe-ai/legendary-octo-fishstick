import { WordPressSource } from '../parser/wordpressSource.js';

export class AnimeVillaSource extends WordPressSource {
  constructor({ configManager, httpClient }) {
    super({ siteId: 'animevilla', configManager, httpClient });
  }
}
