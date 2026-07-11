/**
 * Phase 5 — Denoise.
 *
 * Default path is the classical NL-means filter (no download). It renders each
 * tile frame to RGBA, denoises, then re-quantizes back to that tile's *existing*
 * palette so the banner/carpet color count can never increase — matching the
 * convention of the P-key filter cycle. Respects the selection mask and offers
 * current-frame or all-frames scope. Shown as a previewComp (undoable via Enter).
 *
 * A neural DnCNN-class path is left as a documented follow-up (see MODELS.md);
 * the classical filter measured well enough on real stolen-map data to ship as
 * the default.
 */

import {
  buildOklabLookup, buildPaletteFromTile, MAP_SIZE, type OklabLookup,
} from '../../quantize.js';
import { MC_PALETTE } from '../../palette.js';
import { rgbToOklab, oklabDistSq } from '../../oklab.js';
import { nlMeans, DEFAULT_NLMEANS, type NlMeansParams } from '../helpers/nlmeans.js';
import { showMlToast } from '../ui.js';
import type { EditorBridge } from '../bridge.js';
import type { CompositionState } from '../../payload-state.js';

const MAP_BYTES = MAP_SIZE * MAP_SIZE;

export interface DenoiseOptions {
  scope?: 'frame' | 'all';
  params?: NlMeansParams;
}

export async function runDenoise(bridge: EditorBridge, opts: DenoiseOptions = {}): Promise<void> {
  const scope = opts.scope ?? 'frame';
  const params = opts.params ?? DEFAULT_NLMEANS;
  const comp = bridge.getComp();
  const oklab = buildOklabLookup();
  const selMask = bridge.getSelMask();
  const gridW = comp.gridCols * MAP_SIZE;

  let changed = 0;
  const frames = comp.frames.map((tileFrames, ti) => {
    const tileCol = ti % comp.gridCols;
    const tileRow = Math.floor(ti / comp.gridCols);
    return tileFrames.map((frame, fi) => {
      if (scope === 'frame' && fi !== comp.activeFrame) return frame;
      const localMask = sliceTileMask(selMask, gridW, tileCol, tileRow);
      const next = denoiseTile(frame, oklab, params, localMask);
      changed += diffCount(frame, next);
      return next;
    });
  });

  if (changed === 0) {
    showMlToast('Denoise made no change (already clean or empty selection).', 'info');
    return;
  }
  const preview: CompositionState = { ...comp, frames };
  bridge.showPreview(preview);
  bridge.setStatus(`Denoised ${scope === 'all' ? 'all frames' : 'frame'} (${changed} px) — Enter to keep, Esc to discard.`);
}

/** Denoise one 128² map-byte tile, re-quantizing to its own palette. */
function denoiseTile(
  frame: Uint8Array, oklab: OklabLookup, params: NlMeansParams, mask: Uint8Array | null,
): Uint8Array {
  const palette = buildPaletteFromTile(frame);
  palette[0] = 0;

  // Render to RGBA.
  const rgba = new Uint8ClampedArray(MAP_BYTES * 4);
  for (let i = 0; i < MAP_BYTES; i++) {
    const cb = frame[i];
    const di = i * 4;
    if (cb === 0) { rgba[di + 3] = 0; continue; }
    const p = MC_PALETTE[cb];
    rgba[di] = (p >> 16) & 0xff;
    rgba[di + 1] = (p >> 8) & 0xff;
    rgba[di + 2] = p & 0xff;
    rgba[di + 3] = 255;
  }

  const filtered = nlMeans(rgba, MAP_SIZE, MAP_SIZE, params);

  // Re-quantize filtered pixels to the tile's existing palette.
  const out = frame.slice();
  for (let i = 0; i < MAP_BYTES; i++) {
    if (frame[i] === 0) continue;
    if (mask && !mask[i]) continue;
    const di = i * 4;
    const [L, a, b] = rgbToOklab(filtered[di], filtered[di + 1], filtered[di + 2]);
    let bestDist = Infinity, best = frame[i];
    for (let c = 1; c < 256; c++) {
      if (!palette[c]) continue;
      const e = oklab[c];
      if (!e) continue;
      const d = oklabDistSq(L, a, b, e[0], e[1], e[2]);
      if (d < bestDist) { bestDist = d; best = c; }
    }
    out[i] = best;
  }
  return out;
}

/** Extract a 128² local mask for one tile from the full-grid selection mask. */
function sliceTileMask(
  selMask: Uint8Array | null, gridW: number, tileCol: number, tileRow: number,
): Uint8Array | null {
  if (!selMask) return null;
  const local = new Uint8Array(MAP_BYTES);
  let any = false;
  for (let ly = 0; ly < MAP_SIZE; ly++) {
    const gy = tileRow * MAP_SIZE + ly;
    for (let lx = 0; lx < MAP_SIZE; lx++) {
      const gx = tileCol * MAP_SIZE + lx;
      const v = selMask[gy * gridW + gx];
      local[ly * MAP_SIZE + lx] = v;
      if (v) any = true;
    }
  }
  return any ? local : new Uint8Array(MAP_BYTES); // empty mask = denoise nothing in this tile
}

function diffCount(a: Uint8Array, b: Uint8Array): number {
  let n = 0;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) n++;
  return n;
}
