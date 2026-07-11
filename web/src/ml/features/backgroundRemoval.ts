/**
 * Phase 1 — Background removal.
 *
 * Runs a matting model (default RMBG-1.4) on the full-resolution retained source
 * (or the 128-grid render when no source exists), composites the alpha matte to
 * make the background transparent (index-0), and feeds the result through the
 * existing requantize path. The result is shown as a previewComp so the editor's
 * built-in Enter=keep / Esc=discard handlers make it undoable for free.
 */

import { requantizeGrid, mapBytesToImageData, MAP_SIZE } from '../../quantize.js';
import { ensureModel, run, preprocess } from '../mlClient.js';
import { getModel, type ModelId } from '../registry.js';
import {
  normalizeMinMax, unpadAndResizeMatte, matteToAlpha, applyAlpha,
} from '../imageOps.js';
import { bitmapToImageData, scaleRgbaToImageData, compToBitmap } from '../canvasOps.js';
import { requestConsent, showMlTask, showMlToast } from '../ui.js';
import type { EditorBridge } from '../bridge.js';
import { withActiveFrameTiles } from '../bridge.js';

/** RMBG / ormbg share the same 1024² input and [0.5]/[1.0] normalization. */
const INPUT = 1024;
const MEAN: [number, number, number] = [0.5, 0.5, 0.5];
const STD: [number, number, number] = [1, 1, 1];

export interface BgRemovalOptions {
  modelId?: Extract<ModelId, 'rmbg-1.4' | 'ormbg'>;
  /** Soft threshold on the [0,1] matte (default 0.5). */
  threshold?: number;
  /** Feather width around the threshold, in matte units (default 0.06 ≈ 1px soft). */
  feather?: number;
}

export async function runBackgroundRemoval(bridge: EditorBridge, opts: BgRemovalOptions = {}): Promise<void> {
  const modelId = opts.modelId ?? 'rmbg-1.4';
  const threshold = opts.threshold ?? 0.5;
  const feather = opts.feather ?? 0.06;

  const model = getModel(modelId);
  if (!(await requestConsent(model))) return;

  const comp = bridge.getComp();
  const gridW = comp.gridCols * MAP_SIZE;
  const gridH = comp.gridRows * MAP_SIZE;

  // Source: full-res bitmap, or the 128-grid render as a reduced-quality fallback.
  let bmp = bridge.getSourceBitmap();
  let createdBmp = false;
  if (!bmp) {
    const img = mapBytesToImageData(comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows);
    bmp = await compToBitmap(img.data, gridW, gridH);
    createdBmp = true;
    showMlToast('No source image retained — running on the 128-grid; quality reduced.', 'warn');
  }
  const sw = bmp.width, sh = bmp.height;

  const task = showMlTask(`Removing background (${model.label})`);
  try {
    task.setStatus('Loading model…');
    const info = await ensureModel(modelId, (l, t) => task.setProgress(l, t));

    task.setStatus('Preparing image…');
    const pre = await preprocess(bmp, {
      targetW: INPUT, targetH: INPUT, mean: MEAN, std: STD, fit: 'pad', layout: 'nchw',
    });

    task.setStatus('Running model…');
    const outputs = await run(modelId, { [info.inputNames[0]]: pre.tensor });
    const matteTensor = outputs[info.outputNames[0]];
    const matte = normalizeMinMax(new Float32Array(matteTensor.data as Float32Array));

    task.setStatus('Compositing…');
    // Source RGBA at native resolution.
    const srcImg = bitmapToImageData(bmp);
    const matteSrc = unpadAndResizeMatte(matte, INPUT, INPUT, pre.padX, pre.padY, pre.drawW, pre.drawH, sw, sh);
    const alpha = matteToAlpha(matteSrc, threshold, feather);
    const cut = applyAlpha(srcImg.data, alpha, sw, sh);

    // Downscale to the grid and requantize through the existing pipeline.
    const gridImg = scaleRgbaToImageData(cut, sw, sh, gridW, gridH);
    const tiles = requantizeGrid(gridImg, comp, null, bridge.getReqParams());

    const transparentPct = Math.round((1 - alpha.reduce((s, a) => s + (a > 127 ? 1 : 0), 0) / alpha.length) * 100);
    bridge.showPreview(withActiveFrameTiles(comp, tiles));
    bridge.setStatus(`Background removed — ${transparentPct}% transparent. Enter to keep, Esc to discard.`);
  } catch (err) {
    showMlToast(`Background removal failed: ${String((err as Error).message ?? err)}`, 'warn');
  } finally {
    task.done();
    // Only close a bitmap we created; never the editor's shared source bitmap.
    if (createdBmp) bmp.close?.();
  }
}
