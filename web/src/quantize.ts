/**
 * Image quantization to Minecraft map-colour bytes.
 *
 * Port of PngToMapColors.java.  The primary entry points:
 *   convertTwoPassGrid()  — import path: full grid in one seamless pass
 *   requantizeGrid()      — editor R key: re-quantize with full param set
 */

import { MC_PALETTE, IS_VALID, IS_LEGAL } from './palette.js';
import { rgbToOklab, oklabToLinearRgb, oklabDistSq, srgbToLinear, linearToSrgb } from './oklab.js';

// ─── Constants ───────────────────────────────────────────────────────────────

export const MAP_SIZE  = 128;
export const MAP_BYTES = MAP_SIZE * MAP_SIZE; // 16384

/** Squared OKLab error below which FS diffusion is suppressed. */
const ERROR_FLOOR_SQ = 0.015 * 0.015;

/** Squared OKLab chroma below which a colour is considered achromatic (for HUE_ONLY). */
const ACHROMATIC_SQ = 0.04 * 0.04;

const BAYER_MATRIX: readonly number[][] = [
  [ 0,  8,  2, 10],
  [12,  4, 14,  6],
  [ 3, 11,  1,  9],
  [15,  7, 13,  5],
] as const;
const DEFAULT_BAYER_SCALE = 0.08;

// ─── Enums ────────────────────────────────────────────────────────────────────

export const DitherAlgo = {
  NONE:     'NONE',
  FS:       'FS',       // Floyd-Steinberg
  ATKINSON: 'ATKINSON',
  BAYER:    'BAYER',
} as const;
export type DitherAlgo = typeof DitherAlgo[keyof typeof DitherAlgo];

/** Distance metric used during nearest-colour matching.
 *
 *  OKLAB        — standard perceptual Euclidean (default)
 *  CHROMA_FIRST — 4× weight on a,b components (pushes saturated palette use)
 *  LUMA_FIRST   — 4× weight on L (preserves luminosity over hue)
 *  HUE_ONLY     — angular hue distance in OKLab a-b plane; achromatic falls back to OKLab
 *  RGB          — sRGB Euclidean (matches what most colour pickers show)
 */
export const MatchMetric = {
  OKLAB:        'OKLAB',
  CHROMA_FIRST: 'CHROMA_FIRST',
  LUMA_FIRST:   'LUMA_FIRST',
  HUE_ONLY:     'HUE_ONLY',
  RGB:          'RGB',
} as const;
export type MatchMetric = typeof MatchMetric[keyof typeof MatchMetric];

// ─── Lookup table types ───────────────────────────────────────────────────────

export type PaletteFlag    = Uint8Array;                                    // length 256, non-zero = in palette
export type OklabLookup    = Array<[number, number, number] | null>;        // [L, a, b] per map-byte
export type LinearRgbLookup = Array<[number, number, number] | null>;       // [r, g, b] linear per map-byte

// ─── Palette helpers ──────────────────────────────────────────────────────────

export function buildPaletteFromTile(mapColors: Uint8Array): PaletteFlag {
  const p = new Uint8Array(256);
  for (let i = 0; i < mapColors.length; i++) p[mapColors[i]] = 1;
  p[0] = 0;
  return p;
}

export function fullLegalPalette(): PaletteFlag  { return IS_LEGAL.slice(); }
export function fullAllShadesPalette(): PaletteFlag { return IS_VALID.slice(); }

// ─── OKLab lookup ────────────────────────────────────────────────────────────

