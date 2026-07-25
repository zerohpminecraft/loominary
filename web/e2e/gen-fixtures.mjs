/**
 * Generates the deterministic image fixtures the docs screenshots and B-roll use.
 *   node e2e/gen-fixtures.mjs        (run from web/)
 *
 * Outputs (committed):
 *   e2e/fixtures/sample.png       512×512  — landscape test art (static imports)
 *   e2e/fixtures/sample-wide.png  1024×512 — same scene, for 2×1 multi-tile shots
 *   e2e/fixtures/sample-anim.gif  128×128  — 12-frame bouncing-ball animation
 *
 * Everything is drawn procedurally with fixed constants — rerunning produces
 * byte-identical files, so screenshots stay reproducible.
 */
import { writeFile, mkdir } from 'node:fs/promises';
import { deflateSync } from 'node:zlib';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { pathToFileURL } from 'node:url';
import { tmpdir } from 'node:os';
import { rm } from 'node:fs/promises';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'fixtures');
await mkdir(OUT, { recursive: true });

// ─── Minimal PNG writer (RGBA, no interlace) ────────────────────────────────
const CRC_TABLE = new Int32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c;
});
function crc32(buf) {
  let c = -1;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function chunk(type, data) {
  const out = Buffer.alloc(8 + data.length + 4);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), data])), 8 + data.length);
  return out;
}
function encodePng(rgba, w, h) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0; // filter: none
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ─── Test art: sunset landscape with mountains and a lake ───────────────────
function drawScene(w, h) {
  const img = Buffer.alloc(w * h * 4);
  const set = (x, y, r, g, b) => {
    const i = (y * w + x) * 4;
    img[i] = r; img[i + 1] = g; img[i + 2] = b; img[i + 3] = 255;
  };
  const horizon = Math.floor(h * 0.62);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (y < horizon) {
        // Sky gradient: deep indigo → orange
        const t = y / horizon;
        set(x, y, Math.floor(40 + 215 * t * t), Math.floor(30 + 110 * t), Math.floor(90 + 30 * (1 - t)));
      } else {
        // Lake: mirrored, darkened sky with a gentle swell (kept smooth — busy
        // ripples quantize into noise that reads as data pixels in screenshots)
        const t = (2 * horizon - y) / horizon;
        const ripple = Math.sin(y * 0.18 + x * 0.01) * 3;
        set(x, y, Math.floor((40 + 215 * t * t) * 0.55 + ripple),
                   Math.floor((30 + 110 * t) * 0.55 + ripple),
                   Math.floor((90 + 30 * (1 - t)) * 0.6 + ripple));
      }
    }
  }
  // Sun
  const sx = Math.floor(w * 0.68), sy = Math.floor(h * 0.42), sr = Math.floor(h * 0.09);
  for (let y = sy - sr; y <= sy + sr; y++)
    for (let x = sx - sr; x <= sx + sr; x++) {
      const d = Math.hypot(x - sx, y - sy);
      if (d <= sr && y < horizon && x >= 0 && x < w) {
        const glow = 1 - (d / sr) * 0.3;
        set(x, y, 255, Math.floor(225 * glow), Math.floor(140 * glow));
      }
    }
  // Mountain silhouettes (two ridges, deterministic sine mix)
  for (let x = 0; x < w; x++) {
    const r1 = horizon - h * 0.18 + Math.sin(x * 0.013) * h * 0.07 + Math.sin(x * 0.037 + 2) * h * 0.03;
    const r2 = horizon - h * 0.07 + Math.sin(x * 0.021 + 5) * h * 0.05;
    for (let y = Math.max(0, Math.floor(r1)); y < horizon; y++) set(x, y, 38, 28, 58);
    for (let y = Math.max(0, Math.floor(r2)); y < horizon; y++) set(x, y, 22, 16, 38);
  }
  return img;
}

