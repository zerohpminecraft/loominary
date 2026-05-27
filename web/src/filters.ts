/**
 * Pre-quantization spatial image filters.
 *
 * Port of PngToMapColors.gaussianBlur / medianFilter / sharpenUnsharpMask /
 * posterize.  All filters operate on ImageData (RGBA, 0–255).
 *
 * The "apply to frames" path (used by the editor's P key) is handled in
 * quantize.ts via `applyFilterToMapColors()`.
 */

import { MC_PALETTE } from './palette.js';
import { rgbToOklab, oklabDistSq } from './oklab.js';
import type { PaletteFlag, OklabLookup } from './quantize.js';

// ─── Filter types ─────────────────────────────────────────────────────────────

export const FilterType = {
  SMOOTH:    'SMOOTH',
  MEDIAN:    'MEDIAN',
  SHARPEN:   'SHARPEN',
  POSTERIZE: 'POSTERIZE',
} as const;
export type FilterType = typeof FilterType[keyof typeof FilterType];

export interface FilterParams {
  type:     FilterType;
  strength: number;  // blur σ, median radius, sharpen amount, or posterize levels
}

// ─── Apply filter to ImageData ────────────────────────────────────────────────

/** Returns a new ImageData with the filter applied. */
export function applyFilter(img: ImageData, params: FilterParams): ImageData {
  switch (params.type) {
    case 'SMOOTH':    return gaussianBlur(img, params.strength);
    case 'MEDIAN':    return medianFilter(img, Math.max(1, Math.round(params.strength)));
    case 'SHARPEN':   return sharpenUnsharpMask(img, params.strength);
    case 'POSTERIZE': return posterize(img, Math.max(2, Math.round(params.strength)));
  }
}

// ─── Apply filter to map-colour frames (editor P key) ─────────────────────────

/**
 * Apply a filter to one or more map-colour frames in-place, re-quantizing
 * filtered pixels back to the union palette so no new colours are introduced.
 *
 * @param frames   Each element is a mutable byte[16384] tile frame.
 * @param filter   Filter parameters.
 * @param oklab    Pre-built OKLab lookup table.
 * @param selMask  Optional 16384-element mask; only selected pixels are filtered.
 *                 When provided, only colours present within the selection are
 *                 candidates for re-quantization.
 */
export function applyFilterToFrames(
  frames: Uint8Array[],
  filter: FilterParams,
  oklab: OklabLookup,
  selMask?: Uint8Array | null,
): void {
  // Build union palette from all frames (restricted to selection if provided).
  const palette: PaletteFlag = new Uint8Array(256);
  for (const frame of frames) {
    for (let i = 0; i < frame.length; i++) {
      if (selMask && !selMask[i]) continue;
      palette[frame[i]] = 1;
    }
  }
  palette[0] = 0; // transparent is not a palette entry

  for (const frame of frames) {
    // Render the frame to ImageData.
    const img = new ImageData(128, 128);
    for (let y = 0; y < 128; y++) {
      for (let x = 0; x < 128; x++) {
        const cb = frame[x + y * 128];
        const di = (y * 128 + x) * 4;
        if (cb === 0) {
          img.data[di + 3] = 0;
        } else {
          const p = MC_PALETTE[cb];
          img.data[di    ] = (p >> 16) & 0xff;
          img.data[di + 1] = (p >>  8) & 0xff;
          img.data[di + 2] =  p        & 0xff;
          img.data[di + 3] = 255;
        }
      }
    }

    // Apply the spatial filter.
    const filtered = applyFilter(img, filter);

    // Re-quantize filtered pixels back to the palette.
    for (let y = 0; y < 128; y++) {
      for (let x = 0; x < 128; x++) {
        const idx = x + y * 128;
        if (frame[idx] === 0) continue;
        if (selMask && !selMask[idx]) continue;

        const di = idx * 4;
        if (filtered.data[di + 3] < 128) { frame[idx] = 0; continue; }

        const [L, a, b] = rgbToOklab(filtered.data[di], filtered.data[di+1], filtered.data[di+2]);
        let bestDist = Infinity, best = frame[idx];
        for (let c = 1; c < 256; c++) {
          if (!palette[c]) continue;
          const e = oklab[c]; if (!e) continue;
          const d = oklabDistSq(L, a, b, e[0], e[1], e[2]);
          if (d < bestDist) { bestDist = d; best = c; }
        }
        frame[idx] = best;
      }
    }
  }
}

// ─── Gaussian blur ────────────────────────────────────────────────────────────