export function buildOklabLookup(): OklabLookup {
  const out: OklabLookup = new Array(256).fill(null);
  for (let b = 1; b < 256; b++) {
    if (!IS_VALID[b]) continue;
    const p = MC_PALETTE[b];
    out[b] = rgbToOklab((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff);
  }
  return out;
}

// ─── Linear RGB lookup (for RGB metric) ──────────────────────────────────────

export function buildLinearRgbLookup(): LinearRgbLookup {
  const out: LinearRgbLookup = new Array(256).fill(null);
  for (let b = 1; b < 256; b++) {
    if (!IS_VALID[b]) continue;
    const p = MC_PALETTE[b];
    out[b] = [
      srgbToLinear(((p >> 16) & 0xff) / 255),
      srgbToLinear(((p >>  8) & 0xff) / 255),
      srgbToLinear(( p        & 0xff) / 255),
    ];
  }
  return out;
}

// ─── Palette hues (for HUE_ONLY metric) ──────────────────────────────────────

export function buildPaletteHues(palette: PaletteFlag, oklab: OklabLookup): Float32Array {
  const hues = new Float32Array(256);
  for (let c = 1; c < 256; c++) {
    if (!palette[c] || !oklab[c]) continue;
    hues[c] = Math.atan2(oklab[c]![2], oklab[c]![1]);
  }
  return hues;
}

// ─── Nearest-match in palette ─────────────────────────────────────────────────

/** OKLAB-only nearest match (fast path). */
export function findClosestInPalette(
  L: number, a: number, b: number,
  palette: PaletteFlag,
  oklab: OklabLookup,
): number {
  let bestDist = Infinity;
  let bestByte = 0;
  for (let c = 1; c < 256; c++) {
    if (!palette[c]) continue;
    const entry = oklab[c];
    if (!entry) continue;
    const d = oklabDistSq(L, a, b, entry[0], entry[1], entry[2]);
    if (d < bestDist) { bestDist = d; bestByte = c; }
  }
  return bestByte;
}

/** Metric-aware nearest match.  Falls through to the fast path for OKLAB. */
export function findClosestInPaletteWithMetric(
  L: number, a: number, b: number,
  palette:  PaletteFlag,
  oklab:    OklabLookup,
  metric:   MatchMetric,
  rgbLookup: LinearRgbLookup | null,
  palHues:   Float32Array | null,
): number {
  if (metric === 'OKLAB') return findClosestInPalette(L, a, b, palette, oklab);

  let bestDist = Infinity;
  let bestByte = 0;

  const inputChromaSq = a * a + b * b;
  const inputHue      = metric === 'HUE_ONLY' ? Math.atan2(b, a) : 0;
  const inputLinRgb   = metric === 'RGB' ? oklabToLinearRgb(L, a, b) : null;

  for (let c = 1; c < 256; c++) {
    if (!palette[c]) continue;
    const entry = oklab[c];
    if (!entry) continue;

    const [cL, ca, cb] = entry;
    const dL = L - cL, da = a - ca, db = b - cb;

    let dist: number;
    if (metric === 'CHROMA_FIRST') {
      dist = dL * dL + 4 * da * da + 4 * db * db;
    } else if (metric === 'LUMA_FIRST') {
      dist = 4 * dL * dL + da * da + db * db;
    } else if (metric === 'HUE_ONLY') {
      const palChromaSq = ca * ca + cb * cb;
      if (inputChromaSq < ACHROMATIC_SQ) {
        dist = dL * dL + da * da + db * db;
      } else if (palChromaSq < ACHROMATIC_SQ) {
        dist = 2.01;
      } else {
        const ph = palHues ? palHues[c] : Math.atan2(cb, ca);
        let hDiff = inputHue - ph;
        if (hDiff >  Math.PI) hDiff -= 2 * Math.PI;
        if (hDiff < -Math.PI) hDiff += 2 * Math.PI;
        dist = 1.0 - Math.cos(hDiff);
      }
    } else if (metric === 'RGB') {
      const rl = rgbLookup?.[c];
      if (!rl || !inputLinRgb) {
        dist = dL * dL + da * da + db * db;
      } else {
        const dr = inputLinRgb[0] - rl[0];
        const dg = inputLinRgb[1] - rl[1];
        const db2 = inputLinRgb[2] - rl[2];
        dist = dr * dr + dg * dg + db2 * db2;
      }
    } else {
      dist = dL * dL + da * da + db * db;
    }

    if (dist < bestDist) { bestDist = dist; bestByte = c; }
  }
  return bestByte;
}

// ─── Chroma boost ─────────────────────────────────────────────────────────────

/**
 * Scale OKLab a,b (chroma) components of every pixel in `img` by `boost`.
 * Values > 1 saturate; < 1 desaturate.  Out-of-gamut results are clamped.
 */
export function boostChromaImageData(img: ImageData, boost: number): ImageData {
  if (boost === 1) return img;
  const out = new ImageData(img.width, img.height);
  const src = img.data, dst = out.data;
  for (let i = 0; i < src.length; i += 4) {
    const alpha = src[i + 3];
    dst[i + 3] = alpha;
    if (alpha < 128) continue;
    const [L, aOk, bOk] = rgbToOklab(src[i], src[i + 1], src[i + 2]);
    const [rl, gl, bl] = oklabToLinearRgb(L, aOk * boost, bOk * boost);
    dst[i]     = Math.round(Math.min(1, Math.max(0, linearToSrgb(rl))) * 255);
    dst[i + 1] = Math.round(Math.min(1, Math.max(0, linearToSrgb(gl))) * 255);
    dst[i + 2] = Math.round(Math.min(1, Math.max(0, linearToSrgb(bl))) * 255);
  }
  return out;
}

// ─── Params ───────────────────────────────────────────────────────────────────

/** Parameters for the image-import path (convertTwoPassGrid). */
export interface QuantizeParams {
  legalOnly:     boolean;
  targetColors:  number;           // 0 = no limit
  dither:        DitherAlgo;
  diffuseAmount?: number;          // FS / Atkinson error scale (default 1.0)
  bayerScale?:    number;
  metric?:        MatchMetric;     // default OKLAB
  chromaBoost?:   number;          // default 1.0
}

/** Full parameter set for the editor requantize (R key). */
export interface RequantizeParams {
  legalOnly:        boolean;
  dither:           DitherAlgo;
  metric:           MatchMetric;
  fsStrength:       number;        // FS error diffusion scale 0.1–1.0
  atkStrength:      number;        // Atkinson error diffusion scale 0.1–1.0
  bayerScale:       number;        // Bayer threshold amplitude 0.02–0.20
  chromaBoost:      number;        // OKLab a,b multiplier 0.25–4.0 (1.0 = off)
  tilePalette:      boolean;       // restrict palette to colors already in the tile
  useCustomDither:  boolean;       // use ditherMask for per-pixel FS strength
  ditherMask:       Float32Array | null; // per-pixel strength (gridW*gridH); null = all 1.0
}

export const DEFAULT_REQ_PARAMS: RequantizeParams = {
  legalOnly:       true,
  dither:          'FS',
  metric:          'OKLAB',
  fsStrength:      1.0,
  atkStrength:     1.0,
  bayerScale:      DEFAULT_BAYER_SCALE,
  chromaBoost:     1.0,
  tilePalette:     false,
  useCustomDither: false,
  ditherMask:      null,
};

// ─── Two-pass grid quantization (import path) ─────────────────────────────────

/**
 * Full two-pass quantization on the entire multi-tile grid.
 * Seamless across tile seams (dither error diffuses grid-wide).
 */
export function convertTwoPassGrid(
  imageData: ImageData,
  cols: number,
  rows: number,
  params: QuantizeParams,
): Uint8Array[] {
  const totalW = cols * MAP_SIZE;
  const totalH = rows * MAP_SIZE;
  const metric  = params.metric ?? 'OKLAB';
  const boost   = params.chromaBoost ?? 1;
  const oklab   = buildOklabLookup();
  const palette: PaletteFlag = params.legalOnly ? IS_LEGAL.slice() : IS_VALID.slice();

  const src = boost !== 1 ? boostChromaImageData(imageData, boost) : imageData;

  // Pass 1: nearest-neighbour to build global colour set.
  const firstPass = new Uint8Array(totalW * totalH);
  for (let y = 0; y < totalH; y++) {
    for (let x = 0; x < totalW; x++) {
      const i = (y * totalW + x) * 4;
      const d = src.data;
      if (d[i + 3] < 128) { firstPass[y * totalW + x] = 0; continue; }
      const [L, a, b] = rgbToOklab(d[i], d[i + 1], d[i + 2]);
      firstPass[y * totalW + x] = findClosestInPaletteWithMetric(L, a, b, palette, oklab, metric, null, null);
    }
  }

  // Palette reduction pass.
  if (params.targetColors > 0 && countDistinct(firstPass) > params.targetColors) {
    reduceColorsInPlace(firstPass, params.targetColors, oklab);
  }

  const activePalette = buildPaletteFromTile(firstPass);

  // Pass 2: dither.
  let fullResult: Uint8Array;
  switch (params.dither) {
    case 'FS': {
      const strength = computeDitherStrength(src, totalW, totalH, oklab);
      fullResult = renderDithered(src, activePalette, oklab, strength, totalW, totalH,
        params.diffuseAmount ?? 1.0, metric);
      break;
    }
    case 'ATKINSON':
      fullResult = renderAtkinson(src, activePalette, oklab, totalW, totalH,
        params.diffuseAmount ?? 1.0, metric);
      break;
    case 'BAYER':
      fullResult = renderBayer4x4(src, activePalette, oklab, totalW, totalH,
        params.bayerScale ?? DEFAULT_BAYER_SCALE, metric);
      break;
    default:
      fullResult = renderNearest(src, activePalette, oklab, totalW, totalH, metric);
  }

  return splitIntoTiles(fullResult, totalW, cols, rows);
}

export function convertSingleTile(imageData: ImageData, params: QuantizeParams): Uint8Array {
  return convertTwoPassGrid(imageData, 1, 1, params)[0];
}

// ─── Re-quantize (editor R key) ───────────────────────────────────────────────

/**
 * Reconstruct an RGBA ImageData from the current map-byte grid.
 * Used when no source image is available — re-quantizes from the visible appearance.
 */
export function mapBytesToImageData(
  frames: Uint8Array[][],
  activeFrame: number,
  cols: number,
  rows: number,
): ImageData {
  const totalW = cols * MAP_SIZE;
  const totalH = rows * MAP_SIZE;
  const img    = new ImageData(totalW, totalH);
  const d      = img.data;
  for (let tileRow = 0; tileRow < rows; tileRow++) {
    for (let tileCol = 0; tileCol < cols; tileCol++) {
      const ti    = tileRow * cols + tileCol;
      const frame = frames[ti]?.[Math.min(activeFrame, (frames[ti]?.length ?? 1) - 1)];
      if (!frame) continue;
      for (let ly = 0; ly < MAP_SIZE; ly++) {
        for (let lx = 0; lx < MAP_SIZE; lx++) {
          const mapByte = frame[lx + ly * MAP_SIZE];
          const gx = tileCol * MAP_SIZE + lx;
          const gy = tileRow * MAP_SIZE + ly;
          const di = (gy * totalW + gx) * 4;
          if (mapByte === 0) {
            d[di + 3] = 0;
          } else {
            const rgb = MC_PALETTE[mapByte];
            d[di]     = (rgb >> 16) & 0xff;
            d[di + 1] = (rgb >>  8) & 0xff;
            d[di + 2] =  rgb        & 0xff;
            d[di + 3] = 255;
          }
        }
      }
    }
  }
  return img;
}

/**
 * Extract a single 128×128 tile's worth of ImageData from the full grid image.
 */
function extractTileImageData(full: ImageData, tileCol: number, tileRow: number, cols: number): ImageData {
  const tile  = new ImageData(MAP_SIZE, MAP_SIZE);
  const totalW = cols * MAP_SIZE;
  for (let y = 0; y < MAP_SIZE; y++) {
    const srcBase = ((tileRow * MAP_SIZE + y) * totalW + tileCol * MAP_SIZE) * 4;
    tile.data.set(full.data.subarray(srcBase, srcBase + MAP_SIZE * 4), y * MAP_SIZE * 4);
  }
  return tile;
}

/**
 * Re-quantize the full grid (or selection) with the given parameters.
 *
 * Returns a new flat tile array (one `Uint8Array` per tile, 16384 bytes each)
 * representing the result for `comp.activeFrame`.  Tiles outside `selMask`
 * are untouched (original data is returned as-is).
 *
 * `source` must already be scaled to `gridCols*128 × gridRows*128`.
 */
export function requantizeGrid(
  source: ImageData,
  comp: { gridCols: number; gridRows: number; frames: Uint8Array[][]; activeFrame: number },
  selMask: Uint8Array | null,
  p: RequantizeParams,
): Uint8Array[] {
  const { gridCols: cols, gridRows: rows } = comp;
  const totalW = cols * MAP_SIZE;
  const totalH = rows * MAP_SIZE;
  const oklab  = buildOklabLookup();
  const palette: PaletteFlag = p.legalOnly ? IS_LEGAL.slice() : IS_VALID.slice();

  const src = p.chromaBoost !== 1 ? boostChromaImageData(source, p.chromaBoost) : source;

  // Build extra lookup tables needed for some metrics.
  const rgbLookup = p.metric === 'RGB'      ? buildLinearRgbLookup() : null;

  // Decide whether to use the fast seamless grid path or per-tile.
  // Seamless grid FS only works for OKLAB + no tilePalette + no custom dither mask.
  const useGridPass = p.dither === 'FS'
    && p.metric === 'OKLAB'
    && !p.tilePalette
    && !p.useCustomDither;

  // ── Seamless grid FS path ───────────────────────────────────────────────
  if (useGridPass) {
    // First pass: build the global palette.
    const firstPass = new Uint8Array(totalW * totalH);
    for (let y = 0; y < totalH; y++) {
      for (let x = 0; x < totalW; x++) {
        const i = (y * totalW + x) * 4;
        const d = src.data;
        if (d[i + 3] < 128) { firstPass[y * totalW + x] = 0; continue; }
        const [L, a, b] = rgbToOklab(d[i], d[i + 1], d[i + 2]);
        firstPass[y * totalW + x] = findClosestInPalette(L, a, b, palette, oklab);
      }
    }
    const activePalette = buildPaletteFromTile(firstPass);

    // Dither strength: custom mask if provided, else Otsu-adaptive.
    let strength: Float32Array;
    if (p.useCustomDither && p.ditherMask) {
      strength = p.ditherMask;
    } else {
      strength = computeDitherStrength(src, totalW, totalH, oklab);
    }

    const full    = renderDithered(src, activePalette, oklab, strength, totalW, totalH, p.fsStrength, 'OKLAB');
    const newTiles = splitIntoTiles(full, totalW, cols, rows);

    return applySelectionMask(newTiles, comp.frames, comp.activeFrame, selMask, cols, rows);
  }

  // ── Per-tile path (all other algo/metric combinations) ─────────────────
  const result: Uint8Array[] = [];

  for (let tileRow = 0; tileRow < rows; tileRow++) {
    for (let tileCol = 0; tileCol < cols; tileCol++) {
      const ti      = tileRow * cols + tileCol;
      const tileImg = extractTileImageData(src, tileCol, tileRow, cols);

      // Check whether any selected pixels exist in this tile (if selection active).
      const localSel = selMask ? localSelMaskForTile(selMask, tileCol, tileRow, cols) : null;
      if (selMask && !localSel) {
        // Nothing selected in this tile — return existing data unchanged.
        result.push(comp.frames[ti]?.[comp.activeFrame]?.slice() ?? new Uint8Array(MAP_BYTES));
        continue;
      }

      // Build palette.
      let tilePalette: PaletteFlag;
      if (p.tilePalette) {
        // Use only colours already present in the tile.
        const existingFrame = comp.frames[ti]?.[comp.activeFrame];
        const src2 = existingFrame ?? new Uint8Array(MAP_BYTES);
        tilePalette = buildPaletteFromTile(selectionFilteredColors(src2, localSel));
        // Fall back to full palette if tile has no colours yet.
        if (!tilePalette.some(v => v)) tilePalette = palette;
      } else {
        // Build from first-pass NN on this tile.
        const firstPassTile = new Uint8Array(MAP_BYTES);
        for (let y = 0; y < MAP_SIZE; y++) {
          for (let x = 0; x < MAP_SIZE; x++) {
            const i = (y * MAP_SIZE + x) * 4;
            const d = tileImg.data;
            if (d[i + 3] < 128) { firstPassTile[y * MAP_SIZE + x] = 0; continue; }
            const [L, a, b] = rgbToOklab(d[i], d[i + 1], d[i + 2]);
            firstPassTile[y * MAP_SIZE + x] = findClosestInPaletteWithMetric(L, a, b, palette, oklab, p.metric, rgbLookup, null);
          }
        }
        tilePalette = buildPaletteFromTile(firstPassTile);
      }

      // Build metric helpers.
      const palHues = p.metric === 'HUE_ONLY' ? buildPaletteHues(tilePalette, oklab) : null;

      // Render.
      let rendered: Uint8Array;
      switch (p.dither) {
        case 'FS': {
          let sm: Float32Array;
          if (p.useCustomDither && p.ditherMask) {
            // Extract tile-local slice of the global dither mask.
            sm = extractTileMask(p.ditherMask, tileCol, tileRow, cols);
          } else {
            sm = new Float32Array(MAP_BYTES).fill(1);
          }
          rendered = renderDithered(tileImg, tilePalette, oklab, sm, MAP_SIZE, MAP_SIZE, p.fsStrength, p.metric, rgbLookup, palHues);
          break;
        }
        case 'ATKINSON':
          rendered = renderAtkinson(tileImg, tilePalette, oklab, MAP_SIZE, MAP_SIZE, p.atkStrength, p.metric, rgbLookup, palHues);
          break;
        case 'BAYER':
          rendered = renderBayer4x4(tileImg, tilePalette, oklab, MAP_SIZE, MAP_SIZE, p.bayerScale, p.metric, rgbLookup, palHues);
          break;
        default:
          rendered = renderNearest(tileImg, tilePalette, oklab, MAP_SIZE, MAP_SIZE, p.metric, rgbLookup, palHues);
      }

      // Blend back with existing data using selection mask.
      if (localSel) {
        const existing = comp.frames[ti]?.[comp.activeFrame] ?? new Uint8Array(MAP_BYTES);
        const blended  = existing.slice();
        for (let i = 0; i < MAP_BYTES; i++) {
          if (localSel[i]) blended[i] = rendered[i];
        }
        result.push(blended);
      } else {
        result.push(rendered);
      }
    }
  }
  return result;
}

// ─── Selection helpers ────────────────────────────────────────────────────────

/** Extract the 128×128 local selection mask for a tile (returns null if nothing selected). */
function localSelMaskForTile(
  globalSel: Uint8Array, tileCol: number, tileRow: number, cols: number,
): Uint8Array | null {
  const gridW = cols * MAP_SIZE;
  const local = new Uint8Array(MAP_BYTES);
  let any = false;
  for (let ly = 0; ly < MAP_SIZE; ly++) {
    for (let lx = 0; lx < MAP_SIZE; lx++) {
      const gx = tileCol * MAP_SIZE + lx;
      const gy = tileRow * MAP_SIZE + ly;
      if (globalSel[gy * gridW + gx]) {
        local[lx + ly * MAP_SIZE] = 1;
        any = true;
      }
    }
  }
  return any ? local : null;
}

/** Filter a map-byte array to only include colours in the selection. */
function selectionFilteredColors(mapColors: Uint8Array, sel: Uint8Array | null): Uint8Array {
  if (!sel) return mapColors;
  const out = new Uint8Array(MAP_BYTES);
  for (let i = 0; i < MAP_BYTES; i++) if (sel[i]) out[i] = mapColors[i];
  return out;
}

/** Apply a selection mask: only overwrite pixels that are selected. */
function applySelectionMask(
  newTiles:  Uint8Array[],
  oldFrames: Uint8Array[][],
  frameIdx:  number,
  selMask:   Uint8Array | null,
  cols:      number,
  rows:      number,
): Uint8Array[] {
  if (!selMask) return newTiles;
  const result: Uint8Array[] = [];
  for (let tileRow = 0; tileRow < rows; tileRow++) {
    for (let tileCol = 0; tileCol < cols; tileCol++) {
      const ti       = tileRow * cols + tileCol;
      const localSel = localSelMaskForTile(selMask, tileCol, tileRow, cols);
      if (!localSel) {
        result.push(oldFrames[ti]?.[frameIdx]?.slice() ?? new Uint8Array(MAP_BYTES));
        continue;
      }
      const existing = oldFrames[ti]?.[frameIdx] ?? new Uint8Array(MAP_BYTES);
      const blended  = existing.slice();
      for (let i = 0; i < MAP_BYTES; i++) {
        if (localSel[i]) blended[i] = newTiles[ti][i];
      }
      result.push(blended);
    }
  }
  return result;
}

/** Extract a tile-local 128×128 slice of the global dither mask. */
function extractTileMask(globalMask: Float32Array, tileCol: number, tileRow: number, cols: number): Float32Array {
  const gridW = cols * MAP_SIZE;
  const local = new Float32Array(MAP_BYTES);
  for (let ly = 0; ly < MAP_SIZE; ly++) {
    for (let lx = 0; lx < MAP_SIZE; lx++) {
      const gx = tileCol * MAP_SIZE + lx;
      const gy = tileRow * MAP_SIZE + ly;
      local[lx + ly * MAP_SIZE] = globalMask[gy * gridW + gx];
    }
  }
  return local;
}

// ─── Re-quantize a selection (simple single-pass wrapper) ────────────────────

export function requantizeSelection(
  current: Uint8Array,
  source: ImageData,
  selMask: Uint8Array | null,
  gridW: number,
  gridH: number,
  params: QuantizeParams,
): Uint8Array {
  const cols  = gridW / MAP_SIZE;
  const rows  = gridH / MAP_SIZE;
  const tiles = convertTwoPassGrid(source, cols, rows, params);
  const full  = new Uint8Array(gridW * gridH);
  flattenTiles(tiles, full, cols, rows);
  if (!selMask) return full;
  const result = current.slice();
  for (let i = 0; i < result.length; i++) if (selMask[i]) result[i] = full[i];
  return result;
}

// ─── Render passes ────────────────────────────────────────────────────────────

function renderNearest(
  img: ImageData, palette: PaletteFlag, oklab: OklabLookup,
  width: number, height: number,
  metric: MatchMetric = 'OKLAB',
  rgbLookup: LinearRgbLookup | null = null,
  palHues:   Float32Array | null = null,
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d   = img.data;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      if (d[si + 3] < 128) { out[y * width + x] = 0; continue; }
      const [L, a, b] = rgbToOklab(d[si], d[si + 1], d[si + 2]);
      out[y * width + x] = findClosestInPaletteWithMetric(L, a, b, palette, oklab, metric, rgbLookup, palHues);
    }
  }
  return out;
}

