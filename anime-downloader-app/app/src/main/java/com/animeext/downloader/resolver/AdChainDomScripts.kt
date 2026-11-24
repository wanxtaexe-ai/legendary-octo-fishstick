package com.animeext.downloader.resolver

object AdChainDomScripts {

    const val DOCUMENT_START_JS = """
(function() {
  'use strict';
  try {
    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
  } catch (_) {}

  try {
    window.open = function() { return null; };
  } catch (_) {}

  // Neutralize Notification permission prompts inside pages
  try {
    if (window.Notification) {
      Object.defineProperty(window.Notification, 'permission', { get: function() { return 'denied'; } });
      window.Notification.requestPermission = function() { return Promise.resolve('denied'); };
    }
  } catch (_) {}

  function cleanOverlays() {
    try {
      var vw = window.innerWidth || 0;
      var vh = window.innerHeight || 0;
      if (vw === 0 || vh === 0) return;
      var els = document.querySelectorAll('body > div, body > a, body > iframe');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var src = (el.src || el.href || '') + '';
        if (src.indexOf('challenges.cloudflare.com') >= 0 ||
            src.indexOf('hcaptcha') >= 0 ||
            src.indexOf('recaptcha') >= 0) {
          continue;
        }
        var cs = getComputedStyle(el);
        var z = parseInt(cs.zIndex || '0', 10);
        if ((cs.position === 'fixed' || cs.position === 'absolute') &&
            z >= 2147483000 &&
            el.offsetWidth >= vw * 0.85 &&
            el.offsetHeight >= vh * 0.85 &&
            !(el.innerText || '').trim()) {
          el.remove();
        }
      }
    } catch (_) {}
  }

  document.addEventListener('DOMContentLoaded', cleanOverlays);
  setTimeout(cleanOverlays, 1200);
  setTimeout(cleanOverlays, 3500);
})();
"""

    const val INSPECT_STAGE_JS = """
(function() {
  try {
    var html = document.documentElement ? document.documentElement.innerHTML.toLowerCase() : '';
    var hasTurnstile = !!document.querySelector('.cf-turnstile, iframe[src*="challenges.cloudflare.com"]');
    var hasRecaptcha = !!document.querySelector('.g-recaptcha, iframe[src*="recaptcha"]');
    var hasHcaptcha = !!document.querySelector('.h-captcha, iframe[src*="hcaptcha"]');
    var hasHumanChallenge = hasTurnstile || hasRecaptcha || hasHcaptcha ||
      html.indexOf('please click on below captcha box') >= 0;

    var tokenEl = document.querySelector('textarea[name="g-recaptcha-response"], input[name="cf-turnstile-response"]');
    var challengeSolved = !!(tokenEl && tokenEl.value && tokenEl.value.trim().length > 10);

    if (hasHumanChallenge && !challengeSolved) {
      var kind = hasTurnstile ? 'cf_turnstile' : (hasRecaptcha ? 'recaptcha' : (hasHcaptcha ? 'hcaptcha' : 'unknown'));
      return JSON.stringify({ stage: 'human_challenge', kind: kind });
    }

    var goForm = document.getElementById('go-link');
    if (goForm) {
      var fields = {};
      var inputs = goForm.querySelectorAll('input[name]');
      for (var i = 0; i < inputs.length; i++) {
        fields[inputs[i].name] = inputs[i].value || '';
      }
      return JSON.stringify({
        stage: 'adlinkfly_form',
        action: goForm.getAttribute('action') || '/links/go',
        fields: fields
      });
    }

    var nextAnchor = document.querySelector('#nextPage a, a.get-link:not(.disabled), #btn-get-link:not(.disabled), #get-link-btn:not(.disabled)');
    if (!nextAnchor) {
      var anchors = document.querySelectorAll('a[href]');
      for (var k = 0; k < anchors.length; k++) {
        var txt = (anchors[k].innerText || '').trim().toLowerCase();
        if ((txt === 'download file' || txt === 'download now' || txt === 'click here to continue') &&
            anchors[k].href && /^https?:/i.test(anchors[k].href)) {
          nextAnchor = anchors[k];
          break;
        }
      }
    }
    if (nextAnchor && nextAnchor.href && /^https?:/i.test(nextAnchor.href)) {
      return JSON.stringify({ stage: 'next_anchor', url: nextAnchor.href });
    }

    var tpForm = document.querySelector('form[name="tp"]');
    if (tpForm) {
      return JSON.stringify({ stage: 'safelink_form' });
    }

    var mediaEl = document.querySelector('video[src], video source[src]');
    if (mediaEl && mediaEl.src && /^https?:/i.test(mediaEl.src)) {
      return JSON.stringify({ stage: 'direct_media', url: mediaEl.src });
    }

    return JSON.stringify({ stage: 'waiting' });
  } catch (e) {
    return JSON.stringify({ stage: 'error', message: String(e) });
  }
})();
"""
}
