/**
 * Pure image / tensor helpers shared by ML features.
 *
 * Everything here is dependency-free and side-effect-free so it can be unit
 * tested under plain Node (see test/ml-imageops.test.mjs). Canvas-dependent
 * resizing of full images lives in the worker (`worker.ts`); this module only
 * touches typed arrays.
 */

import type { PlainTensor } from './types.js';

// ─── RGBA → normalized NCHW/NHWC float tensor ─────────────────────────────────

/**
 * Pack RGBA bytes into a normalized float tensor: (v/255 - mean)/std per channel.
 * `rgba` is W*H*4 bytes. Alpha is dropped.
 */
export function rgbaToTensor(
  rgba: Uint8Array | Uint8ClampedArray,
  w: number,
  h: number,
  mean: readonly [number, number, number],
  std: readonly [number, number, number],
  layout: 'nchw' | 'nhwc',
): PlainTensor {
  const n = w * h;
  const out = new Float32Array(n * 3);
  if (layout === 'nchw') {
    for (let i = 0; i < n; i++) {
      const s = i * 4;
      out[i] = (rgba[s] / 255 - mean[0]) / std[0];
      out[n + i] = (rgba[s + 1] / 255 - mean[1]) / std[1];
      out[2 * n + i] = (rgba[s + 2] / 255 - mean[2]) / std[2];
    }
    return { type: 'float32', data: out, dims: [1, 3, h, w] };
  }
  for (let i = 0; i < n; i++) {
    const s = i * 4;
    out[i * 3] = (rgba[s] / 255 - mean[0]) / std[0];
    out[i * 3 + 1] = (rgba[s + 1] / 255 - mean[1]) / std[1];
    out[i * 3 + 2] = (rgba[s + 2] / 255 - mean[2]) / std[2];
  }
  return { type: 'float32', data: out, dims: [1, h, w, 3] };
}

// ─── Single-channel matte helpers ─────────────────────────────────────────────

export function sigmoid(x: number): number {
  return 1 / (1 + Math.exp(-x));
}

/** Min–max normalize a float matte into [0,1] in place; returns the same array. */
export function normalizeMinMax(m: Float32Array): Float32Array {
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < m.length; i++) {
    const v = m[i];
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  const range = hi - lo;
  if (range < 1e-8) return m;
  for (let i = 0; i < m.length; i++) m[i] = (m[i] - lo) / range;
  return m;
}

/**
 * Bilinear resample of a single-channel float matte from (sw×sh) to (dw×dh).
 */
export function resizeMatteBilinear(
  src: Float32Array,
  sw: number,
  sh: number,
  dw: number,
  dh: number,
): Float32Array {
  const out = new Float32Array(dw * dh);
  const sxRatio = sw / dw;
  const syRatio = sh / dh;
  for (let y = 0; y < dh; y++) {
    const fy = (y + 0.5) * syRatio - 0.5;
    const y0 = Math.max(0, Math.floor(fy));
    const y1 = Math.min(sh - 1, y0 + 1);
    const wy = fy - y0 < 0 ? 0 : fy - y0;
    for (let x = 0; x < dw; x++) {
      const fx = (x + 0.5) * sxRatio - 0.5;
      const x0 = Math.max(0, Math.floor(fx));
      const x1 = Math.min(sw - 1, x0 + 1);
      const wx = fx - x0 < 0 ? 0 : fx - x0;
      const a = src[y0 * sw + x0], b = src[y0 * sw + x1];
      const c = src[y1 * sw + x0], d = src[y1 * sw + x1];
      const top = a + (b - a) * wx;
      const bot = c + (d - c) * wx;
      out[y * dw + x] = top + (bot - top) * wy;
    }
  }
  return out;
}

/**
 * Crop the centered, aspect-preserved region of a matte produced from a padded
 * (letterboxed) model input, then resize it to (dw×dh) source-pixel space.
 *
 * `matte` is the raw model-space matte (mw×mh). `drawW/drawH` is the size of the
 * real (un-padded) content within the model input, offset by `padX/padY`.
 */
export function unpadAndResizeMatte(
  matte: Float32Array,
  mw: number,
  mh: number,
  padX: number,
  padY: number,
  drawW: number,
  drawH: number,
  dw: number,
  dh: number,
): Float32Array {
  // Extract content region.
  const cw = Math.max(1, Math.round(drawW));
  const ch = Math.max(1, Math.round(drawH));
  const px = Math.round(padX);
  const py = Math.round(padY);
  const content = new Float32Array(cw * ch);
  for (let y = 0; y < ch; y++) {
    const sy = Math.min(mh - 1, py + y);
    for (let x = 0; x < cw; x++) {
      const sx = Math.min(mw - 1, px + x);
      content[y * cw + x] = matte[sy * mw + sx];
    }
  }
  return resizeMatteBilinear(content, cw, ch, dw, dh);
}

