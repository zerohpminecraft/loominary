/**
 * Phase 7.1 — Auto dither-mask.
 *
 * Derives a per-pixel dither-strength mask from the image's local contrast
 * (the editor's existing Otsu-based `computeDitherStrength`): full dithering on
 * smooth gradients, none on edges and flats. Previews a requantize that uses the
 * mask, so the user sees the effect; the mask can then be hand-tuned with the
 * Dither Brush. Classical — no model download.
 */

import {
  requantizeGrid, computeDitherStrength, buildOklabLookup, mapBytesToImageData, MAP_SIZE,
} from '../../quantize.js';
import { bitmapToGridImageData } from '../canvasOps.js';
import { withActiveFrameTiles } from '../bridge.js';
import type { EditorBridge } from '../bridge.js';

export async function runAutoDitherMask(bridge: EditorBridge): Promise<void> {
  const comp = bridge.getComp();
  const gridW = comp.gridCols * MAP_SIZE;
  const gridH = comp.gridRows * MAP_SIZE;
  const oklab = buildOklabLookup();

  const bmp = bridge.getSourceBitmap();
  const srcImg = bmp
    ? bitmapToGridImageData(bmp, gridW, gridH)
    : mapBytesToImageData(comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows);

  const mask = computeDitherStrength(srcImg, gridW, gridH, oklab);
  const params = { ...bridge.getReqParams(), useCustomDither: true, ditherMask: mask };
  const tiles = requantizeGrid(srcImg, comp, null, params);

  bridge.showPreview(withActiveFrameTiles(comp, tiles));
  bridge.setStatus('Auto dither-mask applied (smooth areas dithered, edges kept crisp) — Enter to keep, Esc to discard.');
}