function renderDithered(
  img: ImageData, palette: PaletteFlag, oklab: OklabLookup,
  ditherStrength: Float32Array,
  width: number, height: number,
  diffuseAmount: number,
  metric: MatchMetric = 'OKLAB',
  rgbLookup: LinearRgbLookup | null = null,
  palHues:   Float32Array | null = null,
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d   = img.data;
  let errCur = new Float32Array(width * 3);
  let errNxt = new Float32Array(width * 3);

  for (let y = 0; y < height; y++) {
    const tmp = errCur; errCur = errNxt; errNxt = tmp;
    errNxt.fill(0);

    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      if (d[si + 3] < 128) { out[y * width + x] = 0; continue; }

      const [sL, sa, sb] = rgbToOklab(d[si], d[si + 1], d[si + 2]);
      const ei = x * 3;
      const L  = sL + errCur[ei    ];
      const a  = sa + errCur[ei + 1];
      const b  = sb + errCur[ei + 2];

      const chosen = findClosestInPaletteWithMetric(L, a, b, palette, oklab, metric, rgbLookup, palHues);
      out[y * width + x] = chosen;

      const cl = oklab[chosen]!;
      const eL = L - cl[0], ea = a - cl[1], eb = b - cl[2];
      const errMagSq = eL * eL + ea * ea + eb * eb;
      const s = (errMagSq > ERROR_FLOOR_SQ)
        ? ditherStrength[y * width + x] * diffuseAmount
        : 0;

      if (s > 0) {
        const seL = eL * s, sea = ea * s, seb = eb * s;
        if (x + 1 < width) {
          const ri = (x + 1) * 3;
          errCur[ri] += seL * (7/16); errCur[ri+1] += sea * (7/16); errCur[ri+2] += seb * (7/16);
        }
        if (x - 1 >= 0) {
          const li = (x - 1) * 3;
          errNxt[li] += seL * (3/16); errNxt[li+1] += sea * (3/16); errNxt[li+2] += seb * (3/16);
        }
        errNxt[ei] += seL * (5/16); errNxt[ei+1] += sea * (5/16); errNxt[ei+2] += seb * (5/16);
        if (x + 1 < width) {
          const ri = (x + 1) * 3;
          errNxt[ri] += seL * (1/16); errNxt[ri+1] += sea * (1/16); errNxt[ri+2] += seb * (1/16);
        }
      }
    }
  }
  return out;
}

