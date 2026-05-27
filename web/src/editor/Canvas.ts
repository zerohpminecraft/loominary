/**
 * Canvas — unified pixel grid renderer and input handler.
 *
 * Manages a single HTML Canvas element that shows all tiles in a continuous
 * grid (no "active tile" boundary for painting tools).  Pixel data lives
 * in the caller's CompositionState; the canvas only reads it.
 *
 * Rendering layers (bottom to top):
 *   1. Pixel ImageData (all tiles, current frame)
 *   2. Tile grid lines
 *   3. Selection marching ants / tint overlay
 *   4. Tool-specific overlays (brush ring, fill crosshair, wand preview, paste ghost)
 *   5. Dither mask heatmap (M toggle)
 *
 * Coordinate model:
 *   Global pixel coords: (gx, gy) ∈ [0, gridCols*128) × [0, gridRows*128)
 *   Screen coords:       sx = translateX + gx * scale
 *   Tile index:          ti = floor(gy/128)*gridCols + floor(gx/128)
 *   Local coords:        lx = gx % 128, ly = gy % 128
 */

import { MC_PALETTE } from '../palette.js';
import type { CompositionState } from '../payload-state.js';

export const MAP_SIZE = 128;

// Transparent checkerboard colours
const TRANS_A = 0xffc0c0c0;
const TRANS_B = 0xff808080;

export interface CanvasOptions {
  /** Called when the user presses/drags on a pixel. button = 0|2 for down, -1 for drag. */
  onPixelEvent: (gx: number, gy: number, button: number, buttons: number, event: PointerEvent) => void;
  /** Called when a pointer drag ends (pointerup). */
  onPointerUp?: () => void;
  /** Called when the pointer moves (for hover / brush position), even with no buttons held. */
  onPointerHover?: (gx: number, gy: number) => void;
  /** Called on shift+wheel (tool-specific adjustment like radius/tolerance). */
  onWheel?: (e: WheelEvent) => void;
}

export class MapCanvas {
  readonly el: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private imgData: ImageData | null = null;
  private imgDirty = true;
  private offscreen: OffscreenCanvas | null = null;

  // Viewport transform
  translateX = 0;
  translateY = 0;
  scale = 4;

  // Panning state
  private panActive = false;
  private panLastX = 0;
  private panLastY = 0;

  // Selection mask (global grid size)
  selMask: Uint8Array | null = null;
  selPhase = 0; // marching ants animation phase

  // Dither mask (global grid size, float values 0–1)
  ditherMask: Float32Array | null = null;
  showDitherMask = false;

  // Tile grid lines
  showGridLines = true;

  // Wand / lasso / rect hover preview (same size as selMask).
  // When wandPreviewSubtract=true the preview is orange (deselect) instead of blue (select).
  wandPreview: Uint8Array | null = null;
  wandPreviewSubtract = false;

  // Brush overlay
  brushX = -1;
  brushY = -1;
  brushRadius = 0;
  brushShape: 'circle' | 'square' = 'circle';

  // Requantize preview — renders this comp instead of the real one when set.
  previewComp: CompositionState | null = null;

  // Paste ghost — clipboard pixels rendered semi-transparently at cursor.
  pasteGhost: { pixels: Uint8Array; mask: Uint8Array; w: number; h: number } | null = null;

  // Fill hover preview — flood region shown with the fill colour.
  fillPreview:      Uint8Array | null = null;
  fillPreviewColor  = 0;  // map byte whose palette RGB is used for the overlay tint

  // Heatmap LUT — packed 0x00RRGGBB per map-byte; when set, replaces MC_PALETTE.
  heatmapLut: Uint32Array | null = null;

  private raf: number | null = null;
  private opts: CanvasOptions;

  constructor(el: HTMLCanvasElement, opts: CanvasOptions) {
    this.el = el;
    this.opts = opts;
    this.ctx = el.getContext('2d', { alpha: false })!;
    this.attachListeners();
  }

  // ─── Public API ──────────────────────────────────────────────────────────

  /** Start the render loop. */
  start(): void {
    if (this.raf !== null) return;
    const loop = (now: number) => {
      this.raf = requestAnimationFrame(loop);
      this.render(now);
    };
    this.raf = requestAnimationFrame(loop);
  }

