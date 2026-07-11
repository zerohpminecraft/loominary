/**
 * Phase 4 — content-adaptive downscaling (classical, pure).
 *
 * Attacks Loominary's core problem: shrinking detailed art to 128² without
 * turning it to mud. Three opt-in "Downscale style" modes that slot in BEFORE
 * the existing Oklab quantize + dither (they only produce RGBA; quantization
 * stays the single source of truth):
 *
 *   - average:       plain box/area mean (the existing baseline behavior)
 *   - dominant:      most-frequent color per cell → crisp flat regions, less mud
 *   - edge-weighted: area mean weighted by local gradient → keeps edges sharp
 *
 * A neural pixelization path is deferred (see MODELS.md): no model with an
 * exportable ONNX checkpoint + redistributable license was available at build
 * time. The classical modes ship now and cover the same need well.
 *
 * Pure: operates on RGBA typed arrays, no canvas — unit tested under Node.
 */

export type DownscaleStyle = 'bilinear' | 'average' | 'dominant' | 'edge-weighted';

export const DOWNSCALE_STYLES: DownscaleStyle[] = ['bilinear', 'average', 'dominant', 'edge-weighted'];

export const DOWNSCALE_LABEL: Record<DownscaleStyle, string> = {
  'bilinear':      'Bilinear (default)',
  'average':       'Area average',
  'dominant':      'Dominant color',
  'edge-weighted': 'Edge-weighted',
};

export const DOWNSCALE_HINT: Record<DownscaleStyle, string> = {
  'bilinear':      'Smooth bilinear shrink — the standard canvas downscale.',
  'average':       'Box/area mean — softer than bilinear, no ringing.',
  'dominant':      'Most-frequent color per cell — crisp flats, less muddy blends.',
  'edge-weighted': 'Area mean weighted toward edges — keeps outlines sharp.',
};

/**
 * Downscale RGBA from (sw×sh) to (dw×dh) with the chosen content-adaptive style.
 * `bilinear` is not handled here (let the canvas do it) — callers should skip
 * this module for that style.
 */
export function downscale(
  src: Uint8ClampedArray | Uint8Array, sw: number, sh: number,
  dw: number, dh: number, style: Exclude<DownscaleStyle, 'bilinear'>,
): Uint8ClampedArray {
  switch (style) {
    case 'average':       return areaAverage(src, sw, sh, dw, dh, null);
    case 'edge-weighted': return areaAverage(src, sw, sh, dw, dh, gradientMap(src, sw, sh));
    case 'dominant':      return dominant(src, sw, sh, dw, dh);
  }
}

/** Area mean, optionally weighting each source pixel by a per-pixel weight map. */
function areaAverage(
  src: Uint8ClampedArray | Uint8Array, sw: number, sh: number,
  dw: number, dh: number, weight: Float32Array | null,
): Uint8ClampedArray {
  const out = new Uint8ClampedArray(dw * dh * 4);
  for (let ty = 0; ty < dh; ty++) {
    const sy0 = Math.floor((ty * sh) / dh);
    const sy1 = Math.max(sy0 + 1, Math.floor(((ty + 1) * sh) / dh));
    for (let tx = 0; tx < dw; tx++) {
      const sx0 = Math.floor((tx * sw) / dw);
      const sx1 = Math.max(sx0 + 1, Math.floor(((tx + 1) * sw) / dw));
      let r = 0, g = 0, b = 0, a = 0, wsum = 0;
      for (let sy = sy0; sy < sy1; sy++) {
        for (let sx = sx0; sx < sw && sx < sx1; sx++) {
          const si = (sy * sw + sx) * 4;
          const alpha = src[si + 3];
          if (alpha < 128) { continue; } // ignore transparent in color mean
          const w = weight ? 0.25 + weight[sy * sw + sx] : 1;
          r += src[si] * w; g += src[si + 1] * w; b += src[si + 2] * w;
          a += alpha; wsum += w;
        }
      }
      const di = (ty * dw + tx) * 4;
      const count = (sy1 - sy0) * (sx1 - sx0);
      if (wsum > 0) {
        out[di] = Math.round(r / wsum); out[di + 1] = Math.round(g / wsum); out[di + 2] = Math.round(b / wsum);
        out[di + 3] = Math.round(a / count);
      } else {
        out[di + 3] = 0; // fully transparent cell
      }
    }
  }
  return out;
}

/** Most-frequent quantized color per cell (output = mean of the winning bucket). */
function dominant(
  src: Uint8ClampedArray | Uint8Array, sw: number, sh: number, dw: number, dh: number,
): Uint8ClampedArray {
  const out = new Uint8ClampedArray(dw * dh * 4);
  const SHIFT = 4; // 4 bits/channel → 4096 buckets
  const counts = new Map<number, { n: number; r: number; g: number; b: number; a: number }>();
  for (let ty = 0; ty < dh; ty++) {
    const sy0 = Math.floor((ty * sh) / dh);
    const sy1 = Math.max(sy0 + 1, Math.floor(((ty + 1) * sh) / dh));
    for (let tx = 0; tx < dw; tx++) {
      const sx0 = Math.floor((tx * sw) / dw);
      const sx1 = Math.max(sx0 + 1, Math.floor(((tx + 1) * sw) / dw));
      counts.clear();
      let opaque = 0, total = 0;
      for (let sy = sy0; sy < sy1; sy++) {
        for (let sx = sx0; sx < sw && sx < sx1; sx++) {
          const si = (sy * sw + sx) * 4;
          total++;
          if (src[si + 3] < 128) continue;
          opaque++;
          const key = ((src[si] >> SHIFT) << 8) | ((src[si + 1] >> SHIFT) << 4) | (src[si + 2] >> SHIFT);
          let e = counts.get(key);
          if (!e) { e = { n: 0, r: 0, g: 0, b: 0, a: 0 }; counts.set(key, e); }
          e.n++; e.r += src[si]; e.g += src[si + 1]; e.b += src[si + 2]; e.a += src[si + 3];
        }
      }
      const di = (ty * dw + tx) * 4;
      if (opaque * 2 < total) { out[di + 3] = 0; continue; } // mostly transparent
      let best: { n: number; r: number; g: number; b: number; a: number } | null = null;
      for (const e of counts.values()) if (!best || e.n > best.n) best = e;
      if (best) {
        out[di] = Math.round(best.r / best.n);
        out[di + 1] = Math.round(best.g / best.n);
        out[di + 2] = Math.round(best.b / best.n);
        out[di + 3] = 255;
      }
    }
  }
  return out;
}

/** Per-pixel gradient magnitude in [0,1] (luma Sobel-ish), for edge weighting. */
function gradientMap(src: Uint8ClampedArray | Uint8Array, w: number, h: number): Float32Array {
  const g = new Float32Array(w * h);
  const luma = (i: number) => 0.299 * src[i] + 0.587 * src[i + 1] + 0.114 * src[i + 2];
  let max = 1e-6;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const xl = Math.max(0, x - 1), xr = Math.min(w - 1, x + 1);
      const yt = Math.max(0, y - 1), yb = Math.min(h - 1, y + 1);
      const gx = luma((y * w + xr) * 4) - luma((y * w + xl) * 4);
      const gy = luma((yb * w + x) * 4) - luma((yt * w + x) * 4);
      const m = Math.sqrt(gx * gx + gy * gy);
      g[y * w + x] = m;
      if (m > max) max = m;
    }
  }
  for (let i = 0; i < g.length; i++) g[i] /= max;
  return g;
}
