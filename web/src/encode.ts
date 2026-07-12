/**
 * Tile encoding pipeline — inverse of decodeTile in App.tsx.
 *
 * Converts a CompositionState (in-memory pixel data) back into a PayloadState
 * (JSON-serialisable, mod-compatible) by:
 *   1. Building a v2/v4/v5 manifest header
 *   2. Concatenating it with all frame data
 *   3. Compressing with zstd
 *   4. For BANNER mode: CJK-encoding the compressed bytes into banner chunks
 *   5. For CARPET modes: storing carpetCompressedB64 for state roundtrip
 *      (actual carpet/shade channel placement is done by schematic export separately)
 */

import { compress }    from './compression.js';
import { buildChunks } from './cjk-codec.js';
import {
  toBytesV2, toBytesAnimated, toBytesV7, crc32,
  FLAG_ALL_SHADES, FLAG_ANIMATED, FLAG_AV1, FLAG_AV1_LOSSY, FLAG_AV1_COMPOSITE, FLAG2_SRGB,
} from './manifest.js';
import { encodeLosslessMono, encodeLossyColor, decodeLossyColor, encodeLossyRgb, decodeLossyRgb } from './av1/codec.js';
import {
  emptyTileData, emptyPayloadState, isSrgb,
  type TileData, type PayloadState,
} from './payload-state.js';
import type { CodecMode } from './codec-mode.js';
import type { CompositionState } from './payload-state.js';

const RGB_TILE_BYTES = 128 * 128 * 3; // 49 152

const MAP_BYTES = 128 * 128; // 16 384

// ─── Public types ──────────────────────────────────────────────────────────────

export interface ExportOptions {
  title:     string | null;
  author:    string | null;
  /** 0 = no nonce (v1 manifest).  Non-zero = v2 manifest with nonce. */
  nonce:     number;
  /** Player usernames allowed to decode (mod-side whitelist; ignored by web editor). */
  whitelist: string[];
  /** Overrides comp.codecMode when set. */
  codecMode?: CodecMode;
  /** When set (AV1 quantizer 1–63, lower = better), animated tiles may use LOSSY colour AV1. */
  lossyQuality?: number | null;
}

// ─── Shared tile payload encoder (AV1-or-raw) ───────────────────────────────────

export interface TilePayload {
  /** Final compressed bytes carried by the tile's channels (AV1 or raw, whichever is smaller). */
  compressed:    Uint8Array;
  /** The manifest header actually used (raw v2/v4 or AV1 v4 with FLAG_AV1). */
  manifestBytes: Uint8Array;
  /** True if the AV1 path was selected. */
  av1:           boolean;
  /** Decoded lossy frames (only when captureLossyFrames + lossy chosen) — for a truthful preview
   *  without a second encode. */
  lossyFrames?:  Uint8Array[];
  /** The raw lossy AV1 temporal-unit stream (when lossy chosen) — mux it to MP4 for a
   *  zero-re-encode preview / exported video. */
  lossyStream?:  Uint8Array;
  /** sRGB tiles: the decoded true-colour frames (packed RGB) matching {@link lossyFrames}. */
  lossyRgbFrames?: Uint8Array[];
}

/** Normalize per-frame delays to exactly `frameCount` entries (single [100] for static). */
function normalizeDelays(frameCount: number, frameDelays: number[]): number[] {
  return frameCount > 1
    ? (frameDelays.length >= frameCount
        ? frameDelays.slice(0, frameCount)
        : new Array(frameCount).fill(frameDelays[0] ?? 100))
    : [100];
}

/**
 * Assemble an AV1 tile payload: manifest + (v5 delay prefix) + stream.  When the manifest is v5
 * (per-frame delays that overflow the header) the delay table is stored as a PREFIX before the
 * AV1 stream — its trailing position isn't computable for a variable-length stream.
 */
function combineAv1Payload(
  manifest: Uint8Array, delays: number[], frameCount: number, stream: Uint8Array,
): Uint8Array {
  const prefix = manifest[0] >= 5 ? new Uint8Array(frameCount * 2) : new Uint8Array(0);
  if (prefix.length) {
    for (let f = 0; f < frameCount; f++) {
      const d = delays[f] ?? 100;
      prefix[f * 2] = (d >> 8) & 0xff; prefix[f * 2 + 1] = d & 0xff;
    }
  }
  const combined = new Uint8Array(manifest.length + prefix.length + stream.length);
  combined.set(manifest, 0);
  combined.set(prefix, manifest.length);
  combined.set(stream, manifest.length + prefix.length);
  return combined;
}