await writeFile(join(OUT, 'sample.png'), encodePng(drawScene(512, 512), 512, 512));
await writeFile(join(OUT, 'sample-wide.png'), encodePng(drawScene(1024, 512), 1024, 512));
console.log('wrote sample.png, sample-wide.png');

// ─── Animated GIF via the app's own encoder (map-palette frames) ────────────
const entrySrc = `
export { encodeAnimatedGif } from './src/gif-encode.ts';
export { IS_VALID, MC_PALETTE } from './src/palette.ts';
`;
const entry = join(process.cwd(), `.fixture-entry-${process.pid}.ts`);
await writeFile(entry, entrySrc);
let mod;
try {
  const outfile = join(tmpdir(), `loominary-fixtures-${process.pid}.mjs`);
  await build({
    entryPoints: [entry], bundle: true, format: 'esm', outfile,
    platform: 'node', external: ['@bokuweb/zstd-wasm'],
  });
  mod = await import(pathToFileURL(outfile).href);
  await rm(outfile);
} finally {
  await rm(entry);
}
const { encodeAnimatedGif, IS_VALID, MC_PALETTE } = mod;

// Pick a few pleasant valid palette bytes deterministically.
function nearestByte(r, g, b) {
  let best = 0, bd = Infinity;
  for (let i = 1; i < 256; i++) {
    if (!IS_VALID[i]) continue;
    const c = MC_PALETTE[i];
    const d = ((c >> 16) - r) ** 2 + (((c >> 8) & 0xff) - g) ** 2 + ((c & 0xff) - b) ** 2;
    if (d < bd) { bd = d; best = i; }
  }
  return best;
}
const BG = nearestByte(30, 30, 60), BALL = nearestByte(255, 170, 40), FLOOR = nearestByte(70, 130, 70);

const FRAMES = 12, SIZE = 128;
const tileFrames = [];
for (let f = 0; f < FRAMES; f++) {
  const px = new Uint8Array(SIZE * SIZE).fill(BG);
  for (let y = 112; y < SIZE; y++) for (let x = 0; x < SIZE; x++) px[y * SIZE + x] = FLOOR;
  const t = f / FRAMES;
  const bx = Math.floor(14 + t * 100);
  const by = Math.floor(96 - Math.abs(Math.sin(t * Math.PI * 2)) * 70);
  for (let y = -10; y <= 10; y++) for (let x = -10; x <= 10; x++)
    if (x * x + y * y <= 100) {
      const yy = by + y, xx = bx + x;
      if (yy >= 0 && yy < 112 && xx >= 0 && xx < SIZE) px[yy * SIZE + xx] = BALL;
    }
  tileFrames.push(px);
}
const gif = encodeAnimatedGif({
  gridCols: 1, gridRows: 1,
  frames: [tileFrames],
  frameDelays: new Array(FRAMES).fill(90),
}, 0);
await writeFile(join(OUT, 'sample-anim.gif'), gif);
console.log('wrote sample-anim.gif');

// ─── Heavy dithered animation: the lossy-mode hero (ep03) ───────────────────
// A moving plasma with ordered (Bayer) dithering across the full palette: lots
// of distinct bytes plus per-frame change, so lossless AV1 can't shrink it and
// the lossy path wins. 24 frames keeps it under the export's >60-frame
// heavy-animation gate so stats auto-compute in the broll.
const BAYER4 = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map(v => v / 16 - 0.5);
const HFRAMES = 24, HSIZE = 128;
const heavyFrames = [];
for (let f = 0; f < HFRAMES; f++) {
  const px = new Uint8Array(HSIZE * HSIZE);
  const t = (f / HFRAMES) * Math.PI * 2;
  for (let y = 0; y < HSIZE; y++) {
    for (let x = 0; x < HSIZE; x++) {
      const v1 = Math.sin(x * 0.09 + t);
      const v2 = Math.sin(y * 0.075 - t * 0.8);
      const v3 = Math.sin((x + y) * 0.06 + t * 1.3);
      const v4 = Math.sin(Math.hypot(x - 64, y - 64) * 0.08 - t);
      const d = BAYER4[(y & 3) * 4 + (x & 3)] * 46;   // ordered-dither nudge
      const clamp = v => Math.max(0, Math.min(255, v + d));
      px[y * HSIZE + x] = nearestByte(
        clamp(128 + 90 * (v1 + v3)),
        clamp(128 + 90 * (v2 + v4)),
        clamp(128 + 90 * (v3 + v2)));
    }
  }
  heavyFrames.push(px);
}
const heavyGif = encodeAnimatedGif({
  gridCols: 1, gridRows: 1,
  frames: [heavyFrames],
  frameDelays: new Array(HFRAMES).fill(80),
}, 0);
await writeFile(join(OUT, 'sample-anim-heavy.gif'), heavyGif);
console.log('wrote sample-anim-heavy.gif');

