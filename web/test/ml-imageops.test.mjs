/**
 * Node smoke tests for the pure ML image/tensor helpers (no DOM, no ORT).
 * Transpiles the TS module on the fly via esbuild, then asserts behavior.
 *
 *   node test/ml-imageops.test.mjs
 */
import { build } from 'esbuild';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

async function load(tsPath) {
  const out = join(tmpdir(), `loom-test-${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`);
  await build({ entryPoints: [tsPath], bundle: true, format: 'esm', outfile: out, platform: 'node', logLevel: 'silent' });
  return import(pathToFileURL(out).href);
}

let passed = 0;
function ok(name, cond) { assert.ok(cond, name); passed++; }

const m = await load('src/ml/imageOps.ts');

// rgbaToTensor: NCHW normalization
{
  const rgba = new Uint8Array([255, 0, 0, 255, 0, 255, 0, 255]); // 2x1 px
  const t = m.rgbaToTensor(rgba, 2, 1, [0, 0, 0], [1, 1, 1], 'nchw');
  assert.deepEqual([...t.dims], [1, 3, 1, 2]);
  // R plane = [1,0], G plane=[0,1], B plane=[0,0]
  ok('nchw R', Math.abs(t.data[0] - 1) < 1e-6 && Math.abs(t.data[1] - 0) < 1e-6);
  ok('nchw G', Math.abs(t.data[2] - 0) < 1e-6 && Math.abs(t.data[3] - 1) < 1e-6);
  ok('nchw B', t.data[4] === 0 && t.data[5] === 0);
}

// rgbaToTensor: mean/std applied
{
  const rgba = new Uint8Array([128, 128, 128, 255]);
  const t = m.rgbaToTensor(rgba, 1, 1, [0.5, 0.5, 0.5], [0.5, 0.5, 0.5], 'nchw');
  ok('mean/std', Math.abs(t.data[0] - ((128 / 255 - 0.5) / 0.5)) < 1e-6);
}

// sigmoid + normalizeMinMax
ok('sigmoid(0)=0.5', Math.abs(m.sigmoid(0) - 0.5) < 1e-9);
{
  const a = new Float32Array([2, 4, 6]);
  m.normalizeMinMax(a);
  ok('minmax', a[0] === 0 && a[2] === 1 && Math.abs(a[1] - 0.5) < 1e-6);
}

// resizeMatteBilinear: identity preserves values
{
  const src = new Float32Array([0, 1, 1, 0]);
  const r = m.resizeMatteBilinear(src, 2, 2, 2, 2);
  ok('resize identity', Math.abs(r[0] - 0) < 1e-6 && Math.abs(r[1] - 1) < 1e-6);
  const up = m.resizeMatteBilinear(src, 2, 2, 4, 4);
  ok('resize up size', up.length === 16);
}

// matteToAlpha thresholding
{
  const mt = new Float32Array([0.0, 0.5, 1.0]);
  const a = m.matteToAlpha(mt, 0.5, 0.0);
  ok('alpha below thr = 0', a[0] === 0);
  ok('alpha above thr = 255', a[2] === 255);
}

// applyAlpha keeps rgb, takes min alpha
{
  const rgba = new Uint8ClampedArray([10, 20, 30, 255]);
  const al = new Uint8Array([100]);
  const r = m.applyAlpha(rgba, al, 1, 1);
  ok('applyAlpha rgb', r[0] === 10 && r[1] === 20 && r[2] === 30);
  ok('applyAlpha min', r[3] === 100);
}

// bestSquareCrop: hot corner pulls the crop toward it
{
  const sw = 64, sh = 32;
  const sal = new Float32Array(sw * sh);
  for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) sal[y * sw + x] = 1; // top-left blob
  const rect = m.bestSquareCrop(sal, sw, sh, 0.0);
  ok('crop square', rect.w === rect.h);
  ok('crop near blob', rect.x < sw / 2 && rect.y < sh);
}

// saliencyCentroid
{
  const sw = 10, sh = 10;
  const sal = new Float32Array(sw * sh);
  sal[0] = 1; // top-left
  const c = m.saliencyCentroid(sal, sw, sh);
  ok('centroid', c.cx < 0.2 && c.cy < 0.2);
}

console.log(`✓ imageOps: ${passed} assertions passed`);