/** Raw-frames+zstd payload for one tile — the fallback floor every AV1 candidate must beat. */
async function encodeRawTilePayload(
  frames: Uint8Array[], frameDelays: number[],
  tileCol: number, tileRow: number, gridCols: number, gridRows: number, allShades: boolean,
  opts: { author: string | null; title: string | null; nonce: number },
): Promise<{ compressed: Uint8Array; manifestBytes: Uint8Array; flags: number; colorCrc32: number; delays: number[] }> {
  const frameCount = Math.max(1, frames.length);
  const frame0     = frames[0] ?? new Uint8Array(MAP_BYTES);

  let flags = 0;
  if (allShades)      flags |= FLAG_ALL_SHADES;
  if (frameCount > 1) flags |= FLAG_ANIMATED;
  const colorCrc32 = crc32(frame0);

  const delays = normalizeDelays(frameCount, frameDelays);
  const rawManifest = frameCount > 1
    ? toBytesAnimated(flags, gridCols, gridRows, tileCol, tileRow,
        colorCrc32, opts.author, opts.title, opts.nonce, frameCount, 0, delays)
    : toBytesV2(flags, gridCols, gridRows, tileCol, tileRow,
        colorCrc32, opts.author, opts.title, opts.nonce);

  const rawCombined = new Uint8Array(rawManifest.length + frameCount * MAP_BYTES);
  rawCombined.set(rawManifest, 0);
  for (let f = 0; f < frameCount; f++) {
    const src = frames[f] ?? new Uint8Array(MAP_BYTES);
    rawCombined.set(src.length >= MAP_BYTES ? src.subarray(0, MAP_BYTES) : src,
      rawManifest.length + f * MAP_BYTES);
  }
  return { compressed: await compress(rawCombined), manifestBytes: rawManifest, flags, colorCrc32, delays };
}

/**
 * Compute a tile's final compressed payload: manifest + frames, choosing lossless AV1 for
 * animated tiles when it comes out smaller than raw-frames+zstd.  This is the SINGLE source of
 * truth for a tile's compressed size — the export page's stats/budget/mux and the actual
 * encodeTile output both go through here so they never diverge.
 */
export async function encodeTilePayload(
  frames:      Uint8Array[],
  frameDelays: number[],
  tileCol:     number,
  tileRow:     number,
  gridCols:    number,
  gridRows:    number,
  allShades:   boolean,
  opts:        {
    author: string | null; title: string | null; nonce: number;
    lossyQuality?: number | null;
    /** Decode the chosen lossy stream and return the frames (for a reuse-the-encode preview). */
    captureLossyFrames?: boolean;
    /** Per-frame progress during the AV1 encode (phase = 'lossless' | 'lossy'). */
    onProgress?: (phase: string, done: number, total: number) => void;
  },
): Promise<TilePayload> {
  const frameCount = Math.max(1, frames.length);

  const raw = await encodeRawTilePayload(
    frames, frameDelays, tileCol, tileRow, gridCols, gridRows, allShades, opts);
  const { flags, colorCrc32, delays } = raw;
  let compressed    = raw.compressed;
  let manifestBytes = raw.manifestBytes;
  let av1           = false;
  let lossyFrames: Uint8Array[] | undefined;
  let lossyStreamOut: Uint8Array | undefined;

  // AV1 candidates for animated tiles.  The AV1 stream replaces the raw frame block.
  // Each candidate is kept only if it's smaller than the current best.
  if (frameCount > 1) {
    const frames16k = frames.map(f => {
      if (f.length === MAP_BYTES) return f;
      if (f.length > MAP_BYTES) return f.subarray(0, MAP_BYTES);
      const b = new Uint8Array(MAP_BYTES); b.set(f); return b;
    });

    const buildCombined = (manifest: Uint8Array, stream: Uint8Array): Uint8Array =>
      combineAv1Payload(manifest, delays, frameCount, stream);

    // Lossy and lossless are mutually exclusive: when the user opts into lossy we go straight to
    // it (raw is the only fallback floor) instead of also running the slower lossless-AV1 encode.
    if (opts.lossyQuality != null) {
      // Lossy colour: the user accepted approximate art for a much smaller payload.
      try {
        const lossyManifest = toBytesAnimated(flags | FLAG_AV1 | FLAG_AV1_LOSSY,
          gridCols, gridRows, tileCol, tileRow,
          colorCrc32, opts.author, opts.title, opts.nonce, frameCount, 0, delays);
        const lossyStream = await encodeLossyColor(frames16k, opts.lossyQuality, (d, t) => opts.onProgress?.('lossy', d, t));
        const lossyCompressed = await compress(buildCombined(lossyManifest, lossyStream));
        const won = lossyCompressed.length < compressed.length;
        console.info(`[av1] tile(${tileCol},${tileRow}) ${frameCount}f lossy(q${opts.lossyQuality})=${lossyCompressed.length} vs raw=${compressed.length} → ${won ? 'LOSSY' : 'raw'}`);
        if (won) {
          compressed = lossyCompressed; manifestBytes = lossyManifest; av1 = true;
          lossyStreamOut = lossyStream; // the AV1 video itself — mux to MP4 with no re-encode
          // Only decode when the caller needs exact frames (multi-tile composite / fidelity).
          if (opts.captureLossyFrames)
            lossyFrames = await decodeLossyColor(lossyStream, 0, frameCount, (d, t) => opts.onProgress?.('decode', d, t));
        }
      } catch (e) {
        console.warn(`[av1] tile(${tileCol},${tileRow}) lossy encode FAILED`, e);
      }
    } else {
      // Lossless monochrome (default): keep it only if it beats raw+zstd.
      try {
        const av1Manifest = toBytesAnimated(flags | FLAG_AV1, gridCols, gridRows, tileCol, tileRow,
          colorCrc32, opts.author, opts.title, opts.nonce, frameCount, 0, delays);
        const av1Stream = await encodeLosslessMono(frames16k, (d, t) => opts.onProgress?.('lossless', d, t));
        const av1Compressed = await compress(buildCombined(av1Manifest, av1Stream));
        const won = av1Compressed.length < compressed.length;
        console.info(`[av1] tile(${tileCol},${tileRow}) ${frameCount}f: raw=${compressed.length} av1=${av1Compressed.length} → ${won ? 'AV1' : 'raw'}`);
        if (won) { compressed = av1Compressed; manifestBytes = av1Manifest; av1 = true; }
      } catch (e) {
        console.warn(`[av1] tile(${tileCol},${tileRow}) lossless encode FAILED — using raw frames`, e);
      }
    }
  }
  return { compressed, manifestBytes, av1, lossyFrames, lossyStream: lossyStreamOut };
}

