/**
 * Tool interface — all editor tools implement this.
 *
 * A "tool context" is passed on every event so tools are stateless with respect
 * to the composition data (though they may keep their own drag state).
 */

import type { MapCanvas } from '../Canvas.js';
import type { EditHistory } from '../history.js';
import type { CompositionState } from '../../payload-state.js';
import { isSrgb } from '../../payload-state.js';
import type { OklabLookup } from '../../quantize.js';
import { quantizeRgbPixel } from '../../srgb.js';
import { rgbToOklab } from '../../oklab.js';

// ─── Tool context ─────────────────────────────────────────────────────────────

export interface ToolContext {
  comp:          CompositionState;
  canvas:        MapCanvas;
  history:       EditHistory;
  oklabLookup:   OklabLookup;

  // Tool parameters (read-only from tool's perspective)
  /** Palette mode: foreground map byte (0–255).  sRGB mode: packed 0xRRGGBB. */
  activeColor:   number;
  brushRadius:   number;   // pixels
  brushShape:    'circle' | 'square';
  fillTolerance: number;   // OKLab distance (0–1)
  wandTolerance: number;

  // Modifier key state at the time of the event
  ctrlHeld:  boolean;
  shiftHeld: boolean;

  // Callbacks to modify Editor state
  setColor:    (mapByte: number) => void;
  setSelMask:  (mask: Uint8Array | null) => void;
  getSelMask:  () => Uint8Array | null;
}

// ─── Tool interface ───────────────────────────────────────────────────────────

export interface Tool {
  readonly id:     string;
  readonly name:   string;
  readonly cursor: string;  // CSS cursor string

  /** Called on left or right mouse down, and on drag. button=0|2=down, -1=drag. */
  onPointerEvent?(gx: number, gy: number, button: number, buttons: number, ctx: ToolContext): void;
  /** Called when the pointer is released. */
  onPointerUp?(ctx: ToolContext): void;
  /** Shift+wheel — adjust radius or tolerance. */
  onWheel?(delta: number, ctx: ToolContext): void;
  /** Called when this tool becomes active. */
  activate?(ctx: ToolContext): void;
  /** Called when this tool is deactivated. */
  deactivate?(ctx: ToolContext): void;
}

// ─── Pixel write helpers ──────────────────────────────────────────────────────

export const MAP_SIZE = 128;

/**
 * Convert global pixel coordinates to tile index + local offset.
 * Returns null if out of bounds.
 */
export function globalToTile(
  gx: number, gy: number,
  comp: CompositionState,
): { ti: number; lx: number; ly: number } | null {
  const { gridCols, gridRows } = comp;
  if (gx < 0 || gy < 0 || gx >= gridCols * MAP_SIZE || gy >= gridRows * MAP_SIZE) return null;
  const tileCol = Math.floor(gx / MAP_SIZE);
  const tileRow = Math.floor(gy / MAP_SIZE);
  const lx = gx % MAP_SIZE;
  const ly = gy % MAP_SIZE;
  const ti = tileRow * gridCols + tileCol;
  return { ti, lx, ly };
}

/**
 * Read a single pixel from global coordinates.
 * Palette mode: the map byte.  sRGB mode: packed 0xRRGGBB.
 * Returns 0 if out of bounds.
 */
export function readPixel(comp: CompositionState, gx: number, gy: number): number {
  const t = globalToTile(gx, gy, comp);
  if (!t) return 0;
  if (isSrgb(comp)) {
    const rgb = comp.rgbFrames![t.ti]?.[comp.activeFrame];
    if (!rgb) return 0;
    const i = (t.lx + t.ly * MAP_SIZE) * 3;
    return (rgb[i] << 16) | (rgb[i + 1] << 8) | rgb[i + 2];
  }
  const frame = comp.frames[t.ti]?.[comp.activeFrame];
  return frame ? frame[t.lx + t.ly * MAP_SIZE] : 0;
}

/**
 * OKLab coordinates of a colour as returned by {@link readPixel} — a map byte in palette
 * mode, packed 0xRRGGBB in sRGB mode.  Used by the tolerance tools (Fill, Magic Wand).
 */