function gaussianBlur(img: ImageData, radius: number): ImageData {
  const sigma  = Math.max(0.01, radius * 0.5);
  const size   = Math.max(3, ((radius * 2 + 1) | 1));
  const half   = (size >> 1);

  // Build kernel.
  const kernel = new Float32Array(size);
  let sum = 0;
  for (let i = 0; i < size; i++) {
    const x = i - half;
    kernel[i] = Math.exp(-(x * x) / (2 * sigma * sigma));
    sum += kernel[i];
  }
  for (let i = 0; i < size; i++) kernel[i] /= sum;

  const w = img.width, h = img.height;
  const src = img.data;

  // Two separable 1D passes: horizontal then vertical.
  const tmp = new Float32Array(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let k = 0; k < size; k++) {
        const sx = Math.max(0, Math.min(w - 1, x + k - half));
        const si = (y * w + sx) * 4;
        const kw = kernel[k];
        r += src[si    ] * kw;
        g += src[si + 1] * kw;
        b += src[si + 2] * kw;
        a += src[si + 3] * kw;
      }
      const di = (y * w + x) * 4;
      tmp[di] = r; tmp[di+1] = g; tmp[di+2] = b; tmp[di+3] = a;
    }
  }

  const out = new ImageData(w, h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let k = 0; k < size; k++) {
        const sy = Math.max(0, Math.min(h - 1, y + k - half));
        const si = (sy * w + x) * 4;
        const kw = kernel[k];
        r += tmp[si    ] * kw;
        g += tmp[si + 1] * kw;
        b += tmp[si + 2] * kw;
        a += tmp[si + 3] * kw;
      }
      const di = (y * w + x) * 4;
      out.data[di    ] = clamp(r);
      out.data[di + 1] = clamp(g);
      out.data[di + 2] = clamp(b);
      out.data[di + 3] = clamp(a);
    }
  }
  return out;
}

// ─── Median filter ────────────────────────────────────────────────────────────

function medianFilter(img: ImageData, radius: number): ImageData {
  const w = img.width, h = img.height;
  const n = (2 * radius + 1) * (2 * radius + 1);
  const src = img.data;
  const out = new ImageData(w, h);
  const rs = new Int32Array(n);
  const gs = new Int32Array(n);
  const bs = new Int32Array(n);

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let k = 0;
      for (let dy = -radius; dy <= radius; dy++) {
        for (let dx = -radius; dx <= radius; dx++) {
          const px = Math.max(0, Math.min(w - 1, x + dx));
          const py = Math.max(0, Math.min(h - 1, y + dy));
          const si = (py * w + px) * 4;
          rs[k] = src[si    ];
          gs[k] = src[si + 1];
          bs[k] = src[si + 2];
          k++;
        }
      }
      rs.subarray(0, n).sort();
      gs.subarray(0, n).sort();
      bs.subarray(0, n).sort();
      const di = (y * w + x) * 4;
      out.data[di    ] = rs[n >> 1];
      out.data[di + 1] = gs[n >> 1];
      out.data[di + 2] = bs[n >> 1];
      out.data[di + 3] = src[di + 3];
    }
  }
  return out;
}

// ─── Unsharp mask ─────────────────────────────────────────────────────────────

function sharpenUnsharpMask(img: ImageData, amount: number): ImageData {
  const blurred = gaussianBlur(img, 1.0);
  const w = img.width, h = img.height;
  const src = img.data, bsrc = blurred.data;
  const out = new ImageData(w, h);
  for (let i = 0; i < w * h * 4; i += 4) {
    out.data[i    ] = clamp(src[i    ] + amount * (src[i    ] - bsrc[i    ]));
    out.data[i + 1] = clamp(src[i + 1] + amount * (src[i + 1] - bsrc[i + 1]));
    out.data[i + 2] = clamp(src[i + 2] + amount * (src[i + 2] - bsrc[i + 2]));
    out.data[i + 3] = src[i + 3];
  }
  return out;
}

// ─── Posterize ────────────────────────────────────────────────────────────────

function posterize(img: ImageData, levels: number): ImageData {
  const step = 255 / (levels - 1);
  const out = new ImageData(img.width, img.height);
  const src = img.data;
  for (let i = 0; i < src.length; i += 4) {
    out.data[i    ] = clamp(Math.round(Math.round(src[i    ] / step) * step));
    out.data[i + 1] = clamp(Math.round(Math.round(src[i + 1] / step) * step));
    out.data[i + 2] = clamp(Math.round(Math.round(src[i + 2] / step) * step));
    out.data[i + 3] = src[i + 3];
  }
  return out;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function clamp(v: number): number {
  return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
}