// ─── sRGB (full-colour) tile encoder ────────────────────────────────────────────

/**
 * Encode one sRGB tile: true-colour frames as a lossy AV1 colour stream under a v7 manifest
 * (FLAG2_SRGB).  There is NO raw floor — raw map bytes cannot represent sRGB art, so the AV1
 * stream ships unconditionally.  `previewFrames` are the nearest-palette twins (used only for
 * the informational manifest CRC).
 */
export async function encodeSrgbTilePayload(
  rgbFrames:     Uint8Array[],   // [frameIdx] = packed RGB, 49152 bytes
  previewFrames: Uint8Array[],   // [frameIdx] = quantized twins, 16384 bytes
  frameDelays:   number[],
  tileCol:       number,
  tileRow:       number,
  gridCols:      number,
  gridRows:      number,
  opts: {
    author: string | null; title: string | null; nonce: number;
    lossyQuality: number;
    captureLossyFrames?: boolean;
    onProgress?: (phase: string, done: number, total: number) => void;
  },
): Promise<TilePayload> {
  const frameCount = Math.max(1, rgbFrames.length);
  const delays = normalizeDelays(frameCount, frameDelays);

  let flags = FLAG_AV1 | FLAG_AV1_LOSSY;
  if (frameCount > 1) flags |= FLAG_ANIMATED;
  const colorCrc32 = crc32(previewFrames[0] ?? new Uint8Array(MAP_BYTES));

  const manifest = toBytesV7(flags, FLAG2_SRGB, gridCols, gridRows, tileCol, tileRow,
    colorCrc32, opts.author, opts.title, opts.nonce, frameCount, 0);
  const stream = await encodeLossyRgb(rgbFrames, opts.lossyQuality,
    (d, t) => opts.onProgress?.('lossy', d, t));
  const compressed = await compress(combineAv1Payload(manifest, delays, frameCount, stream));

  let lossyFrames: Uint8Array[] | undefined;
  let lossyRgbFrames: Uint8Array[] | undefined;
  if (opts.captureLossyFrames) {
    const dec = await decodeLossyRgb(stream, 0, frameCount, (d, t) => opts.onProgress?.('decode', d, t));
    lossyFrames = dec.idx;
    lossyRgbFrames = dec.rgb;
  }
  return { compressed, manifestBytes: manifest, av1: true,
           lossyFrames, lossyRgbFrames, lossyStream: stream };
}

// ─── Composition-wide lossy encoder (seamless multi-tile) ──────────────────────

/**
 * True when the composition can use composition-wide lossy encoding: multiple tiles, animated,
 * and every tile has the same frame count (one AV1 stream must cover all of them).
 */