  stop(): void {
    if (this.raf !== null) { cancelAnimationFrame(this.raf); this.raf = null; }
  }

  /** Mark pixel data dirty so the ImageData is rebuilt on the next frame. */
  markDirty(): void { this.imgDirty = true; }

  /** Convert screen coordinates to global pixel coordinates. */
  screenToGlobal(sx: number, sy: number): [gx: number, gy: number] {
    return [
      Math.floor((sx - this.translateX) / this.scale),
      Math.floor((sy - this.translateY) / this.scale),
    ];
  }

  /** Convert global pixel coordinates to screen coordinates (top-left of pixel). */
  globalToScreen(gx: number, gy: number): [sx: number, sy: number] {
    return [
      this.translateX + gx * this.scale,
      this.translateY + gy * this.scale,
    ];
  }

  /** Resize the canvas element to its CSS display size. */
  resize(): void {
    const dpr = window.devicePixelRatio || 1;
    const rect = this.el.getBoundingClientRect();
    const w = Math.round(rect.width  * dpr);
    const h = Math.round(rect.height * dpr);
    if (this.el.width !== w || this.el.height !== h) {
      this.el.width = w; this.el.height = h;
      this.imgDirty = true;
    }
  }

  /** Center the full grid in the current viewport at a scale that fits. */
  fitToView(comp: CompositionState): void {
    const gridW = comp.gridCols * MAP_SIZE;
    const gridH = comp.gridRows * MAP_SIZE;
    const scaleX = Math.floor(this.el.width  / gridW);
    const scaleY = Math.floor(this.el.height / gridH);
    this.scale = Math.max(1, Math.min(16, Math.min(scaleX, scaleY)));
    this.translateX = Math.round((this.el.width  - gridW * this.scale) / 2);
    this.translateY = Math.round((this.el.height - gridH * this.scale) / 2);
    this.imgDirty = true;
  }

  // ─── Rendering ───────────────────────────────────────────────────────────

  private render(now: number): void {
    const { el, ctx, scale, translateX, translateY } = this;
    const w = el.width, h = el.height;

    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(0, 0, w, h);

    const comp = this.previewComp ?? this._comp;
    if (!comp) return;
    const gridW = comp.gridCols * MAP_SIZE;
    const gridH = comp.gridRows * MAP_SIZE;

    // Rebuild pixel ImageData if dirty.
    if (this.imgDirty) {
      this.rebuildImageData(comp);
      this.imgDirty = false;
    }

    // Draw the pixel grid.
    if (this.imgData) {
      // Recreate offscreen canvas only when dimensions change.
      if (!this.offscreen
          || this.offscreen.width  !== this.imgData.width
          || this.offscreen.height !== this.imgData.height) {
        this.offscreen = new OffscreenCanvas(this.imgData.width, this.imgData.height);
      }
      const octx = this.offscreen.getContext('2d')!;
      octx.putImageData(this.imgData, 0, 0);

      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(this.offscreen,
        0, 0, gridW, gridH,
        translateX, translateY, gridW * scale, gridH * scale,
      );
    }

    // Tile grid lines (visible when scale >= 2 and showGridLines is true).
    if (this.showGridLines && scale >= 2) {
      ctx.strokeStyle = 'rgba(255,255,255,0.15)';
      ctx.lineWidth = 1;
      for (let tc = 1; tc < comp.gridCols; tc++) {
        const x = translateX + tc * MAP_SIZE * scale;
        ctx.beginPath(); ctx.moveTo(x, translateY); ctx.lineTo(x, translateY + gridH * scale);
        ctx.stroke();
      }
      for (let tr = 1; tr < comp.gridRows; tr++) {
        const y = translateY + tr * MAP_SIZE * scale;
        ctx.beginPath(); ctx.moveTo(translateX, y); ctx.lineTo(translateX + gridW * scale, y);
        ctx.stroke();
      }
    }

    // Selection overlay + marching ants.
    this.selPhase = Math.floor(now / 120) % 8;
    if (this.selMask) this.drawSelection(ctx, gridW, gridH);

    // Dither mask heatmap.
    if (this.showDitherMask && this.ditherMask) this.drawDitherMask(ctx, gridW, gridH);

    // Wand / lasso / rect hover preview.
    if (this.wandPreview) this.drawWandPreview(ctx, gridW, gridH);

    // Fill tool hover preview (coloured with the active fill colour).
    if (this.fillPreview) this.drawFillPreview(ctx, gridW, gridH);

    // Brush ring overlay.
    if (this.brushX >= 0) this.drawBrushOverlay(ctx);

    // Paste ghost (rendered last so it appears on top).
    if (this.pasteGhost && this.brushX >= 0) this.drawPasteGhost(ctx);

    // Preview border — thin cyan outline around canvas when preview is active.
    if (this.previewComp) {
      ctx.strokeStyle = 'rgba(80,200,255,0.55)';
      ctx.lineWidth = 4;
      ctx.strokeRect(2, 2, w - 4, h - 4);
    }
  }