function renderAtkinson(
  img: ImageData, palette: PaletteFlag, oklab: OklabLookup,
  width: number, height: number,
  diffuseAmount: number,
  metric: MatchMetric = 'OKLAB',
  rgbLookup: LinearRgbLookup | null = null,
  palHues:   Float32Array | null = null,
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d   = img.data;
  let errCur  = new Float32Array(width * 3);
  let errNxt  = new Float32Array(width * 3);
  let errNxt2 = new Float32Array(width * 3);

  for (let y = 0; y < height; y++) {
    const tmp = errCur; errCur = errNxt; errNxt = errNxt2; errNxt2 = tmp;
    errNxt2.fill(0);

    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      if (d[si + 3] < 128) { out[y * width + x] = 0; continue; }

      const [sL, sa, sb] = rgbToOklab(d[si], d[si + 1], d[si + 2]);
      const ei = x * 3;
      const L  = sL + errCur[ei];
      const a  = sa + errCur[ei + 1];
      const b  = sb + errCur[ei + 2];

      const chosen = findClosestInPaletteWithMetric(L, a, b, palette, oklab, metric, rgbLookup, palHues);
      out[y * width + x] = chosen;

      const cl  = oklab[chosen]!;
      const sc  = diffuseAmount / 8;
      const seL = (L - cl[0]) * sc;
      const sea = (a - cl[1]) * sc;
      const seb = (b - cl[2]) * sc;

      if (x + 1 < width) { const ri=(x+1)*3; errCur[ri]+=seL; errCur[ri+1]+=sea; errCur[ri+2]+=seb; }
      if (x + 2 < width) { const ri=(x+2)*3; errCur[ri]+=seL; errCur[ri+1]+=sea; errCur[ri+2]+=seb; }
      if (x - 1 >= 0)    { const li=(x-1)*3; errNxt[li]+=seL; errNxt[li+1]+=sea; errNxt[li+2]+=seb; }
      errNxt[ei]+=seL;  errNxt[ei+1]+=sea;  errNxt[ei+2]+=seb;
      if (x + 1 < width) { const ri=(x+1)*3; errNxt[ri]+=seL; errNxt[ri+1]+=sea; errNxt[ri+2]+=seb; }
      errNxt2[ei]+=seL; errNxt2[ei+1]+=sea; errNxt2[ei+2]+=seb;
    }
  }
  return out;
}