export function compositeEligible(tileFrames: (Uint8Array[] | undefined)[], tilesTotal: number): boolean {
  if (tilesTotal < 2) return false;
  const fc = tileFrames[0]?.length ?? 1;
  if (fc < 2) return false;
  for (let ti = 1; ti < tilesTotal; ti++)
    if ((tileFrames[ti]?.length ?? 1) !== fc) return false;
  return true;
}

/**
 * sRGB variant: static grids qualify too (frameCount 1 is fine — a seam-free single-frame
 * stream is exactly what a static multi-tile photo wants).  Frame counts must still match.
 */
export function compositeEligibleSrgb(tileFrames: (Uint8Array[] | undefined)[], tilesTotal: number): boolean {
  if (tilesTotal < 2) return false;
  const fc = tileFrames[0]?.length ?? 1;
  for (let ti = 1; ti < tilesTotal; ti++)
    if ((tileFrames[ti]?.length ?? 1) !== fc) return false;
  return true;
}

/** Stitch per-tile 128×128 index frames into one (cols·128)×(rows·128) composition frame. */
export function stitchTiles(
  tileFrames: (Uint8Array[] | undefined)[], f: number, gridCols: number, gridRows: number,
): Uint8Array {
  const W = gridCols * 128;
  const out = new Uint8Array(W * gridRows * 128);
  for (let ti = 0; ti < gridCols * gridRows; ti++) {
    const frame = tileFrames[ti]?.[f];
    if (!frame) continue;
    const x0 = (ti % gridCols) * 128, y0 = Math.floor(ti / gridCols) * 128;
    for (let y = 0; y < 128; y++)
      out.set(frame.subarray(y * 128, y * 128 + 128), (y0 + y) * W + x0);
  }
  return out;
}

/** Crop one tile's 128×128 window out of a (cols·128)-wide composition frame. */
export function cropTile(frame: Uint8Array, tileCol: number, tileRow: number, gridCols: number): Uint8Array {
  const W = gridCols * 128;
  const out = new Uint8Array(MAP_BYTES);
  const x0 = tileCol * 128, y0 = tileRow * 128;
  for (let y = 0; y < 128; y++)
    out.set(frame.subarray((y0 + y) * W + x0, (y0 + y) * W + x0 + 128), y * 128);
  return out;
}

/** 3-bytes-per-pixel twin of {@link stitchTiles} for packed-RGB tile frames. */
export function stitchTilesRgb(
  tileFrames: (Uint8Array[] | undefined)[], f: number, gridCols: number, gridRows: number,
): Uint8Array {
  const W = gridCols * 128;
  const out = new Uint8Array(W * gridRows * 128 * 3);
  for (let ti = 0; ti < gridCols * gridRows; ti++) {
    const frame = tileFrames[ti]?.[f];
    if (!frame) continue;
    const x0 = (ti % gridCols) * 128, y0 = Math.floor(ti / gridCols) * 128;
    for (let y = 0; y < 128; y++)
      out.set(frame.subarray(y * 128 * 3, (y + 1) * 128 * 3), ((y0 + y) * W + x0) * 3);
  }
  return out;
}

/** 3-bytes-per-pixel twin of {@link cropTile}. */
export function cropTileRgb(frame: Uint8Array, tileCol: number, tileRow: number, gridCols: number): Uint8Array {
  const W = gridCols * 128;
  const out = new Uint8Array(RGB_TILE_BYTES);
  const x0 = tileCol * 128, y0 = tileRow * 128;
  for (let y = 0; y < 128; y++)
    out.set(frame.subarray(((y0 + y) * W + x0) * 3, ((y0 + y) * W + x0 + 128) * 3), y * 128 * 3);
  return out;
}

export interface CompositionLossyResult {
  tiles: TilePayload[];
  /** True when the composite stream beat the raw floor (tiles carry stream segments). */
  composite: boolean;
  /** Decoded truth per tile ([tile][frame]) — only when captureLossyFrames and composite. */
  lossyTileFrames?: Uint8Array[][];
  /** sRGB: the decoded true-colour twins of {@link lossyTileFrames} (packed RGB per tile frame). */
  lossyTileRgb?: Uint8Array[][];
  /** The whole-composition AV1 stream — mux to MP4 for a zero-re-encode preview. */
  stream?: Uint8Array;
}

/**
 * Composition-wide sRGB encode: like {@link encodeCompositionLossy} but the source frames are
 * true-colour and there is no raw floor — if the composite stream can't be built, each tile
 * falls back to its own per-tile sRGB stream ({@link encodeSrgbTilePayload}), never raw bytes.
 * Static grids (frameCount 1) are eligible: one seam-free stream is the whole point.
 */
