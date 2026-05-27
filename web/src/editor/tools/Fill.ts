/**
 * Fill tool (F) — OKLab tolerance flood fill.
 *
 * Left-click fills the connected region (within tolerance) with the active colour.
 * Right-click flood-fills with colour 0 (transparent/erase).
 *
 * Flood fill is performed in global pixel coordinates so it crosses tile seams.
 * If a selection is active, only selected pixels are filled.
 */

import type { Tool, ToolContext } from './Tool.js';
import { readPixel, writePixel, MAP_SIZE } from './Tool.js';
import { oklabDistSq } from '../../oklab.js';

export class FillTool implements Tool {
  readonly id     = 'fill';
  readonly name   = 'Fill';
  readonly cursor = 'crosshair';

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    if (button !== 0 && button !== 2) return;

    const { comp, history, oklabLookup, fillTolerance } = ctx;
    const fillColor  = button === 0 ? ctx.activeColor : 0;
    const startColor = readPixel(comp, gx, gy);
    if (startColor === fillColor) return;

    const sel      = ctx.getSelMask();
    const gridW    = comp.gridCols * MAP_SIZE;
    const gridH    = comp.gridRows * MAP_SIZE;
    const selActive = sel !== null;

    if (selActive && !sel![gy * gridW + gx]) return;

    // Snapshot BEFORE any writes.
    history.snapshot(comp.frames);

    const startEntry = oklabLookup[startColor];
    const tolSq      = fillTolerance * fillTolerance;

    const visited = new Uint8Array(gridW * gridH);
    const queue: number[] = [gy * gridW + gx];
    visited[gy * gridW + gx] = 1;

    let anyWrite = false;

    while (queue.length > 0) {
      const cur = queue.pop()!;
      const cy = (cur / gridW) | 0;
      const cx = cur % gridW;
      const curColor = readPixel(comp, cx, cy);

      // Tolerance gate — OKLab distance from the start colour.
      if (startEntry && oklabLookup[curColor]) {
        const e = oklabLookup[curColor]!;
        if (oklabDistSq(startEntry[0], startEntry[1], startEntry[2], e[0], e[1], e[2]) > tolSq) {
          continue;
        }
      } else if (curColor !== startColor) {
        continue;
      }

      if (writePixel(comp, cx, cy, fillColor, sel)) anyWrite = true;

      const DIRS: [number, number][] = [[-1,0],[1,0],[0,-1],[0,1]];
      for (const [dx, dy] of DIRS) {
        const nx = cx + dx, ny = cy + dy;
        if (nx < 0 || ny < 0 || nx >= gridW || ny >= gridH) continue;
        const ni = ny * gridW + nx;
        if (visited[ni]) continue;
        if (selActive && !sel![ni]) continue;
        visited[ni] = 1;
        queue.push(ni);
      }
    }

    if (anyWrite) ctx.canvas.markDirty();
  }
}