function renderBayer4x4(
  img: ImageData, palette: PaletteFlag, oklab: OklabLookup,
  width: number, height: number,
  bayerScale: number,
  metric: MatchMetric = 'OKLAB',
  rgbLookup: LinearRgbLookup | null = null,
  palHues:   Float32Array | null = null,
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d   = img.data;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      if (d[si + 3] < 128) { out[y * width + x] = 0; continue; }
      const [L, a, b] = rgbToOklab(d[si], d[si + 1], d[si + 2]);
      const t      = (BAYER_MATRIX[y & 3][x & 3] + 0.5) / 16.0 - 0.5;
      const offset = t * bayerScale;
      out[y * width + x] = findClosestInPaletteWithMetric(
        L + offset, a + offset, b + offset, palette, oklab, metric, rgbLookup, palHues);
    }
  }
  return out;
}

// ─── Adaptive dither strength (Otsu) ─────────────────────────────────────────

export function computeDitherStrength(
  img: ImageData,
  width: number,
  height: number,
  _oklab: OklabLookup,
): Float32Array {
  const total = width * height;
  const d     = img.data;

  const okL    = new Float32Array(total);
  const okA    = new Float32Array(total);
  const okB    = new Float32Array(total);
  const opaque = new Uint8Array(total);

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      const i  = y * width + x;
      if (d[si + 3] >= 128) {
        const [L, a, b] = rgbToOklab(d[si], d[si+1], d[si+2]);
        okL[i] = L; okA[i] = a; okB[i] = b;
        opaque[i] = 1;
      }
    }
  }

  const contrast = new Float32Array(total);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = y * width + x;
      if (!opaque[i]) continue;
      let sumSq = 0, n = 0;
      if (x > 0        && opaque[i - 1])     { const dL=okL[i]-okL[i-1],     da=okA[i]-okA[i-1],     db=okB[i]-okB[i-1];     sumSq+=dL*dL+da*da+db*db; n++; }
      if (x < width-1  && opaque[i + 1])     { const dL=okL[i]-okL[i+1],     da=okA[i]-okA[i+1],     db=okB[i]-okB[i+1];     sumSq+=dL*dL+da*da+db*db; n++; }
      if (y > 0        && opaque[i - width]) { const dL=okL[i]-okL[i-width], da=okA[i]-okA[i-width], db=okB[i]-okB[i-width]; sumSq+=dL*dL+da*da+db*db; n++; }
      if (y < height-1 && opaque[i + width]) { const dL=okL[i]-okL[i+width], da=okA[i]-okA[i+width], db=okB[i]-okB[i+width]; sumSq+=dL*dL+da*da+db*db; n++; }
      contrast[i] = n > 0 ? Math.sqrt(sumSq / n) : 0;
    }
  }

  const T      = otsuThreshold(contrast);
  const lower  = T * 0.5, upper = T * 1.5, range = upper - lower;

  const strength = new Float32Array(total);
  for (let i = 0; i < total; i++) {
    if (!opaque[i]) continue;
    strength[i] = range > 1e-6
      ? 1.0 - Math.min(1, Math.max(0, (contrast[i] - lower) / range))
      : (contrast[i] <= T ? 1.0 : 0.0);
  }
  return strength;
}

