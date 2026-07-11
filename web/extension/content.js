/**
 * Content script — injected into the active tab when the extension icon is clicked.
 *
 * Shows a fixed overlay ranking all images by Minecraft map-art palette coverage.
 * The palette used for scoring can be changed via a dropdown; cached pixel data
 * means palette changes re-score instantly without re-fetching.
 */
(function () {
  'use strict';

  if (document.getElementById('_mcpal_overlay')) return;

  // ── Palette data ───────────────────────────────────────────────────────────
  //
  // PAL_LEGAL_RGB: 186 entries = 62 base colours × 3 shades (0,1,2), packed in
  // groups-of-3 so that:
  //   shade-1-only (flat fullblock)  → entries where index % 3 === 1  (62)
  //   all three shades (staircase)   → all 186 entries
  //
  // Source: Minecraft 1.21.4 /loominary dumppalette

  var PAL_LEGAL_RGB = [
    // ←shade0       shade1        shade2→  (one row = one base colour)
    [89,125,39],  [109,153,48],  [127,178,56],   // colorId 1
    [174,164,115],[213,201,140], [247,233,163],   // 2
    [140,140,140],[171,171,171], [199,199,199],   // 3
    [180,0,0],    [220,0,0],    [255,0,0],        // 4
    [112,112,180],[138,138,220], [160,160,255],   // 5
    [117,117,117],[144,144,144], [167,167,167],   // 6
    [0,87,0],     [0,106,0],    [0,124,0],        // 7
    [180,180,180],[220,220,220], [255,255,255],   // 8
    [115,118,129],[141,144,158], [164,168,184],   // 9
    [106,76,54],  [130,94,66],  [151,109,77],     // 10
    [79,79,79],   [96,96,96],   [112,112,112],    // 11
    [45,45,180],  [55,55,220],  [64,64,255],      // 12
    [100,84,50],  [123,102,62], [143,119,72],     // 13
    [180,177,172],[220,217,211],[255,252,245],    // 14
    [152,89,36],  [186,109,44], [216,127,51],     // 15
    [125,53,152], [153,65,186], [178,76,216],     // 16
    [72,108,152], [88,132,186], [102,153,216],    // 17
    [161,161,36], [197,197,44], [229,229,51],     // 18
    [89,144,17],  [109,176,21], [127,204,25],     // 19
    [170,89,116], [208,109,142],[242,127,165],    // 20
    [53,53,53],   [65,65,65],   [76,76,76],       // 21
    [108,108,108],[132,132,132],[153,153,153],    // 22
    [53,89,108],  [65,109,132], [76,127,153],     // 23
    [89,44,125],  [109,54,153], [127,63,178],     // 24
    [36,53,125],  [44,65,153],  [51,76,178],      // 25
    [72,53,36],   [88,65,44],   [102,76,51],      // 26
    [72,89,36],   [88,109,44],  [102,127,51],     // 27
    [108,36,36],  [132,44,44],  [153,51,51],      // 28
    [17,17,17],   [21,21,21],   [25,25,25],       // 29
    [176,168,54], [215,205,66], [250,238,77],     // 30
    [64,154,150], [79,188,183], [92,219,213],     // 31
    [52,90,180],  [63,110,220], [74,128,255],     // 32
    [0,153,40],   [0,187,50],   [0,217,58],       // 33
    [91,60,34],   [111,74,42],  [129,86,49],      // 34
    [79,1,0],     [96,1,0],     [112,2,0],        // 35
    [147,124,113],[180,152,138],[209,177,161],    // 36
    [112,57,25],  [137,70,31],  [159,82,36],      // 37
    [105,61,76],  [128,75,93],  [149,87,108],     // 38
    [79,76,97],   [96,93,119],  [112,108,138],    // 39
    [131,93,25],  [160,114,31], [186,133,36],     // 40
    [72,82,37],   [88,100,45],  [103,117,53],     // 41
    [112,54,55],  [138,66,67],  [160,77,78],      // 42
    [40,28,24],   [49,35,30],   [57,41,35],       // 43
    [95,75,69],   [116,92,84],  [135,107,98],     // 44
    [61,64,64],   [75,79,79],   [87,92,92],       // 45
    [86,51,62],   [105,62,75],  [122,73,88],      // 46
    [53,43,64],   [65,53,79],   [76,62,92],       // 47
    [53,35,24],   [65,43,30],   [76,50,35],       // 48
    [53,57,29],   [65,70,36],   [76,82,42],       // 49
    [100,42,32],  [122,51,39],  [142,60,46],      // 50
    [26,15,11],   [31,18,13],   [37,22,16],       // 51
    [133,33,34],  [163,41,42],  [189,48,49],      // 52
    [104,44,68],  [127,54,83],  [148,63,97],      // 53
    [64,17,20],   [79,21,25],   [92,25,29],       // 54
    [15,88,94],   [18,108,115], [22,126,134],     // 55
    [40,100,98],  [50,122,120], [58,142,140],     // 56
    [60,31,43],   [74,37,53],   [86,44,62],       // 57
    [14,127,93],  [17,155,114], [20,180,133],     // 58
    [70,70,70],   [86,86,86],   [100,100,100],    // 59
    [152,123,103],[186,150,126],[216,175,147],    // 60
    [89,117,105], [109,144,129],[127,167,150],    // 61
  ];

  // Additional shade-3 entries (unobtainable in survival) for the "All shades" option.
  var PAL_SHADE3_RGB = [
    [67,94,29],[130,123,86],[105,105,105],[135,0,0],[84,84,135],[88,88,88],
    [0,65,0],[135,135,135],[86,88,97],[79,57,40],[59,59,59],[33,33,135],
    [75,63,38],[135,133,129],[114,67,27],[94,40,114],[54,81,114],[121,121,27],
    [67,108,13],[128,67,87],[40,40,40],[81,81,81],[40,67,81],[67,33,94],
    [27,40,94],[54,40,27],[54,67,27],[81,27,27],[13,13,13],[132,126,40],
    [48,115,112],[39,67,135],[0,114,30],[68,45,25],[59,1,0],[110,93,85],
    [84,43,19],[78,46,57],[59,57,73],[98,70,19],[54,61,28],[84,40,41],
    [30,21,18],[71,56,51],[46,48,48],[64,38,46],[40,32,48],[40,26,18],
    [40,43,22],[75,31,24],[19,11,8],[100,25,25],[78,33,51],[48,13,15],
    [11,66,70],[30,75,74],[45,23,32],[10,95,70],[52,52,52],[114,92,77],
    [67,88,79],
  ];

  // Positions in PAL_LEGAL_RGB that correspond to the 16 flat-carpet map bytes.
  // NIBBLE_TO_MAP_BYTE = [33,61,65,69,73,77,81,85,89,93,97,101,105,109,113,117]
  // Position formula: (colorId-1)*3 + shade  where colorId=b>>2, shade=b&3
  // WHITE=33(cid8), ORANGE=61(cid15), MAGENTA=65(cid16), LT_BLUE=69(cid17),
  // YELLOW=73(cid18), LIME=77(cid19), PINK=81(cid20), GRAY=85(cid21),
  // LT_GRAY=89(cid22), CYAN=93(cid23), PURPLE=97(cid24), BLUE=101(cid25),
  // BROWN=105(cid26), GREEN=109(cid27), RED=113(cid28), BLACK=117(cid29)
  var CARPET_POS = [22,43,46,49,52,55,58,61,64,67,70,73,76,79,82,85];

  // ── Palette options ────────────────────────────────────────────────────────

  var PAL_OPTIONS = [
    { value: 'staircase',          label: 'Staircase fullblock (183)' },
    { value: 'flat',               label: 'Flat fullblock (61)' },
    { value: 'all',                label: 'All shades (244)' },
    { value: 'grey',               label: 'Greyscale' },
    { value: 'carpet',             label: 'Flat carpet (16)' },
    { value: 'staircase-carpet',   label: 'Staircase carpet (48)' },
  ];

  function buildPaletteRgb(mode, greyThresh) {
    switch (mode) {
      case 'flat':
        return PAL_LEGAL_RGB.filter(function (_, i) { return i % 3 === 1; });
      case 'all':
        return PAL_LEGAL_RGB.concat(PAL_SHADE3_RGB);
      case 'grey':
        return PAL_LEGAL_RGB.filter(function (c) {
          return Math.max(c[0], c[1], c[2]) - Math.min(c[0], c[1], c[2]) < (greyThresh || 40);
        });
      case 'carpet':
        return CARPET_POS.map(function (p) { return PAL_LEGAL_RGB[p]; });
      case 'staircase-carpet':
        return CARPET_POS.reduce(function (acc, p) {
          return acc.concat([PAL_LEGAL_RGB[p - 1], PAL_LEGAL_RGB[p], PAL_LEGAL_RGB[p + 1]]);
        }, []);
      default: // staircase fullblock
        return PAL_LEGAL_RGB;
    }
  }

  // ── OKLab conversion ───────────────────────────────────────────────────────

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

  function buildPaletteLab(mode, greyThresh) {
    return buildPaletteRgb(mode, greyThresh).map(function (c) {
      return rgbToOklab(c[0], c[1], c[2]);
    });
  }

  // ── Scoring ────────────────────────────────────────────────────────────────

  function scoreData(data, palLab) {
    var GOOD_SQ = 0.0025;
    var good = 0, total = 0, sumDe = 0;
    for (var i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 128) continue;
      var lab = rgbToOklab(data[i], data[i + 1], data[i + 2]);
      var L = lab[0], a = lab[1], bv = lab[2];
      var best = Infinity;
      for (var j = 0; j < palLab.length; j++) {
        var e = palLab[j];
        var dSq = (L-e[0])*(L-e[0]) + (a-e[1])*(a-e[1]) + (bv-e[2])*(bv-e[2]);
        if (dSq < best) best = dSq;
      }
      sumDe += Math.sqrt(best);
      total++;
      if (best <= GOOD_SQ) good++;
    }
    return total > 0 ? { pct: good / total * 100, de: sumDe / total } : null;
  }

  // ── HTML helpers ───────────────────────────────────────────────────────────

  function esc(s) {
    return String(s)
      .replace(/&/g,'&amp;').replace(/</g,'&lt;')
      .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  // ── State ──────────────────────────────────────────────────────────────────

  var results   = [];   // { src, pixels, score }  (pixels = Uint8ClampedArray)
  var palMode   = 'staircase';
  var greyThr   = 40;
  var palLab    = buildPaletteLab(palMode, greyThr);

  // ── Build overlay ──────────────────────────────────────────────────────────

  var overlay = document.createElement('div');
  overlay.id = '_mcpal_overlay';
  overlay.style.cssText = [
    'position:fixed;inset:0;z-index:2147483647;overflow-y:auto',
    'background:#111;color:#eee;font:14px/1.5 monospace;padding:16px',
  ].join(';');

  overlay.innerHTML = [
    // ── Header bar ──────────────────────────────────────────────────────────
    '<div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:8px">',
    '  <span style="color:#5af;font-weight:bold;font-size:1.1em;white-space:nowrap">🧵 MC Map Palette Coverage</span>',

    // Palette selector
    '  <label style="color:#aaa;font-size:.85em;white-space:nowrap">Palette:',
    '    <select id="_mcpal_sel" style="background:#252525;border:1px solid #555;color:#ccc;',
    '      padding:3px 6px;border-radius:4px;cursor:pointer;font-size:.85em;margin-left:4px">',
    PAL_OPTIONS.map(function (o) {
      return '<option value="' + o.value + '"' + (o.value === 'staircase' ? ' selected' : '') + '>' + esc(o.label) + '</option>';
    }).join(''),
    '    </select>',
    '  </label>',

    // Greyscale threshold (hidden unless grey selected)
    '  <span id="_mcpal_grey_ctrl" style="display:none;align-items:center;gap:6px;color:#aaa;font-size:.85em">',
    '    Threshold:',
    '    <input id="_mcpal_grey_rng" type="range" min="5" max="120" step="5" value="40"',
    '      style="width:90px;vertical-align:middle">',
    '    <span id="_mcpal_grey_val">40</span>',
    '  </span>',

    '  <span id="_mcpal_pal_count" style="color:#666;font-size:.8em"></span>',
    '  <button id="_mcpal_close" style="margin-left:auto;background:#252525;border:1px solid #555;',
    '    color:#ccc;padding:4px 12px;border-radius:4px;cursor:pointer">✕ Close</button>',
    '</div>',

    // Subtitle
    '<div style="color:#555;font-size:.78em;margin-bottom:10px">',
    '  Nearest-palette-entry OKLab ΔE per pixel. Score = % within ΔE 0.05.',
    '  Palette changes re-score without re-fetching.',
    '</div>',

    '<div id="_mcpal_status" style="color:#888;margin-bottom:8px"></div>',
    '<div id="_mcpal_list"></div>',
  ].join('');

  document.body.appendChild(overlay);

  // ── Control wiring ─────────────────────────────────────────────────────────

  document.getElementById('_mcpal_close').addEventListener('click', function () {
    overlay.remove();
  });

  var selEl      = document.getElementById('_mcpal_sel');
  var greyCtrl   = document.getElementById('_mcpal_grey_ctrl');
  var greyRng    = document.getElementById('_mcpal_grey_rng');
  var greyVal    = document.getElementById('_mcpal_grey_val');
  var palCount   = document.getElementById('_mcpal_pal_count');
  var statusEl   = document.getElementById('_mcpal_status');
  var listEl     = document.getElementById('_mcpal_list');

  function updatePalCount() {
    var n = buildPaletteRgb(palMode, greyThr).length;
    palCount.textContent = '(' + n + ' colour' + (n !== 1 ? 's' : '') + ')';
  }
  updatePalCount();

  function rescoreAll() {
    palLab = buildPaletteLab(palMode, greyThr);
    updatePalCount();
    results.forEach(function (item) {
      if (item.pixels) item.score = scoreData(item.pixels, palLab);
    });
    renderResults();
  }

  // Free pixel buffers after a palette change settles — they're needed for
  // re-scoring but consume significant memory when many images are loaded.
  var freePixelsTimer = null;
  function scheduleFreePixels() {
    if (freePixelsTimer) clearTimeout(freePixelsTimer);
    freePixelsTimer = setTimeout(function () {
      results.forEach(function (r) { r.pixels = null; });
      freePixelsTimer = null;
    }, 10000); // keep pixels alive 10s after last palette change
  }

  selEl.addEventListener('change', function () {
    palMode = selEl.value;
    greyCtrl.style.display = palMode === 'grey' ? 'inline-flex' : 'none';
    rescoreAll();
    scheduleFreePixels();
  });

  greyRng.addEventListener('input', function () {
    greyThr = parseInt(greyRng.value, 10);
    greyVal.textContent = greyThr;
    rescoreAll();
    scheduleFreePixels();
  });

  // ── Image collection ───────────────────────────────────────────────────────
  // Use currentSrc (browser's best srcset pick) rather than src.
  // Skip images smaller than MIN_DIM — Discord/Slack use tiny data-URL blurs
  // as lazy-load placeholders; scoring those is meaningless and misleading.

  var MIN_DIM = 64;
  var seen = {}, skippedSmall = 0;

  var imgs = [].slice.call(document.querySelectorAll('img')).filter(function (img) {
    if (img === overlay) return false;
    if (!img.naturalWidth || !img.naturalHeight) return false;
    if (img.naturalWidth < MIN_DIM || img.naturalHeight < MIN_DIM) { skippedSmall++; return false; }
    var src = img.currentSrc || img.src;
    if (!src || seen[src]) return false;
    seen[src] = true;
    img._src = src;
    return true;
  });

  if (!imgs.length) {
    statusEl.textContent = 'No images found' +
      (skippedSmall ? ' (' + skippedSmall + ' smaller than ' + MIN_DIM + 'px skipped)' : '') + '.';
    return;
  }

  statusEl.textContent = 'Scoring ' + imgs.length + ' image' + (imgs.length !== 1 ? 's' : '') +
    (skippedSmall ? ' (' + skippedSmall + ' tiny skipped)' : '') + '…';

  // ── Process images ─────────────────────────────────────────────────────────

  var idx = 0;

  function samplePixels(imgEl, cb) {
    var w = imgEl.naturalWidth, h = imgEl.naturalHeight;
    var MAX = 200, ar = w / h;
    var sw = ar >= 1 ? MAX : Math.round(MAX * ar);
    var sh = ar <  1 ? MAX : Math.round(MAX / ar);
    try {
      var c = document.createElement('canvas');
      c.width = sw; c.height = sh;
      c.getContext('2d').drawImage(imgEl, 0, 0, sw, sh);
      cb(c.getContext('2d').getImageData(0, 0, sw, sh).data);
    } catch (e) { cb(null); }
  }

  function scoreUrl(dataUrl, naturalW, naturalH, cb) {
    var MAX = 200, ar = naturalW && naturalH ? naturalW / naturalH : 1;
    var w = ar >= 1 ? MAX : Math.round(MAX * ar);
    var h = ar <  1 ? MAX : Math.round(MAX / ar);
    var im = new Image();
    im.onload = function () {
      try {
        var c = document.createElement('canvas');
        c.width = w; c.height = h;
        c.getContext('2d').drawImage(im, 0, 0, w, h);
        cb(c.getContext('2d').getImageData(0, 0, w, h).data);
      } catch (e) { cb(null); }
    };
    im.onerror = function () { cb(null); };
    im.src = dataUrl;
  }

  function processNext() {
    if (idx >= imgs.length) { renderResults(); scheduleFreePixels(); return; }
    var img = imgs[idx++];
    var src = img._src;
    var dims = [img.naturalWidth, img.naturalHeight];
    statusEl.textContent = 'Scoring ' + idx + ' / ' + imgs.length + '…';

    function push(px) {
      results.push({ src: src, dims: dims, pixels: px, score: px ? scoreData(px, palLab) : null });
      processNext();
    }

    // Data-URLs are always same-origin — canvas can read them directly.
    // Skip the background-worker roundtrip entirely.
    if (src.startsWith('data:')) {
      samplePixels(img, push);
      return;
    }

    // External URL: fetch via background worker (bypasses CORS).
    chrome.runtime.sendMessage({ type: 'MCPAL_FETCH', url: src }, function (resp) {
      if (chrome.runtime.lastError || !resp || !resp.ok) {
        // Fallback: draw directly (works for same-origin or already-CORS-permitted images).
        samplePixels(img, push);
        return;
      }
      scoreUrl(resp.dataUrl, dims[0], dims[1], push);
    });
  }

  processNext();

  // ── Render ─────────────────────────────────────────────────────────────────

  function renderResults() {
    var sorted = results.slice().sort(function (a, b) {
      return (b.score ? b.score.pct : -1) - (a.score ? a.score.pct : -1);
    });

    var scored = sorted.filter(function (i) { return i.score; }).length;
    statusEl.textContent = scored + ' / ' + results.length + ' scored' +
      (skippedSmall ? ' · ' + skippedSmall + ' tiny skipped' : '') + '.';
    listEl.innerHTML = '';

    sorted.forEach(function (item) {
      var row = document.createElement('div');
      row.style.cssText = 'display:flex;align-items:center;gap:14px;padding:10px;margin:5px 0;' +
        'background:#1a1a1a;border-radius:5px;border:1px solid #2a2a2a';

      var thumb = document.createElement('img');
      thumb.src = item.src; thumb.alt = '';
      thumb.style.cssText = 'width:80px;height:80px;object-fit:contain;background:#252525;flex-shrink:0;border-radius:3px';
      row.appendChild(thumb);

      var info = document.createElement('div');
      info.style.cssText = 'flex:1;min-width:0';

      // Friendly URL: truncate data-URLs to just their MIME type.
      var isData = item.src.startsWith('data:');
      var mime   = isData ? item.src.split(';')[0].replace('data:', '') : null;
      var dimStr = item.dims ? item.dims[0] + '×' + item.dims[1] : '';
      var urlLine = isData
        ? esc('[inline ' + mime + (dimStr ? '  ' + dimStr + 'px' : '') + ']')
        : esc(item.src);

      if (item.score) {
        var pct = Math.round(item.score.pct);
        var col = pct >= 75 ? '#7f7' : pct >= 50 ? '#fc6' : '#f77';
        var lbl = pct >= 75 ? 'good fit' : pct >= 50 ? 'moderate' : 'poor fit';
        info.innerHTML =
          '<div style="font-size:1.4em;font-weight:bold;color:' + col + '">' + pct + '%</div>' +
          '<div style="color:#888;font-size:.82em">avg ΔE ' + item.score.de.toFixed(3) +
            ' · ' + lbl + (dimStr && !isData ? '  <span style="color:#555">' + dimStr + '</span>' : '') + '</div>' +
          '<div style="width:140px;height:5px;background:#2a2a2a;border-radius:3px;margin-top:5px">' +
          '  <div style="height:100%;width:' + pct + '%;background:' + col + ';border-radius:3px"></div></div>' +
          '<div style="color:#555;font-size:.75em;margin-top:4px;word-break:break-all">' + urlLine + '</div>';
      } else {
        info.innerHTML =
          '<div style="color:#555;font-size:.85em">⚠ Could not read pixel data</div>' +
          '<div style="color:#555;font-size:.75em;word-break:break-all">' + urlLine + '</div>';
      }

      row.appendChild(info);
      listEl.appendChild(row);
    });
  }

})();
