/**
 * X Pro Lite: injected.js
 *
 * A. inject injected.css
 * B. capture mp4 variants (lowest AND highest bitrate) from X's API responses
 * C. cover every video player with a thumbnail + "Play Low Data Video" button
 *    and stop X's own player from loading or autoplaying video
 * D. hide "Open in app" banners and promoted tweets
 *
 * Talks to Android through window.Android (see MainActivity.Bridge).
 */
(function () {
  'use strict';
  if (window.__xproInstalled) return;
  window.__xproInstalled = true;

  var HIDDEN_CLASS = 'xpro-hidden';
  var OV_CLASS = 'xpro-ov';
  var API_RE = /\/i\/api\/|\/\/api\.(x|twitter)\.com\//;
  var GIF_RE = /\/tweet_video\//;

  var byMedia = {};   // mediaId -> {low, high, lowBytes, highBytes}
  var byTweet = {};   // tweetId -> same

  function toast(msg) { try { window.Android.toast(msg); } catch (e) { /* not in app */ } }

  /* ---------------------------------------------------------------- A. CSS */

  function ensureStyle() {
    if (document.getElementById('xpro-style')) return;
    var parent = document.head || document.documentElement;
    if (!parent) return;
    var s = document.createElement('style');
    s.id = 'xpro-style';
    s.textContent = window.__XPRO_CSS || '';
    parent.appendChild(s);
  }
  ensureStyle();

  /* ---------------------------------------------- B. Capture variants from API JSON */

  /** mp4 variants sorted by bitrate ASCENDING: [0] = lowest (saves data), last = HD. */
  function pickVariants(variants, durMs) {
    var mp4 = [];
    for (var i = 0; i < variants.length; i++) {
      var v = variants[i];
      if (v && v.content_type === 'video/mp4' && v.url) mp4.push(v);
    }
    if (!mp4.length) return null;
    mp4.sort(function (a, b) { return (a.bitrate || 0) - (b.bitrate || 0); });
    var low = mp4[0];
    var high = mp4[mp4.length - 1];
    var secs = (durMs || 0) / 1000;
    return {
      low: low.url,
      high: high.url,
      lowBytes: Math.round((low.bitrate || 0) / 8 * secs),
      highBytes: Math.round((high.bitrate || 0) / 8 * secs)
    };
  }

  function mediaIdFrom(s) {
    var m = /(?:amplify_video_thumb|ext_tw_video_thumb|amplify_video|ext_tw_video)\/(\d+)/.exec(s || '');
    return m ? m[1] : null;
  }

  function scan(o, depth, seen) {
    if (!o || typeof o !== 'object' || depth > 40 || seen.has(o)) return;
    seen.add(o);
    if (o.video_info && o.video_info.variants) {
      var info = pickVariants(o.video_info.variants, o.video_info.duration_millis);
      if (info) {
        var mid = mediaIdFrom(o.media_url_https) || mediaIdFrom(info.low);
        if (mid) byMedia[mid] = info;
        var t = /status\/(\d+)/.exec(o.expanded_url || '');
        if (t && !byTweet[t[1]]) byTweet[t[1]] = info;
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

  /* ------------------------------ C. Replace X's player with a low-data overlay */

  // X's own player must never autoplay (GIFs are tiny and keep playing).
  var origPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (this.tagName === 'VIDEO' && !GIF_RE.test(this.currentSrc || this.src || '')) {
      try { this.pause(); } catch (e) { /* ignore */ }
      return Promise.resolve();
    }
    return origPlay.apply(this, arguments);
  };

  function quietVideos() {
    document.querySelectorAll('video').forEach(function (v) {
      if (GIF_RE.test(v.currentSrc || v.src || '')) return;
      v.preload = 'none';
      v.autoplay = false;
      if (!v.paused) { try { v.pause(); } catch (e) { /* ignore */ } }
    });
  }

  function isGif(host) {
    var v = host.querySelector('video');
    return !!v && GIF_RE.test(v.currentSrc || v.src || '');
  }

  function posterOf(host) {
    var v = host.querySelector('video');
    if (v && v.poster) return v.poster;
    var img = host.querySelector('img');
    return img ? img.src : '';
  }

  function nearbyTweetIds(el) {
    var ids = [];
    function add(href) {
      var m = /\/status\/(\d+)/.exec(href || '');
      if (m && ids.indexOf(m[1]) === -1) ids.push(m[1]);
    }
    while (el && el !== document.body) {
      var links = el.querySelectorAll ? el.querySelectorAll('a[href*="/status/"]') : [];
      for (var i = 0; i < links.length; i++) add(links[i].getAttribute('href'));
      if (ids.length || el.tagName === 'ARTICLE') break;
      el = el.parentElement;
    }
    add(location.pathname);
    return ids;
  }

  function lookup(host, ids) {
    var mid = mediaIdFrom(posterOf(host));
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
          var info = pickVariants(media[i].video_info.variants, media[i].video_info.duration_millis);
          if (info) return info;
        }
      }
    } catch (e) { /* fall through */ }
    return null;
  }

  function sizeLabel(info) {
    if (!info || !info.lowBytes) return '';
    return ' - ' + (info.lowBytes / 1e6).toFixed(1) + 'MB';
  }

  async function openNativePlayer(host, btn) {
    if (btn.__busy) return;
    btn.__busy = true;
    var label = btn.textContent;
    btn.textContent = 'Loading...';
    try {
      var ids = nearbyTweetIds(host);
      var info = lookup(host, ids);
      if (!info && window.__INITIAL_STATE__) {
        scanRoot(window.__INITIAL_STATE__);
        info = lookup(host, ids);
      }
      for (var i = 0; !info && i < ids.length; i++) info = await fetchSyndication(ids[i]);

      if (!info) {
        toast('Video link not found. Reload the page and try again.');
      } else {
        var id = ids[0] || mediaIdFrom(info.low) || String(Date.now());
        window.Android.playLowDataVideo(info.low, info.high, id, info.lowBytes, info.highBytes);
      }
    } catch (e) {
      toast('Could not open the video');
    }
    btn.textContent = label;
    btn.__busy = false;
  }

  function findHosts() {
    var hosts = [];
    function add(h) {
      if (!h || hosts.indexOf(h) !== -1) return;
      if (h.parentElement && h.parentElement.closest('[data-xpro-host]')) return;
      hosts.push(h);
    }
    document.querySelectorAll('[data-testid="videoPlayer"]').forEach(add);
    document.querySelectorAll('video').forEach(function (v) {
      add(v.closest('[data-testid="videoPlayer"]') || v.parentElement);
    });
    return hosts;
  }

  function refreshOverlay(host, ov, btn) {
    if (btn.__busy) return;
    btn.textContent = '\u25B6 Play Low Data Video' + sizeLabel(lookup(host, nearbyTweetIds(host)));
    if (!ov.__bg) {
      var p = posterOf(host);
      if (/^https:\/\/pbs\.twimg\.com\//.test(p)) {
        ov.style.backgroundImage = 'url("' + p + '")';
        ov.__bg = true;
      }
    }
  }

  function attachOverlays() {
    findHosts().forEach(function (host) {
      if (isGif(host)) return;
      var ov = null;
      for (var i = 0; i < host.children.length; i++) {
        if (host.children[i].classList.contains(OV_CLASS)) { ov = host.children[i]; break; }
      }
      var btn;
      if (!ov) {
        if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
        host.setAttribute('data-xpro-host', '1');

        ov = document.createElement('div');
        ov.className = OV_CLASS;
        ov.style.cssText =
          'position:absolute;left:0;top:0;right:0;bottom:0;z-index:2147483646;' +
          'background:#000 center/cover no-repeat;display:flex;align-items:center;' +
          'justify-content:center;cursor:pointer;';

        btn = document.createElement('button');
        btn.type = 'button';
        btn.style.cssText =
          'padding:10px 16px;border:2px solid #fff;border-radius:22px;' +
          'background:rgba(0,0,0,.75);color:#fff;font:700 14px sans-serif;';
        ov.appendChild(btn);

        ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'touchstart', 'touchend'].forEach(function (ev) {
          ov.addEventListener(ev, function (e) { e.stopPropagation(); });
        });
        var target = btn;
        ov.addEventListener('click', function (e) {
          e.preventDefault();
          e.stopPropagation();
          openNativePlayer(host, target);
        });
        host.appendChild(ov);
      } else {
        btn = ov.firstChild;
      }
      refreshOverlay(host, ov, btn);
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
    quietVideos();
    attachOverlays();
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
      attachOverlays();
      hideAds();
    }, 250);
  }).observe(document, { childList: true, subtree: true });
})();
