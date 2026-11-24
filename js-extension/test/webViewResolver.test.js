import test from 'node:test';
import assert from 'node:assert/strict';
import { matchDirectMediaHost } from '../src/resolver/mediaHostMatcher.js';
import {
  WEBVIEW_INTERCEPT_SCRIPT,
  WebViewResolverSession,
  resolveStream,
} from '../src/resolver/webViewResolver.js';
import { HttpClient } from '../src/http/httpClient.js';

test('matchDirectMediaHost detects Pixeldrain, Gofile, .mp4, and .m3u8 URLs', () => {
  const pd = matchDirectMediaHost('https://pixeldrain.com/u/AbCdEf12');
  assert.equal(pd.hostType, 'pixeldrain');
  assert.equal(pd.streamUrl, 'https://pixeldrain.com/api/file/AbCdEf12');
  assert.equal(pd.isDirectStream, true);

  const gf = matchDirectMediaHost('https://gofile.io/d/xyz789');
  assert.equal(gf.hostType, 'gofile');

  const mp4 = matchDirectMediaHost('https://cdn.example.com/anime/ep01.mp4?token=123');
  assert.equal(mp4.hostType, 'direct');
  assert.equal(mp4.mimeType, 'video/mp4');

  const hls = matchDirectMediaHost('https://hls.example.com/master.m3u8');
  assert.equal(hls.hostType, 'direct');
  assert.equal(hls.mimeType, 'application/x-mpegURL');

  assert.equal(matchDirectMediaHost('https://doubleclick.net/pixel.mp4'), null);
  assert.equal(matchDirectMediaHost('https://cdn.example.com/segment001.ts'), null);
});

test('WebViewResolverSession blocks ads, tracks navigation, and hands off direct stream on detection', async () => {
  const session = new WebViewResolverSession({
    initialUrl: 'https://links.tpxsub.com/redirect/main2.php?url=abc',
    headers: { 'User-Agent': 'TestAgent/1.0' },
    expectedQuality: '1080p',
  });

  const promise = session.waitForResolution();

  // Ad network request should be blocked and not resolve session
  const adCheck = session.shouldInterceptRequest('https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js');
  assert.equal(adCheck.blockRequest, true);
  assert.equal(session.status, 'pending');

  // User completes intermediate countdown / click step
  session.onPageStarted('https://intermediate-shortener.example/step2');
  assert.equal(session.status, 'pending');

  // Final media host loads via network intercept or DOM hook
  const hit = session.shouldInterceptRequest('https://pixeldrain.com/u/FinalVideo99');
  assert.equal(hit.blockRequest, false);
  assert.ok(hit.resolvedStream);

  const resolved = await promise;
  assert.equal(session.status, 'resolved');
  assert.equal(resolved.url, 'https://pixeldrain.com/api/file/FinalVideo99');
  assert.equal(resolved.hostType, 'pixeldrain');
  assert.equal(resolved.quality, '1080p');
  assert.equal(resolved.headers.Referer, 'https://intermediate-shortener.example/step2');
});

test('WebViewResolverSession supports manual handoff and DOM hook script', async () => {
  assert.ok(WEBVIEW_INTERCEPT_SCRIPT.includes('AndroidStreamBridge'));
  assert.ok(WEBVIEW_INTERCEPT_SCRIPT.includes('MutationObserver'));

  const session = new WebViewResolverSession({
    initialUrl: 'https://hsalinks.eu.org/short.php?link=123',
    expectedQuality: '720p',
  });

  const resolved = session.onDomMediaDetected(
    'https://store5.gofile.io/download/direct/episode01.mp4',
    'video/mp4',
    '720p'
  );
  assert.equal(session.status, 'resolved');
  assert.equal(resolved.resolvedVia, 'webview-dom-hook');
  assert.equal(resolved.hostType, 'gofile');
});

test('resolveStream handles direct links immediately and delegates gate pages to interactive WebView', async () => {
  const client = new HttpClient();

  // 1. Direct Pixeldrain link resolves without network or WebView
  const directRes = await resolveStream(
    { url: 'https://pixeldrain.com/u/DirectId1', quality: '1080p' },
    client
  );
  assert.equal(directRes.status, 'resolved');
  assert.equal(directRes.method, 'direct');
  assert.equal(directRes.stream.url, 'https://pixeldrain.com/api/file/DirectId1');

  // 2. Gate URL delegates to interactive handler
  const mockFetcher = async () => ({
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'text/html' }),
    text: async () => '<html><body><button>Please wait 5 seconds...</button></body></html>',
  });
  const gateClient = new HttpClient({ fetcher: mockFetcher });

  const webViewRes = await resolveStream(
    { url: 'https://shortsync.epdexx.com/go/test123', quality: '720p' },
    gateClient,
    {
      interactiveHandler: async (session) => {
        session.onPageStarted('https://shortsync.epdexx.com/verify');
        session.shouldInterceptRequest('https://cdn.example.org/video/ep1-720p.m3u8');
      },
    }
  );

  assert.equal(webViewRes.status, 'resolved');
  assert.equal(webViewRes.method, 'webview-interactive');
  assert.equal(webViewRes.stream.url, 'https://cdn.example.org/video/ep1-720p.m3u8');
  assert.equal(webViewRes.stream.mimeType, 'application/x-mpegURL');
});

test('WebViewResolverSession blocks popups, intent:// hijacks, main-frame ad redirects, and returns JS stubs', () => {
  const session = new WebViewResolverSession({
    initialUrl: 'https://hsalinks.eu.org/short.php?link=step1',
    expectedQuality: '1080p',
  });

  // 1. Blocked .js ad request receives valid JS stub so anti-adblock timers don't freeze
  const adJs = session.shouldInterceptRequest('https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js');
  assert.equal(adJs.blockRequest, true);
  assert.equal(adJs.stubResponse.mimeType, 'application/javascript');
  assert.ok(adJs.stubResponse.body.includes('adsbygoogle'));

  // 2. Popunder / new-tab ad attempt is blocked without losing current page
  const popup = session.onPopupAttempt('https://popads.net/popunder');
  assert.equal(popup.allowPopup, false);
  assert.equal(session.currentUrl, 'https://hsalinks.eu.org/short.php?link=step1');

  // 3. External app-launch hijack (intent://) and main-frame ad redirect are blocked
  const intentCheck = session.shouldOverrideUrlLoading('intent://spam/#Intent;scheme=https;end');
  assert.equal(intentCheck.overrideLoad, true);
  assert.equal(intentCheck.reason, 'blocked-external-scheme');

  const mainFrameAd = session.shouldOverrideUrlLoading('https://adsterra.com/landing', {
    isForMainFrame: true,
  });
  assert.equal(mainFrameAd.overrideLoad, true);
  assert.equal(mainFrameAd.reason, 'blocked-ad-redirect');
  assert.ok(session.blockedHijacksCount >= 3);

  // 4. User can toggle adFilterEnabled off if an intermediate chain page stalls
  session.setAdFilterEnabled(false);
  const relaxed = session.shouldInterceptRequest('https://adsterra.com/chain-check.js');
  assert.equal(relaxed.blockRequest, false);

  // 5. DownloadListener captures attachment-triggered media download
  const dl = session.onDownloadTriggered(
    'https://cdn.example.net/stream/episode-01.mp4',
    'video/mp4',
    'attachment; filename="episode-01.mp4"'
  );
  assert.equal(session.status, 'resolved');
  assert.equal(dl.url, 'https://cdn.example.net/stream/episode-01.mp4');
});

