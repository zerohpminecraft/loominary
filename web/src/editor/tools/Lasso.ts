/**
 * Lasso tool (L) — freehand and polygon selection.
 *
 * Left-drag       freehand add; committed on mouse up.
 * Right-drag      freehand subtract; committed on mouse up.
 * Left-click      enter polygon vertex mode; double-click closes.
 * Right-click     cancel the active path (no drag detected).
 * Ctrl+left-drag  same as right-drag (subtract).
 */

import type { Tool, ToolContext } from './Tool.js';
import { MAP_SIZE } from './Tool.js';

type LassoMode = 'idle' | 'freehand' | 'polygon';

export class LassoTool implements Tool {
  readonly id     = 'lasso';
  readonly name   = 'Lasso';
  readonly cursor = 'crosshair';

  private mode:          LassoMode = 'idle';
  private points:        Array<[number, number]> = [];
  private subtract       = false;
  private hasDragged     = false;
  private lastClickTime  = 0;
  private rightButton    = false;  // freehand started with right-click

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    if (button === 2) {
      if (this.mode === 'idle') {
        // Right-click from idle: start a subtract freehand drag.
        this.subtract      = true;
        this.rightButton   = true;
        this.points        = [[gx, gy]];
        this.hasDragged    = false;
        this.mode          = 'freehand';
        this.lastClickTime = Date.now();
      } else {
        // Right-click while a path is active → cancel it.
        this.reset(ctx);
      }
      return;
    }

    if (button === 0) {
      if (this.mode === 'idle') {
        this.subtract      = ctx.ctrlHeld;
        this.rightButton   = false;
        this.points        = [[gx, gy]];
        this.hasDragged    = false;
        this.mode          = 'freehand';
        this.lastClickTime = Date.now();
        return;
      }

      if (this.mode === 'polygon') {
        const now = Date.now();
        const dbl = now - this.lastClickTime < 350 && this.points.length >= 2;
        this.lastClickTime = now;

        if (dbl) {
          this.commit(ctx);
          this.reset(ctx);
        } else {
          this.points.push([gx, gy]);
          this.updatePreview(ctx);
        }
        return;
      }

      // mode === 'freehand': a second pointerdown can't normally occur mid-drag,
      // but handle it defensively.
    }

    if (button === -1 && this.mode === 'freehand') {
      const last = this.points[this.points.length - 1];
      if (!last || gx !== last[0] || gy !== last[1]) {
        this.hasDragged = true;
        this.points.push([gx, gy]);
        this.updatePreview(ctx);
      }
    }
  }

  onPointerUp(ctx: ToolContext): void {
    if (this.mode !== 'freehand') return;

    if (this.hasDragged && this.points.length >= 3) {
      // Freehand drag complete — commit the region.
      this.commit(ctx);
      this.reset(ctx);
    } else if (this.rightButton) {
      // Right-click with no meaningful drag → cancel (don't enter polygon mode).
      this.reset(ctx);
    } else {
      // Left-click with no drag → enter polygon vertex mode.
      this.mode = 'polygon';
      ctx.canvas.wandPreview        = null;
      ctx.canvas.wandPreviewSubtract = false;
      ctx.canvas.markDirty();
    }
  }

  /** Rasterize the polygon into the selection mask. */
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

  private reset(ctx: ToolContext): void {
    this.mode        = 'idle';
    this.points      = [];
    this.subtract    = false;
    this.hasDragged  = false;
    this.rightButton = false;
    ctx.canvas.wandPreview        = null;
    ctx.canvas.wandPreviewSubtract = false;
    ctx.canvas.markDirty();
  }

  /** True while a path is in progress (used by Editor Escape handler). */
  hasPath(): boolean { return this.mode !== 'idle'; }

  deactivate(ctx: ToolContext): void {
    this.reset(ctx);
  }
}