function otsuThreshold(values: Float32Array): number {
  const N      = values.length;
  const sorted = values.slice().sort();
  if (sorted[0] === sorted[N - 1]) return sorted[N >> 1];

  let totalSum = 0;
  for (let i = 0; i < N; i++) totalSum += sorted[i];

  let cumSum = 0;
  let bestVar = -1, threshold = sorted[N >> 1];
  for (let k = 1; k < N; k++) {
    cumSum += sorted[k - 1];
    const w0 = k / N, w1 = 1 - w0;
    const mu0 = cumSum / k, mu1 = (totalSum - cumSum) / (N - k);
    const betweenVar = w0 * w1 * (mu0 - mu1) * (mu0 - mu1);
    if (betweenVar > bestVar) { bestVar = betweenVar; threshold = sorted[k]; }
  }
  return threshold;
}

// ─── Palette reduction ────────────────────────────────────────────────────────

export const Strategy = {
  RAREST:   'RAREST',
  CLOSEST:  'CLOSEST',
  WEIGHTED: 'WEIGHTED',
} as const;
export type Strategy = typeof Strategy[keyof typeof Strategy];

export function reduceColorsInPlace(
  mapColors: Uint8Array,
  targetColors: number,
  oklab: OklabLookup,
  strategy: Strategy = 'RAREST',
): [number, number] {
  let colorsRemoved = 0, pixelsAffected = 0;
  while (countDistinct(mapColors) > targetColors) {
    const freq = new Int32Array(256);
    for (let i = 0; i < mapColors.length; i++) freq[mapColors[i]]++;

    let victim: number, survivor: number, victimFreq: number;

    if (strategy === 'RAREST') {
      let rarestColor = -1, rarestFreq = Infinity;
      for (let c = 1; c < 256; c++) {
        if (freq[c] > 0 && freq[c] < rarestFreq) { rarestFreq = freq[c]; rarestColor = c; }
      }
      if (rarestColor === -1) break;
      const neighbor = findNearestNeighbor(rarestColor, freq, oklab);
      if (neighbor === rarestColor) break;
      victim = rarestColor; survivor = neighbor; victimFreq = rarestFreq;
    } else {
      const pair = findClosestPairForMerge(freq, oklab, strategy);
      if (pair[0] === -1) break;
      victim = pair[0]; survivor = pair[1]; victimFreq = freq[victim];
    }

    for (let i = 0; i < mapColors.length; i++) {
      if (mapColors[i] === victim) mapColors[i] = survivor;
    }
    colorsRemoved++;
    pixelsAffected += victimFreq;
  }
  return [colorsRemoved, pixelsAffected];
}

