/**
 * Phase 7.2 — Palette suggestion.
 *
 * K-means in Oklab over the image proposes the N most useful map-palette colors,
 * then previews a requantize restricted to that suggested palette. Purely
 * classical — no model download. Shown as a previewComp (undoable via Enter).
 */

import {
  requantizeGrid, mapBytesToImageData, buildOklabLookup, fullLegalPalette,
  findClosestInPaletteWithMetric, MAP_SIZE, type PaletteFlag,
} from '../../quantize.js';
import { rgbToOklab } from '../../oklab.js';
import { kmeansOklab } from '../helpers/kmeansOklab.js';
import { bitmapToImageData } from '../canvasOps.js';
import { showMlToast } from '../ui.js';
import { withActiveFrameTiles } from '../bridge.js';
import type { EditorBridge } from '../bridge.js';

export interface PaletteSuggestOptions {
  /** Target number of suggested colors. */
  colors?: number;
  /** Max pixels to sample for k-means (subsampled for speed). */
  maxSamples?: number;
}

export async function runPaletteSuggestion(bridge: EditorBridge, opts: PaletteSuggestOptions = {}): Promise<void> {
  const k = opts.colors ?? 16;
  const maxSamples = opts.maxSamples ?? 6000;
  const comp = bridge.getComp();

  // Sample from the retained source if present, else the current grid render.
  const bmp = bridge.getSourceBitmap();
  const img = bmp
    ? bitmapToImageData(bmp)
    : mapBytesToImageData(comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows);

  const samples = sampleOklab(img, maxSamples);
  if (samples.length < 9) { showMlToast('Not enough opaque pixels to suggest a palette.', 'warn'); return; }

  const { centers } = kmeansOklab(samples, k);

  // Map each Oklab center to the nearest legal map color → suggested palette.
  const oklab = buildOklabLookup();
  const legal = fullLegalPalette();
  const suggested: PaletteFlag = new Uint8Array(256);
  const nCenters = Math.floor(centers.length / 3);
  for (let c = 0; c < nCenters; c++) {
    const mb = findClosestInPaletteWithMetric(
      centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2], legal, oklab, 'OKLAB', null, null,
    );
    if (mb > 0) suggested[mb] = 1;
  }
  const distinct = suggested.reduce((s, v) => s + v, 0);

  // Preview a requantize restricted to the suggested palette.
  const params = { ...bridge.getReqParams(), customPalette: suggested, tilePalette: false };
  const tiles = requantizeGrid(
    bmp ? scaleToGrid(img, comp.gridCols * MAP_SIZE, comp.gridRows * MAP_SIZE) : img,
    comp, null, params,
  );
  bridge.showPreview(withActiveFrameTiles(comp, tiles));
  bridge.setStatus(`Suggested ${distinct} colors (k-means in Oklab) — Enter to keep, Esc to discard.`);
}

/** Collect a subsample of opaque pixels as flat Oklab triples. */
function sampleOklab(img: ImageData, maxSamples: number): Float32Array {
  const n = img.width * img.height;
  const stride = Math.max(1, Math.floor(n / maxSamples));
  const triples: number[] = [];
  const d = img.data;
  for (let i = 0; i < n; i += stride) {
    const s = i * 4;
    if (d[s + 3] < 128) continue;
    const [L, a, b] = rgbToOklab(d[s], d[s + 1], d[s + 2]);
    triples.push(L, a, b);
  }
  return new Float32Array(triples);
}

/** Scale an arbitrary-size ImageData to the grid via a canvas (source path). */
function scaleToGrid(img: ImageData, gw: number, gh: number): ImageData {
  const src = new OffscreenCanvas(img.width, img.height);
  src.getContext('2d')!.putImageData(img, 0, 0);
  const dst = new OffscreenCanvas(gw, gh);
  const dctx = dst.getContext('2d')!;
  dctx.imageSmoothingEnabled = true;
  dctx.imageSmoothingQuality = 'high';
  dctx.drawImage(src, 0, 0, gw, gh);
  return dctx.getImageData(0, 0, gw, gh);
}