/**
 * Apply a soft threshold + feather to a [0,1] matte, returning an 8-bit alpha.
 * Pixels well below `threshold` go fully transparent; a `feather`-wide ramp
 * around the threshold is preserved as partial alpha.
 */
export function matteToAlpha(
  matte: Float32Array,
  threshold: number,
  feather: number,
): Uint8Array {
  const out = new Uint8Array(matte.length);
  const lo = threshold - feather * 0.5;
  const hi = threshold + feather * 0.5;
  const range = Math.max(1e-6, hi - lo);
  for (let i = 0; i < matte.length; i++) {
    const t = (matte[i] - lo) / range;
    const a = t <= 0 ? 0 : t >= 1 ? 1 : t;
    out[i] = Math.round(a * 255);
  }
  return out;
}

/**
 * Composite an alpha matte onto an RGBA image: pixels become transparent where
 * alpha is 0. The image and alpha must share dimensions. Returns a new buffer.
 */
export function applyAlpha(
  rgba: Uint8ClampedArray | Uint8Array,
  alpha: Uint8Array,
  w: number,
  h: number,
): Uint8ClampedArray {
  const out = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    const s = i * 4;
    out[s] = rgba[s];
    out[s + 1] = rgba[s + 1];
    out[s + 2] = rgba[s + 2];
    // Combine with any pre-existing alpha (min — both must pass).
    out[s + 3] = Math.min(rgba[s + 3], alpha[i]);
  }
  return out;
}

// ─── Saliency bounding-box helpers (Phase 2) ──────────────────────────────────

export interface Rect { x: number; y: number; w: number; h: number; }

/**
 * Best square crop maximizing contained saliency mass.
 *
 * `sal` is a (sw×sh) saliency map in [0,1]. Returns a square Rect in saliency
 * coordinates, padded by `padFrac` of the side length, clamped to bounds.
 * Uses a separable integral image so candidate scoring is O(1) per position.
 */
export function bestSquareCrop(
  sal: Float32Array,
  sw: number,
  sh: number,
  padFrac = 0.05,
): Rect {
  const integral = buildIntegral(sal, sw, sh);
  const side = Math.min(sw, sh);
  // Coarse search step keeps this cheap even on large maps.
  const step = Math.max(1, Math.floor(side / 64));
  let best = -1, bx = 0, by = 0;
  for (let y = 0; y + side <= sh; y += step) {
    for (let x = 0; x + side <= sw; x += step) {
      const sum = rectSum(integral, sw, x, y, side, side);
      if (sum > best) { best = sum; bx = x; by = y; }
    }
  }
  // Apply padding by shrinking the square toward its center.
  const pad = Math.round(side * padFrac);
  const inner = Math.max(1, side - pad * 2);
  return clampRect({ x: bx + pad, y: by + pad, w: inner, h: inner }, sw, sh);
}

/** Centroid of a saliency map in [0,1] normalized coordinates. */
export function saliencyCentroid(sal: Float32Array, sw: number, sh: number): { cx: number; cy: number } {
  let sum = 0, sx = 0, sy = 0;
  for (let y = 0; y < sh; y++) {
    for (let x = 0; x < sw; x++) {
      const v = sal[y * sw + x];
      sum += v; sx += v * x; sy += v * y;
    }
  }
  if (sum < 1e-6) return { cx: 0.5, cy: 0.5 };
  return { cx: sx / sum / sw, cy: sy / sum / sh };
}

function buildIntegral(m: Float32Array, w: number, h: number): Float64Array {
  // (w+1)×(h+1) integral image.
  const I = new Float64Array((w + 1) * (h + 1));
  for (let y = 1; y <= h; y++) {
    let rowSum = 0;
    for (let x = 1; x <= w; x++) {
      rowSum += m[(y - 1) * w + (x - 1)];
      I[y * (w + 1) + x] = I[(y - 1) * (w + 1) + x] + rowSum;
    }
  }
  return I;
}

function rectSum(I: Float64Array, w: number, x: number, y: number, rw: number, rh: number): number {
  const W = w + 1;
  const x2 = x + rw, y2 = y + rh;
  return I[y2 * W + x2] - I[y * W + x2] - I[y2 * W + x] + I[y * W + x];
}

function clampRect(r: Rect, w: number, h: number): Rect {
  const x = Math.max(0, Math.min(r.x, w - 1));
  const y = Math.max(0, Math.min(r.y, h - 1));
  return { x, y, w: Math.min(r.w, w - x), h: Math.min(r.h, h - y) };
}