  private _comp: CompositionState | null = null;

  /** Provide updated composition state for the next render. */
  setComposition(comp: CompositionState): void {
    this._comp = comp;
    this.imgDirty = true;
  }

  private rebuildImageData(comp: CompositionState): void {
    const gridW = comp.gridCols * MAP_SIZE;
    const gridH = comp.gridRows * MAP_SIZE;

    if (!this.imgData || this.imgData.width !== gridW || this.imgData.height !== gridH) {
      this.imgData = new ImageData(gridW, gridH);
    }

    const d = this.imgData.data;
    for (let tileRow = 0; tileRow < comp.gridRows; tileRow++) {
      for (let tileCol = 0; tileCol < comp.gridCols; tileCol++) {
        const ti = tileRow * comp.gridCols + tileCol;
        const frame = comp.frames[ti]?.[comp.activeFrame];
        if (!frame) continue;

        for (let ly = 0; ly < MAP_SIZE; ly++) {
          for (let lx = 0; lx < MAP_SIZE; lx++) {
            const gx = tileCol * MAP_SIZE + lx;
            const gy = tileRow * MAP_SIZE + ly;
            const di = (gy * gridW + gx) * 4;
            const mapByte = frame[lx + ly * MAP_SIZE];

            if (mapByte === 0) {
              // Transparent — checkerboard
              const checkered = (lx + ly) % 2 === 0 ? TRANS_A : TRANS_B;
              d[di    ] = (checkered >> 16) & 0xff;
              d[di + 1] = (checkered >>  8) & 0xff;
              d[di + 2] =  checkered        & 0xff;
              d[di + 3] = 255;
            } else {
              const rgb = this.heatmapLut ? this.heatmapLut[mapByte] : MC_PALETTE[mapByte];
              d[di    ] = (rgb >> 16) & 0xff;
              d[di + 1] = (rgb >>  8) & 0xff;
              d[di + 2] =  rgb        & 0xff;
              d[di + 3] = 255;
            }
          }
        }
      }
    }
  }

  private drawSelection(ctx: CanvasRenderingContext2D, gridW: number, gridH: number): void {
    const { scale, translateX, translateY } = this;
    const sel = this.selMask!;
    const phase = this.selPhase;

    // Compute visible pixel bounds.
    const [gxMin, gyMin] = this.screenToGlobal(0, 0);
    const [gxMax, gyMax] = this.screenToGlobal(this.el.width, this.el.height);

    for (let gy = Math.max(0, gyMin); gy <= Math.min(gridH - 1, gyMax); gy++) {
      for (let gx = Math.max(0, gxMin); gx <= Math.min(gridW - 1, gxMax); gx++) {
        const idx = gy * gridW + gx;
        if (!sel[idx]) continue;

        const sx = translateX + gx * scale;
        const sy = translateY + gy * scale;

        // Semi-transparent white tint
        ctx.fillStyle = 'rgba(255,255,255,0.07)';
        ctx.fillRect(sx, sy, scale, scale);

        // Marching ants on each border where neighbor is not selected
        const drawAnt = (x1: number, y1: number, x2: number, y2: number, coord: number) => {
          ctx.fillStyle = (coord + phase) % 8 < 4 ? '#fff' : '#222';
          ctx.fillRect(x1, y1, x2 - x1, y2 - y1);
        };

        if (gy === 0 || !sel[(gy - 1) * gridW + gx])
          drawAnt(sx, sy - 1, sx + scale, sy, gx);
        if (gy === gridH - 1 || !sel[(gy + 1) * gridW + gx])
          drawAnt(sx, sy + scale, sx + scale, sy + scale + 1, gx);
        if (gx === 0 || !sel[gy * gridW + gx - 1])
          drawAnt(sx - 1, sy, sx, sy + scale, gy);
        if (gx === gridW - 1 || !sel[gy * gridW + gx + 1])
          drawAnt(sx + scale, sy, sx + scale + 1, sy + scale, gy);
      }
    }
  }

