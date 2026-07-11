/**
 * Phase 2 — Saliency auto-crop.
 *
 * Reuses the Phase-1 matte model's alpha as a saliency proxy (no extra
 * download), finds the best square crop that maximizes contained subject mass,
 * and returns a cropped ImageBitmap the import pipeline can consume. Suggestion
 * only — the caller decides whether to apply it.
 *
 * For murals (N×M > 1×1) it also reports the subject centroid so the caller can
 * nudge framing away from seam intersections.
 */

import { ensureModel, run, preprocess } from '../mlClient.js';
import { getModel, type ModelId } from '../registry.js';
import {
  normalizeMinMax, unpadAndResizeMatte, bestSquareCrop, saliencyCentroid, type Rect,
} from '../imageOps.js';
import { requestConsent, showMlTask, showMlToast } from '../ui.js';

const INPUT = 1024;
const MEAN: [number, number, number] = [0.5, 0.5, 0.5];
const STD: [number, number, number] = [1, 1, 1];
/** Cap the saliency map size so the integral-image crop search stays cheap. */
const SAL_MAX = 256;

export interface AutoCropResult {
  bitmap: ImageBitmap;
  /** Crop rectangle in source-pixel coordinates. */
  rect: Rect;
  /** Subject centroid in [0,1] of the source (for mural seam-aware framing). */
  centroid: { cx: number; cy: number };
}

export interface AutoCropOptions {
  modelId?: Extract<ModelId, 'rmbg-1.4' | 'ormbg'>;
  /** Padding around the subject as a fraction of the crop side (default 0.05). */
  padFrac?: number;
}

export async function computeAutoCrop(bmp: ImageBitmap, opts: AutoCropOptions = {}): Promise<AutoCropResult | null> {
  const modelId = opts.modelId ?? 'rmbg-1.4';
  const model = getModel(modelId);
  if (!(await requestConsent(model))) return null;

  const sw = bmp.width, sh = bmp.height;
  const task = showMlTask('Finding the subject…');
  try {
    task.setStatus('Loading model…');
    const info = await ensureModel(modelId, (l, t) => task.setProgress(l, t));

    task.setStatus('Running model…');
    const pre = await preprocess(bmp, { targetW: INPUT, targetH: INPUT, mean: MEAN, std: STD, fit: 'pad', layout: 'nchw' });
    const outputs = await run(modelId, { [info.inputNames[0]]: pre.tensor });
    const matte = normalizeMinMax(new Float32Array(outputs[info.outputNames[0]].data as Float32Array));

    // Downsampled saliency map at source aspect.
    const scale = SAL_MAX / Math.max(sw, sh);
    const salW = Math.max(1, Math.round(sw * scale));
    const salH = Math.max(1, Math.round(sh * scale));
    const sal = unpadAndResizeMatte(matte, INPUT, INPUT, pre.padX, pre.padY, pre.drawW, pre.drawH, salW, salH);

    const rectSal = bestSquareCrop(sal, salW, salH, opts.padFrac ?? 0.05);
    const centroid = saliencyCentroid(sal, salW, salH);

    // Map the crop rect back to source coordinates.
    const kx = sw / salW, ky = sh / salH;
    const rect: Rect = {
      x: Math.round(rectSal.x * kx),
      y: Math.round(rectSal.y * ky),
      w: Math.max(1, Math.round(rectSal.w * kx)),
      h: Math.max(1, Math.round(rectSal.h * ky)),
    };

    const cropped = await createImageBitmap(bmp, rect.x, rect.y, rect.w, rect.h);
    return { bitmap: cropped, rect, centroid };
  } catch (err) {
    showMlToast(`Auto-crop failed: ${String((err as Error).message ?? err)}`, 'warn');
    return null;
  } finally {
    task.done();
  }
}