export async function encodeCompositionSrgb(
  rgbTileFrames:     Uint8Array[][],  // [tile][frame], each 49152 bytes
  previewTileFrames: Uint8Array[][],  // [tile][frame], quantized twins (16384 bytes)
  tileDelays:        number[][],
  gridCols:          number,
  gridRows:          number,
  opts: {
    author: string | null; title: string | null; nonce: number;
    lossyQuality: number;
    captureLossyFrames?: boolean;
    onProgress?: (phase: string, done: number, total: number) => void;
  },
): Promise<CompositionLossyResult> {
  const tilesTotal = gridCols * gridRows;
  const frameCount = Math.max(1, rgbTileFrames[0]?.length ?? 1);

  const perTileFallback = async (): Promise<CompositionLossyResult> => {
    const tiles: TilePayload[] = [];
    for (let ti = 0; ti < tilesTotal; ti++) {
      tiles.push(await encodeSrgbTilePayload(
        rgbTileFrames[ti] ?? [new Uint8Array(RGB_TILE_BYTES)],
        previewTileFrames[ti] ?? [new Uint8Array(MAP_BYTES)],
        tileDelays[ti] ?? [100],
        ti % gridCols, Math.floor(ti / gridCols), gridCols, gridRows, opts));
    }
    return { tiles, composite: false };
  };

  try {
    const stitched: Uint8Array[] = [];
    for (let f = 0; f < frameCount; f++) stitched.push(stitchTilesRgb(rgbTileFrames, f, gridCols, gridRows));
    const dims = { width: gridCols * 128, height: gridRows * 128 };
    const stream = await encodeLossyRgb(stitched, opts.lossyQuality,
      (d, t) => opts.onProgress?.('lossy', d, t), dims);

    // Even split in tile-index order; the decoder reassembles by concatenation.
    const base = Math.floor(stream.length / tilesTotal);
    const rem  = stream.length % tilesTotal;
    let flagsBase = FLAG_AV1 | FLAG_AV1_LOSSY | FLAG_AV1_COMPOSITE;
    if (frameCount > 1) flagsBase |= FLAG_ANIMATED;

    const tiles: TilePayload[] = [];
    let off = 0;
    for (let ti = 0; ti < tilesTotal; ti++) {
      const segLen  = base + (ti < rem ? 1 : 0);
      const segment = stream.subarray(off, off + segLen);
      off += segLen;
      const tileCol = ti % gridCols, tileRow = Math.floor(ti / gridCols);
      const delays  = normalizeDelays(frameCount, tileDelays[ti] ?? [100]);
      const colorCrc32 = crc32(previewTileFrames[ti]?.[0] ?? new Uint8Array(MAP_BYTES));
      const manifest = toBytesV7(flagsBase, FLAG2_SRGB, gridCols, gridRows, tileCol, tileRow,
        colorCrc32, opts.author, opts.title, opts.nonce, frameCount, 0);
      const compressed = await compress(combineAv1Payload(manifest, delays, frameCount, segment));
      tiles.push({ compressed, manifestBytes: manifest, av1: true });
    }
    console.info(`[av1] srgb composite ${gridCols}x${gridRows} ${frameCount}f q${opts.lossyQuality}`
      + ` stream=${stream.length}B → ${tiles.reduce((s, t) => s + t.compressed.length, 0)}B compressed`);

    let lossyTileFrames: Uint8Array[][] | undefined;
    let lossyTileRgb:    Uint8Array[][] | undefined;
    if (opts.captureLossyFrames) {
      const dec = await decodeLossyRgb(stream, 0, frameCount,
        (d, t) => opts.onProgress?.('decode', d, t), dims);
      lossyTileFrames = Array.from({ length: tilesTotal }, (_, ti) =>
        dec.idx.map(fr => cropTile(fr, ti % gridCols, Math.floor(ti / gridCols), gridCols)));
      lossyTileRgb = Array.from({ length: tilesTotal }, (_, ti) =>
        dec.rgb.map(fr => cropTileRgb(fr, ti % gridCols, Math.floor(ti / gridCols), gridCols)));
    }
    return { tiles, composite: true, lossyTileFrames, lossyTileRgb, stream };
  } catch (e) {
    console.warn('[av1] srgb composite encode FAILED — falling back to per-tile sRGB streams', e);
    return perTileFallback();
  }
}

/**
 * Composition-wide lossy encode (FLAG_AV1_COMPOSITE): the whole composition is encoded as ONE
 * lossy AV1 stream at (cols·128)×(rows·128) — no per-tile block-artifact seams — and the stream
 * bytes are split evenly across the tiles.  Each tile's payload is its manifest + its segment;
 * segments concatenate in tile-index order on decode.  Falls back to raw frames+zstd per tile
 * when the composite total isn't smaller (same floor as the per-tile lossy path).
 *
 * The caller must have checked {@link compositeEligible} first.
 */
