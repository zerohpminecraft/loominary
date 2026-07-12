/**
 * Fill tool (F) — OKLab tolerance flood fill.
 *
 * Left-click  floods the connected region with the active colour.
 * Right-click picks the colour under the cursor (eyedropper behaviour).
 *
 * Hovering shows a semi-transparent preview of the fill region in the active
 * colour.  Shift+scroll (or = / -) adjusts the OKLab flood tolerance.
 */

import type { Tool, ToolContext } from './Tool.js';
import { readPixel, writePixel, colorOklab, MAP_SIZE } from './Tool.js';
import { oklabDistSq } from '../../oklab.js';

export class FillTool implements Tool {
  readonly id     = 'fill';
  readonly name   = 'Fill';
  readonly cursor = 'crosshair';

  private lastHoverGx  = -1;
  private lastHoverGy  = -1;
  private lastHoverTol = -1;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    // Right-click → pick colour
    if (button === 2) {
      ctx.setColor(readPixel(ctx.comp, gx, gy));
      return;
    }

    if (button !== 0) return;

    const { comp, history, fillTolerance } = ctx;
    const fillColor  = ctx.activeColor;
    const startColor = readPixel(comp, gx, gy);
    if (startColor === fillColor) return;

    const sel       = ctx.getSelMask();
    const gridW     = comp.gridCols * MAP_SIZE;
    const gridH     = comp.gridRows * MAP_SIZE;
    const selActive = sel !== null;

    if (selActive && !sel![gy * gridW + gx]) return;

    // Snapshot BEFORE any writes.
    history.snapshot(comp.frames, comp.rgbFrames);

    const startEntry = colorOklab(ctx, startColor);
    const tolSq      = fillTolerance * fillTolerance;
    const visited    = new Uint8Array(gridW * gridH);
    const queue: number[] = [gy * gridW + gx];
    visited[gy * gridW + gx] = 1;

    let anyWrite = false;

    while (queue.length > 0) {
      const cur = queue.pop()!;
      const cy  = (cur / gridW) | 0;
      const cx  = cur % gridW;
      const curColor = readPixel(comp, cx, cy);

      const e = startEntry ? colorOklab(ctx, curColor) : null;
      if (startEntry && e) {
        if (oklabDistSq(startEntry[0], startEntry[1], startEntry[2], e[0], e[1], e[2]) > tolSq) continue;
      } else if (curColor !== startColor) {
        continue;
      }

      if (writePixel(comp, cx, cy, fillColor, sel)) anyWrite = true;

      for (const [dx, dy] of [[-1,0],[1,0],[0,-1],[0,1]] as [number,number][]) {
        const nx = cx + dx, ny = cy + dy;
        if (nx < 0 || ny < 0 || nx >= gridW || ny >= gridH) continue;
        const ni = ny * gridW + nx;
        if (visited[ni]) continue;
        if (selActive && !sel![ni]) continue;
        visited[ni] = 1;
        queue.push(ni);
      }
    }

    // After fill: clear the hover preview (canvas will show the new pixels).
    ctx.canvas.fillPreview = null;
    if (anyWrite) ctx.canvas.markDirty();
  }

  /** Update the hover preview for the current cursor position. */
  updateHover(gx: number, gy: number, ctx: ToolContext): void {
    if (gx === this.lastHoverGx
        && gy === this.lastHoverGy
        && ctx.fillTolerance === this.lastHoverTol) return;
    this.lastHoverGx = gx; this.lastHoverGy = gy; this.lastHoverTol = ctx.fillTolerance;

    const region = this.floodRegion(gx, gy, ctx);
    ctx.canvas.fillPreview      = region;
    ctx.canvas.fillPreviewColor = ctx.activeColor;
    ctx.canvas.markDirty();
  }

  /** Force the next hover call to recompute even if position hasn't changed. */
  invalidateHoverCache(): void {
    this.lastHoverGx = -1; this.lastHoverGy = -1;
  }

  deactivate(ctx: ToolContext): void {
    ctx.canvas.fillPreview = null;
    ctx.canvas.markDirty();
  }

  /** Flood-fill region (read-only) — returns the region mask, or null if out of bounds. */
  private floodRegion(gx: number, gy: number, ctx: ToolContext): Uint8Array | null {
    const { comp, fillTolerance } = ctx;
    const gridW = comp.gridCols * MAP_SIZE;
    const gridH = comp.gridRows * MAP_SIZE;

    if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return null;

    const startColor = readPixel(comp, gx, gy);
    // If hover color matches fill color, nothing would change — no preview.
    if (startColor === ctx.activeColor) return null;

    const startEntry = colorOklab(ctx, startColor);
    const tolSq      = fillTolerance * fillTolerance;
    const sel        = ctx.getSelMask();
    const selActive  = sel !== null;

    if (selActive && !sel![gy * gridW + gx]) return null;

    const result  = new Uint8Array(gridW * gridH);
    const visited = new Uint8Array(gridW * gridH);
    const queue: number[] = [gy * gridW + gx];
    visited[gy * gridW + gx] = 1;

    while (queue.length > 0) {
      const cur = queue.pop()!;
      const cy = (cur / gridW) | 0;
      const cx = cur % gridW;
      const curColor = readPixel(comp, cx, cy);

      const e = startEntry ? colorOklab(ctx, curColor) : null;
      if (startEntry && e) {
        if (oklabDistSq(startEntry[0], startEntry[1], startEntry[2], e[0], e[1], e[2]) > tolSq) continue;
      } else if (curColor !== startColor) {
        continue;
      }

      result[cur] = 1;

      for (const [dx, dy] of [[-1,0],[1,0],[0,-1],[0,1]] as [number,number][]) {
        const nx = cx + dx, ny = cy + dy;
        if (nx < 0 || ny < 0 || nx >= gridW || ny >= gridH) continue;
        const ni = ny * gridW + nx;
        if (visited[ni]) continue;
        if (selActive && !sel![ni]) continue;
        visited[ni] = 1;
        queue.push(ni);
      }
    }

    return result;
  }
}
