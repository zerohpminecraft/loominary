/**
 * Node roundtrip test for web/src/av1/codec.ts — proves the web AV1 encode→decode path
 * (PERM/INV_PERM + length-prefixed temporal-unit framing) is lossless, using the real
 * committed wasm binaries in public/av1/.
 *   node test/av1-codec.test.mjs
 */
import { build } from 'esbuild';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

async function load(p) {
  const o = join(tmpdir(), `t-${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`);
  await build({ entryPoints: [p], bundle: true, format: 'esm', outfile: o, platform: 'node', logLevel: 'silent' });
  return import(pathToFileURL(o).href);
}

const { createWasiShim } = await load('src/av1/wasi.ts');
const codec = await load('src/av1/codec.ts');
const { IS_VALID } = await load('src/palette.ts');

async function makeExports(file) {
  const wasi = createWasiShim();
  const { instance } = await WebAssembly.instantiate(await readFile(file), wasi.imports);
  const ex = instance.exports;
  wasi.bind(ex.memory);
  ex._initialize?.();
  return ex;
}
codec._setCodecFactory(
  () => makeExports('public/av1/av1-encode.wasm'),
  () => makeExports('public/av1/av1-decode.wasm'),
);

const MAP = 128 * 128, N = 5;
const valid = [];
for (let b = 0; b < 256; b++) if (IS_VALID[b]) valid.push(b);
const frames = [];
for (let f = 0; f < N; f++) {
  const a = new Uint8Array(MAP);
  for (let i = 0; i < MAP; i++) a[i] = valid[(i + f * 3) % valid.length];
  frames.push(a);
}

const stream = await codec.encodeLosslessMono(frames);
const out = await codec.decodeLosslessMono(stream, 0, N);
assert.equal(out.length, N, 'frame count');
let bad = 0;
for (let f = 0; f < N; f++) for (let i = 0; i < MAP; i++) if (out[f][i] !== frames[f][i]) bad++;
assert.equal(bad, 0, `${bad} mismatched pixels`);
console.log(`av1-codec: lossless roundtrip OK (${N} frames -> ${stream.length} B)`);

// Composite dims: the lossy path at composition size (2×1 tiles → 256×128) must produce
// full-size planes of valid palette bytes, deterministically (parity with the mod is proven
// separately by Av1CompositeRoundtripTest against generated fixtures).
{
  const W = 256, H = 128, PLANE = W * H, NC = 3, dims = { width: W, height: H };
  const comp = [];
  for (let f = 0; f < NC; f++) {
    const a = new Uint8Array(PLANE);
    for (let i = 0; i < PLANE; i++) a[i] = valid[(Math.floor(i / 64) + f * 5) % valid.length];
    comp.push(a);
  }
  const cs = await codec.encodeLossyColor(comp, 30, undefined, dims);
  const d1 = await codec.decodeLossyColor(cs, 0, NC, undefined, dims);
  const d2 = await codec.decodeLossyColor(cs, 0, NC, undefined, dims);
  assert.equal(d1.length, NC, 'composite frame count');
  for (let f = 0; f < NC; f++) {
    assert.equal(d1[f].length, PLANE, `composite frame ${f} plane size`);
    for (let i = 0; i < PLANE; i++) {
      if (!IS_VALID[d1[f][i]]) assert.fail(`invalid palette byte ${d1[f][i]} at frame ${f} px ${i}`);
      if (d1[f][i] !== d2[f][i]) assert.fail(`non-deterministic decode at frame ${f} px ${i}`);
    }
  }
  console.log(`av1-codec: composite lossy dims OK (${W}x${H}, ${NC} frames -> ${cs.length} B)`);
}