function findNearestNeighbor(color: number, freq: Int32Array, oklab: OklabLookup): number {
  const entry = oklab[color];
  if (!entry) return color;
  let bestDist = Infinity, bestC = color;
  for (let c = 1; c < 256; c++) {
    if (c === color || freq[c] === 0) continue;
    const e = oklab[c];
    if (!e) continue;
    const d = oklabDistSq(entry[0], entry[1], entry[2], e[0], e[1], e[2]);
    if (d < bestDist) { bestDist = d; bestC = c; }
  }
  return bestC;
}

function findClosestPairForMerge(
  freq: Int32Array,
  oklab: OklabLookup,
  strategy: Strategy,
): [number, number] {
  let bestA = -1, bestB = -1, bestScore = Infinity;
  for (let a = 1; a < 256; a++) {
    if (!freq[a]) continue;
    const ea = oklab[a]; if (!ea) continue;
    for (let b = a + 1; b < 256; b++) {
      if (!freq[b]) continue;
      const eb = oklab[b]; if (!eb) continue;
      const distSq = oklabDistSq(ea[0], ea[1], ea[2], eb[0], eb[1], eb[2]);
      const score  = strategy === 'WEIGHTED' ? distSq / (freq[a] + freq[b]) : distSq;
      if (score < bestScore) { bestScore = score; bestA = a; bestB = b; }
    }
  }
  if (bestA === -1) return [-1, -1];
  const victim   = freq[bestA] <= freq[bestB] ? bestA : bestB;
  const survivor = freq[bestA] <= freq[bestB] ? bestB : bestA;
  return [victim, survivor];
}

