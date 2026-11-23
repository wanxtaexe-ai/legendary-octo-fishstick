import { WordPressSource } from '../parser/wordpressSource.js';

export class TpxSubSource extends WordPressSource {
  constructor({ configManager, httpClient }) {
    super({ siteId: 'tpxsub', configManager, httpClient });
  }
}
