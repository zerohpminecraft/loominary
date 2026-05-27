/**
 * Lasso tool (L) — freehand polygon selection.
 *
 * Left-click adds vertices to a polygon.  Double-click closes the polygon and
 * rasterizes it to the selection mask via browser-native Path2D.isPointInPath().
 * Right-click cancels the current path.
 *
 * Hold Ctrl while clicking to start a subtract-lasso (orange preview):
 * closing the polygon removes the area from the current selection.
 */

import type { Tool, ToolContext } from './Tool.js';
import { MAP_SIZE } from './Tool.js';

export class LassoTool implements Tool {
  readonly id     = 'lasso';
  readonly name   = 'Lasso';
  readonly cursor = 'crosshair';

  private points: Array<[number, number]> = [];
  private lastClickTime = 0;
  private subtract      = false; // set on first vertex, locked for the rest of the path

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    const sub = ctx.ctrlHeld;
    if (button === 2) {
      // Right-click → cancel
      this.points   = [];
      this.subtract = false;
      ctx.canvas.wandPreview        = null;
      ctx.canvas.wandPreviewSubtract = false;
      ctx.canvas.markDirty();
      return;
    }

    if (button !== 0) return;

    const now = Date.now();
    const dblClick = now - this.lastClickTime < 350 && this.points.length >= 2;
    this.lastClickTime = now;

    if (dblClick) {
      // Close polygon and rasterize
      this.commit(ctx);
      this.points   = [];
      this.subtract = false;
      ctx.canvas.wandPreview        = null;
      ctx.canvas.wandPreviewSubtract = false;
      ctx.canvas.markDirty();
      return;
    }

    // First vertex: lock in the mode (add or subtract).
    if (this.points.length === 0) this.subtract = sub;

    this.points.push([gx, gy]);
    this.updatePreview(ctx);
  }

  /** Rasterize the Path2D polygon into the selection mask. */
  private commit(ctx: ToolContext): void {
    if (this.points.length < 3) return;

    const gridW = ctx.comp.gridCols * MAP_SIZE;
    const gridH = ctx.comp.gridRows * MAP_SIZE;

    const path = new Path2D();
    path.moveTo(this.points[0][0] + 0.5, this.points[0][1] + 0.5);
    for (let i = 1; i < this.points.length; i++) {
      path.lineTo(this.points[i][0] + 0.5, this.points[i][1] + 0.5);
    }
    path.closePath();

    const osc  = new OffscreenCanvas(gridW, gridH);
    const octx = osc.getContext('2d')!;

    const existing = ctx.getSelMask();
    const newMask  = existing ? existing.slice() : new Uint8Array(gridW * gridH);

    const xs = this.points.map(p => p[0]);
    const ys = this.points.map(p => p[1]);
    const x0 = Math.max(0, Math.floor(Math.min(...xs)));
    const y0 = Math.max(0, Math.floor(Math.min(...ys)));
    const x1 = Math.min(gridW - 1, Math.ceil(Math.max(...xs)));
    const y1 = Math.min(gridH - 1, Math.ceil(Math.max(...ys)));

    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        if (octx.isPointInPath(path, x + 0.5, y + 0.5)) {
          newMask[y * gridW + x] = this.subtract ? 0 : 1;
        }
      }
    }

    ctx.setSelMask(newMask);
    ctx.canvas.selMask = newMask;
  }

  private updatePreview(ctx: ToolContext): void {
    if (this.points.length < 2) {
      ctx.canvas.wandPreview        = null;
      ctx.canvas.wandPreviewSubtract = false;
      return;
    }

    const gridW = ctx.comp.gridCols * MAP_SIZE;
    const gridH = ctx.comp.gridRows * MAP_SIZE;

    const path = new Path2D();
    path.moveTo(this.points[0][0] + 0.5, this.points[0][1] + 0.5);
    for (let i = 1; i < this.points.length; i++) {
      path.lineTo(this.points[i][0] + 0.5, this.points[i][1] + 0.5);
    }
    path.closePath();

    const osc  = new OffscreenCanvas(gridW, gridH);
    const octx = osc.getContext('2d')!;

    const xs = this.points.map(p => p[0]);
    const ys = this.points.map(p => p[1]);
    const x0 = Math.max(0, Math.floor(Math.min(...xs)));
    const y0 = Math.max(0, Math.floor(Math.min(...ys)));
    const x1 = Math.min(gridW - 1, Math.ceil(Math.max(...xs)));
    const y1 = Math.min(gridH - 1, Math.ceil(Math.max(...ys)));

    const preview  = new Uint8Array(gridW * gridH);
    const existing = this.subtract ? ctx.getSelMask() : null;

    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        if (octx.isPointInPath(path, x + 0.5, y + 0.5)) {
          if (this.subtract) {
            // Only highlight pixels that are actually selected (and would be removed)
            if (existing?.[y * gridW + x]) preview[y * gridW + x] = 1;
          } else {
            preview[y * gridW + x] = 1;
          }
        }
      }
    }

    ctx.canvas.wandPreview        = preview;
    ctx.canvas.wandPreviewSubtract = this.subtract;
    ctx.canvas.markDirty();
  }

  /** True while vertices have been added but the path is not yet closed. */
  hasPath(): boolean { return this.points.length > 0; }

  deactivate(ctx: ToolContext): void {
    this.points   = [];
    this.subtract = false;
    ctx.canvas.wandPreview        = null;
    ctx.canvas.wandPreviewSubtract = false;
    ctx.canvas.markDirty();
  }
}
