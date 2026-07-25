/**
 * Generates a varied set of animated art state files for the in-game video harness
 * (ep03/ep04). Each is a real PayloadState (encodeComposition → animated LOOM
 * payload) the DocsDriver loads with `/loominary load <name>` and paints onto a
 * framed map with `/loominary preview` (frameCount>1 → AnimatedMapState → the map
 * plays). Variety spans sizes, colour modes, and palettes.
 *
 *   node scripts/gen-docs-anim-states.mjs      (run from web/)
 *
 * Output (committed): docs/tools/anim-<name>-state.json
 */
import { build } from 'esbuild';
import { writeFile, rm, readFile } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const webDir  = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(webDir, '..');

const entrySrc = `
export { encodeComposition } from './src/encode.ts';
export { quantizeRgbTile } from './src/srgb.ts';
export { IS_VALID, MC_PALETTE } from './src/palette.ts';
export { _setCodecFactory } from './src/av1/codec.ts';
export { createWasiShim } from './src/av1/wasi.ts';
`;
const entry = join(webDir, `.anim-state-entry-${process.pid}.ts`);
await writeFile(entry, entrySrc);
let mod;
try {
  const o = join(webDir, `.anim-state-bundle-${process.pid}.mjs`);
  try {
    await build({ entryPoints: [entry], bundle: true, format: 'esm', outfile: o, platform: 'node',
      logLevel: 'silent', external: ['@bokuweb/zstd-wasm'] });
    mod = await import(pathToFileURL(o).href);
  } finally { await rm(o, { force: true }); }
} finally { await rm(entry, { force: true }); }

async function makeExports(file) {
  const wasi = mod.createWasiShim();
  const { instance } = await WebAssembly.instantiate(await readFile(join(webDir, file)), wasi.imports);
  const ex = instance.exports;
  wasi.bind(ex.memory);
  ex._initialize?.();
  return ex;
}
mod._setCodecFactory(
  () => makeExports('public/av1/av1-encode.wasm'),
  () => makeExports('public/av1/av1-decode.wasm'),
);

const { IS_VALID, MC_PALETTE, quantizeRgbTile } = mod;
const clamp = v => Math.max(0, Math.min(255, v | 0));
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
const S = 128;
const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map(v => v / 16 - 0.5);

// ─── Frame generators (return Uint8Array[] of palette-index frames) ──────────
function ballFrames(n) {
  const BG = nearestByte(30, 30, 60), BALL = nearestByte(255, 170, 40), FLOOR = nearestByte(70, 130, 70);
  const out = [];
  for (let f = 0; f < n; f++) {
    const px = new Uint8Array(S * S).fill(BG);
    for (let y = 112; y < S; y++) for (let x = 0; x < S; x++) px[y * S + x] = FLOOR;
    const t = f / n, bx = Math.floor(14 + t * 100), by = Math.floor(96 - Math.abs(Math.sin(t * Math.PI * 2)) * 70);
    for (let y = -10; y <= 10; y++) for (let x = -10; x <= 10; x++)
      if (x * x + y * y <= 100) { const yy = by + y, xx = bx + x; if (yy >= 0 && yy < 112 && xx >= 0 && xx < S) px[yy * S + xx] = BALL; }
    out.push(px);
  }
  return out;
}
function plasmaFrames(n, dither, grey, colOffset = 0) {
  const out = [];
  for (let f = 0; f < n; f++) {
    const px = new Uint8Array(S * S);
    const t = (f / n) * Math.PI * 2;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const gx = x + colOffset * S;
      const v = Math.sin(gx * 0.05 + t) + Math.sin(y * 0.045 - t * 0.8) + Math.sin((gx + y) * 0.03 + t * 1.2)
              + Math.sin(Math.hypot(gx - 64, y - 64) * 0.06 - t);
      const d = dither ? BAYER[(y & 3) * 4 + (x & 3)] * 40 : 0;
      let r = 128 + 60 * v, g = 128 + 60 * Math.sin(v + 2), b = 128 + 60 * Math.sin(v + 4);
      if (grey) { const l = 128 + 70 * v; r = g = b = l; }
      px[y * S + x] = nearestByte(clamp(r + d), clamp(g + d), clamp(b + d));
    }
    out.push(px);
  }
  return out;
}
function demoFrames(n) {   // ep05: bouncing ball over a dithered gradient sky + textured ground.
  const BALL = nearestByte(255, 170, 40);
  const out = [];
  for (let f = 0; f < n; f++) {
    const px = new Uint8Array(S * S);
    const t = f / n;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const grad = y / S;
      let r = 20 + grad * 70, g = 18 + grad * 26, b = 60 + grad * 95;   // night sky, dark → mauve
      if (y >= 108) { r = 42; g = 66 + ((x ^ y) & 7) * 4; b = 44; }     // textured green ground
      const d = BAYER[(y & 3) * 4 + (x & 3)] * 28;
      px[y * S + x] = nearestByte(clamp(r + d), clamp(g + d), clamp(b + d));
    }
    const bx = Math.floor(20 + t * 88), by = Math.floor(90 - Math.abs(Math.sin(t * Math.PI * 2)) * 64);
    for (let dy = -11; dy <= 11; dy++) for (let dx = -11; dx <= 11; dx++)
      if (dx * dx + dy * dy <= 121) { const yy = by + dy, xx = bx + dx; if (yy >= 0 && yy < 108 && xx >= 0 && xx < S) px[yy * S + xx] = BALL; }
    out.push(px);
  }
  return out;
}
function ringsFrames(n) {   // greyscale expanding rings
  const out = [];
  for (let f = 0; f < n; f++) {
    const px = new Uint8Array(S * S);
    const t = f / n;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const r = Math.hypot(x - 64, y - 64);
      const l = 128 + 110 * Math.sin(r * 0.28 - t * Math.PI * 2);
      px[y * S + x] = nearestByte(clamp(l), clamp(l), clamp(l));
    }
    out.push(px);
  }
  return out;
}
function rgbSweepFrames(n) {  // full-colour: returns {rgb:Uint8Array[], preview:Uint8Array[]}
  const rgb = [], preview = [];
  for (let f = 0; f < n; f++) {
    const buf = new Uint8Array(S * S * 3);
    const t = (f / n) * Math.PI * 2;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const ang = Math.atan2(y - 64, x - 64), rad = Math.hypot(x - 64, y - 64) / 90;
      const hue = (ang / Math.PI + 1) / 2 + t / (Math.PI * 2), fall = Math.max(0, 1 - rad);
      const o = (y * S + x) * 3;
      buf[o] = clamp(255 * fall * (0.5 + 0.5 * Math.sin(hue * 6.283)));
      buf[o + 1] = clamp(235 * fall * (0.5 + 0.5 * Math.sin(hue * 6.283 + 2.1)));
      buf[o + 2] = clamp(255 * fall * (0.5 + 0.5 * Math.sin(hue * 6.283 + 4.2)));
    }
    rgb.push(buf); preview.push(quantizeRgbTile(buf));
  }
  return { rgb, preview };
}

