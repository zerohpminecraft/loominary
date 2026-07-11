/**
 * Generates the composite-lossy AV1 parity fixtures for the mod's JUnit tests:
 *
 *   src/test/resources/av1/av1_composite_stream.bin      — bare composition-wide lossy stream
 *                                                          (2×1 tiles → 256×128, 4 frames)
 *   src/test/resources/av1/frames_composite_expected.bin — the WEB decoder's output for that
 *                                                          stream (4 × 32768 map bytes)
 *
 * Av1CompositeRoundtripTest asserts Av1FrameDecoder (Chicory + MapPalette) reproduces these
 * bytes exactly, proving web preview == in-game result for composite lossy art.
 *
 *   node web/scripts/gen-av1-fixtures.mjs   (run from the repo root or from web/)
 */
import { build } from 'esbuild';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const webDir  = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(webDir, '..');
const outDir  = join(repoDir, 'src/test/resources/av1');

async function load(p) {
  const o = join(tmpdir(), `gen-${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`);
  await build({ entryPoints: [join(webDir, p)], bundle: true, format: 'esm', outfile: o, platform: 'node', logLevel: 'silent' });
  return import(pathToFileURL(o).href);
}

const { createWasiShim } = await load('src/av1/wasi.ts');
const codec = await load('src/av1/codec.ts');
const { IS_VALID } = await load('src/palette.ts');

async function makeExports(file) {
  const wasi = createWasiShim();
  const { instance } = await WebAssembly.instantiate(await readFile(join(webDir, file)), wasi.imports);
  const ex = instance.exports;
  wasi.bind(ex.memory);
  ex._initialize?.();
  return ex;
}
codec._setCodecFactory(
  () => makeExports('public/av1/av1-encode.wasm'),
  () => makeExports('public/av1/av1-decode.wasm'),
);

// 2×1 tile composition: 256×128, 4 frames of moving palette bands (content that straddles the
// tile boundary at x=128, which is exactly what composite mode exists to keep seamless).
const W = 256, H = 128, N = 4, QUALITY = 30;
const valid = [];
for (let b = 0; b < 256; b++) if (IS_VALID[b]) valid.push(b);
const frames = [];
for (let f = 0; f < N; f++) {
  const a = new Uint8Array(W * H);
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++)
      a[y * W + x] = valid[(Math.floor((x + f * 7) / 16) + Math.floor(y / 16) * 3) % valid.length];
  frames.push(a);
}

const stream  = await codec.encodeLossyColor(frames, QUALITY, undefined, { width: W, height: H });
const decoded = await codec.decodeLossyColor(stream, 0, N, undefined, { width: W, height: H });
if (decoded.length !== N) throw new Error(`decoded ${decoded.length} frames, expected ${N}`);

const expected = new Uint8Array(N * W * H);
decoded.forEach((fr, f) => expected.set(fr, f * W * H));

await mkdir(outDir, { recursive: true });
await writeFile(join(outDir, 'av1_composite_stream.bin'), stream);
await writeFile(join(outDir, 'frames_composite_expected.bin'), expected);
console.log(`composite fixtures: ${W}x${H} ×${N} frames, stream ${stream.length} B → ${outDir}`);