export async function encodeCompositionLossy(
  tileFrames:  Uint8Array[][],     // [tile][frame], each frame 16384 bytes
  tileDelays:  number[][],         // [tile][frame] delays in ms
  gridCols:    number,
  gridRows:    number,
  allShades:   boolean,
  opts: {
    author: string | null; title: string | null; nonce: number;
    lossyQuality: number;
    /** Decode the composite stream and return per-tile frames (truthful preview, no re-encode). */
    captureLossyFrames?: boolean;
    onProgress?: (phase: string, done: number, total: number) => void;
  },
): Promise<CompositionLossyResult> {
  const tilesTotal = gridCols * gridRows;
  const frameCount = tileFrames[0]?.length ?? 1;

  // Raw floor, per tile (identical to what the non-composite path would ship on fallback).
  const rawTiles: TilePayload[] = [];
  for (let ti = 0; ti < tilesTotal; ti++) {
    const raw = await encodeRawTilePayload(
      tileFrames[ti] ?? [new Uint8Array(MAP_BYTES)], tileDelays[ti] ?? [100],
      ti % gridCols, Math.floor(ti / gridCols), gridCols, gridRows, allShades, opts);
    rawTiles.push({ compressed: raw.compressed, manifestBytes: raw.manifestBytes, av1: false });
  }
  const rawTotal = rawTiles.reduce((s, t) => s + t.compressed.length, 0);

  try {
    const stitched: Uint8Array[] = [];
    for (let f = 0; f < frameCount; f++) stitched.push(stitchTiles(tileFrames, f, gridCols, gridRows));
    const dims = { width: gridCols * 128, height: gridRows * 128 };
    const stream = await encodeLossyColor(stitched, opts.lossyQuality, (d, t) => opts.onProgress?.('lossy', d, t), dims);

    // Even split in tile-index order; the decoder reassembles by concatenation.
    const base = Math.floor(stream.length / tilesTotal);
    const rem  = stream.length % tilesTotal;
    let flagsBase = FLAG_ANIMATED | FLAG_AV1 | FLAG_AV1_LOSSY | FLAG_AV1_COMPOSITE;
    if (allShades) flagsBase |= FLAG_ALL_SHADES;

    const tiles: TilePayload[] = [];
    let off = 0;
    for (let ti = 0; ti < tilesTotal; ti++) {
      const segLen  = base + (ti < rem ? 1 : 0);
      const segment = stream.subarray(off, off + segLen);
      off += segLen;
      const tileCol = ti % gridCols, tileRow = Math.floor(ti / gridCols);
      const delays  = normalizeDelays(frameCount, tileDelays[ti] ?? [100]);
      const colorCrc32 = crc32(tileFrames[ti]?.[0] ?? new Uint8Array(MAP_BYTES));
      const manifest = toBytesAnimated(flagsBase, gridCols, gridRows, tileCol, tileRow,
        colorCrc32, opts.author, opts.title, opts.nonce, frameCount, 0, delays);
      const compressed = await compress(combineAv1Payload(manifest, delays, frameCount, segment));
      tiles.push({ compressed, manifestBytes: manifest, av1: true });
    }
    const compositeTotal = tiles.reduce((s, t) => s + t.compressed.length, 0);

    const won = compositeTotal < rawTotal;
    console.info(`[av1] composite ${gridCols}x${gridRows} ${frameCount}f lossy(q${opts.lossyQuality})=${compositeTotal} vs raw=${rawTotal} → ${won ? 'COMPOSITE' : 'raw'}`);
    if (!won) return { tiles: rawTiles, composite: false };

    let lossyTileFrames: Uint8Array[][] | undefined;
    if (opts.captureLossyFrames) {
      const decoded = await decodeLossyColor(stream, 0, frameCount,
        (d, t) => opts.onProgress?.('decode', d, t), dims);
      lossyTileFrames = Array.from({ length: tilesTotal }, (_, ti) =>
        decoded.map(fr => cropTile(fr, ti % gridCols, Math.floor(ti / gridCols), gridCols)));
    }
    return { tiles, composite: true, lossyTileFrames, stream };
  } catch (e) {
    console.warn('[av1] composite lossy encode FAILED — using raw frames', e);
    return { tiles: rawTiles, composite: false };
  }
}

// ─── Single-tile encoder ───────────────────────────────────────────────────────

