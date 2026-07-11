/**
 * Minecraft Map Art Palette Coverage Bookmarklet
 * ================================================
 * Scores every image on the current page by how well the Minecraft 1.21.4
 * legal map-colour palette (186 colours: shades 0–2 of all 61 base colours)
 * can represent it, then replaces the page with a ranked list.
 *
 * Score = % of pixels whose nearest-palette-entry OKLab ΔE is ≤ 0.05.
 * (This measures palette suitability, not dither quality.)
 *
 * TO INSTALL:
 *   1. Open bookmarklet-install.html in a browser and click "Add to bookmarks".
 *   OR
 *   1. Minify this file (e.g. https://jscompress.com).
 *   2. Prepend  javascript:
 *   3. Paste as the URL of a new bookmark.
 *
 * CORS NOTE: images served from other origins without CORS headers cannot have
 * their pixels read; those entries will show "CORS – pixel data unavailable".
 */

(function () {
  'use strict';

  // ── Minecraft 1.21.4 legal map palette (186 colours) ─────────────────────
  // Each entry is [R, G, B] for map-byte indices where (index & 3) !== 3.
  // Source: /loominary dumppalette on MC 1.21.4.
  const PAL_RGB = [
    [89,125,39],[109,153,48],[127,178,56],
    [174,164,115],[213,201,140],[247,233,163],
    [140,140,140],[171,171,171],[199,199,199],
    [180,0,0],[220,0,0],[255,0,0],
    [112,112,180],[138,138,220],[160,160,255],
    [117,117,117],[144,144,144],[167,167,167],
    [0,87,0],[0,106,0],[0,124,0],
    [180,180,180],[220,220,220],[255,255,255],
    [115,118,129],[141,144,158],[164,168,184],
    [106,76,54],[130,94,66],[151,109,77],
    [79,79,79],[96,96,96],[112,112,112],
    [45,45,180],[55,55,220],[64,64,255],
    [100,84,50],[123,102,62],[143,119,72],
    [180,177,172],[220,217,211],[255,252,245],
    [152,89,36],[186,109,44],[216,127,51],
    [125,53,152],[153,65,186],[178,76,216],
    [72,108,152],[88,132,186],[102,153,216],
    [161,161,36],[197,197,44],[229,229,51],
    [89,144,17],[109,176,21],[127,204,25],
    [170,89,116],[208,109,142],[242,127,165],
    [53,53,53],[65,65,65],[76,76,76],
    [108,108,108],[132,132,132],[153,153,153],
    [53,89,108],[65,109,132],[76,127,153],
    [89,44,125],[109,54,153],[127,63,178],
    [36,53,125],[44,65,153],[51,76,178],
    [72,53,36],[88,65,44],[102,76,51],
    [72,89,36],[88,109,44],[102,127,51],
    [108,36,36],[132,44,44],[153,51,51],
    [17,17,17],[21,21,21],[25,25,25],
    [176,168,54],[215,205,66],[250,238,77],
    [64,154,150],[79,188,183],[92,219,213],
    [52,90,180],[63,110,220],[74,128,255],
    [0,153,40],[0,187,50],[0,217,58],
    [91,60,34],[111,74,42],[129,86,49],
    [79,1,0],[96,1,0],[112,2,0],
    [147,124,113],[180,152,138],[209,177,161],
    [112,57,25],[137,70,31],[159,82,36],
    [105,61,76],[128,75,93],[149,87,108],
    [79,76,97],[96,93,119],[112,108,138],
    [131,93,25],[160,114,31],[186,133,36],
    [72,82,37],[88,100,45],[103,117,53],
    [112,54,55],[138,66,67],[160,77,78],
    [40,28,24],[49,35,30],[57,41,35],
    [95,75,69],[116,92,84],[135,107,98],
    [61,64,64],[75,79,79],[87,92,92],
    [86,51,62],[105,62,75],[122,73,88],
    [53,43,64],[65,53,79],[76,62,92],
    [53,35,24],[65,43,30],[76,50,35],
    [53,57,29],[65,70,36],[76,82,42],
    [100,42,32],[122,51,39],[142,60,46],
    [26,15,11],[31,18,13],[37,22,16],
    [133,33,34],[163,41,42],[189,48,49],
    [104,44,68],[127,54,83],[148,63,97],
    [64,17,20],[79,21,25],[92,25,29],
    [15,88,94],[18,108,115],[22,126,134],
    [40,100,98],[50,122,120],[58,142,140],
    [60,31,43],[74,37,53],[86,44,62],
    [14,127,93],[17,155,114],[20,180,133],
    [70,70,70],[86,86,86],[100,100,100],
    [152,123,103],[186,150,126],[216,175,147],
    [89,117,105],[109,144,129],[127,167,150],
  ];

  // ── OKLab conversion ──────────────────────────────────────────────────────
  function toLinear(x) {
    x /= 255;
    return x > 0.04045 ? Math.pow((x + 0.055) / 1.055, 2.4) : x / 12.92;
  }
  function rgbToOklab(r, g, b) {
    var rl = toLinear(r), gl = toLinear(g), bl = toLinear(b);
    var l = Math.cbrt(0.4122 * rl + 0.5363 * gl + 0.0514 * bl);
    var m = Math.cbrt(0.2119 * rl + 0.6807 * gl + 0.1074 * bl);
    var s = Math.cbrt(0.0883 * rl + 0.2817 * gl + 0.6300 * bl);
    return [
      0.2105 * l + 0.7936 * m - 0.0041 * s,
      1.9780 * l - 2.4286 * m + 0.4506 * s,
      0.0259 * l + 0.1302 * m - 0.9119 * s,
    ];
  }

  // Pre-compute palette OKLab entries once.
  var PAL_LAB = PAL_RGB.map(function (c) { return rgbToOklab(c[0], c[1], c[2]); });

  // ── Palette coverage score ────────────────────────────────────────────────
  // For each opaque pixel: find the nearest palette entry (pure NN, no dither).
  // Returns { pct: 0-100, de: average ΔE } or null if no opaque pixels.
  function scoreImageData(data) {
    var GOOD_SQ = 0.0025; // (0.05)²
    var good = 0, total = 0, sumDe = 0;
    for (var i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 128) continue;
      var lab = rgbToOklab(data[i], data[i + 1], data[i + 2]);
      var L = lab[0], a = lab[1], b = lab[2];
      var best = Infinity;
      for (var j = 0; j < PAL_LAB.length; j++) {
        var e = PAL_LAB[j];
        var dSq = (L - e[0]) * (L - e[0]) + (a - e[1]) * (a - e[1]) + (b - e[2]) * (b - e[2]);
        if (dSq < best) best = dSq;
      }
      sumDe += Math.sqrt(best);
      total++;
      if (best <= GOOD_SQ) good++;
    }
    return total > 0 ? { pct: good / total * 100, de: sumDe / total } : null;
  }

  // ── Collect images ────────────────────────────────────────────────────────
  var imgs = [].slice.call(document.querySelectorAll('img')).filter(function (img) {
    return img.naturalWidth > 0 && img.naturalHeight > 0;
  });

  if (!imgs.length) {
    alert('No loaded images found on this page.');
    return;
  }

  // ── Replace page contents ─────────────────────────────────────────────────
  document.open();
  document.write([
    '<!DOCTYPE html><html><head><meta charset="utf-8">',
    '<title>MC Map Palette Coverage</title><style>',
    '*{box-sizing:border-box}',
    'body{background:#111;color:#eee;font:14px/1.5 monospace;margin:0;padding:20px}',
    'h1{color:#5af;margin:0 0 6px}',
    '.note{color:#666;font-size:.82em;margin-bottom:16px}',
    '.row{display:flex;align-items:center;gap:14px;padding:10px;margin:5px 0;',
    '  background:#1a1a1a;border-radius:5px;border:1px solid #2a2a2a}',
    '.thumb{width:80px;height:80px;object-fit:contain;background:#252525;flex-shrink:0;border-radius:3px}',
    '.pct{font-size:1.5em;font-weight:bold;min-width:3.2em}',
    '.de{color:#888;font-size:.82em;margin-top:2px}',
    '.bar-wrap{width:140px;height:5px;background:#2a2a2a;border-radius:3px;margin-top:5px}',
    '.bar{height:100%;border-radius:3px}',
    '.src{color:#555;font-size:.75em;margin-top:4px;word-break:break-all}',
    '.cors{color:#555;font-size:.85em}',
    '#status{color:#888;margin-bottom:10px}',
    '</style></head><body>',
    '<h1>🧵 Minecraft Map Palette Coverage</h1>',
    '<div class="note">Nearest-palette-entry OKLab ΔE per pixel against the 186-colour MC 1.21.4 legal palette.',
    ' Higher = better suitability for map art. Score is independent of dither / chroma boost settings.</div>',
    '<div id="status">Scoring ' + imgs.length + ' image' + (imgs.length !== 1 ? 's' : '') + '…</div>',
    '<div id="results"></div>',
    '</body></html>',
  ].join(''));
  document.close();

  var results = [];
  var pending = imgs.length;

  function finish() {
    results.sort(function (a, b) {
      return (b.score ? b.score.pct : -1) - (a.score ? a.score.pct : -1);
    });

    var status = document.getElementById('status');
    var container = document.getElementById('results');
    if (status) status.textContent = results.length + ' image' + (results.length !== 1 ? 's' : '') + ' scored.';
    if (!container) return;

    results.forEach(function (item) {
      var row = document.createElement('div');
      row.className = 'row';

      var thumb = document.createElement('img');
      thumb.src = item.src;
      thumb.className = 'thumb';
      thumb.alt = '';
      row.appendChild(thumb);

      var info = document.createElement('div');
      info.style.flex = '1';
      info.style.minWidth = '0';

      if (item.score) {
        var pct = Math.round(item.score.pct);
        var col = pct >= 75 ? '#7f7' : pct >= 50 ? '#fc6' : '#f77';
        var label = pct >= 75 ? 'good fit' : pct >= 50 ? 'moderate' : 'poor fit';
        info.innerHTML =
          '<div class="pct" style="color:' + col + '">' + pct + '%</div>' +
          '<div class="de">avg ΔE ' + item.score.de.toFixed(3) + ' · ' + label + '</div>' +
          '<div class="bar-wrap"><div class="bar" style="width:' + pct + '%;background:' + col + '"></div></div>' +
          '<div class="src">' + escHtml(item.src) + '</div>';
      } else {
        info.innerHTML =
          '<div class="cors">⚠ CORS – pixel data unavailable</div>' +
          '<div class="src">' + escHtml(item.src) + '</div>';
      }

      row.appendChild(info);
      container.appendChild(row);
    });
  }

  function escHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function processImg(img) {
    var src = img.src;
    var MAX = 200; // sample at most 200×200 px for speed
    var ar = img.naturalWidth / img.naturalHeight;
    var w = ar >= 1 ? MAX : Math.round(MAX * ar);
    var h = ar < 1 ? MAX : Math.round(MAX / ar);

    function tryDirect() {
      try {
        var c = document.createElement('canvas');
        c.width = w; c.height = h;
        c.getContext('2d').drawImage(img, 0, 0, w, h);
        var data = c.getContext('2d').getImageData(0, 0, w, h).data;
        results.push({ src: src, score: scoreImageData(data) });
      } catch (e) {
        results.push({ src: src, score: null });
      }
      if (!--pending) finish();
    }

    // Try reloading with CORS first (works when server sends Access-Control-Allow-Origin).
    var ci = new Image();
    ci.crossOrigin = 'anonymous';
    ci.onload = function () {
      try {
        var c = document.createElement('canvas');
        c.width = w; c.height = h;
        c.getContext('2d').drawImage(ci, 0, 0, w, h);
        var data = c.getContext('2d').getImageData(0, 0, w, h).data;
        results.push({ src: src, score: scoreImageData(data) });
        if (!--pending) finish();
      } catch (e) {
        tryDirect();
      }
    };
    ci.onerror = tryDirect;
    ci.src = src;
  }

  imgs.forEach(processImg);

})();
