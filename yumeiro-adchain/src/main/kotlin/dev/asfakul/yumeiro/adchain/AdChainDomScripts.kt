package dev.asfakul.yumeiro.adchain

/**
 * Non-destructive document-start and stage-inspection JavaScript payloads for Yumeiro's AdChain
 * WebView controller.
 *
 * Design rules learned from open-source shortener studies:
 * 1. Spoof `document.hidden = false` / `visibilityState = 'visible'` so countdown timers do not
 *    pause in an off-screen WebView.
 * 2. Neutralize `window.open` pop-unders and full-screen transparent click-catcher overlays while
 *    preserving verification iframes (`challenges.cloudflare.com`, `recaptcha`, `hcaptcha`).
 * 3. NEVER call synthetic `.click()` on Turnstile/reCAPTCHA checkboxes (`Event.isTrusted === false`
 *    triggers infinite reload loops on modern anti-bot shorteners).
 */
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
    var noopOpen = function() { return null; };
    Object.defineProperty(window, 'open', { value: noopOpen, writable: false, configurable: false });
  } catch (_) {
    try { window.open = function() { return null; }; } catch (__) {}
  }

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
            el.offsetWidth >= vw * 0.9 &&
            el.offsetHeight >= vh * 0.9 &&
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

    /**
     * Evaluates the current DOM state without forging untrusted clicks on CAPTCHA widgets.
     * Returns a compact JSON string describing the next safe step.
     */
    const val INSPECT_STAGE_JS = """
(function() {
  try {
    var html = document.documentElement ? document.documentElement.innerHTML.toLowerCase() : '';
    var hasHumanChallenge = !!(
      document.querySelector('.cf-turnstile, .g-recaptcha, .h-captcha, iframe[src*="challenges.cloudflare.com"], iframe[src*="recaptcha"], iframe[src*="hcaptcha"]') ||
      html.indexOf('please click on below captcha box') >= 0
    );
    var recaptchaTokenEl = document.querySelector('textarea[name="g-recaptcha-response"], input[name="cf-turnstile-response"]');
    var challengeSolved = !!(recaptchaTokenEl && recaptchaTokenEl.value && recaptchaTokenEl.value.trim().length > 10);

    if (hasHumanChallenge && !challengeSolved) {
      return JSON.stringify({ stage: 'human_challenge' });
    }

    // 1. AdLinkFly /links/go form ready for XHR submission
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

    // 2. Ready destination / next-step anchor
    var nextAnchor = document.querySelector('#nextPage a, a.get-link:not(.disabled), #btn-get-link:not(.disabled), #get-link-btn:not(.disabled)');
    if (nextAnchor && nextAnchor.href && /^https?:/i.test(nextAnchor.href)) {
      return JSON.stringify({ stage: 'next_anchor', url: nextAnchor.href });
    }

    // 3. WP Safelink form[name=tp]
    var tpForm = document.querySelector('form[name="tp"]');
    if (tpForm) {
      var tpFields = {};
      var tpInputs = tpForm.querySelectorAll('input[name]');
      for (var j = 0; j < tpInputs.length; j++) {
        tpFields[tpInputs[j].name] = tpInputs[j].value || '';
      }
      return JSON.stringify({
        stage: 'safelink_form',
        action: tpForm.getAttribute('action') || window.location.href,
        method: (tpForm.getAttribute('method') || 'GET').toUpperCase(),
        fields: tpFields
      });
    }

    // 4. Captured video/source element
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
