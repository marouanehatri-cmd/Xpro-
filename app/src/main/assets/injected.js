/**
 * X Pro: injected.js
 *
 * A. inject injected.css
 * B. capture video URLs from X's own API responses (fetch/XHR hooks)
 * C. add a download button on every <video> (re-scanned every 2 s)
 * D. hide "Open in app" banners and promoted tweets
 *
 * Talks to Android through window.Android (see MainActivity.Bridge).
 */
(function () {
  'use strict';
  if (window.__xproInstalled) return;          // idempotent: safe to inject twice
  window.__xproInstalled = true;

  var BTN_CLASS = 'xpro-dl';
  var HIDDEN_CLASS = 'xpro-hidden';
  var API_RE = /\/i\/api\/|\/\/api\.(x|twitter)\.com\//;

  var byMedia = {};   // mediaId -> best mp4 URL
  var byTweet = {};   // tweetId -> best mp4 URL

  function toast(msg) { try { window.Android.toast(msg); } catch (e) { /* not in app */ } }

  /* ---------------------------------------------------------------- A. CSS */

  function ensureStyle() {
    if (document.getElementById('xpro-style')) return;
    var parent = document.head || document.documentElement;
    if (!parent) return;                        // too early; the next tick retries
    var s = document.createElement('style');
    s.id = 'xpro-style';
    s.textContent = window.__XPRO_CSS || '';
    parent.appendChild(s);
  }
  ensureStyle();

  /* ------------------------------------------- B. Capture mp4 URLs from API JSON */

  function bestMp4(variants) {
    var best = null;
    for (var i = 0; i < variants.length; i++) {
      var v = variants[i];
      if (v && v.content_type === 'video/mp4' && v.url) {
        if (!best || (v.bitrate || 0) > (best.bitrate || 0)) best = v;
      }
    }
    return best ? best.url : null;
  }

  function mediaIdFrom(s) {
    var m = /(?:amplify_video_thumb|ext_tw_video_thumb|amplify_video|ext_tw_video)\/(\d+)/.exec(s || '');
    return m ? m[1] : null;
  }

  function scan(o, depth, seen) {
    if (!o || typeof o !== 'object' || depth > 40 || seen.has(o)) return;
    seen.add(o);
    if (o.video_info && o.video_info.variants) {
      var url = bestMp4(o.video_info.variants);
      if (url) {
        var mid = mediaIdFrom(o.media_url_https) || mediaIdFrom(url);
        if (mid) byMedia[mid] = url;
        var t = /status\/(\d+)/.exec(o.expanded_url || '');
        if (t && !byTweet[t[1]]) byTweet[t[1]] = url;
      }
    }
    for (var k in o) {
      if (Object.prototype.hasOwnProperty.call(o, k)) scan(o[k], depth + 1, seen);
    }
  }
  function scanRoot(o) { try { scan(o, 0, new WeakSet()); } catch (e) { /* ignore */ } }

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input) {
      var p = origFetch.apply(this, arguments);
      try {
        var u = typeof input === 'string' ? input : (input && input.url) || '';
        if (API_RE.test(u)) {
          p.then(function (r) {
            r.clone().json().then(scanRoot).catch(function () {});
          }).catch(function () {});
        }
      } catch (e) { /* never break the page */ }
      return p;
    };
  }

  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    var xhr = this;
    if (API_RE.test(String(url))) {
      xhr.addEventListener('load', function () {
        try { scanRoot(JSON.parse(xhr.responseText)); } catch (e) { /* ignore */ }
      });
    }
    return origOpen.apply(this, arguments);
  };

  /* ---------------------------------------------- C. Download buttons on videos */

  function nearbyTweetIds(video) {
    var ids = [];
    function add(href) {
      var m = /\/status\/(\d+)/.exec(href || '');
      if (m && ids.indexOf(m[1]) === -1) ids.push(m[1]);
    }
    var el = video;
    while (el && el !== document.body) {
      var links = el.querySelectorAll ? el.querySelectorAll('a[href*="/status/"]') : [];
      for (var i = 0; i < links.length; i++) add(links[i].getAttribute('href'));
      if (ids.length || el.tagName === 'ARTICLE') break;
      el = el.parentElement;
    }
    add(location.pathname);
    return ids;
  }

  function resolveFromCache(video, ids) {
    var src = video.currentSrc || video.src || '';
    if (/^https:/.test(src) && /\.mp4(\?|$)/.test(src)) return src;
    var mid = mediaIdFrom(video.poster || '');
    if (mid && byMedia[mid]) return byMedia[mid];
    for (var i = 0; i < ids.length; i++) if (byTweet[ids[i]]) return byTweet[ids[i]];
    return null;
  }

  async function fetchSyndication(id) {
    try {
      var token = ((Number(id) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '');
      var r = await fetch('https://cdn.syndication.twimg.com/tweet-result?id=' + id +
                          '&lang=en&token=' + token);
      if (!r.ok) return null;
      var j = await r.json();
      var media = (j && j.mediaDetails) || [];
      for (var i = 0; i < media.length; i++) {
        if (media[i].video_info && media[i].video_info.variants) {
          var u = bestMp4(media[i].video_info.variants);
          if (u) return u;
        }
      }
    } catch (e) { /* fall through */ }
    return null;
  }

  async function downloadFor(video) {
    var ids = nearbyTweetIds(video);
    var url = resolveFromCache(video, ids);

    if (!url && window.__INITIAL_STATE__) {
      scanRoot(window.__INITIAL_STATE__);
      url = resolveFromCache(video, ids);
    }
    for (var i = 0; !url && i < ids.length; i++) url = await fetchSyndication(ids[i]);

    if (!url) { toast('Video link not found. Reload the page and try again.'); return; }
    var name = ids[0] || mediaIdFrom(url) || String(Date.now());
    window.Android.downloadVideo(url, name);
  }

  function onDownloadClick(video, btn) {
    if (btn.__busy) return;
    btn.__busy = true;
    var label = btn.textContent;
    btn.textContent = '\u2026';
    downloadFor(video)
      .catch(function () { toast('Download failed'); })
      .then(function () { btn.textContent = label; btn.__busy = false; });
  }

  function attachButtons() {
    document.querySelectorAll('video').forEach(function (video) {
      var host = video.closest('[data-testid="videoPlayer"]') || video.parentElement;
      if (!host || host.querySelector('.' + BTN_CLASS)) return;
      if (getComputedStyle(host).position === 'static') host.style.position = 'relative';

      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = BTN_CLASS;
      btn.textContent = '\u2B07\uFE0E';

      ['pointerdown', 'pointerup', 'mousedown', 'touchstart'].forEach(function (ev) {
        btn.addEventListener(ev, function (e) { e.stopPropagation(); });
      });
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        onDownloadClick(video, btn);
      });
      host.appendChild(btn);
    });
  }

  /* ------------------------------------------------- D. Banners and promoted tweets */

  var APP_RE = /open in (the )?app|get the app|use the app|try the app|download the app|ouvrir dans l.application/i;

  var PROMO_LABELS = ['promoted', 'ad', 'sponsored', 'promu', 'sponsoris\u00e9',
                      'gesponsert', 'anzeige', 'promocionado', 'patrocinado',
                      '\u0625\u0639\u0644\u0627\u0646', '\u0645\u0645\u0648\u0644'];

  function hidePromoBanners() {
    document.querySelectorAll('[role="dialog"],[data-testid="sheetDialog"]').forEach(function (el) {
      if (APP_RE.test(el.textContent || '')) {
        (el.closest('#layers > div') || el).classList.add(HIDDEN_CLASS);
      }
    });
  }

  function hideAds() {
    document.querySelectorAll('article[data-testid="tweet"]').forEach(function (art) {
      var cell = art.closest('[data-testid="cellInnerDiv"]') || art;
      if (cell.classList.contains(HIDDEN_CLASS)) return;
      var spans = art.querySelectorAll('span');
      for (var i = 0; i < spans.length; i++) {
        var s = spans[i];
        if (s.children.length) continue;
        var t = (s.textContent || '').trim().toLowerCase();
        if (t && PROMO_LABELS.indexOf(t) !== -1) {
          cell.classList.add(HIDDEN_CLASS);
          return;
        }
      }
    });
  }

  /* ------------------------------------------------------------------- scheduling */

  function tick() {
    ensureStyle();
    attachButtons();
    hidePromoBanners();
    hideAds();
  }
  setInterval(tick, 2000);

  var pending = null;
  new MutationObserver(function () {
    if (pending) return;
    pending = setTimeout(function () {
      pending = null;
      ensureStyle();
      hideAds();
    }, 250);
  }).observe(document, { childList: true, subtree: true });
})();
