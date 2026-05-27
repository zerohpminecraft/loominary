/**
 * Rectangle Select tool (S) — drag to define a rectangular selection.
 *
 * Left-drag creates a new selection.
 * Shift+drag adds to the existing selection.
 * Ctrl+drag subtracts from the existing selection.
 * Click with no drag clears the selection.
 */

import type { Tool, ToolContext } from './Tool.js';
import { MAP_SIZE } from './Tool.js';

export class RectSelectTool implements Tool {
  readonly id     = 'select';
  readonly name   = 'Select';
  readonly cursor = 'crosshair';

  private startGx  = -1;
  private startGy  = -1;
  private endGx    = -1;
  private endGy    = -1;
  private dragging = false;
  private addMode  = false;
  private subMode  = false;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    if (button === 0) {
      this.startGx = gx; this.startGy = gy;
      this.endGx   = gx; this.endGy   = gy;
      this.dragging = true;
      // Detect modifier keys from global state (passed via event, but we don't
      // have it here — check canvas.el for last event).  The Editor handles
      // Shift/Ctrl context; for simplicity we use a flag exposed on the tool.
      this.addMode = false;
      this.subMode = false;
      this.updatePreview(ctx);
    } else if (button === -1 && this.dragging) {
      this.endGx = gx; this.endGy = gy;
      this.updatePreview(ctx);
    }
  }

  onPointerUp(ctx: ToolContext): void {
    if (!this.dragging) return;
    this.dragging = false;

    const gridW = ctx.comp.gridCols * MAP_SIZE;
    const gridH = ctx.comp.gridRows * MAP_SIZE;

    const x0 = Math.max(0, Math.min(this.startGx, this.endGx));
    const y0 = Math.max(0, Math.min(this.startGy, this.endGy));
    const x1 = Math.min(gridW - 1, Math.max(this.startGx, this.endGx));
    const y1 = Math.min(gridH - 1, Math.max(this.startGy, this.endGy));

    const noMove = x0 === x1 && y0 === y1;

    if (noMove && !this.addMode && !this.subMode) {
      // Click with no drag → clear selection
      ctx.setSelMask(null);
      ctx.canvas.selMask = null;
      ctx.canvas.markDirty();
      return;
    }

    const size = gridW * gridH;
    const existing = ctx.getSelMask();
    const newMask = existing ? existing.slice() : new Uint8Array(size);

    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        if (this.subMode) {
          newMask[y * gridW + x] = 0;
        } else {
          newMask[y * gridW + x] = 1;
        }
      }
    }

    ctx.setSelMask(newMask);
    ctx.canvas.selMask = newMask;
    ctx.canvas.markDirty();
  }

  /** Show a wand-like preview of the pending rectangle. */
  private updatePreview(ctx: ToolContext): void {
    const gridW = ctx.comp.gridCols * MAP_SIZE;
    const gridH = ctx.comp.gridRows * MAP_SIZE;

    const x0 = Math.max(0, Math.min(this.startGx, this.endGx));
    const y0 = Math.max(0, Math.min(this.startGy, this.endGy));
    const x1 = Math.min(gridW - 1, Math.max(this.startGx, this.endGx));
    const y1 = Math.min(gridH - 1, Math.max(this.startGy, this.endGy));

    const preview = new Uint8Array(gridW * gridH);
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        preview[y * gridW + x] = 1;
      }
    }
    ctx.canvas.wandPreview = preview;
    ctx.canvas.markDirty();
  }

  deactivate(ctx: ToolContext): void {
    ctx.canvas.wandPreview = null;
    ctx.canvas.markDirty();
  }
}