// ─── Ep04 fixtures ───────────────────────────────────────────────────────────
// A 3:2 landscape for the 3×2 grid / seamless-dither demo.
await writeFile(join(OUT, 'sample-grid32.png'), encodePng(drawScene(768, 512), 768, 512));
console.log('wrote sample-grid32.png');

// A 2×1 animated source for the composite (one seam-free stream) demo: a moving
// plasma that is continuous across the tile seam, so the composite path wins.
const clampB = v => Math.max(0, Math.min(255, v));
function widePlasmaTile(colOffset, WT, frames) {
  const tiles = [];
  for (let f = 0; f < frames; f++) {
    const px = new Uint8Array(WT * WT);
    const t = (f / frames) * Math.PI * 2;
    for (let y = 0; y < WT; y++) {
      for (let x = 0; x < WT; x++) {
        const gx = x + colOffset * WT;   // global x across the 2 tiles
        const v = Math.sin(gx * 0.03 + t) + Math.sin(y * 0.04 - t * 0.7) + Math.sin((gx + y) * 0.025 + t * 1.2);
        px[y * WT + x] = nearestByte(clampB(128 + 70 * Math.sin(v)),
                                     clampB(128 + 70 * Math.sin(v + 2)),
                                     clampB(128 + 70 * Math.sin(v + 4)));
      }
    }
    tiles.push(px);
  }
  return tiles;
}
const WWF = 16;
const wideGif = encodeAnimatedGif({
  gridCols: 2, gridRows: 1,
  frames: [widePlasmaTile(0, 128, WWF), widePlasmaTile(1, 128, WWF)],
  frameDelays: new Array(WWF).fill(90),
}, 0);
await writeFile(join(OUT, 'sample-anim-wide.gif'), wideGif);
console.log('wrote sample-anim-wide.gif');

// ─── Ep02 fixtures ───────────────────────────────────────────────────────────
// sample-poster.png   — gradient-heavy "poster": the dither-montage showcase.
// sample-pixelart.png — chunky sprite art for the editor-tools segment.
// sample-portrait.png — greyscale bust for the greyscale palette preset.
// Same rule as above: fixed constants only, byte-identical on rerun.

function drawPoster(w, h) {
  const img = Buffer.alloc(w * h * 4);
  const set = (x, y, r, g, b) => {
    const i = (y * w + x) * 4;
    img[i] = r; img[i + 1] = g; img[i + 2] = b; img[i + 3] = 255;
  };
  const cx = w * 0.5, cy = h * 0.58;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      // Sunburst poster: angular color sweep × radial falloff — nothing but
      // gradients, so every dither algorithm's texture is visible.
      const dx = x - cx, dy = y - cy;
      const ang = Math.atan2(dy, dx);
      const rad = Math.hypot(dx, dy) / (h * 0.75);
      const hue = (ang / Math.PI + 1) / 2;               // 0..1 sweep
      const fall = Math.max(0, 1 - rad);
      const r = Math.floor(255 * fall * (0.55 + 0.45 * Math.sin(hue * Math.PI * 2)));
      const g = Math.floor(235 * fall * (0.55 + 0.45 * Math.sin(hue * Math.PI * 2 + 2.1)));
      const b = Math.floor(255 * fall * (0.55 + 0.45 * Math.sin(hue * Math.PI * 2 + 4.2)));
      set(x, y, r, g, b);
    }
  }
  // Horizon band: one clean linear gradient strip across the lower third.
  for (let y = Math.floor(h * 0.8); y < h; y++) {
    const t = (y - h * 0.8) / (h * 0.2);
    for (let x = 0; x < w; x++) {
      const s = x / w;
      set(x, y, Math.floor(20 + 200 * s * (1 - t)), Math.floor(16 + 60 * (1 - t)),
          Math.floor(60 + 160 * (1 - s) * (1 - t)));
    }
  }
  return img;
}
await writeFile(join(OUT, 'sample-poster.png'), encodePng(drawPoster(512, 512), 512, 512));
console.log('wrote sample-poster.png');

