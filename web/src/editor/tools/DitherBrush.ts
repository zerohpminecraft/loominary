/**
 * Dither Brush tool (T) — paint the dither-strength mask.
 *
 * Left-drag  stamps `strength` (0–1) at every pixel under the brush.
 * Right-drag stamps 0 (erase — reverts to nearest-neighbour in that region).
 * Scroll / onWheel adjusts `strength` in 0.1 steps.
 *
 * The mask is consumed by requantize (R): pixels with high strength get full
 * error-diffusion; pixels with zero get pure nearest-neighbour.
 *
 * Pressing M toggles the heatmap overlay; the Editor manages that flag.
 */

import type { Tool, ToolContext } from './Tool.js';
import { bresenhamLine } from './Tool.js';

export class DitherBrushTool implements Tool {
  readonly id     = 'ditherbrush';
  readonly name   = 'Dither Brush';
  readonly cursor = 'crosshair';

  /** Value stamped by left-drag (0.0–1.0). Adjusted by scroll wheel. */
  strength = 1.0;

  private dragging = false;
  private dragSign = 1;    // +1 paint strength, -1 paint 0
  private lastGx   = -1;
  private lastGy   = -1;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    if (button === 0 || button === 2) {
      this.dragging = true;
      this.dragSign = button === 0 ? 1 : -1;
      this.stamp(gx, gy, ctx);
      this.lastGx = gx; this.lastGy = gy;
    } else if (button === -1 && this.dragging) {
      bresenhamLine(this.lastGx, this.lastGy, gx, gy, (px, py) => this.stamp(px, py, ctx));
      this.lastGx = gx; this.lastGy = gy;
    }
  }

  onPointerUp(_ctx: ToolContext): void {
    this.dragging = false;
    this.lastGx = -1; this.lastGy = -1;
  }

  onWheel(delta: number, _ctx: ToolContext): void {
    this.strength = Math.round(Math.min(1, Math.max(0.1, this.strength + delta * 0.1)) * 10) / 10;
  }

  private stamp(gx: number, gy: number, ctx: ToolContext): void {
    const { comp } = ctx;
    const gridW = comp.gridCols * 128;
    const gridH = comp.gridRows * 128;
    const r     = ctx.brushRadius;
    const value = this.dragSign > 0 ? this.strength : 0;

    if (!ctx.canvas.ditherMask) {
      ctx.canvas.ditherMask = new Float32Array(gridW * gridH);
    }
    const mask = ctx.canvas.ditherMask;

    const r2 = (r + 0.5) * (r + 0.5);
    for (let dy = -r; dy <= r; dy++) {
      for (let dx = -r; dx <= r; dx++) {
        if (ctx.brushShape === 'circle' && dx * dx + dy * dy > r2) continue;
        const nx = gx + dx, ny = gy + dy;
        if (nx < 0 || ny < 0 || nx >= gridW || ny >= gridH) continue;
        mask[ny * gridW + nx] = value;
      }
    }

    ctx.canvas.showDitherMask = true;
    ctx.canvas.markDirty();
  }
}
