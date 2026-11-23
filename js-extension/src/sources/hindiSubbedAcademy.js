import { WordPressSource } from '../parser/wordpressSource.js';

export class HindiSubbedAcademySource extends WordPressSource {
  constructor({ configManager, httpClient }) {
    super({ siteId: 'hindisubbedacademy', configManager, httpClient });
  }
}
