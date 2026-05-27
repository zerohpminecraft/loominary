/**
 * Brush tool (B) — paint pixels with the active colour.
 *
 * Left-click/drag  paints the foreground colour.
 * Right-click      picks the colour under the cursor (eyedropper behaviour).
 *
 * Brush is drawn as a stamp at each position, with Bresenham interpolation
 * between drag events to prevent gaps.
 */

import type { Tool, ToolContext } from './Tool.js';
import { paintBrushStamp, bresenhamLine, readPixel } from './Tool.js';

export class BrushTool implements Tool {
  readonly id     = 'brush';
  readonly name   = 'Brush';
  readonly cursor = 'crosshair';

  private dragging  = false;
  private dragColor = 0;
  private lastGx    = -1;
  private lastGy    = -1;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    // Right-click → pick colour (eyedropper)
    if (button === 2) {
      ctx.setColor(readPixel(ctx.comp, gx, gy));
      return;
    }

    if (button !== 0 && button !== -1) return;

    const sel = ctx.getSelMask();

    if (button === 0) {
      // Initial press — snapshot before first write
      if (!this.dragging) {
        ctx.history.snapshot(ctx.comp.frames);
        this.dragging  = true;
        this.dragColor = ctx.activeColor;
      }
      const wrote = paintBrushStamp(ctx.comp, gx, gy, this.dragColor, ctx.brushRadius, ctx.brushShape, sel);
      if (wrote) { ctx.canvas.markDirty(); }
      this.lastGx = gx; this.lastGy = gy;

    } else if (button === -1 && this.dragging) {
      // Drag — interpolate from last position
      bresenhamLine(this.lastGx, this.lastGy, gx, gy, (px, py) => {
        const wrote = paintBrushStamp(ctx.comp, px, py, this.dragColor, ctx.brushRadius, ctx.brushShape, sel);
        if (wrote) { ctx.canvas.markDirty(); }
      });
      this.lastGx = gx; this.lastGy = gy;
    }
  }

  onPointerUp(_ctx: ToolContext): void {
    this.dragging = false;
    this.lastGx   = -1;
    this.lastGy   = -1;
  }
}
