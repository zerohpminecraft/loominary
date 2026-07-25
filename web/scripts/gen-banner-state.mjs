/**
 * Produces a BANNER-mode LOOM state for the ep07 capture: a small static image
 * encoded entirely into CJK banner-name chunks (no carpet platform). The mod
 * reads these chunk strings as map-decoration names once the banners are placed
 * and registered onto a map.
 *
 *   cd web && node scripts/gen-banner-state.mjs
 *
 * Output: docs/tools/banner-art-state.json  (tile.chunks = CJK banner names)
 */
import { build } from 'esbuild';
import { writeFile, rm, readFile } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const webDir  = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(webDir, '..');

const entrySrc = `
export { encodeComposition } from './src/encode.ts';
export { IS_VALID, MC_PALETTE } from './src/palette.ts';
`;
const entry = join(webDir, `.banner-entry-${process.pid}.ts`);
await writeFile(entry, entrySrc);
let mod;
try {
  const o = join(webDir, `.banner-bundle-${process.pid}.mjs`);
  try {
    await build({ entryPoints: [entry], bundle: true, format: 'esm', outfile: o, platform: 'node',
      logLevel: 'silent', external: ['@bokuweb/zstd-wasm'] });
    mod = await import(pathToFileURL(o).href);
  } finally { await rm(o, { force: true }); }
} finally { await rm(entry, { force: true }); }

const { IS_VALID, MC_PALETTE } = mod;
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

// A bold heart on a dithered sunset gradient — recognizable, and detailed enough
// to need a couple dozen banners.
function heartFrame() {
  const px = new Uint8Array(S * S);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const g = y / S;
    let r = 240 - g * 120, gr = 90 + g * 40, b = 120 + g * 90;   // sunset sky
    const d = BAYER[(y & 3) * 4 + (x & 3)] * 26;
    // heart: two lobes + point, centered
    const nx = (x - 64) / 42, ny = (y - 58) / 42;
    const h = (nx * nx + ny * ny - 1);
    const inside = h * h * h - nx * nx * ny * ny * ny < 0;
    if (inside) { r = 230; gr = 30; b = 60; }
    px[y * S + x] = nearestByte(clamp(r + d), clamp(gr + d), clamp(b + d));
  }
  return px;
}

const comp = {
  gridCols: 1, gridRows: 1,
  frames: [[heartFrame()]],
  frameDelays: [[100]],
  activeFrame: 0, sourceFilename: 'heart.png', title: 'Heart', author: null,
  allShades: true, codecMode: 'BANNER',
};

const ps = await mod.encodeComposition(comp, { title: 'Heart', author: null, nonce: 21, whitelist: [] });
const outPath = join(repoDir, 'docs/tools/banner-art-state.json');
await writeFile(outPath, JSON.stringify(ps, null, 2));
const tile = ps.tiles[0];
console.log(`banner-art-state.json: codec=${ps.codecMode}, ${tile.chunks.length} banner chunks, first="${tile.chunks[0]?.slice(0,6)}…"`);
