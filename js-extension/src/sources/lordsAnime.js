import { WordPressSource } from '../parser/wordpressSource.js';

export class LordsAnimeSource extends WordPressSource {
  constructor({ configManager, httpClient }) {
    super({ siteId: 'lordsanime', configManager, httpClient });
  }
}