export function colorOklab(ctx: ToolContext, color: number): [number, number, number] | null {
  if (isSrgb(ctx.comp)) {
    return rgbToOklab((color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff);
  }
  return ctx.oklabLookup[color] ?? null;
}

/**
 * Write a single pixel at global coordinates.
 * Palette mode writes the map byte.  sRGB mode writes the packed 0xRRGGBB into rgbFrames
 * AND its nearest-palette twin into frames — the preview invariant every consumer of
 * `frames` relies on.
 * Respects the selection mask if provided (only writes if pixel is selected).
 * Returns true if a write occurred.
 */
export function writePixel(
  comp:    CompositionState,
  gx:      number,
  gy:      number,
  color:   number,
  selMask: Uint8Array | null,
): boolean {
  const { gridCols, gridRows } = comp;
  if (gx < 0 || gy < 0 || gx >= gridCols * MAP_SIZE || gy >= gridRows * MAP_SIZE) return false;

  const gridW = gridCols * MAP_SIZE;
  if (selMask && !selMask[gy * gridW + gx]) return false;

  const t = globalToTile(gx, gy, comp);
  if (!t) return false;
  const frame = comp.frames[t.ti]?.[comp.activeFrame];
  if (!frame) return false;
  const p = t.lx + t.ly * MAP_SIZE;
  if (isSrgb(comp)) {
    const rgb = comp.rgbFrames![t.ti]?.[comp.activeFrame];
    if (!rgb) return false;
    const r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
    rgb[p * 3] = r; rgb[p * 3 + 1] = g; rgb[p * 3 + 2] = b;
    frame[p] = quantizeRgbPixel(r, g, b);
    return true;
  }
  frame[p] = color;
  return true;
}

/**
 * Paint a brush stamp at (gx, gy): fills all pixels within radius r from the
 * centre.  Shape = 'circle' uses Euclidean distance; 'square' fills the AABB.
 */
export function paintBrushStamp(
  comp:    CompositionState,
  gx:      number,
  gy:      number,
  color:   number,
  radius:  number,
  shape:   'circle' | 'square',
  selMask: Uint8Array | null,
): boolean {
  let wrote = false;
  const r2 = (radius + 0.5) * (radius + 0.5);
  for (let dy = -radius; dy <= radius; dy++) {
    for (let dx = -radius; dx <= radius; dx++) {
      if (shape === 'circle' && dx * dx + dy * dy > r2) continue;
      if (writePixel(comp, gx + dx, gy + dy, color, selMask)) wrote = true;
    }
  }
  return wrote;
}

/**
 * Bresenham line: call `paint(x, y)` for every pixel on the line from (x0,y0) to (x1,y1).
 */
export function bresenhamLine(
  x0: number, y0: number,
  x1: number, y1: number,
  paint: (x: number, y: number) => void,
): void {
  const dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
  const sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
  let err = dx - dy;
  for (;;) {
    paint(x0, y0);
    if (x0 === x1 && y0 === y1) break;
    const e2 = 2 * err;
    if (e2 > -dy) { err -= dy; x0 += sx; }
    if (e2 < dx)  { err += dx; y0 += sy; }
  }
}

/**
 * Morphological dilation of a flat selection mask.
 * Each pass expands the selection by 1 pixel in 4 directions.
 */
export function dilateSelMask(mask: Uint8Array, gridW: number, gridH: number, amount = 1): Uint8Array {
  let cur = mask;
  for (let pass = 0; pass < amount; pass++) {
    const next = cur.slice();
    for (let y = 0; y < gridH; y++) {
      for (let x = 0; x < gridW; x++) {
        if (!cur[y * gridW + x]) continue;
        if (x > 0)         next[y * gridW + x - 1] = 1;
        if (x < gridW - 1) next[y * gridW + x + 1] = 1;
        if (y > 0)         next[(y - 1) * gridW + x] = 1;
        if (y < gridH - 1) next[(y + 1) * gridW + x] = 1;
      }
    }
    cur = next;
  }
  return cur;
}

/**
 * Morphological erosion of a flat selection mask.
 */
export function erodeSelMask(mask: Uint8Array, gridW: number, gridH: number, amount = 1): Uint8Array {
  let cur = mask;
  for (let pass = 0; pass < amount; pass++) {
    const next = cur.slice();
    for (let y = 0; y < gridH; y++) {
      for (let x = 0; x < gridW; x++) {
        if (!cur[y * gridW + x]) continue;
        // Erode: clear if any 4-neighbor is unselected
        if (x === 0 || !cur[y * gridW + x - 1] ||
            x === gridW - 1 || !cur[y * gridW + x + 1] ||
            y === 0 || !cur[(y - 1) * gridW + x] ||
            y === gridH - 1 || !cur[(y + 1) * gridW + x]) {
          next[y * gridW + x] = 0;
        }
      }
    }
    cur = next;
  }
  return cur;
}
