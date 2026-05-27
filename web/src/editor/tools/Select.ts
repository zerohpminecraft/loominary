/**
 * Rectangle Select tool (S) — drag to define a rectangular selection.
 *
 * Left-drag          creates a new selection.
 * Shift+drag         adds to the existing selection.
 * Ctrl+drag          subtracts from the selection (orange preview).
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
  addMode  = false;  // Shift held — exposed so Editor can set before first event
  subMode  = false;  // Ctrl held  — exposed so Editor can set before first event

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    if (button === 0) {
      this.startGx = gx; this.startGy = gy;
      this.endGx   = gx; this.endGy   = gy;
      this.dragging = true;
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

    ctx.canvas.wandPreview        = null;
    ctx.canvas.wandPreviewSubtract = false;

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
        newMask[y * gridW + x] = this.subMode ? 0 : 1;
      }
    }

    ctx.setSelMask(newMask);
    ctx.canvas.selMask = newMask;
    ctx.canvas.markDirty();
  }

  /** Show a preview of the pending rectangle; orange when subMode. */
  private updatePreview(ctx: ToolContext): void {
    const gridW = ctx.comp.gridCols * MAP_SIZE;
    const gridH = ctx.comp.gridRows * MAP_SIZE;

    const x0 = Math.max(0, Math.min(this.startGx, this.endGx));
    const y0 = Math.max(0, Math.min(this.startGy, this.endGy));
    const x1 = Math.min(gridW - 1, Math.max(this.startGx, this.endGx));
    const y1 = Math.min(gridH - 1, Math.max(this.startGy, this.endGy));

    const preview = new Uint8Array(gridW * gridH);
    if (this.subMode) {
      // Orange: show only the pixels that would actually be removed
      const existing = ctx.getSelMask();
      for (let y = y0; y <= y1; y++)
        for (let x = x0; x <= x1; x++)
          if (existing?.[y * gridW + x]) preview[y * gridW + x] = 1;
    } else {
      for (let y = y0; y <= y1; y++)
        for (let x = x0; x <= x1; x++)
          preview[y * gridW + x] = 1;
    }

    ctx.canvas.wandPreview        = preview;
    ctx.canvas.wandPreviewSubtract = this.subMode;
    ctx.canvas.markDirty();
  }

  deactivate(ctx: ToolContext): void {
    ctx.canvas.wandPreview        = null;
    ctx.canvas.wandPreviewSubtract = false;
    ctx.canvas.markDirty();
  }
}
