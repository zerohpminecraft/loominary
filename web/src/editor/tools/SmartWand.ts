/**
 * Smart Wand tool — semantic selection via SAM (Phase 3).
 *
 * Click a subject and SAM selects the whole thing (not just the contiguous
 * color region the Magic Wand would grab — invaluable on dithered tiles).
 *   - Click            → new selection from one positive point
 *   - Shift + click    → add another positive point (grow the subject)
 *   - Ctrl/right-click → add a negative point (carve away)
 *
 * The vision encoder runs once per source image (cached); each click runs only
 * the tiny decoder. All ML work is async and dynamically imported, so the editor
 * stays responsive and ML code stays out of the main bundle.
 */

import type { Tool, ToolContext } from './Tool.js';
import { MAP_SIZE } from './Tool.js';
import type { SmartSelector } from '../../ml/features/smartSelect.js';

interface GridPoint { gx: number; gy: number; positive: boolean; }

export class SmartWandTool implements Tool {
  readonly id = 'smartwand';
  readonly name = 'Smart Wand';
  readonly cursor = 'crosshair';

  private selector: SmartSelector | null = null;
  private points: GridPoint[] = [];
  private busy = false;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    if (button !== 0 && button !== 2) return; // clicks only (ignore drags)
    if (this.busy) return;

    const bmp = ctx.getSourceBitmap?.();
    if (!bmp) {
      ctx.setStatus?.('Smart Wand needs the original source image (not available for this tile).');
      return;
    }

    const negative = button === 2 || ctx.ctrlHeld;
    const additive = ctx.shiftHeld || negative;
    if (!additive) this.points = [];
    this.points.push({ gx, gy, positive: !negative });

    void this.runSegment(bmp, ctx);
  }

  private async runSegment(bmp: ImageBitmap, ctx: ToolContext): Promise<void> {
    this.busy = true;
    try {
      if (!this.selector) {
        const { SmartSelector } = await import('../../ml/features/smartSelect.js');
        this.selector = new SmartSelector();
      }
      const okEncoded = await this.selector.ensureEncoded(bmp);
      if (!okEncoded) { this.points = []; return; }

      ctx.setStatus?.('Smart Wand: segmenting…');
      const mask = await this.selector.selectFromGridClicks(
        this.points, ctx.comp.gridCols, ctx.comp.gridRows, MAP_SIZE,
      );
      if (mask) {
        ctx.setSelMask(mask);
        ctx.canvas.selMask = mask;
        ctx.canvas.markDirty();
        const count = mask.reduce((s, v) => s + v, 0);
        ctx.setStatus?.(`Smart Wand: selected ${count} px (${this.points.length} point${this.points.length > 1 ? 's' : ''}).`);
      } else {
        ctx.setStatus?.('Smart Wand: no mask returned.');
      }
    } finally {
      this.busy = false;
    }
  }

  activate(ctx: ToolContext): void {
    this.points = [];
    ctx.setStatus?.('Smart Wand: click a subject. Shift=add · Ctrl=subtract.');
  }

  deactivate(): void {
    this.points = [];
  }
}