/** Wrap a tile's compressed payload into a mod-compatible TileData (banner chunks or carpet b64). */
export function payloadToTileData(
  compressed: Uint8Array, codecMode: CodecMode, nonce: number,
  frameCount: number, frameDelays: number[],
): TileData {
  const chunks              = codecMode === 'BANNER' ? buildChunks(compressed) : [];
  const carpetCompressedB64 = codecMode !== 'BANNER' ? toBase64(compressed) : null;
  const storedDelays = frameCount > 1
    ? (frameDelays.length >= frameCount ? frameDelays.slice(0, frameCount) : new Array(frameCount).fill(100))
    : null;
  return {
    ...emptyTileData(),
    chunks,
    nonce,
    frameCount,
    frameDelays:   storedDelays,
    carpetEncoded: codecMode !== 'BANNER',
    carpetCompressedB64,
  };
}

export async function encodeTile(
  frames:      Uint8Array[],   // [frameIdx] = Uint8Array[16384]
  frameDelays: number[],       // per-frame delays in ms
  tileCol:     number,
  tileRow:     number,
  gridCols:    number,
  gridRows:    number,
  allShades:   boolean,
  codecMode:   CodecMode,
  opts:        ExportOptions,
): Promise<TileData> {
  const frameCount = Math.max(1, frames.length);
  const { compressed } = await encodeTilePayload(
    frames, frameDelays, tileCol, tileRow, gridCols, gridRows, allShades, opts,
  );
  return payloadToTileData(compressed, codecMode, opts.nonce, frameCount, frameDelays);
}

// ─── Composition encoder ───────────────────────────────────────────────────────

/** Progress callback: overall = (tilesDone + frameDone/frameTotal) / tilesTotal. */
export type CompositionProgress = (info: {
  tilesDone: number; tilesTotal: number; phase: string; frameDone: number; frameTotal: number;
}) => void;

/**
 * Optional hooks: `encodePayload` offloads the heavy per-tile encode (e.g. to a Worker); when
 * given, tiles are encoded sequentially so progress is meaningful and one Worker suffices.
 */
export interface EncodeHooks {
  encodePayload?: (args: {
    frames: Uint8Array[]; frameDelays: number[]; tileCol: number; tileRow: number;
    gridCols: number; gridRows: number; allShades: boolean;
    author: string | null; title: string | null; nonce: number; lossyQuality?: number | null;
  }, onProgress?: (p: { phase: string; done: number; total: number }) => void) => Promise<TilePayload>;
  onProgress?: CompositionProgress;
}

/**
 * Encode every tile in `comp` and return a complete PayloadState ready for JSON download.
 * Without hooks, tiles encode in parallel (in-thread); with `encodePayload` they encode
 * sequentially (off-thread) and report progress.
 *
 * Multi-tile lossy animations take the composition-wide path (one seamless AV1 stream split
 * across the tiles) — the export page routes those through the worker's computeComposition
 * instead of the per-tile hooks, so `encodePayload` is not consulted for them here.
 */
