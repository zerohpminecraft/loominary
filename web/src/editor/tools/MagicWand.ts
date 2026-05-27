/**
 * Magic Wand tool (W) — OKLab tolerance flood-select.
 *
 * Hover shows a preview of what would be selected (blue) or deselected (orange).
 * Left-click  — add flood to selection; Ctrl+left-click → subtract.
 * Right-click — subtract flood from selection.
 *
 * Shift+scroll (or = / -) adjusts tolerance; the hover preview updates live.
 */

import type { Tool, ToolContext } from './Tool.js';
import { readPixel, MAP_SIZE } from './Tool.js';
import { oklabDistSq } from '../../oklab.js';

export class MagicWandTool implements Tool {
  readonly id     = 'wand';
  readonly name   = 'Magic Wand';
  readonly cursor = 'crosshair';

  private lastHoverGx       = -1;
  private lastHoverGy       = -1;
  private lastHoverSubtract = false;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    const doSubtract = button === 2 || (button === 0 && ctx.ctrlHeld);
    if (button === 0 || button === 2) {
      const region = this.flood(gx, gy, ctx);
      if (!region) return;

      const gridW    = ctx.comp.gridCols * MAP_SIZE;
      const gridH    = ctx.comp.gridRows * MAP_SIZE;
      const existing = ctx.getSelMask();
      const newMask  = existing ? existing.slice() : new Uint8Array(gridW * gridH);

      for (let i = 0; i < region.length; i++) {
        newMask[i] = doSubtract ? (newMask[i] & ~region[i]) : (newMask[i] | region[i]);
      }

      ctx.setSelMask(newMask);
      ctx.canvas.selMask            = newMask;
      ctx.canvas.wandPreview        = null;
      ctx.canvas.wandPreviewSubtract = false;
      ctx.canvas.markDirty();
    }
  }

  activate(ctx: ToolContext): void {
    ctx.canvas.wandPreview        = null;
    ctx.canvas.wandPreviewSubtract = false;
    this.lastHoverGx = -1; this.lastHoverGy = -1; this.lastHoverSubtract = false;
  }

  deactivate(ctx: ToolContext): void {
    ctx.canvas.wandPreview        = null;
    ctx.canvas.wandPreviewSubtract = false;
    ctx.canvas.markDirty();
  }

  /**
   * Called from Editor on pointer-hover (no buttons held) or after tolerance
   * changes.  `subtract` true → show what would be REMOVED (orange preview).
   */
  updateHover(gx: number, gy: number, ctx: ToolContext, subtract = false): void {
    if (gx === this.lastHoverGx && gy === this.lastHoverGy && subtract === this.lastHoverSubtract) return;
    this.lastHoverGx = gx; this.lastHoverGy = gy; this.lastHoverSubtract = subtract;

    const flood = this.flood(gx, gy, ctx);
    if (!flood) {
      ctx.canvas.wandPreview        = null;
      ctx.canvas.wandPreviewSubtract = false;
      ctx.canvas.markDirty();
      return;
    }

    if (subtract) {
      // Orange: show only the pixels that would actually be removed
      const existing = ctx.getSelMask();
      if (existing) {
        const intersect = new Uint8Array(flood.length);
        for (let i = 0; i < flood.length; i++) intersect[i] = flood[i] & existing[i];
        ctx.canvas.wandPreview = intersect;
      } else {
        ctx.canvas.wandPreview = new Uint8Array(flood.length);
      }
      ctx.canvas.wandPreviewSubtract = true;
    } else {
      ctx.canvas.wandPreview        = flood;
      ctx.canvas.wandPreviewSubtract = false;
    }
    ctx.canvas.markDirty();
  }

  /** Force the next hover call to recompute even if position hasn't changed. */
  invalidateHoverCache(): void {
    this.lastHoverGx = -1; this.lastHoverGy = -1;
  }

  private flood(gx: number, gy: number, ctx: ToolContext): Uint8Array | null {
    const { comp, oklabLookup, wandTolerance } = ctx;
    const gridW = comp.gridCols * MAP_SIZE;
    const gridH = comp.gridRows * MAP_SIZE;

    if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return null;

    const startColor = readPixel(comp, gx, gy);
    const startEntry = oklabLookup[startColor];
    const tolSq      = wandTolerance * wandTolerance;

    const result  = new Uint8Array(gridW * gridH);
    const visited = new Uint8Array(gridW * gridH);
    const queue: number[] = [gy * gridW + gx];
    visited[gy * gridW + gx] = 1;

    while (queue.length > 0) {
      const cur = queue.pop()!;
      const cy = (cur / gridW) | 0;
      const cx = cur % gridW;
      const curColor = readPixel(comp, cx, cy);

      if (startEntry && oklabLookup[curColor]) {
        const e = oklabLookup[curColor]!;
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
        visited[ni] = 1;
        queue.push(ni);
      }
    }

    return result;
  }
}