function drawPixelArt(w, h) {
  // 16×16 mushroom sprite, nearest-neighbour scaled. 0 sky, 1 cap, 2 spots,
  // 3 stem, 4 outline, 5 grass.
  const S = [
    '0000044444000000',
    '0004411111440000',
    '0041111221114000',
    '0411122222111400',
    '0411222222211140',
    '4111222222221114',
    '4112222112222114',
    '4112221111222114',
    '4444444444444444',
    '0004333333340000',
    '0004333113340000',
    '0004331111340000',
    '0004333333340000',
    '0044333333344000',
    '5555555555555555',
    '5555555555555555',
  ];
  const PAL = {
    0: [96, 168, 255], 1: [220, 60, 60], 2: [244, 238, 220],
    3: [232, 214, 180], 4: [40, 34, 48], 5: [88, 160, 72],
  };
  const img = Buffer.alloc(w * h * 4);
  const cell = w / 16;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const c = PAL[S[Math.floor(y / cell)][Math.floor(x / cell)]];
      const i = (y * w + x) * 4;
      img[i] = c[0]; img[i + 1] = c[1]; img[i + 2] = c[2]; img[i + 3] = 255;
    }
  }
  return img;
}
await writeFile(join(OUT, 'sample-pixelart.png'), encodePng(drawPixelArt(512, 512), 512, 512));
console.log('wrote sample-pixelart.png');

function drawPortrait(w, h) {
  const img = Buffer.alloc(w * h * 4);
  const set = (x, y, v) => {
    const i = (y * w + x) * 4;
    img[i] = img[i + 1] = img[i + 2] = Math.max(0, Math.min(255, Math.floor(v)));
    img[i + 3] = 255;
  };
  // Soft radial backlight behind a dark bust silhouette — pure greyscale
  // gradients, which the greyscale palette preset renders faithfully.
  const lx = w * 0.38, ly = h * 0.30;
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const d = Math.hypot(x - lx, y - ly) / (h * 0.9);
      set(x, y, 230 * Math.max(0, 1 - d) ** 1.6 + 8);
    }
  const inHead = (x, y) => Math.hypot((x - w * 0.52) / (w * 0.16), (y - h * 0.38) / (h * 0.20)) < 1;
  const inBody = (x, y) => {
    if (y < h * 0.62) return false;
    const sp = (y - h * 0.62) / (h * 0.38);
    return Math.abs(x - w * 0.52) < w * (0.10 + 0.24 * Math.sqrt(sp));
  };
  const inNeck = (x, y) => y >= h * 0.52 && y < h * 0.66 && Math.abs(x - w * 0.52) < w * 0.055;
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++)
      if (inHead(x, y) || inBody(x, y) || inNeck(x, y)) {
        // Rim light: silhouette brightens toward the light source edge.
        const rim = Math.max(0, 1 - Math.hypot(x - lx, y - ly) / (h * 0.55));
        set(x, y, 14 + 90 * rim * rim);
      }
  return img;
}
await writeFile(join(OUT, 'sample-portrait.png'), encodePng(drawPortrait(512, 512), 512, 512));
console.log('wrote sample-portrait.png');
