/**
 * Phase 7.4 — Smart "reduce" preview ranking (feature).
 *
 * Analyzes the bottleneck tile (most distinct colors in the active frame),
 * simulates each reduction strategy, and shows projected color counts so the
 * user can choose with data. Informational — does not modify the composition.
 */

import { buildOklabLookup, countDistinct, Strategy, MAP_SIZE } from '../../quantize.js';
import { projectReductions } from '../helpers/reduceRanking.js';
import { showMlReport } from '../ui.js';
import type { EditorBridge } from '../bridge.js';

const STEPS = 8;

export async function runReduceRanking(bridge: EditorBridge): Promise<void> {
  const comp = bridge.getComp();
  const oklab = buildOklabLookup();

  // Pick the tile whose active frame has the most distinct colors.
  let worst = comp.frames[0]?.[comp.activeFrame] ?? new Uint8Array(MAP_SIZE * MAP_SIZE);
  let worstDistinct = -1, worstTi = 0;
  comp.frames.forEach((tf, ti) => {
    const f = tf[comp.activeFrame];
    if (!f) return;
    const d = countDistinct(f);
    if (d > worstDistinct) { worstDistinct = d; worst = f; worstTi = ti; }
  });

  const proj = projectReductions(worst, oklab, [Strategy.RAREST, Strategy.CLOSEST, Strategy.WEIGHTED], STEPS);
  const rows: string[] = [
    `Bottleneck tile #${worstTi}: ${worstDistinct} distinct colors`,
    `Projected after ${STEPS} reduction steps:`,
    '',
    'strategy    colors  Δcolors  pixels moved',
    '--------    ------  -------  ------------',
  ];
  for (const p of proj) {
    rows.push(
      `${p.strategy.toLowerCase().padEnd(10)}  ${String(p.finalDistinct).padStart(6)}  ` +
      `${String(p.startDistinct - p.finalDistinct).padStart(7)}  ${String(p.pixelsChanged).padStart(12)}`,
    );
  }
  rows.push('', 'Fewer distinct colors → smaller compressed payload (banner/carpet count).',
    'Pick a strategy in the Reduce (K) panel and press K to apply.');
  showMlReport('Reduce strategy ranking', rows);
}
