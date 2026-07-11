/**
 * Phase 7.4 — Smart "reduce" preview ranking (pure).
 *
 * Simulates each color-reduction strategy a few steps and reports the projected
 * distinct-color count and pixels affected, so the user can pick the strategy
 * with data instead of guessing. Reuses the editor's own reduction primitives
 * (findReductionTarget / countDistinct) so the simulation matches reality.
 *
 * Note: true *compressed-byte* savings need zstd (not available purely); fewer
 * distinct colors is the honest proxy we report, which correlates strongly with
 * banner/carpet count.
 */

import { findReductionTarget, countDistinct, type Strategy, type OklabLookup } from '../../quantize.js';

export interface StrategyProjection {
  strategy: Strategy;
  startDistinct: number;
  finalDistinct: number;
  pixelsChanged: number;
  /** Per-step distinct count after each merge. */
  trail: number[];
}

/** Simulate `steps` reductions per strategy on a copy of `mapColors`. */
export function projectReductions(
  mapColors: Uint8Array,
  oklab: OklabLookup,
  strategies: readonly Strategy[],
  steps: number,
): StrategyProjection[] {
  const start = countDistinct(mapColors);
  return strategies.map((strategy) => {
    const work = mapColors.slice();
    const trail: number[] = [];
    let pixelsChanged = 0;
    for (let s = 0; s < steps; s++) {
      const freq = new Int32Array(256);
      for (let i = 0; i < work.length; i++) freq[work[i]]++;
      const [victim, survivor] = findReductionTarget(freq, oklab, strategy);
      if (victim < 0) break;
      for (let i = 0; i < work.length; i++) {
        if (work[i] === victim) { work[i] = survivor; pixelsChanged++; }
      }
      trail.push(countDistinct(work));
    }
    return { strategy, startDistinct: start, finalDistinct: countDistinct(work), pixelsChanged, trail };
  });
}
