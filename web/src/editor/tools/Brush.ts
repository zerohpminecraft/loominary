/**
 * Brush tool (B) — paint pixels with the active colour.
 *
 * Left-click/drag paints the foreground colour.
 * Right-click/drag erases (colour 0 = transparent).
 *
 * Brush is drawn as a stamp at each position, with Bresenham interpolation
 * between drag events to prevent gaps.
 */

import type { Tool, ToolContext } from './Tool.js';
import { paintBrushStamp, bresenhamLine } from './Tool.js';

export class BrushTool implements Tool {
  readonly id     = 'brush';
  readonly name   = 'Brush';
  readonly cursor = 'crosshair';

  private dragging  = false;
  private dragColor = 0;
  private lastGx    = -1;
  private lastGy    = -1;
  private hasWritten = false;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    const sel = ctx.getSelMask();

    if (button === 0 || button === 2) {
      // Initial press — snapshot before first write
      if (!this.dragging) {
        ctx.history.snapshot(ctx.comp.frames);
        this.dragging   = true;
        this.hasWritten = false;
        this.dragColor  = button === 0 ? ctx.activeColor : 0;
      }
      const wrote = paintBrushStamp(ctx.comp, gx, gy, this.dragColor, ctx.brushRadius, ctx.brushShape, sel);
      if (wrote) { this.hasWritten = true; ctx.canvas.markDirty(); }
      this.lastGx = gx; this.lastGy = gy;

    } else if (button === -1 && this.dragging) {
      // Drag — interpolate from last position
      bresenhamLine(this.lastGx, this.lastGy, gx, gy, (px, py) => {
        const wrote = paintBrushStamp(ctx.comp, px, py, this.dragColor, ctx.brushRadius, ctx.brushShape, sel);
        if (wrote) { this.hasWritten = true; ctx.canvas.markDirty(); }
      });
      this.lastGx = gx; this.lastGy = gy;
    }
  }

  onPointerUp(_ctx: ToolContext): void {
    if (this.dragging && !this.hasWritten) {
      // No actual paint — undo the empty snapshot
      // (history.undo isn't right here; just pop the stack)
    }
    this.dragging  = false;
    this.lastGx    = -1;
    this.lastGy    = -1;
    this.hasWritten = false;
  }

  onWheel(_delta: number, _ctx: ToolContext): void {
    // Radius adjustment is handled by the Editor ([ and ] keys and scroll).
  }
}
