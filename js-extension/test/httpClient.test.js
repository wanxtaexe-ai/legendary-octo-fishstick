import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs/promises';
import { CookieJar } from '../src/http/cookieJar.js';
import { HttpClient, DEFAULT_USER_AGENT } from '../src/http/httpClient.js';

test('HttpClient sends User-Agent and persists cookies across redirects', async () => {
  const seenRequests = [];

  const server = http.createServer((req, res) => {
    seenRequests.push({
      url: req.url,
      ua: req.headers['user-agent'],
      cookie: req.headers.cookie || null,
      referer: req.headers.referer || null,
    });

    if (req.url === '/start') {
      res.writeHead(302, {
        Location: '/protected',
        'Set-Cookie': ['cf_clearance=token123; Path=/; HttpOnly', 'prefs=hd; Path=/'],
      });
      res.end();
      return;
    }

    if (req.url === '/protected') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`<html><body>Cookie=${req.headers.cookie}</body></html>`);
      return;
    }

    res.writeHead(404);
    res.end('Not found');
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    const cookieJar = new CookieJar();
    const client = new HttpClient({ cookieJar });
    const result = await client.getHtml(`${baseUrl}/start`);

    assert.equal(result.status, 200);
    assert.equal(result.url, `${baseUrl}/protected`);
    assert.deepEqual(result.redirectChain, [`${baseUrl}/start`, `${baseUrl}/protected`]);
    assert.equal(seenRequests[0].ua, DEFAULT_USER_AGENT);
    assert.equal(seenRequests[1].referer, `${baseUrl}/start`);
    assert.ok(seenRequests[1].cookie.includes('cf_clearance=token123'));
    assert.ok(seenRequests[1].cookie.includes('prefs=hd'));

    // Subsequent call retains cookie automatically
    const second = await client.getHtml(`${baseUrl}/protected`);
    assert.ok(second.html.includes('cf_clearance=token123'));
  } finally {
    server.close();
  }
});

test('CookieJar persists cookies to disk and restores them', async () => {
  const tmpFile = path.join(os.tmpdir(), `cookiejar-test-${Date.now()}.json`);
  try {
    const jar1 = new CookieJar();
    jar1.setCookie('https://www.tpxsub.com/page/1', 'session_id', 'abc999');
    await jar1.saveToFile(tmpFile);

    const jar2 = await CookieJar.loadFromFile(tmpFile);
    assert.equal(jar2.getCookieHeader('https://www.tpxsub.com/anime'), 'session_id=abc999');
  } finally {
    await fs.rm(tmpFile, { force: true });
  }
});
