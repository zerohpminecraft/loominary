/**
 * Phase 7.5 — GIF frame importance scoring (pure).
 *
 * Ranks animation frames by perceptual (Oklab) difference from their neighbors.
 * Frames most similar to their predecessor carry the least new information and
 * are the best candidates to drop when a GIF is over budget — smarter than a
 * uniform stride/skip.
 */

import { mapByteToRgb } from '../../palette.js';
import { rgbToOklab, oklabDistSq } from '../../oklab.js';

export interface FrameScore {
  index: number;
  /** Mean Oklab distance² to the previous frame (0 for frame 0). */
  diffPrev: number;
  /** Lower = more droppable (more redundant). */
  redundancy: number;
}

/**
 * Score each frame of a single tile (each `frames[i]` is one 16384-byte frame).
 * Returns scores in frame order; sort by `redundancy` ascending to pick drops.
 */
export function scoreFrames(frames: Uint8Array[]): FrameScore[] {
  const n = frames.length;
  if (n === 0) return [];
  // Cache Oklab per map byte (0–255) to avoid repeated conversions.
  const lab = buildLabCache();

  const diffs = new Float64Array(n);
  for (let f = 1; f < n; f++) {
    diffs[f] = meanFrameDist(frames[f - 1], frames[f], lab);
  }
  // Redundancy: how little a frame differs from BOTH neighbors (forward+back).
  return frames.map((_, f) => {
    const back = diffs[f];
    const fwd = f + 1 < n ? diffs[f + 1] : back;
    const redundancy = f === 0 ? Infinity : (back + fwd) / 2;
    return { index: f, diffPrev: back, redundancy };
  });
}

/** Suggest which frame indices to drop to reach `targetCount` frames. */
export function suggestDrops(frames: Uint8Array[], targetCount: number): number[] {
  const scores = scoreFrames(frames);
  // Never drop frame 0; drop lowest-redundancy first.
  const droppable = scores.filter((s) => s.index !== 0).sort((a, b) => a.redundancy - b.redundancy);
  const dropCount = Math.max(0, frames.length - targetCount);
  return droppable.slice(0, dropCount).map((s) => s.index).sort((a, b) => a - b);
}

function meanFrameDist(a: Uint8Array, b: Uint8Array, lab: Float32Array): number {
  let sum = 0, n = 0;
  for (let i = 0; i < a.length; i++) {
    const ca = a[i], cb = b[i];
    if (ca === 0 && cb === 0) continue;
    const ai = ca * 3, bi = cb * 3;
    sum += oklabDistSq(lab[ai], lab[ai + 1], lab[ai + 2], lab[bi], lab[bi + 1], lab[bi + 2]);
    n++;
  }
  return n > 0 ? sum / n : 0;
}

function buildLabCache(): Float32Array {
  const lab = new Float32Array(256 * 3);
  for (let c = 1; c < 256; c++) {
    const [r, g, b] = mapByteToRgb(c);
    const [L, A, B] = rgbToOklab(r, g, b);
    lab[c * 3] = L; lab[c * 3 + 1] = A; lab[c * 3 + 2] = B;
  }
  return lab;
}
