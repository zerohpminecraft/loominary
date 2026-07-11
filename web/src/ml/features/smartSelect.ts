/**
 * Phase 3 — Smart Wand (SAM) orchestration.
 *
 * Runs the SlimSAM vision encoder ONCE per source image (the heavy part, cached)
 * and the prompt encoder + mask decoder per click (tiny). Produces a grid-space
 * selection mask compatible with the editor's wand/lasso selection.
 *
 * IO-name robustness: the exact input/output tensor names of the SlimSAM ONNX
 * export are matched heuristically from the session's reported names (see
 * pickName), so this keeps working if the export uses `pixel_values` vs `input`,
 * `input_points` vs `point_coords`, etc. Assumptions are documented in MODELS.md.
 *
 * Geometry: the encoder preprocesses the source by letterboxing to 1024². Click
 * points (in source pixels) are mapped into that padded frame; the 256² mask
 * logits are mapped back through the same transform to source, then to grid.
 */

import { ensureModel, run, preprocess } from '../mlClient.js';
import { getModel } from '../registry.js';
import { sigmoid } from '../imageOps.js';
import { requestConsent, showMlTask, showMlToast } from '../ui.js';
import type { PlainTensor } from '../types.js';

const INPUT = 1024;
const MASK = 256;
/** SAM ImageNet normalization. */
const MEAN: [number, number, number] = [0.485, 0.456, 0.406];
const STD: [number, number, number] = [0.229, 0.224, 0.225];

export interface SamPoint { x: number; y: number; positive: boolean; }

interface Geom { scale: number; padX: number; padY: number; srcW: number; srcH: number; }

export class SmartSelector {
  private encodedKey: ImageBitmap | null = null;
  /** ALL vision-encoder outputs (image_embeddings AND image_positional_embeddings). */
  private encoderOut: Record<string, PlainTensor> | null = null;
  private geom: Geom | null = null;
  private decoderReady = false;

  /** True once an image has been encoded and clicks can be decoded. */
  get ready(): boolean { return this.encoderOut != null; }

  /** Encode the source image (idempotent per bitmap). Returns false if declined/failed. */
  async ensureEncoded(bmp: ImageBitmap): Promise<boolean> {
    if (this.encodedKey === bmp && this.encoderOut) return true;

    const enc = getModel('sam-encoder');
    if (!(await requestConsent(enc))) return false;

    const task = showMlTask('Analyzing image (SAM encoder)…');
    try {
      task.setStatus('Loading encoder…');
      const info = await ensureModel('sam-encoder', (l, t) => task.setProgress(l, t));
      task.setStatus('Encoding…');
      const pre = await preprocess(bmp, { targetW: INPUT, targetH: INPUT, mean: MEAN, std: STD, fit: 'pad', layout: 'nchw' });
      // Keep ALL encoder outputs — the decoder needs both image_embeddings and
      // image_positional_embeddings, which the vision encoder emits together.
      this.encoderOut = await run('sam-encoder', { [info.inputNames[0]]: pre.tensor });
      this.geom = { scale: pre.scale, padX: pre.padX, padY: pre.padY, srcW: bmp.width, srcH: bmp.height };
      this.encodedKey = bmp;

      // Warm the decoder so the first click is fast.
      task.setStatus('Loading decoder…');
      await ensureModel('sam-decoder');
      this.decoderReady = true;
      return true;
    } catch (err) {
      showMlToast(`SAM encode failed: ${String((err as Error).message ?? err)}`, 'warn');
      return false;
    } finally {
      task.done();
    }
  }