  private drawDitherMask(ctx: CanvasRenderingContext2D, gridW: number, gridH: number): void {
    const { scale, translateX, translateY } = this;
    const mask = this.ditherMask!;
    const [gxMin, gyMin] = this.screenToGlobal(0, 0);
    const [gxMax, gyMax] = this.screenToGlobal(this.el.width, this.el.height);

    for (let gy = Math.max(0, gyMin); gy <= Math.min(gridH - 1, gyMax); gy++) {
      for (let gx = Math.max(0, gxMin); gx <= Math.min(gridW - 1, gxMax); gx++) {
        const s = mask[gy * gridW + gx];
        if (s <= 0) continue;
        const alpha = Math.round(s * 90);
        ctx.fillStyle = `rgba(255,210,0,${alpha / 255})`;
        ctx.fillRect(translateX + gx * scale, translateY + gy * scale, scale, scale);
      }
    }
  }

  private drawWandPreview(ctx: CanvasRenderingContext2D, gridW: number, gridH: number): void {
    const { scale, translateX, translateY } = this;
    const preview = this.wandPreview!;
    const [gxMin, gyMin] = this.screenToGlobal(0, 0);
    const [gxMax, gyMax] = this.screenToGlobal(this.el.width, this.el.height);

    // Blue tint = add to selection; orange tint = subtract from selection.
    ctx.fillStyle = this.wandPreviewSubtract
      ? 'rgba(255, 140, 0, 0.38)'
      : 'rgba(0, 136, 255, 0.31)';
    for (let gy = Math.max(0, gyMin); gy <= Math.min(gridH - 1, gyMax); gy++) {
      for (let gx = Math.max(0, gxMin); gx <= Math.min(gridW - 1, gxMax); gx++) {
        if (preview[gy * gridW + gx]) {
          ctx.fillRect(translateX + gx * scale, translateY + gy * scale, scale, scale);
        }
      }
    }
  }

  private drawFillPreview(ctx: CanvasRenderingContext2D, gridW: number, gridH: number): void {
    const { scale, translateX, translateY } = this;
    const preview = this.fillPreview!;
    const [gxMin, gyMin] = this.screenToGlobal(0, 0);
    const [gxMax, gyMax] = this.screenToGlobal(this.el.width, this.el.height);

    const rgb = MC_PALETTE[this.fillPreviewColor] ?? 0;
    const r = (rgb >> 16) & 0xff;
    const g = (rgb >>  8) & 0xff;
    const b = rgb & 0xff;
    ctx.fillStyle = `rgba(${r},${g},${b},0.55)`;
    for (let gy = Math.max(0, gyMin); gy <= Math.min(gridH - 1, gyMax); gy++) {
      for (let gx = Math.max(0, gxMin); gx <= Math.min(gridW - 1, gxMax); gx++) {
        if (preview[gy * gridW + gx]) {
          ctx.fillRect(translateX + gx * scale, translateY + gy * scale, scale, scale);
        }
      }
    }
  }

