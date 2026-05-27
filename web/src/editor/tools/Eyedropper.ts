/**
 * Eyedropper tool (E) — pick a colour from the canvas.
 *
 * Left-click picks the foreground colour and returns to the previous tool.
 * Right-click picks but stays in eyedropper mode.
 */

import type { Tool, ToolContext } from './Tool.js';
import { readPixel } from './Tool.js';

export class EyedropperTool implements Tool {
  readonly id     = 'eyedropper';
  readonly name   = 'Eyedropper';
  readonly cursor = 'crosshair';

  /** Set by Editor before activating eyedropper so we can return. */
  returnToToolId: string | null = null;

  onPointerEvent(gx: number, gy: number, button: number, _buttons: number, ctx: ToolContext): void {
    if (button !== 0 && button !== 2) return;

    const picked = readPixel(ctx.comp, gx, gy);
    if (picked !== 0) {
      ctx.setColor(picked);
    }
  }
}