// ─── The varied set ──────────────────────────────────────────────────────────
const configs = [
  { name: 'ball',  title: 'Bouncing Ball',   grid: [1, 1], allShades: false, nonce: 11,
    frames: [ballFrames(12)],                 delays: [90] },
  { name: 'plasma', title: 'Plasma',         grid: [1, 1], allShades: true,  nonce: 12,
    frames: [plasmaFrames(16, false, false)], delays: [80] },
  { name: 'rings', title: 'Rings',           grid: [1, 1], allShades: true,  nonce: 13,
    frames: [ringsFrames(16)],                delays: [80] },
  { name: 'heavy', title: 'Dithered',        grid: [1, 1], allShades: true,  nonce: 14,
    frames: [plasmaFrames(24, true, false)],  delays: [80], lossy: 40 },
  { name: 'srgb',  title: 'Full Colour',     grid: [1, 1], allShades: true,  nonce: 15, srgb: true,
    rgbSweep: rgbSweepFrames(16),             delays: [80], lossy: 8 },
  { name: 'wide',  title: 'Wide',            grid: [2, 1], allShades: true,  nonce: 16,
    frames: [plasmaFrames(16, false, false, 0), plasmaFrames(16, false, false, 1)], delays: [90] },
  // ep05 autonomous-print demo: a mid-size floor (bigger than `ball`, far short of the
  // 8 KB cap) so a survival print actually drains inventory and forces restock on camera.
  { name: 'demo',  title: 'Bouncing Ball',    grid: [1, 1], allShades: true,  nonce: 17,
    frames: [demoFrames(16)],                 delays: [90] },
];

// Optional filter: `node scripts/gen-docs-anim-states.mjs demo wide` regenerates just those.
const only = process.argv.slice(2);
for (const c of (only.length ? configs.filter(c => only.includes(c.name)) : configs)) {
  const [cols, rows] = c.grid;
  const nTiles = cols * rows;
  let comp;
  if (c.srgb) {
    comp = {
      gridCols: cols, gridRows: rows,
      frames: [c.rgbSweep.preview], rgbFrames: [c.rgbSweep.rgb],
      frameDelays: [new Array(c.rgbSweep.preview.length).fill(c.delays[0])],
      activeFrame: 0, sourceFilename: `${c.name}.gif`, title: c.title, author: null,
      allShades: c.allShades, codecMode: 'CARPET', colorSpace: 'srgb',
    };
  } else {
    comp = {
      gridCols: cols, gridRows: rows,
      frames: c.frames,
      frameDelays: c.frames.map(tf => new Array(tf.length).fill(c.delays[0])),
      activeFrame: 0, sourceFilename: `${c.name}.gif`, title: c.title, author: null,
      allShades: c.allShades, codecMode: 'CARPET',
    };
  }
  const ps = await mod.encodeComposition(comp, {
    title: c.title, author: null, nonce: c.nonce, whitelist: [],
    lossyQuality: c.lossy ?? null,
  });
  const outPath = join(repoDir, `docs/tools/anim-${c.name}-state.json`);
  await writeFile(outPath, JSON.stringify(ps, null, 2));
  const bytes = ps.tiles.reduce((s, t) => s + Buffer.from(t.carpetCompressedB64, 'base64').length, 0);
  console.log(`anim-${c.name}-state.json: ${cols}×${rows}, ${c.frames?.[0]?.length ?? c.rgbSweep.preview.length}f${c.lossy ? ' lossy' : ''}${c.srgb ? ' sRGB' : ''}, ${bytes} B`);
}