  private drawPasteGhost(ctx: CanvasRenderingContext2D): void {
    const { scale, translateX, translateY, pasteGhost, brushX, brushY } = this;
    if (!pasteGhost) return;
    const { pixels, mask, w, h } = pasteGhost;
    const ox = brushX - Math.floor(w / 2);
    const oy = brushY - Math.floor(h / 2);

    for (let py = 0; py < h; py++) {
      for (let px = 0; px < w; px++) {
        const ci = py * w + px;
        if (!mask[ci]) continue;
        const rgb = MC_PALETTE[pixels[ci]] ?? 0;
        const r = (rgb >> 16) & 0xff;
        const g = (rgb >>  8) & 0xff;
        const b =  rgb        & 0xff;
        ctx.fillStyle = `rgba(${r},${g},${b},0.72)`;
        ctx.fillRect(
          translateX + (ox + px) * scale,
          translateY + (oy + py) * scale,
          scale, scale,
        );
      }
    }
    // Outline around the bounding box
    ctx.strokeStyle = 'rgba(255,255,255,0.85)';
    ctx.lineWidth = 1;
    ctx.strokeRect(
      translateX + ox * scale - 0.5,
      translateY + oy * scale - 0.5,
      w * scale + 1,
      h * scale + 1,
    );
  }

  private drawBrushOverlay(ctx: CanvasRenderingContext2D): void {
    const { scale, translateX, translateY, brushX, brushY, brushRadius, brushShape } = this;
    ctx.strokeStyle = 'rgba(255,255,255,0.7)';
    ctx.lineWidth = 1;

    if (brushShape === 'circle') {
      const cx = translateX + brushX * scale + scale / 2;
      const cy = translateY + brushY * scale + scale / 2;
      const r  = (brushRadius + 0.5) * scale;
      ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke();
    } else {
      const x = translateX + (brushX - brushRadius) * scale;
      const y = translateY + (brushY - brushRadius) * scale;
      const s = (brushRadius * 2 + 1) * scale;
      ctx.strokeRect(x, y, s, s);
    }
  }

  // ─── Input ───────────────────────────────────────────────────────────────

  private attachListeners(): void {
    const el = this.el;
    el.addEventListener('pointerdown',  this.onPointerDown);
    el.addEventListener('pointermove',  this.onPointerMove);
    el.addEventListener('pointerup',    this.onPointerUp);
    el.addEventListener('wheel',        this.onWheel, { passive: false });
    el.addEventListener('contextmenu',  e => e.preventDefault());
  }

  private onPointerDown = (e: PointerEvent) => {
    e.preventDefault();
    this.el.setPointerCapture(e.pointerId);

    if (e.button === 1) {
      // Middle-click: start pan
      this.panActive = true;
      this.panLastX = e.clientX;
      this.panLastY = e.clientY;
      return;
    }

    const [gx, gy] = this.screenToGlobal(e.offsetX, e.offsetY);
    this.opts.onPixelEvent(gx, gy, e.button, e.buttons, e);
  };

  private onPointerMove = (e: PointerEvent) => {
    if (this.panActive) {
      this.translateX += e.clientX - this.panLastX;
      this.translateY += e.clientY - this.panLastY;
      this.panLastX = e.clientX;
      this.panLastY = e.clientY;
      return;
    }

    const [gx, gy] = this.screenToGlobal(e.offsetX, e.offsetY);
    if (e.buttons > 0) {
      this.opts.onPixelEvent(gx, gy, -1, e.buttons, e); // -1 = drag
    } else {
      this.opts.onPointerHover?.(gx, gy);
    }
    // Update brush overlay position
    this.brushX = gx; this.brushY = gy;
  };

  private onPointerUp = (e: PointerEvent) => {
    this.panActive = false;
    this.el.releasePointerCapture(e.pointerId);
    this.opts.onPointerUp?.();
  };

  private onWheel = (e: WheelEvent) => {
    e.preventDefault();
    if (e.shiftKey) {
      // Shift+scroll → delegate to tool (tolerance / radius)
      this.opts.onWheel?.(e);
      return;
    }

    const oldScale = this.scale;
    const delta = e.deltaY < 0 ? 1 : -1;
    this.scale = Math.max(1, Math.min(16, this.scale + delta));
    if (this.scale === oldScale) return;

    // Keep mouse position stable during zoom.
    const mx = e.offsetX, my = e.offsetY;
    this.translateX = mx - (mx - this.translateX) * this.scale / oldScale;
    this.translateY = my - (my - this.translateY) * this.scale / oldScale;
  };
}
