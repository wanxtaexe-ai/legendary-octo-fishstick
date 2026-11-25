package com.animeext.downloader.resolver

object AdChainDomScripts {

    /**
     * Non-invasive DOM overlay cleaner. Does NOT overwrite `window.open` or `document.hidden`
     * descriptors (which anti-tamper shorteners/indexes detect and redirect to error pages);
     * popup blocking is enforced natively by WebChromeClient.onCreateWindow + AdFilterPolicy.
     */
    const val DOCUMENT_START_JS = """
(function() {
  'use strict';
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

  if (location && location.hostname && location.hostname.indexOf('lordsanime') >= 0) {
    try {
      var obs = new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
          var added = mutations[i].addedNodes;
          for (var j = 0; j < added.length; j++) {
            var n = added[j];
            if (n.nodeType !== 1) continue;
            if (n.tagName === 'A') {
              var h = n.getAttribute('href') || '';
              if (h.indexOf('epdexx.com') >= 0 && !n.dataset.origHref) n.dataset.origHref = h;
            } else if (n.querySelectorAll) {
              var as = n.querySelectorAll('a[href*="epdexx.com"]');
              for (var k = 0; k < as.length; k++) {
                var ah = as[k].getAttribute('href') || '';
                if (!as[k].dataset.origHref) as[k].dataset.origHref = ah;
              }
            }
          }
        }
      });
      if (document.documentElement) {
        obs.observe(document.documentElement, { childList: true, subtree: true });
      }
    } catch (_) {}
  }
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
        var href = (anchors[k].href || '').toLowerCase();
        if (!txt || !/^https?:/i.test(href)) continue;
        if (href.indexOf('tryedesign.') >= 0 || href.indexOf('rvpaste.') >= 0) continue;
        if (txt === 'download file' || txt === 'download now' || txt === 'click here to continue') {
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

    // Post-captcha / countdown CTA auto-progression (only visible, enabled, non-waiting controls)
    var ctaExact = [
      'continue', 'continue...', 'click here to continue', 'verify', 'human verify',
      'get link', 'go to link', 'download file', 'download now',
      'generate direct download link', 'proceed', 'unlock link', 'submit'
    ];
    var candidates = document.querySelectorAll('a[href], button, input[type="submit"], input[type="button"]');
    for (var c = 0; c < candidates.length; c++) {
      var el = candidates[c];
      if (el.disabled || el.classList.contains('disabled') || el.getAttribute('aria-disabled') === 'true') continue;
      if (el.dataset && el.dataset.avClicked === '1') continue;
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') < 0.2) continue;
      if (el.offsetWidth <= 0 || el.offsetHeight <= 0) continue;

      var rawTxt = ((el.innerText || el.value || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
      if (!rawTxt || rawTxt.length > 48) continue;
      if (rawTxt.indexOf('wait') >= 0 || rawTxt.indexOf('loading') >= 0 || rawTxt.indexOf('second') >= 0) continue;

      var matched = false;
      for (var t = 0; t < ctaExact.length; t++) {
        if (rawTxt === ctaExact[t]) { matched = true; break; }
      }
      if (!matched) continue;

      if (el.tagName === 'A') {
        var aHref = (el.href || '').trim();
        var aLower = aHref.toLowerCase();
        if (!/^https?:/i.test(aHref)) continue;
        if (aLower.indexOf('tryedesign.') >= 0 || aLower.indexOf('rvpaste.') >= 0 ||
            aLower.indexOf('core-x.live') >= 0 || aLower.indexOf('1xlite') >= 0 ||
            aLower.indexOf('1xbet') >= 0 || aLower.indexOf('t.me/') >= 0 ||
            aLower.indexOf('telegram.') >= 0) {
          continue;
        }
        el.dataset.avClicked = '1';
        return JSON.stringify({ stage: 'next_anchor', url: aHref });
      } else {
        el.dataset.avClicked = '1';
        setTimeout(function(node) {
          return function() { try { delete node.dataset.avClicked; } catch (_) {} };
        }(el), 4500);
        el.click();
        return JSON.stringify({ stage: 'auto_clicked', label: rawTxt });
      }
    }

    var isGateOrTutorialPage = !!(
      goForm || tpForm || nextAnchor || hasTurnstile || hasRecaptcha || hasHcaptcha ||
      document.querySelector('#nextPage, a.get-link, #btn-get-link, #get-link-btn') ||
      html.indexOf('please wait') >= 0 ||
      html.indexOf('how to download') >= 0 ||
      html.indexOf('tutorial') >= 0 ||
      html.indexOf('adblocker detected') >= 0
    );

    if (!isGateOrTutorialPage) {
      var mediaEls = document.querySelectorAll('video[src], video source[src]');
      for (var m = 0; m < mediaEls.length; m++) {
        var mSrc = (mediaEls[m].src || '').trim();
        var mLower = mSrc.toLowerCase();
        if (!/^https?:/i.test(mSrc)) continue;
        if (/\.webm(\?|$)/i.test(mLower)) continue;
        if (mLower.indexOf('wp-content/uploads') >= 0 ||
            mLower.indexOf('tutorial') >= 0 ||
            mLower.indexOf('how-to') >= 0 ||
            mLower.indexOf('howto') >= 0 ||
            mLower.indexOf('promo') >= 0 ||
            mLower.indexOf('trailer') >= 0 ||
            mLower.indexOf('bumper') >= 0) {
          continue;
        }
        return JSON.stringify({ stage: 'direct_media', url: mSrc });
      }
    }

    return JSON.stringify({ stage: 'waiting' });
  } catch (e) {
    return JSON.stringify({ stage: 'error', message: String(e) });
  }
})();
"""
}