export async function encodeComposition(
  comp: CompositionState,
  opts: ExportOptions,
  hooks?: EncodeHooks,
): Promise<PayloadState> {
  const { gridCols, gridRows, allShades } = comp;
  const codecMode = opts.codecMode ?? comp.codecMode;
  const tilesTotal = gridCols * gridRows;

  // sRGB compositions: AV1 colour streams only (no raw floor, hooks not consulted — the
  // encode is stream-per-composition or stream-per-tile either way).
  if (isSrgb(comp)) {
    const quality = opts.lossyQuality ?? 30;
    const rgbTiles = Array.from({ length: tilesTotal }, (_, ti) => comp.rgbFrames![ti] ?? [new Uint8Array(RGB_TILE_BYTES)]);
    const pvTiles  = Array.from({ length: tilesTotal }, (_, ti) => comp.frames[ti] ?? [new Uint8Array(MAP_BYTES)]);
    const tileDelays = Array.from({ length: tilesTotal }, (_, ti) =>
      comp.frameDelays[ti] ?? new Array(rgbTiles[ti].length).fill(100));
    const srgbOpts = {
      author: opts.author, title: opts.title, nonce: opts.nonce, lossyQuality: quality,
      onProgress: (phase: string, done: number, total: number) =>
        hooks?.onProgress?.({ tilesDone: 0, tilesTotal: 1, phase, frameDone: done, frameTotal: total }),
    };
    let payloads: { compressed: Uint8Array }[];
    if (compositeEligibleSrgb(comp.rgbFrames!, tilesTotal)) {
      payloads = (await encodeCompositionSrgb(rgbTiles, pvTiles, tileDelays, gridCols, gridRows, srgbOpts)).tiles;
    } else {
      payloads = [];
      for (let ti = 0; ti < tilesTotal; ti++) {
        payloads.push(await encodeSrgbTilePayload(rgbTiles[ti], pvTiles[ti], tileDelays[ti],
          ti % gridCols, Math.floor(ti / gridCols), gridCols, gridRows, srgbOpts));
      }
    }
    return assemblePayloadState(comp, opts, payloads);
  }

  // Composition-wide lossy: one stream, no per-tile seams, all-or-nothing decode.
  if (opts.lossyQuality != null && compositeEligible(comp.frames, tilesTotal)) {
    const tileFrames = Array.from({ length: tilesTotal }, (_, ti) => comp.frames[ti] ?? [new Uint8Array(MAP_BYTES)]);
    const tileDelays = Array.from({ length: tilesTotal }, (_, ti) =>
      comp.frameDelays[ti] ?? new Array(tileFrames[ti].length).fill(100));
    const result = await encodeCompositionLossy(tileFrames, tileDelays, gridCols, gridRows, allShades, {
      author: opts.author, title: opts.title, nonce: opts.nonce, lossyQuality: opts.lossyQuality,
      onProgress: (phase, done, total) =>
        hooks?.onProgress?.({ tilesDone: 0, tilesTotal: 1, phase, frameDone: done, frameTotal: total }),
    });
    return assemblePayloadState(comp, opts, result.tiles);
  }

  const tileArgs = (ti: number) => {
    const tileCol = ti % gridCols, tileRow = Math.floor(ti / gridCols);
    const frames  = comp.frames[ti]      ?? [new Uint8Array(MAP_BYTES)];
    const delays  = comp.frameDelays[ti] ?? new Array(frames.length).fill(100);
    return { frames, frameDelays: delays, tileCol, tileRow, gridCols, gridRows, allShades,
             author: opts.author, title: opts.title, nonce: opts.nonce, lossyQuality: opts.lossyQuality };
  };

  let tiles: TileData[];
  if (hooks?.encodePayload) {
    tiles = [];
    for (let ti = 0; ti < tilesTotal; ti++) {
      const a = tileArgs(ti);
      const payload = await hooks.encodePayload(a,
        p => hooks.onProgress?.({ tilesDone: ti, tilesTotal, phase: p.phase, frameDone: p.done, frameTotal: p.total }));
      tiles.push(payloadToTileData(payload.compressed, codecMode, opts.nonce, a.frames.length, a.frameDelays));
      hooks.onProgress?.({ tilesDone: ti + 1, tilesTotal, phase: 'tile', frameDone: 0, frameTotal: 0 });
    }
  } else {
    tiles = await Promise.all(Array.from({ length: tilesTotal }, (_, ti) => {
      const a = tileArgs(ti);
      return encodeTile(a.frames, a.frameDelays, a.tileCol, a.tileRow, gridCols, gridRows, allShades, codecMode, opts);
    }));
  }

  const ps           = emptyPayloadState(gridCols, gridRows);
  ps.title           = opts.title;
  ps.authorOverride  = opts.author;
  ps.allShades       = allShades;
  ps.codecMode       = codecMode;
  ps.tiles           = tiles;
  ps.sourceFilename  = comp.sourceFilename;
  ps.whitelist       = opts.whitelist;
  return ps;
}

/**
 * Build a PayloadState from already-encoded per-tile compressed payloads (e.g. reusing the export
 * page's compute result) — no re-encode.  Only valid when the compressed bytes were produced with
 * the SAME frames/metadata/nonce/codec (the caller checks).
 */
export function assemblePayloadState(
  comp: CompositionState, opts: ExportOptions, tilePayloads: { compressed: Uint8Array }[],
): PayloadState {
  const { gridCols, gridRows, allShades } = comp;
  const codecMode = opts.codecMode ?? comp.codecMode;
  const tiles = tilePayloads.map((tp, ti) => {
    const frames = comp.frames[ti]      ?? [new Uint8Array(MAP_BYTES)];
    const delays = comp.frameDelays[ti] ?? new Array(frames.length).fill(100);
    return payloadToTileData(tp.compressed, codecMode, opts.nonce, frames.length, delays);
  });
  const ps = emptyPayloadState(gridCols, gridRows);
  ps.title = opts.title; ps.authorOverride = opts.author; ps.allShades = allShades;
  ps.codecMode = codecMode; ps.tiles = tiles; ps.sourceFilename = comp.sourceFilename;
  ps.whitelist = opts.whitelist;
  return ps;
}

// ─── Helpers ───────────────────────────────────────────────────────────────────

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  // Process in chunks to avoid stack overflow on large arrays.
  const chunk = 8192;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}
