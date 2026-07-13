/**
 * Generates the sRGB demo state the in-game docs harness loads with
 * `/loominary load srgb` (scripts/game-shots.sh copies it into run/loominary_saves/).
 *
 * The art is the SAME 512×512 sunset scene as web/e2e/fixtures/sample.png (keep drawScene in
 * sync with e2e/gen-fixtures.mjs), box-downscaled to one 128×128 tile and encoded through the
 * real sRGB export pipeline (encodeComposition → manifest v7 + AV1 colour stream), so the wiki
 * can show the palette preview and the full-colour version of the identical image side by side.
 * Everything is deterministic; rerunning produces the same file.
 *
 *   node scripts/gen-docs-srgb-state.mjs   (run from web/)
 *
 * Output (committed): docs/tools/srgb-state.json
 */
import { build } from 'esbuild';
import { writeFile, rm } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const webDir  = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(webDir, '..');

const entrySrc = `
export { encodeComposition } from './src/encode.ts';
export { quantizeRgbTile } from './src/srgb.ts';
export { _setCodecFactory } from './src/av1/codec.ts';
export { createWasiShim } from './src/av1/wasi.ts';
`;
const entry = join(webDir, `.srgb-state-entry-${process.pid}.ts`);
await writeFile(entry, entrySrc);
let mod;
try {
  const o = join(webDir, `.srgb-state-bundle-${process.pid}.mjs`);
  try {
    await build({ entryPoints: [entry], bundle: true, format: 'esm', outfile: o, platform: 'node',
      logLevel: 'silent', external: ['@bokuweb/zstd-wasm'] });
    mod = await import(pathToFileURL(o).href);
  } finally {
    await rm(o, { force: true });
  }
} finally {
  await rm(entry, { force: true });
}

const { readFile } = await import('node:fs/promises');
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

// ─── Test art: sunset landscape — keep in sync with e2e/gen-fixtures.mjs drawScene ─
function drawScene(w, h) {
  const img = new Uint8Array(w * h * 4);
  const set = (x, y, r, g, b) => {
    const i = (y * w + x) * 4;
    img[i] = r; img[i + 1] = g; img[i + 2] = b; img[i + 3] = 255;
  };
  const horizon = Math.floor(h * 0.62);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (y < horizon) {
        const t = y / horizon;
        set(x, y, Math.floor(40 + 215 * t * t), Math.floor(30 + 110 * t), Math.floor(90 + 30 * (1 - t)));
      } else {
        const t = (2 * horizon - y) / horizon;
        const ripple = Math.sin(y * 0.18 + x * 0.01) * 3;
        set(x, y, Math.floor((40 + 215 * t * t) * 0.55 + ripple),
                   Math.floor((30 + 110 * t) * 0.55 + ripple),
                   Math.floor((90 + 30 * (1 - t)) * 0.6 + ripple));
      }
    }
  }
  const sx = Math.floor(w * 0.68), sy = Math.floor(h * 0.42), sr = Math.floor(h * 0.09);
  for (let y = sy - sr; y <= sy + sr; y++)
    for (let x = sx - sr; x <= sx + sr; x++) {
      const d = Math.hypot(x - sx, y - sy);
      if (d <= sr && y < horizon && x >= 0 && x < w) {
        const glow = 1 - (d / sr) * 0.3;
        set(x, y, 255, Math.floor(225 * glow), Math.floor(140 * glow));
      }
    }
  for (let x = 0; x < w; x++) {
    const r1 = horizon - h * 0.18 + Math.sin(x * 0.013) * h * 0.07 + Math.sin(x * 0.037 + 2) * h * 0.03;
    const r2 = horizon - h * 0.07 + Math.sin(x * 0.021 + 5) * h * 0.05;
    for (let y = Math.max(0, Math.floor(r1)); y < horizon; y++) set(x, y, 38, 28, 58);
    for (let y = Math.max(0, Math.floor(r2)); y < horizon; y++) set(x, y, 22, 16, 38);
  }
  return img;
}

// 512×512 RGBA → 128×128 packed RGB via 4×4 box filter (deterministic integer math).
function downscaleToTileRgb(rgba, srcW) {
  const K = srcW / 128;
  const out = new Uint8Array(128 * 128 * 3);
  for (let y = 0; y < 128; y++) {
    for (let x = 0; x < 128; x++) {
      let r = 0, g = 0, b = 0;
      for (let dy = 0; dy < K; dy++)
        for (let dx = 0; dx < K; dx++) {
          const i = ((y * K + dy) * srcW + x * K + dx) * 4;
          r += rgba[i]; g += rgba[i + 1]; b += rgba[i + 2];
        }
      const n = K * K, o = (y * 128 + x) * 3;
      out[o] = Math.round(r / n); out[o + 1] = Math.round(g / n); out[o + 2] = Math.round(b / n);
    }
  }
  return out;
}

const rgbTile = downscaleToTileRgb(drawScene(512, 512), 512);
const preview = mod.quantizeRgbTile(rgbTile);

const comp = {
  gridCols: 1, gridRows: 1,
  frames: [[preview]],
  rgbFrames: [[rgbTile]],
  frameDelays: [[100]],
  activeFrame: 0,
  sourceFilename: 'sample.png',
  title: 'Full Colour Demo',
  author: null,
  allShades: true,
  codecMode: 'CARPET',
  colorSpace: 'srgb',
};

const ps = await mod.encodeComposition(comp, {
  title: 'Full Colour Demo', author: null, nonce: 7, whitelist: [],
  lossyQuality: 8, // AV1 quantizer, low = high quality — this is a showcase shot
});

const outPath = join(repoDir, 'docs/tools/srgb-state.json');
await writeFile(outPath, JSON.stringify(ps, null, 2));
const bytes = Buffer.from(ps.tiles[0].carpetCompressedB64, 'base64').length;
console.log(`srgb-state.json: 1×1 static sRGB tile, payload ${bytes} B compressed → ${outPath}`);
