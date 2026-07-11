/**
 * End-to-end wire-format test for composition-wide lossy encoding (FLAG_AV1_COMPOSITE).
 *
 * Encodes a 2×2 animated composition through encodeCompositionLossy, then reassembles it the
 * way the MOD does: decompress each tile payload → parse its manifest → slice the stream
 * segment at av1StreamOffset → concatenate in tile-index order → decode once at composition
 * size → crop each tile.  Locks the payload layout both sides depend on.
 *   node test/composite-payload.test.mjs
 */
import { build } from 'esbuild';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const entrySrc = `
export { encodeCompositionLossy, compositeEligible, cropTile } from './src/encode.ts';
export { _setCodecFactory, decodeLossyColor } from './src/av1/codec.ts';
export { fromBytes, av1Composite, av1StreamOffset, FLAG_ANIMATED, FLAG_AV1, FLAG_AV1_LOSSY } from './src/manifest.ts';
export { decompress } from './src/compression.ts';
export { IS_VALID } from './src/palette.ts';
export { computeMuxAllocation, applyCarpetMux, budgetStatus } from './src/mux.ts';
export { emptyTileData } from './src/payload-state.ts';
`;
const entry = join(process.cwd(), `.composite-test-entry-${process.pid}.ts`);
await writeFile(entry, entrySrc);
let mod;
try {
  // zstd-wasm stays external (its Node init uses require(), which esbuild's ESM output breaks),
  // so the bundle must live under web/ where the bare import can resolve node_modules.
  const o = join(process.cwd(), `.composite-test-bundle-${process.pid}.mjs`);
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

const { createWasiShim } = await (async () => {
  const o = join(tmpdir(), `ws-${Date.now()}.mjs`);
  await build({ entryPoints: ['src/av1/wasi.ts'], bundle: true, format: 'esm', outfile: o, platform: 'node', logLevel: 'silent' });
  return import(pathToFileURL(o).href);
})();

async function makeExports(file) {
  const wasi = createWasiShim();
  const { instance } = await WebAssembly.instantiate(await readFile(file), wasi.imports);
  const ex = instance.exports;
  wasi.bind(ex.memory);
  ex._initialize?.();
  return ex;
}
mod._setCodecFactory(
  () => makeExports('public/av1/av1-encode.wasm'),
  () => makeExports('public/av1/av1-decode.wasm'),
);

// 2×2 composition, 3 frames per tile.  Content is drifting value-noise — smooth 2D gradients
// continuous across the tile boundaries (like real art), where lossy AV1 reliably beats the
// raw+zstd floor.  Pure random noise would go the other way (zstd wins) and skip the point.
const COLS = 2, ROWS = 2, TILES = COLS * ROWS, N = 3, MAP = 128 * 128;
const W = COLS * 128, H = ROWS * 128;
const valid = [];
for (let b = 0; b < 256; b++) if (mod.IS_VALID[b]) valid.push(b);
let seed = 42;
const rand = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
const STEP = 16, GW = W / STEP + 1, GH = H / STEP + 1;
const latA = Array.from({ length: GH }, () => Array.from({ length: GW }, rand));
const latB = Array.from({ length: GH }, () => Array.from({ length: GW }, rand));
function noiseAt(gx, gy, t) {
  const cx = Math.floor(gx / STEP), cy = Math.floor(gy / STEP);
  const fx = (gx % STEP) / STEP,    fy = (gy % STEP) / STEP;
  const at = (lat, x, y) => lat[Math.min(y, GH - 1)][Math.min(x, GW - 1)];
  const bilerp = (lat) =>
    at(lat, cx, cy) * (1 - fx) * (1 - fy) + at(lat, cx + 1, cy) * fx * (1 - fy)
    + at(lat, cx, cy + 1) * (1 - fx) * fy + at(lat, cx + 1, cy + 1) * fx * fy;
  return bilerp(latA) * (1 - t) + bilerp(latB) * t;
}
const tileFrames = Array.from({ length: TILES }, (_, ti) =>
  Array.from({ length: N }, (_, f) => {
    const a = new Uint8Array(MAP);
    const x0 = (ti % COLS) * 128, y0 = Math.floor(ti / COLS) * 128;
    const t = N > 1 ? f / (N - 1) : 0;
    for (let y = 0; y < 128; y++)
      for (let x = 0; x < 128; x++)
        a[y * 128 + x] = valid[Math.min(valid.length - 1,
          Math.floor(noiseAt(x0 + x, y0 + y, t) * valid.length))];
    return a;
  }));
const tileDelays = Array.from({ length: TILES }, () => new Array(N).fill(80));

assert.equal(mod.compositeEligible(tileFrames, TILES), true, 'eligibility');

const r = await mod.encodeCompositionLossy(tileFrames, tileDelays, COLS, ROWS, false, {
  author: 'Tester', title: 'Seamless', nonce: 7, lossyQuality: 40, captureLossyFrames: true,
});
assert.equal(r.composite, true, 'composite must beat the raw floor on noise content');
assert.equal(r.tiles.length, TILES, 'one payload per tile');
assert.ok(r.stream?.length > 0, 'stream present');
assert.ok(r.lossyTileFrames?.length === TILES, 'decoded truth per tile');

// Reassemble exactly like MapBannerDecoder.handleCompositeTile does.
const segments = new Array(TILES);
for (let ti = 0; ti < TILES; ti++) {
  const raw = await mod.decompress(r.tiles[ti].compressed);
  const mf  = mod.fromBytes(raw);
  assert.equal(mod.av1Composite(mf), true, `tile ${ti} composite flag`);
  assert.equal(mf.flags & mod.FLAG_ANIMATED,  mod.FLAG_ANIMATED,  `tile ${ti} animated flag`);
  assert.equal(mf.flags & mod.FLAG_AV1,       mod.FLAG_AV1,       `tile ${ti} av1 flag`);
  assert.equal(mf.flags & mod.FLAG_AV1_LOSSY, mod.FLAG_AV1_LOSSY, `tile ${ti} lossy flag`);
  assert.equal(mf.cols, COLS); assert.equal(mf.rows, ROWS);
  assert.equal(mf.tileCol, ti % COLS); assert.equal(mf.tileRow, Math.floor(ti / COLS));
  assert.equal(mf.frameCount, N); assert.equal(mf.nonce, 7);
  assert.equal(mf.username, 'Tester'); assert.equal(mf.title, 'Seamless');
  assert.deepEqual(mf.frameDelays, [80], `tile ${ti} uniform delays collapse to global`);
  segments[mf.tileRow * COLS + mf.tileCol] = raw.subarray(mod.av1StreamOffset(mf));
}
const stream = new Uint8Array(segments.reduce((n, s) => n + s.length, 0));
{ let off = 0; for (const s of segments) { stream.set(s, off); off += s.length; } }
assert.deepEqual(stream, r.stream, 'concatenated segments must rebuild the stream exactly');

// Decode at composition size and crop — must equal the encoder's captured truth frames.
const frames = await mod.decodeLossyColor(stream, 0, N, undefined, { width: COLS * 128, height: ROWS * 128 });
assert.equal(frames.length, N);
for (let ti = 0; ti < TILES; ti++) {
  for (let f = 0; f < N; f++) {
    const crop = mod.cropTile(frames[f], ti % COLS, Math.floor(ti / COLS), COLS);
    assert.deepEqual(crop, r.lossyTileFrames[ti][f], `tile ${ti} frame ${f} crop matches captured truth`);
  }
}
console.log(`composite-payload: ${COLS}x${ROWS} ×${N} frames OK — stream ${r.stream.length} B split across ${TILES} payloads, reassembly + crop exact`);

// ── Mux round-trip over the composite payloads ────────────────────────────────
// Composite tiles are incompressible AV1 slices, so multi-tile lossy always overflows carpet
// budgets and goes through mux.  Simulate the FULL loop the way the mod runs it: allocation
// from the ACTUAL payload sizes (the encodeAndMux invariant) → applyCarpetMux slices → receiver
// buffer of role.totalBytes filled from own segment + donor cargo slices → must rebuild each
// receiver's payload byte-exactly (a stale allocation is what corrupted receivers in the field:
// "Src size is incorrect").
{
  const CODEC = 'CARPET_SHADE';
  const b64 = (bytes) => Buffer.from(bytes).toString('base64');
  const unb64 = (s) => new Uint8Array(Buffer.from(s, 'base64'));

  const payloads = r.tiles.map(t => t.compressed);
  const sizes = payloads.map(p => p.length);
  assert.ok(mod.budgetStatus(sizes, CODEC).overBudget.length > 0,
    'composite payloads must overflow the carpet budget so mux is exercised');

  // Escalate blank donors until the allocation resolves (same loop as encodeAndMux).
  let donors = 0;
  let alloc = mod.computeMuxAllocation(sizes, CODEC);
  while (alloc.unresolved > 0 && donors < 99) {
    donors++;
    alloc = mod.computeMuxAllocation([...sizes, ...Array(donors).fill(0)], CODEC);
  }
  assert.equal(alloc.unresolved, 0, 'allocation must resolve with blank donors');

  const tiles = [...payloads.map(p => ({ ...mod.emptyTileData(), carpetCompressedB64: b64(p) })),
                 ...Array.from({ length: donors }, () => ({ ...mod.emptyTileData(), isDonorOnly: true }))];
  const allPayloads = [...payloads, ...Array.from({ length: donors }, () => new Uint8Array(0))];
  mod.applyCarpetMux(tiles, alloc, allPayloads);

  for (const role of alloc.roles) {
    if (role.role !== 'receiver') continue;
    const buffer = new Uint8Array(role.totalBytes);
    let received = 0;
    const own = unb64(tiles[role.ti].muxCargoB64);       // receiver's own segment
    buffer.set(own, 0); received += own.length;
    for (const { donorTi, offset, len } of role.donors) { // donor cargo = own ++ guests in guest order
      const donorRole = alloc.roles[donorTi];
      const cargo = unb64(tiles[donorTi].muxCargoB64);
      let pos = donorRole.ownBytes;
      for (const g of donorRole.guests) {
        if (g.rxTi === role.ti && g.rxOffset === offset) break;
        pos += g.len;
      }
      buffer.set(cargo.subarray(pos, pos + len), offset); received += len;
    }
    assert.equal(received, role.totalBytes, `receiver ${role.ti} byte count`);
    assert.deepEqual(buffer, payloads[role.ti], `receiver ${role.ti} payload rebuilt byte-exactly`);
    const raw = await mod.decompress(buffer);
    assert.equal(mod.av1Composite(mod.fromBytes(raw)), true, `receiver ${role.ti} decompresses to a composite manifest`);
  }
  console.log(`composite-payload: mux round-trip OK — ${alloc.roles.filter(x => x.role === 'receiver').length} receivers via ${donors} blank donors (${CODEC})`);
}