// ─── Utility ──────────────────────────────────────────────────────────────────

export function countDistinct(mapColors: Uint8Array): number {
  const seen = new Uint8Array(256);
  for (let i = 0; i < mapColors.length; i++) seen[mapColors[i]] = 1;
  let n = 0;
  for (let c = 1; c < 256; c++) if (seen[c]) n++;
  return n;
}

export function splitIntoTiles(full: Uint8Array, totalW: number, cols: number, rows: number): Uint8Array[] {
  const tiles: Uint8Array[] = [];
  for (let tileRow = 0; tileRow < rows; tileRow++) {
    for (let tileCol = 0; tileCol < cols; tileCol++) {
      const tile = new Uint8Array(MAP_BYTES);
      for (let ty = 0; ty < MAP_SIZE; ty++) {
        const srcOff = tileCol * MAP_SIZE + (tileRow * MAP_SIZE + ty) * totalW;
        tile.set(full.subarray(srcOff, srcOff + MAP_SIZE), ty * MAP_SIZE);
      }
      tiles.push(tile);
    }
  }
  return tiles;
}

export function flattenTiles(tiles: Uint8Array[], out: Uint8Array, cols: number, rows: number): void {
  const totalW = cols * MAP_SIZE;
  for (let tileRow = 0; tileRow < rows; tileRow++) {
    for (let tileCol = 0; tileCol < cols; tileCol++) {
      const tile = tiles[tileRow * cols + tileCol];
      for (let ty = 0; ty < MAP_SIZE; ty++) {
        out.set(
          tile.subarray(ty * MAP_SIZE, (ty + 1) * MAP_SIZE),
          tileCol * MAP_SIZE + (tileRow * MAP_SIZE + ty) * totalW,
        );
      }
    }
  }
}

export async function scaleImage(bitmap: ImageBitmap, targetW: number, targetH: number): Promise<ImageData> {
  const canvas = new OffscreenCanvas(targetW, targetH);
  const ctx    = canvas.getContext('2d')!;
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bitmap, 0, 0, targetW, targetH);
  return ctx.getImageData(0, 0, targetW, targetH);
}
