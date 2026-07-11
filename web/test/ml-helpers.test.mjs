/**
 * Node smoke tests for pure ML helpers: NL-means and k-means.
 *   node test/ml-helpers.test.mjs
 */
import { build } from 'esbuild';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

async function load(tsPath) {
  const out = join(tmpdir(), `loom-${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`);
  await build({ entryPoints: [tsPath], bundle: true, format: 'esm', outfile: out, platform: 'node', logLevel: 'silent' });
  return import(pathToFileURL(out).href);
}

let passed = 0;
const ok = (n, c) => { assert.ok(c, n); passed++; };

// ── NL-means ──────────────────────────────────────────────────────────────
{
  const { nlMeans } = await load('src/ml/helpers/nlmeans.ts');
  const w = 24, h = 24;
  // Gray field with broad-spectrum additive noise (the case NL-means targets).
  let rseed = 12345;
  const rnd = () => { rseed = (1103515245 * rseed + 12345) & 0x7fffffff; return rseed / 0x7fffffff; };
  const rgba = new Uint8ClampedArray(w * h * 4);
  let varIn = 0;
  for (let i = 0; i < w * h; i++) {
    const v = 120 + Math.round((rnd() - 0.5) * 60);
    rgba[i*4]=v; rgba[i*4+1]=v; rgba[i*4+2]=v; rgba[i*4+3]=255;
    varIn += (v - 120) * (v - 120);
  }
  const out = nlMeans(rgba, w, h, { searchRadius: 4, patchRadius: 1, h: 20 });
  let varOut = 0;
  for (let i = 0; i < w * h; i++) varOut += (out[i*4] - 120) * (out[i*4] - 120);
  ok('nlmeans reduces variance', varOut < varIn);
  ok('nlmeans keeps alpha', out[(8*w+8)*4+3] === 255);
  // Transparent passthrough
  const t = new Uint8ClampedArray([10,20,30,0]);
  const to = nlMeans(t, 1, 1);
  ok('nlmeans transparent passthrough', to[3] === 0 && to[0] === 10);
}

// ── k-means Oklab ─────────────────────────────────────────────────────────
{
  const { kmeansOklab } = await load('src/ml/helpers/kmeansOklab.ts');
  // Two tight clusters far apart in L.
  const s = [];
  for (let i = 0; i < 50; i++) s.push(0.1 + Math.random()*0.01, 0, 0);
  for (let i = 0; i < 50; i++) s.push(0.9 + Math.random()*0.01, 0, 0);
  const { centers, counts } = kmeansOklab(new Float32Array(s), 2, 20, 7);
  ok('kmeans k=2 centers', centers.length === 6);
  const Ls = [centers[0], centers[3]].sort((a,b)=>a-b);
  ok('kmeans separates clusters', Ls[0] < 0.3 && Ls[1] > 0.7);
  ok('kmeans counts sum', counts[0] + counts[1] === 100);
  // k clamped to n
  const r2 = kmeansOklab(new Float32Array([0.5,0,0]), 5);
  ok('kmeans clamps k to n', r2.centers.length === 3);
}

// ── reduce ranking ────────────────────────────────────────────────────────
{
  const { projectReductions } = await load('src/ml/helpers/reduceRanking.ts');
  const { buildOklabLookup, Strategy } = await load('src/quantize.ts');
  const oklab = buildOklabLookup();
  // 16384 bytes using ~6 distinct colors.
  const mc = new Uint8Array(16384);
  const cols = [4, 8, 12, 16, 20, 24];
  for (let i = 0; i < mc.length; i++) mc[i] = cols[i % cols.length];
  const proj = projectReductions(mc, oklab, [Strategy.RAREST, Strategy.CLOSEST, Strategy.WEIGHTED], 3);
  ok('reduceRanking 3 strategies', proj.length === 3);
  ok('reduceRanking starts at 6', proj[0].startDistinct === 6);
  ok('reduceRanking reduces', proj[0].finalDistinct < proj[0].startDistinct);
  ok('reduceRanking pixels changed', proj[0].pixelsChanged > 0);
}

// ── gif frame scoring ─────────────────────────────────────────────────────
{
  const { scoreFrames, suggestDrops } = await load('src/ml/helpers/gifFrameScore.ts');
  const A = new Uint8Array(16384).fill(4);
  const B = new Uint8Array(16384).fill(4); // identical to A → redundant
  const C = new Uint8Array(16384).fill(40); // very different
  const scores = scoreFrames([A, B, C]);
  ok('gif scores length', scores.length === 3);
  ok('gif frame0 infinite redundancy', scores[0].redundancy === Infinity);
  ok('gif identical frame redundant', scores[1].redundancy < scores[2].redundancy);
  const drops = suggestDrops([A, B, C], 2);
  ok('gif suggests dropping the redundant frame', drops.length === 1 && drops[0] === 1);
}

// ── pixelize downscale ────────────────────────────────────────────────────
{
  const { downscale } = await load('src/ml/pixelize.ts');
  // 2x2 all-opaque, average → 1x1 mean.
  const px = new Uint8ClampedArray([
    100,0,0,255,  200,0,0,255,
    0,100,0,255,   0,200,0,255,
  ]);
  const avg = downscale(px, 2, 2, 1, 1, 'average');
  ok('downscale average mean R', avg[0] === 75); // (100+200+0+0)/4
  ok('downscale average alpha', avg[3] === 255);

  // dominant: a 4x1 cell where red dominates (3 of 4) → red output.
  const dom = new Uint8ClampedArray([
    240,16,16,255,  240,16,16,255,  16,16,240,255,  240,16,16,255,
  ]);
  const d = downscale(dom, 4, 1, 1, 1, 'dominant');
  ok('downscale dominant picks majority', d[0] > 200 && d[2] < 60);

  // edge-weighted produces valid opaque output and correct size.
  const ew = downscale(px, 2, 2, 1, 1, 'edge-weighted');
  ok('downscale edge-weighted size', ew.length === 4 && ew[3] === 255);

  // transparent cell → transparent out.
  const tr = new Uint8ClampedArray([10,10,10,0, 10,10,10,0, 10,10,10,0, 10,10,10,0]);
  const t = downscale(tr, 2, 2, 1, 1, 'average');
  ok('downscale transparent cell', t[3] === 0);
}

console.log(`✓ ml-helpers: ${passed} assertions passed`);
