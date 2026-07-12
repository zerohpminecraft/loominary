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
import { isSrgb } from '../payload-state.js';

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
  // pixels: map bytes in palette mode, packed 0xRRGGBB in sRGB mode.
  pasteGhost: { pixels: Uint32Array; mask: Uint8Array; w: number; h: number } | null = null;

  // Fill hover preview — flood region shown with the fill colour.
  fillPreview:      Uint8Array | null = null;
  fillPreviewColor  = 0;  // map byte whose palette RGB is used for the overlay tint

  // Heatmap LUT — packed 0x00RRGGBB per map-byte; when set, replaces MC_PALETTE.
  heatmapLut: Uint32Array | null = null;

  // Compression detail map — per-pixel edge-density (0=cheap, 255=expensive).
  // When active, replaces the normal pixel colours with a blue→red heat gradient
  // (same colour scheme as the frequency heatmap).
  compressionMap:     Uint8Array | null = null;
  showCompressionMap: boolean           = false;
  private _cmImgData: ImageData | null  = null;   // cached at grid resolution

  // Tile index (row*cols+col) to highlight with a white overlay (from stats panel).
  hoveredTileTi: number | null = null;

  // Palette-swatch hover — all pixels of this map byte flash with a white overlay.
  hoveredPaletteColor: number | null = null;
  private _hmColor: number              = -1;
  private _hmComp:  CompositionState | null = null;
  private _hmMask:  Uint8Array | null   = null;

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

  /**
   * Set the compression detail map.  Passing null hides it and restores normal
   * pixel rendering.  The ImageData is built lazily on the next render frame.
   */
  setCompressionMap(map: Uint8Array | null): void {
    this.compressionMap     = map;
    this.showCompressionMap = map !== null;
    this._cmImgData         = null;   // force rebuild on next frame
  }

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

    // Choose which ImageData to display: compression detail map or normal pixels.
    const useCompMap = this.showCompressionMap && this.compressionMap !== null;
    if (useCompMap) {
      if (!this._cmImgData) this._cmImgData = this.buildCompressionImageData(gridW, gridH);
    } else {
      if (this.imgDirty) { this.rebuildImageData(comp); this.imgDirty = false; }
    }
    const displayData = useCompMap ? this._cmImgData : this.imgData;

    if (displayData) {
      if (!this.offscreen
          || this.offscreen.width  !== displayData.width
          || this.offscreen.height !== displayData.height) {
        this.offscreen = new OffscreenCanvas(displayData.width, displayData.height);
      }
      const octx = this.offscreen.getContext('2d')!;
      octx.putImageData(displayData, 0, 0);
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(this.offscreen, 0, 0, gridW, gridH, translateX, translateY, gridW * scale, gridH * scale);
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

    // Hovered-tile highlight (from stats panel hover).
    if (this.hoveredTileTi !== null) this.drawTileHighlight(ctx, comp);

    // Palette-swatch hover: flash all pixels of the hovered color.
    if (this.hoveredPaletteColor !== null && !useCompMap) {
      this.drawHoveredColorFlash(ctx, gridW, gridH, now, comp);
    }

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

    const srgb = isSrgb(comp);
    const d = this.imgData.data;
    for (let tileRow = 0; tileRow < comp.gridRows; tileRow++) {
      for (let tileCol = 0; tileCol < comp.gridCols; tileCol++) {
        const ti = tileRow * comp.gridCols + tileCol;
        const frame = comp.frames[ti]?.[comp.activeFrame];
        if (!frame) continue;

        // sRGB mode: render the true-colour bytes directly (no transparency,
        // heatmap/dither overlays don't apply).  Falls back to the palette
        // preview twin if this tile's rgb frame is missing.
        const rgbFrame = srgb ? comp.rgbFrames![ti]?.[comp.activeFrame] ?? null : null;

        for (let ly = 0; ly < MAP_SIZE; ly++) {
          for (let lx = 0; lx < MAP_SIZE; lx++) {
            const gx = tileCol * MAP_SIZE + lx;
            const gy = tileRow * MAP_SIZE + ly;
            const di = (gy * gridW + gx) * 4;

            if (rgbFrame) {
              const si = (lx + ly * MAP_SIZE) * 3;
              d[di    ] = rgbFrame[si];
              d[di + 1] = rgbFrame[si + 1];
              d[di + 2] = rgbFrame[si + 2];
              d[di + 3] = 255;
              continue;
            }

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

  /**
   * Build an ImageData for the compression cost map.
   * Cost 0 (uniform/cheap) → blue; cost 255 (noisy/expensive) → red.
   * Same hue range (0°–240°) as the frequency heatmap so the two views look consistent.
   */
  private buildCompressionImageData(gridW: number, gridH: number): ImageData {
    const id  = new ImageData(gridW, gridH);
    const d   = id.data;
    const map = this.compressionMap!;
    // Pre-compute 256-entry LUT: 0=blue(cheap), 255=red(expensive)
    const lut = new Uint32Array(256);
    for (let v = 0; v < 256; v++) {
      const t  = (255 - v) / 255;        // invert: 0=expensive, 1=cheap
      const h  = (240 * t) / 360;        // 0=red, 0.667=blue
      const s  = 0.9, l = 0.45;
      const cv = (1 - Math.abs(2*l - 1)) * s;
      const x  = cv * (1 - Math.abs((h * 6) % 2 - 1));
      const m  = l - cv / 2;
      let r = 0, g = 0, b = 0;
      const h6 = h * 6;
      if      (h6 < 1) { r=cv; g=x;  b=0;  }
      else if (h6 < 2) { r=x;  g=cv; b=0;  }
      else if (h6 < 3) { r=0;  g=cv; b=x;  }
      else if (h6 < 4) { r=0;  g=x;  b=cv; }
      else if (h6 < 5) { r=x;  g=0;  b=cv; }
      else             { r=cv; g=0;  b=x;  }
      lut[v] = ((Math.round((r+m)*255) << 16) | (Math.round((g+m)*255) << 8) | Math.round((b+m)*255)) >>> 0;
    }
    for (let i = 0; i < gridW * gridH; i++) {
      const rgb = lut[map[i]];
      d[i*4    ] = (rgb >> 16) & 0xff;
      d[i*4 + 1] = (rgb >>  8) & 0xff;
      d[i*4 + 2] =  rgb        & 0xff;
      d[i*4 + 3] = 255;
    }
    return id;
  }

  private drawHoveredColorFlash(
    ctx: CanvasRenderingContext2D, gridW: number, gridH: number,
    now: number, comp: CompositionState,
  ): void {
    const color = this.hoveredPaletteColor!;

    // Rebuild mask when color or comp changes.
    if (this._hmColor !== color || this._hmComp !== comp) {
      this._hmColor = color;
      this._hmComp  = comp;
      const mask = new Uint8Array(gridW * gridH);
      for (let tileRow = 0; tileRow < comp.gridRows; tileRow++) {
        for (let tileCol = 0; tileCol < comp.gridCols; tileCol++) {
          const ti    = tileRow * comp.gridCols + tileCol;
          const frame = comp.frames[ti]?.[comp.activeFrame];
          if (!frame) continue;
          for (let ly = 0; ly < MAP_SIZE; ly++) {
            const gy = tileRow * MAP_SIZE + ly;
            for (let lx = 0; lx < MAP_SIZE; lx++) {
              if (frame[lx + ly * MAP_SIZE] === color)
                mask[gy * gridW + tileCol * MAP_SIZE + lx] = 1;
            }
          }
        }
      }
      this._hmMask = mask;
    }

    const mask = this._hmMask;
    if (!mask) return;

    // Smooth 1 Hz pulse: alpha oscillates 0.2 → 0.6
    const pulse = (Math.sin(now * 0.006283) + 1) / 2; // 0–1, period ≈1 s
    const alpha = (0.2 + pulse * 0.4).toFixed(2);
    ctx.fillStyle = `rgba(255,255,255,${alpha})`;

    const { scale, translateX, translateY } = this;
    const [gxMin, gyMin] = this.screenToGlobal(0, 0);
    const [gxMax, gyMax] = this.screenToGlobal(this.el.width, this.el.height);
    for (let gy = Math.max(0, gyMin); gy <= Math.min(gridH - 1, gyMax); gy++) {
      for (let gx = Math.max(0, gxMin); gx <= Math.min(gridW - 1, gxMax); gx++) {
        if (mask[gy * gridW + gx])
          ctx.fillRect(translateX + gx * scale, translateY + gy * scale, scale, scale);
      }
    }
  }

  private drawTileHighlight(ctx: CanvasRenderingContext2D, comp: CompositionState): void {
    if (this.hoveredTileTi === null) return;
    const { scale, translateX, translateY } = this;
    const tileCol = this.hoveredTileTi % comp.gridCols;
    const tileRow = Math.floor(this.hoveredTileTi / comp.gridCols);
    const tx = translateX + tileCol * MAP_SIZE * scale;
    const ty = translateY + tileRow * MAP_SIZE * scale;
    const ts = MAP_SIZE * scale;
    ctx.fillStyle   = 'rgba(255,255,255,0.18)';
    ctx.fillRect(tx, ty, ts, ts);
    ctx.strokeStyle = 'rgba(255,255,255,0.55)';
    ctx.lineWidth   = 2;
    ctx.strokeRect(tx + 1, ty + 1, ts - 2, ts - 2);
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

    // fillPreviewColor is a map byte in palette mode, a packed 0xRRGGBB in sRGB mode.
    const comp = this.previewComp ?? this._comp;
    const rgb = comp && isSrgb(comp)
      ? this.fillPreviewColor
      : MC_PALETTE[this.fillPreviewColor] ?? 0;
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

    // Clipboard values are map bytes in palette mode, packed 0xRRGGBB in sRGB mode.
    const comp = this.previewComp ?? this._comp;
    const srgb = !!comp && isSrgb(comp);
    for (let py = 0; py < h; py++) {
      for (let px = 0; px < w; px++) {
        const ci = py * w + px;
        if (!mask[ci]) continue;
        const rgb = srgb ? pixels[ci] : MC_PALETTE[pixels[ci]] ?? 0;
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

    const dpr = window.devicePixelRatio || 1;
    const [gx, gy] = this.screenToGlobal(e.offsetX * dpr, e.offsetY * dpr);
    this.opts.onPixelEvent(gx, gy, e.button, e.buttons, e);
  };

  private onPointerMove = (e: PointerEvent) => {
    const dpr = window.devicePixelRatio || 1;
    if (this.panActive) {
      // clientX/Y deltas are in CSS pixels; translateX/Y are in canvas device pixels.
      this.translateX += (e.clientX - this.panLastX) * dpr;
      this.translateY += (e.clientY - this.panLastY) * dpr;
      this.panLastX = e.clientX;
      this.panLastY = e.clientY;
      return;
    }

    const [gx, gy] = this.screenToGlobal(e.offsetX * dpr, e.offsetY * dpr);
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

    // Keep mouse position stable during zoom (offsetX/Y are CSS px → scale to device px).
    const dpr = window.devicePixelRatio || 1;
    const mx = e.offsetX * dpr, my = e.offsetY * dpr;
    this.translateX = mx - (mx - this.translateX) * this.scale / oldScale;
    this.translateY = my - (my - this.translateY) * this.scale / oldScale;
  };
}
