/**
 * Phase 7.5 — GIF frame importance scoring (feature).
 *
 * Ranks animation frames by how redundant they are (low perceptual difference
 * from neighbors, aggregated across all tiles) and suggests which to drop when
 * over budget. Informational — does not modify the composition.
 */

import { scoreFrames } from '../helpers/gifFrameScore.js';
import { showMlReport, showMlToast } from '../ui.js';
import type { EditorBridge } from '../bridge.js';

export async function runGifFrameScore(bridge: EditorBridge): Promise<void> {
  const comp = bridge.getComp();
  const frameCount = Math.max(...comp.frames.map((tf) => tf.length), 1);
  if (frameCount <= 1) { showMlToast('Only one frame — nothing to rank.', 'info'); return; }

  // Aggregate per-frame redundancy across every tile.
  const redundancy = new Float64Array(frameCount);
  const diffPrev = new Float64Array(frameCount);
  let tilesCounted = 0;
  for (const tf of comp.frames) {
    if (tf.length < frameCount) continue;
    const scores = scoreFrames(tf);
    for (const s of scores) {
      redundancy[s.index] += s.redundancy === Infinity ? 0 : s.redundancy;
      diffPrev[s.index] += s.diffPrev;
    }
    tilesCounted++;
  }
  const denom = Math.max(1, tilesCounted);

  const order = Array.from({ length: frameCount }, (_, f) => f)
    .filter((f) => f !== 0)
    .sort((a, b) => redundancy[a] - redundancy[b]);

  const rows: string[] = [
    `${frameCount} frames. Most-droppable first (lowest new information):`,
    '',
    'frame   redundancy   Δfrom-prev',
    '-----   ----------   ----------',
  ];
  for (const f of order) {
    rows.push(
      `${String(f + 1).padStart(5)}   ${(redundancy[f] / denom).toExponential(2).padStart(10)}   ` +
      `${(diffPrev[f] / denom).toExponential(2).padStart(10)}`,
    );
  }
  rows.push('', 'Drop the top entries first — they differ least from their neighbors.',
    'Use the frame controls to remove them (smarter than a uniform stride).');
  showMlReport('GIF frame importance', rows);
}
