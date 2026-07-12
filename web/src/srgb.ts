/**
 * sRGB-mode pixel model helpers — the true-colour twin of quantize.ts.
 *
 * In sRGB mode ({@link CompositionState.colorSpace} === 'srgb') the art lives in
 * `rgbFrames` as packed RGB (128·128·3 per tile frame) and `frames` holds the
 * nearest-palette PREVIEW twin, kept in sync on every write.  Nothing here dithers or
 * restricts — the wire format is a lossy AV1 colour stream (manifest v7 / FLAG2_SRGB),
 * so the palette only exists as a preview/back-compat artifact.
 */

import { MAP_SIZE, MAP_BYTES, buildOklabLookup, findClosestInPalette, fullAllShadesPalette } from './quantize.js';
import type { OklabLookup, PaletteFlag } from './quantize.js';
import { rgbToOklab } from './oklab.js';

export const RGB_TILE_BYTES = MAP_BYTES * 3; // 49152

// Shared lookups for preview quantisation (the full all-shades palette: the preview should be
// the best palette approximation, restrictions are meaningless in sRGB mode).
let _oklab: OklabLookup | null = null;
let _palette: PaletteFlag | null = null;
function lookups(): { oklab: OklabLookup; palette: PaletteFlag } {
  _oklab ??= buildOklabLookup();
  _palette ??= fullAllShadesPalette();
  return { oklab: _oklab, palette: _palette };
}

/** Nearest-palette map byte for one RGB pixel — the preview twin of an sRGB write. */
export function quantizeRgbPixel(r: number, g: number, b: number): number {
  const { oklab, palette } = lookups();
  const [L, a, bb] = rgbToOklab(r, g, b);
  return findClosestInPalette(L, a, bb, palette, oklab);
}

/**
 * Rasterise a full-composition RGBA image into per-tile packed-RGB frames.
 * Alpha composites over black (the AV1 colour stream has no transparency).
 */
export function rasterizeGridRgb(imageData: ImageData, cols: number, rows: number): Uint8Array[] {
  const totalW = cols * MAP_SIZE;
  const full = new Uint8Array(totalW * rows * MAP_SIZE * 3);
  const d = imageData.data;
  for (let p = 0, n = totalW * rows * MAP_SIZE; p < n; p++) {
    const a = d[p * 4 + 3] / 255;
    full[p * 3]     = Math.round(d[p * 4]     * a);
    full[p * 3 + 1] = Math.round(d[p * 4 + 1] * a);
    full[p * 3 + 2] = Math.round(d[p * 4 + 2] * a);
  }
  return splitIntoTilesRgb(full, totalW, cols, rows);
}

/** Nearest-palette preview frame for one packed-RGB tile. */
export function quantizeRgbTile(rgb: Uint8Array): Uint8Array {
  const out = new Uint8Array(MAP_BYTES);
  for (let p = 0; p < MAP_BYTES; p++) {
    out[p] = quantizeRgbPixel(rgb[p * 3], rgb[p * 3 + 1], rgb[p * 3 + 2]);
  }
  return out;
}

/** 3-bytes-per-pixel twin of quantize.ts splitIntoTiles. */
export function splitIntoTilesRgb(full: Uint8Array, totalW: number, cols: number, rows: number): Uint8Array[] {
  const tiles: Uint8Array[] = [];
  for (let tileRow = 0; tileRow < rows; tileRow++) {
    for (let tileCol = 0; tileCol < cols; tileCol++) {
      const tile = new Uint8Array(RGB_TILE_BYTES);
      for (let ty = 0; ty < MAP_SIZE; ty++) {
        const srcOff = (tileCol * MAP_SIZE + (tileRow * MAP_SIZE + ty) * totalW) * 3;
        tile.set(full.subarray(srcOff, srcOff + MAP_SIZE * 3), ty * MAP_SIZE * 3);
      }
      tiles.push(tile);
    }
  }
  return tiles;
}

/** 3-bytes-per-pixel twin of quantize.ts flattenTiles. */
export function flattenTilesRgb(tiles: Uint8Array[], out: Uint8Array, cols: number, rows: number): void {
  const totalW = cols * MAP_SIZE;
  for (let tileRow = 0; tileRow < rows; tileRow++) {
    for (let tileCol = 0; tileCol < cols; tileCol++) {
      const tile = tiles[tileRow * cols + tileCol];
      for (let ty = 0; ty < MAP_SIZE; ty++) {
        out.set(
          tile.subarray(ty * MAP_SIZE * 3, (ty + 1) * MAP_SIZE * 3),
          (tileCol * MAP_SIZE + (tileRow * MAP_SIZE + ty) * totalW) * 3,
        );
      }
    }
  }
}

/** Reconstruct an RGBA ImageData of the whole composition from packed-RGB tile frames. */
export function rgbFramesToImageData(
  rgbFrames: Uint8Array[][], activeFrame: number, cols: number, rows: number,
): ImageData {
  const totalW = cols * MAP_SIZE, totalH = rows * MAP_SIZE;
  const img = new ImageData(totalW, totalH);
  for (let tr = 0; tr < rows; tr++) {
    for (let tc = 0; tc < cols; tc++) {
      const tile = rgbFrames[tr * cols + tc]?.[activeFrame];
      if (!tile) continue;
      for (let ty = 0; ty < MAP_SIZE; ty++) {
        for (let tx = 0; tx < MAP_SIZE; tx++) {
          const s = (ty * MAP_SIZE + tx) * 3;
          const d = ((tr * MAP_SIZE + ty) * totalW + tc * MAP_SIZE + tx) * 4;
          img.data[d] = tile[s]; img.data[d + 1] = tile[s + 1]; img.data[d + 2] = tile[s + 2];
          img.data[d + 3] = 255;
        }
      }
    }
  }
  return img;
}
