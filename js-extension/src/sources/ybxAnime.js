import { WordPressSource } from '../parser/wordpressSource.js';

export class YbxAnimeSource extends WordPressSource {
  constructor({ configManager, httpClient }) {
    super({ siteId: 'ybxanime', configManager, httpClient });
  }
}