  /** Decode a set of source-pixel prompts into a 256² mask of sigmoid scores. */
  async segment(points: SamPoint[]): Promise<Float32Array | null> {
    if (!this.encoderOut || !this.geom || points.length === 0) return null;
    if (!this.decoderReady) await ensureModel('sam-decoder');

    const info = await ensureModel('sam-decoder');
    const n = points.length;
    // Map source points into the padded 1024 frame.
    const coords = new Float32Array(n * 2);
    const labels = new BigInt64Array(n);
    points.forEach((p, i) => {
      coords[i * 2] = this.geom!.padX + p.x * this.geom!.scale;
      coords[i * 2 + 1] = this.geom!.padY + p.y * this.geom!.scale;
      labels[i] = BigInt(p.positive ? 1 : 0);
    });
    const pointTensor: PlainTensor = { type: 'float32', data: coords, dims: [1, 1, n, 2] };
    const labelTensor: PlainTensor = { type: 'int64', data: labels, dims: [1, 1, n] };

    // Wire every decoder input by name: labels, points, or a matching encoder
    // output (image_embeddings / image_positional_embeddings flow through here).
    const encKeys = Object.keys(this.encoderOut);
    const feeds: Record<string, PlainTensor> = {};
    for (const name of info.inputNames) {
      if (/label/i.test(name)) feeds[name] = labelTensor;
      else if (/point|coord/i.test(name)) feeds[name] = pointTensor;
      else {
        const key = encKeys.find((k) => k === name)
          ?? encKeys.find((k) => k.includes(name) || name.includes(k));
        if (key) feeds[name] = this.encoderOut[key];
      }
    }

    let out: Record<string, PlainTensor>;
    try {
      out = await run('sam-decoder', feeds);
    } catch (err) {
      showMlToast(`SAM decode failed: ${String((err as Error).message ?? err)}`, 'warn');
      return null;
    }

    const maskName = pickName(Object.keys(out), ['pred_mask', 'mask', 'output']);
    const iouName = pickName(Object.keys(out), ['iou', 'score']);
    const masks = out[maskName];
    const best = bestMaskIndex(out[iouName]);
    return extractMask(masks, best);
  }

  reset(): void {
    this.encodedKey = null;
    this.encoderOut = null;
    this.geom = null;
  }

  /**
   * High-level: given click points in GRID coordinates, segment and return a
   * grid-space selection mask. Converts grid→source via the cached geometry.
   */
  async selectFromGridClicks(
    points: { gx: number; gy: number; positive: boolean }[],
    gridCols: number, gridRows: number, mapSize: number,
  ): Promise<Uint8Array | null> {
    if (!this.geom) return null;
    const gridW = gridCols * mapSize, gridH = gridRows * mapSize;
    const src: SamPoint[] = points.map((p) => ({
      x: (p.gx / gridW) * this.geom!.srcW,
      y: (p.gy / gridH) * this.geom!.srcH,
      positive: p.positive,
    }));
    const mask = await this.segment(src);
    if (!mask) return null;
    return this.toGridMask(mask, gridCols, gridRows, mapSize);
  }

  /**
   * Convert a 256² SAM mask (sigmoid scores) into a grid-space selection mask.
   * Each grid pixel maps → source → padded 1024 frame → 256 mask, threshold 0.5.
   */
  toGridMask(mask256: Float32Array, gridCols: number, gridRows: number, mapSize: number): Uint8Array {
    const g = this.geom!;
    const gridW = gridCols * mapSize, gridH = gridRows * mapSize;
    const out = new Uint8Array(gridW * gridH);
    for (let gy = 0; gy < gridH; gy++) {
      // grid → source (proportional; assumes uniform scale/crop import).
      const sy = (gy / gridH) * g.srcH;
      const my = (g.padY + sy * g.scale) * (MASK / INPUT);
      const myi = Math.floor(my);
      if (myi < 0 || myi >= MASK) continue;
      for (let gx = 0; gx < gridW; gx++) {
        const sx = (gx / gridW) * g.srcW;
        const mx = (g.padX + sx * g.scale) * (MASK / INPUT);
        const mxi = Math.floor(mx);
        if (mxi < 0 || mxi >= MASK) continue;
        if (mask256[myi * MASK + mxi] >= 0.5) out[gy * gridW + gx] = 1;
      }
    }
    return out;
  }
}

/** Pick the output channel with the highest IoU score (defaults to 0). */
function bestMaskIndex(iou: PlainTensor | undefined): number {
  if (!iou) return 0;
  const d = iou.data as Float32Array;
  let best = 0, bestV = -Infinity;
  for (let i = 0; i < d.length; i++) if (d[i] > bestV) { bestV = d[i]; best = i; }
  return best;
}

/** Extract one 256² mask plane (index `idx`) from pred_masks, as sigmoid scores. */
function extractMask(masks: PlainTensor, idx: number): Float32Array {
  const d = masks.data as Float32Array;
  const plane = MASK * MASK;
  // pred_masks is [...,, numMasks, 256, 256]; idx selects along the numMasks axis.
  const base = Math.min(idx, Math.max(0, Math.floor(d.length / plane) - 1)) * plane;
  const out = new Float32Array(plane);
  for (let i = 0; i < plane; i++) out[i] = sigmoid(d[base + i]);
  return out;
}

/** Heuristic: first name containing any of `needles` (case-insensitive), else first name. */
function pickName(names: string[], needles: string[]): string {
  for (const needle of needles) {
    const hit = names.find((n) => n.toLowerCase().includes(needle));
    if (hit) return hit;
  }
  return names[0];
}
