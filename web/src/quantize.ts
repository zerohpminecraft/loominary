/**
 * Image quantization to Minecraft map-colour bytes.
 *
 * Port of PngToMapColors.java.  The primary entry point for the editor is
 * `convertTwoPassGrid()` which processes the full grid in a single pass so
 * that dither error diffuses naturally across tile seams.
 *
 * All functions operate on RGBA ImageData (browser Canvas API) rather than
 * Java BufferedImage, but the algorithms are unchanged.
 */

import { MC_PALETTE, IS_VALID, IS_LEGAL } from './palette.js';
import { rgbToOklab, oklabDistSq } from './oklab.js';

// ─── Constants ───────────────────────────────────────────────────────────────

export const MAP_SIZE  = 128;
export const MAP_BYTES = MAP_SIZE * MAP_SIZE; // 16384

/** Squared OKLab error below which diffusion is suppressed. */
const ERROR_FLOOR_SQ = 0.015 * 0.015;

const BAYER_MATRIX: readonly number[][] = [
  [ 0,  8,  2, 10],
  [12,  4, 14,  6],
  [ 3, 11,  1,  9],
  [15,  7, 13,  5],
] as const;
const BAYER_SCALE = 0.08;

// ─── Palette helpers ──────────────────────────────────────────────────────────

export type PaletteFlag = Uint8Array; // length 256, non-zero = in palette

/** Build a palette flag array from an existing map-colour tile. */
export function buildPaletteFromTile(mapColors: Uint8Array): PaletteFlag {
  const p = new Uint8Array(256);
  for (let i = 0; i < mapColors.length; i++) p[mapColors[i]] = 1;
  return p;
}

/** Full legal palette (shades 0–2, colorIds 1–61). */
export function fullLegalPalette(): PaletteFlag {
  return IS_LEGAL.slice();
}

/** Full all-shades palette (includes unobtainable shade 3). */
export function fullAllShadesPalette(): PaletteFlag {
  return IS_VALID.slice();
}

// ─── OKLab lookup table ───────────────────────────────────────────────────────

/** Pre-computed OKLab [L, a, b] for every valid map byte. Null entries = invalid. */
export type OklabLookup = Array<[number, number, number] | null>;

export function buildOklabLookup(): OklabLookup {
  const out: OklabLookup = new Array(256).fill(null);
  for (let b = 1; b < 256; b++) {
    if (!IS_VALID[b]) continue;
    const p = MC_PALETTE[b];
    out[b] = rgbToOklab((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff);
  }
  return out;
}

// ─── Nearest-match in palette ─────────────────────────────────────────────────

/** Find the map byte in `palette` with the smallest OKLab distance to (L,a,b). */
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

/** Find the nearest palette neighbour of `color` (excluding itself). */
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

// ─── Dither algorithms ────────────────────────────────────────────────────────

export const DitherAlgo = {
  NONE:     'NONE',
  FS:       'FS',       // Floyd-Steinberg
  ATKINSON: 'ATKINSON',
  BAYER:    'BAYER',
} as const;
export type DitherAlgo = typeof DitherAlgo[keyof typeof DitherAlgo];

// ─── Two-pass grid quantization (primary path) ────────────────────────────────

export interface QuantizeParams {
  legalOnly:    boolean;   // restrict to obtainable shades 0–2
  targetColors: number;    // 0 = no limit; >0 = reduce to this many
  dither:       DitherAlgo;
  diffuseAmount?: number;  // FS / Atkinson error scale (default 1.0)
  bayerScale?:    number;  // Bayer threshold amplitude (default BAYER_SCALE)
}

/**
 * Full two-pass quantization.  Operates on the entire multi-tile grid image
 * as a unit so that palette, dither strength, and error diffusion are
 * consistent across tile seams.
 *
 * @param imageData  Source image already scaled to `cols*128 × rows*128` pixels.
 * @param cols       Grid columns.
 * @param rows       Grid rows.
 * @returns  `Uint8Array[cols*rows]` — one 16384-byte tile array per tile, row-major.
 */
export function convertTwoPassGrid(
  imageData: ImageData,
  cols: number,
  rows: number,
  params: QuantizeParams,
): Uint8Array[] {
  const totalW = cols * MAP_SIZE;
  const totalH = rows * MAP_SIZE;
  const oklab  = buildOklabLookup();
  const palette: PaletteFlag = params.legalOnly ? IS_LEGAL.slice() : IS_VALID.slice();

  // Pass 1: nearest-neighbor to build global colour set.
  const firstPass = new Uint8Array(totalW * totalH);
  for (let y = 0; y < totalH; y++) {
    for (let x = 0; x < totalW; x++) {
      const i  = (y * totalW + x) * 4;
      const d  = imageData.data;
      const alpha = d[i + 3];
      if (alpha < 128) { firstPass[y * totalW + x] = 0; continue; }
      const [L, a, b] = rgbToOklab(d[i], d[i + 1], d[i + 2]);
      firstPass[y * totalW + x] = findClosestInPalette(L, a, b, palette, oklab);
    }
  }

  // Palette reduction pass.
  if (params.targetColors > 0 && countDistinct(firstPass) > params.targetColors) {
    reduceColorsInPlace(firstPass, params.targetColors, oklab);
  }

  const activePalette = buildPaletteFromTile(firstPass);

  // Pass 2: render with chosen dither algorithm.
  let fullResult: Uint8Array;
  switch (params.dither) {
    case 'FS': {
      const strength = computeDitherStrength(imageData, totalW, totalH, oklab);
      fullResult = renderDithered(imageData, activePalette, oklab, strength, totalW, totalH,
        params.diffuseAmount ?? 1.0);
      break;
    }
    case 'ATKINSON':
      fullResult = renderAtkinson(imageData, activePalette, oklab, totalW, totalH,
        params.diffuseAmount ?? 1.0);
      break;
    case 'BAYER':
      fullResult = renderBayer4x4(imageData, activePalette, oklab, totalW, totalH,
        params.bayerScale ?? BAYER_SCALE);
      break;
    default:
      fullResult = renderNearest(imageData, activePalette, oklab, totalW, totalH);
  }

  return splitIntoTiles(fullResult, totalW, cols, rows);
}

/** Single-tile convenience wrapper (1×1 grid). */
export function convertSingleTile(
  imageData: ImageData,
  params: QuantizeParams,
): Uint8Array {
  return convertTwoPassGrid(imageData, 1, 1, params)[0];
}

// ─── Re-quantize a selection ──────────────────────────────────────────────────

/**
 * Re-quantize a sub-region of an existing grid from a source image.
 *
 * The quantization runs on the full source image (to keep dither calibration
 * global), but only pixels covered by `selMask` are written back to `current`.
 *
 * @param current   Existing flat pixel array (gridW × gridH, one byte per pixel).
 * @param source    Source image scaled to gridW × gridH.
 * @param selMask   Selection mask of size gridW × gridH (0 = not selected).
 * @param gridW     gridCols * 128
 * @param gridH     gridRows * 128
 * @returns New flat pixel array with selected pixels re-quantized.
 */
export function requantizeSelection(
  current: Uint8Array,
  source: ImageData,
  selMask: Uint8Array | null,
  gridW: number,
  gridH: number,
  params: QuantizeParams,
): Uint8Array {
  const cols = gridW / MAP_SIZE;
  const rows = gridH / MAP_SIZE;
  const tiles = convertTwoPassGrid(source, cols, rows, params);

  // Flatten the per-tile result back to a single grid array.
  const full = new Uint8Array(gridW * gridH);
  flattenTiles(tiles, full, cols, rows);

  if (!selMask) return full;

  const result = current.slice();
  for (let i = 0; i < result.length; i++) {
    if (selMask[i]) result[i] = full[i];
  }
  return result;
}

// ─── Render passes ────────────────────────────────────────────────────────────

function renderNearest(
  img: ImageData, palette: PaletteFlag, oklab: OklabLookup,
  width: number, height: number,
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d = img.data;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      if (d[si + 3] < 128) { out[y * width + x] = 0; continue; }
      const [L, a, b] = rgbToOklab(d[si], d[si + 1], d[si + 2]);
      out[y * width + x] = findClosestInPalette(L, a, b, palette, oklab);
    }
  }
  return out;
}

