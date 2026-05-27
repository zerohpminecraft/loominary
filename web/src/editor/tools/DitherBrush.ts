/**
 * Dither Brush tool (T) — paint the dither-strength mask.
 *
 * Left-drag increases the dither strength (0→1) at the brush location.
 * Right-drag decreases it.
 * The dither mask is a Float32Array (values 0–1) that `computeDitherStrength`
 * uses to mix between nearest-neighbour and full dither per pixel.
 * Pressing M toggles the heatmap overlay; the Editor manages that flag.
 */

import type { Tool, ToolContext } from './Tool.js';
import { bresenhamLine } from './Tool.js';

export class DitherBrushTool implements Tool {
  readonly id     = 'ditherbrush';
  readonly name   = 'Dither Brush';
  readonly cursor = 'crosshair';

  private dragging  = false;
  private dragSign  = 1;   // +1 paint, -1 erase
  private lastGx    = -1;
  private lastGy    = -1;
  private strength  = 0.1; // delta per pixel per stamp

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

  private stamp(gx: number, gy: number, ctx: ToolContext): void {
    const { comp } = ctx;
    const gridW = comp.gridCols * 128;
    const gridH = comp.gridRows * 128;
    const r     = ctx.brushRadius;

    if (!ctx.canvas.ditherMask) {
      ctx.canvas.ditherMask = new Float32Array(gridW * gridH);
    }
    const mask = ctx.canvas.ditherMask;
    const delta = this.strength * this.dragSign;

    const r2 = (r + 0.5) * (r + 0.5);
    for (let dy = -r; dy <= r; dy++) {
      for (let dx = -r; dx <= r; dx++) {
        if (dx * dx + dy * dy > r2) continue;
        const nx = gx + dx, ny = gy + dy;
        if (nx < 0 || ny < 0 || nx >= gridW || ny >= gridH) continue;
        const i = ny * gridW + nx;
        mask[i] = Math.min(1, Math.max(0, mask[i] + delta));
      }
    }

    ctx.canvas.showDitherMask = true;
    ctx.canvas.markDirty();
  }
}
