/**
 * Generates the composite-lossy and sRGB AV1 parity fixtures for the mod's JUnit tests:
 *
 *   src/test/resources/av1/av1_composite_stream.bin      — bare composition-wide lossy stream
 *                                                          (2×1 tiles → 256×128, 4 frames)
 *   src/test/resources/av1/frames_composite_expected.bin — the WEB decoder's output for that
 *                                                          stream (4 × 32768 map bytes)
 *   src/test/resources/av1/av1_srgb_stream.bin           — true-RGB (off-palette) lossy stream,
 *                                                          single tile 128×128, 3 frames
 *   src/test/resources/av1/frames_srgb_idx_expected.bin  — web dec_tu_full idx output (3 × 16384)
 *   src/test/resources/av1/frames_srgb_rgb_expected.bin  — web dec_tu_full RGB output (3 × 49152)
 *   src/test/resources/av1/av1_srgb_composite_stream.bin — true-RGB composite stream (256×128, 3 frames)
 *   src/test/resources/av1/frames_srgb_composite_rgb_expected.bin — RGB output (3 × 98304)
 *
 * Av1CompositeRoundtripTest / Av1SrgbRoundtripTest assert Av1FrameDecoder (Chicory + MapPalette)
 * reproduces these bytes exactly, proving web preview == in-game result.
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

// ─── sRGB fixtures: true-RGB frames deliberately OFF the map palette, so the idx and rgb
// outputs differ meaningfully and any accidental palette quantisation in the RGB path fails
// the parity test. Smooth gradients + moving hue wheel exercise 4:2:0 chroma reconstruction.
function rgbFrame(w, h, f) {
  const a = new Uint8Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 3;
      a[i]     = (x * 2 + f * 31) & 0xff;               // red: horizontal ramp, shifts per frame
      a[i + 1] = (y * 2 + f * 17) & 0xff;               // green: vertical ramp
      a[i + 2] = ((x ^ y) + f * 53) & 0xff;             // blue: xor texture
    }
  }
  return a;
}

{
  const SW = 128, SH = 128, SN = 3;
  const rgbFrames = Array.from({ length: SN }, (_, f) => rgbFrame(SW, SH, f));
  const srgbStream = await codec.encodeLossyRgb(rgbFrames, QUALITY, undefined, { width: SW, height: SH });
  const dec = await codec.decodeLossyRgb(srgbStream, 0, SN, undefined, { width: SW, height: SH });
  if (dec.idx.length !== SN) throw new Error(`srgb decoded ${dec.idx.length} frames, expected ${SN}`);
  const idxOut = new Uint8Array(SN * SW * SH);
  const rgbOut = new Uint8Array(SN * SW * SH * 3);
  dec.idx.forEach((fr, f) => idxOut.set(fr, f * SW * SH));
  dec.rgb.forEach((fr, f) => rgbOut.set(fr, f * SW * SH * 3));
  await writeFile(join(outDir, 'av1_srgb_stream.bin'), srgbStream);
  await writeFile(join(outDir, 'frames_srgb_idx_expected.bin'), idxOut);
  await writeFile(join(outDir, 'frames_srgb_rgb_expected.bin'), rgbOut);
  console.log(`srgb fixtures: ${SW}x${SH} ×${SN} frames, stream ${srgbStream.length} B → ${outDir}`);
}

{
  const CW = 256, CH = 128, CN = 3;
  const rgbFrames = Array.from({ length: CN }, (_, f) => rgbFrame(CW, CH, f));
  const srgbStream = await codec.encodeLossyRgb(rgbFrames, QUALITY, undefined, { width: CW, height: CH });
  const dec = await codec.decodeLossyRgb(srgbStream, 0, CN, undefined, { width: CW, height: CH });
  if (dec.rgb.length !== CN) throw new Error(`srgb composite decoded ${dec.rgb.length} frames, expected ${CN}`);
  const rgbOut = new Uint8Array(CN * CW * CH * 3);
  dec.rgb.forEach((fr, f) => rgbOut.set(fr, f * CW * CH * 3));
  await writeFile(join(outDir, 'av1_srgb_composite_stream.bin'), srgbStream);
  await writeFile(join(outDir, 'frames_srgb_composite_rgb_expected.bin'), rgbOut);
  console.log(`srgb composite fixtures: ${CW}x${CH} ×${CN} frames, stream ${srgbStream.length} B → ${outDir}`);
}
