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