function renderDithered(
  img: ImageData, palette: PaletteFlag, oklab: OklabLookup,
  ditherStrength: Float32Array,
  width: number, height: number,
  diffuseAmount: number,
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d = img.data;
  let errCur = new Float32Array(width * 3);
  let errNxt = new Float32Array(width * 3);

  for (let y = 0; y < height; y++) {
    // Swap row buffers.
    const tmp = errCur; errCur = errNxt; errNxt = tmp;
    errNxt.fill(0);

    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      if (d[si + 3] < 128) { out[y * width + x] = 0; continue; }

      const [sL, sa, sb] = rgbToOklab(d[si], d[si + 1], d[si + 2]);
      const ei = x * 3;
      const L = sL + errCur[ei    ];
      const a = sa + errCur[ei + 1];
      const b = sb + errCur[ei + 2];

      const chosen = findClosestInPalette(L, a, b, palette, oklab);
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
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d = img.data;
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
      const L = sL + errCur[ei];
      const a = sa + errCur[ei + 1];
      const b = sb + errCur[ei + 2];

      const chosen = findClosestInPalette(L, a, b, palette, oklab);
      out[y * width + x] = chosen;

      const cl = oklab[chosen]!;
      const sc = diffuseAmount / 8;
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
): Uint8Array {
  const out = new Uint8Array(width * height);
  const d = img.data;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 4;
      if (d[si + 3] < 128) { out[y * width + x] = 0; continue; }
      const [L, a, b] = rgbToOklab(d[si], d[si + 1], d[si + 2]);
      const t = (BAYER_MATRIX[y & 3][x & 3] + 0.5) / 16.0 - 0.5;
      const offset = t * bayerScale;
      out[y * width + x] = findClosestInPalette(L + offset, a + offset, b + offset, palette, oklab);
    }
  }
  return out;
}

// ─── Adaptive dither strength (Otsu) ─────────────────────────────────────────

export function computeDitherStrength(
  img: ImageData,
  width: number,
  height: number,
  oklab: OklabLookup,
): Float32Array {
  const total = width * height;
  const d = img.data;

  const okL = new Float32Array(total);
  const okA = new Float32Array(total);
  const okB = new Float32Array(total);
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

  const T = otsuThreshold(contrast);
  const lower = T * 0.5, upper = T * 1.5, range = upper - lower;

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
  const N = values.length;
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

/**
 * Reduce the distinct colour count in `mapColors` to `targetColors` in-place.
 * Returns `[colorsRemoved, pixelsAffected]`.
 */
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

/** Split a flat grid pixel array into per-tile 16384-byte arrays. */
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

/** Inverse of splitIntoTiles: write tiles back into a flat grid array. */
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

/** Scale an ImageBitmap to a target size using a canvas (bicubic via browser). */
export async function scaleImage(
  bitmap: ImageBitmap,
  targetW: number,
  targetH: number,
): Promise<ImageData> {
  const canvas = new OffscreenCanvas(targetW, targetH);
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bitmap, 0, 0, targetW, targetH);
  return ctx.getImageData(0, 0, targetW, targetH);
}
