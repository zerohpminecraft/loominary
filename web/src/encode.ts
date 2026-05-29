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
  toBytesV2, toBytesAnimated, crc32,
  FLAG_ALL_SHADES, FLAG_ANIMATED,
} from './manifest.js';
import {
  emptyTileData, emptyPayloadState,
  type TileData, type PayloadState,
} from './payload-state.js';
import type { CodecMode } from './codec-mode.js';
import type { CompositionState } from './payload-state.js';

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
}

// ─── Single-tile encoder ───────────────────────────────────────────────────────

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
  const frame0     = frames[0] ?? new Uint8Array(MAP_BYTES);

  // ── Manifest flags ─────────────────────────────────────────────────────────
  let flags = 0;
  if (allShades)      flags |= FLAG_ALL_SHADES;
  if (frameCount > 1) flags |= FLAG_ANIMATED;

  const colorCrc32 = crc32(frame0);

  // ── Manifest bytes ─────────────────────────────────────────────────────────
  let manifestBytes: Uint8Array;
  if (frameCount > 1) {
    const delays = frameDelays.length >= frameCount
      ? frameDelays.slice(0, frameCount)
      : new Array(frameCount).fill(frameDelays[0] ?? 100);
    manifestBytes = toBytesAnimated(
      flags, gridCols, gridRows, tileCol, tileRow,
      colorCrc32, opts.author, opts.title, opts.nonce,
      frameCount, 0, delays,
    );
  } else {
    manifestBytes = toBytesV2(
      flags, gridCols, gridRows, tileCol, tileRow,
      colorCrc32, opts.author, opts.title, opts.nonce,
    );
  }

  // ── Combine manifest + frames ──────────────────────────────────────────────
  const combined = new Uint8Array(manifestBytes.length + frameCount * MAP_BYTES);
  combined.set(manifestBytes, 0);
  for (let f = 0; f < frameCount; f++) {
    const src = frames[f] ?? new Uint8Array(MAP_BYTES);
    combined.set(
      src.length >= MAP_BYTES ? src.subarray(0, MAP_BYTES) : src,
      manifestBytes.length + f * MAP_BYTES,
    );
  }

  // ── Compress ────────────────────────────────────────────────────────────────
  const compressed = await compress(combined);

  // ── Encode for storage ─────────────────────────────────────────────────────
  // BANNER: CJK banner chunks (used by AnvilAutoFillHandler in the mod).
  // CARPET*: carpetCompressedB64 only — the carpet channel is encoded by
  //          schematic export, not stored in the state file.
  const chunks             = codecMode === 'BANNER' ? buildChunks(compressed) : [];
  const carpetCompressedB64 = codecMode !== 'BANNER' ? toBase64(compressed) : null;

  // Normalise frameDelays for storage
  const storedDelays = frameCount > 1
    ? (frameDelays.length >= frameCount ? frameDelays.slice(0, frameCount) : new Array(frameCount).fill(100))
    : null; // null → default 100 ms

  return {
    ...emptyTileData(),
    chunks,
    nonce:              opts.nonce,
    frameCount,
    frameDelays:        storedDelays,
    carpetEncoded:      codecMode !== 'BANNER',
    carpetCompressedB64,
  };
}

// ─── Composition encoder ───────────────────────────────────────────────────────

/**
 * Encode every tile in `comp` and return a complete PayloadState ready for
 * JSON download.  Tiles are encoded in parallel for speed.
 */
export async function encodeComposition(
  comp: CompositionState,
  opts: ExportOptions,
): Promise<PayloadState> {
  const { gridCols, gridRows, allShades } = comp;
  const codecMode = opts.codecMode ?? comp.codecMode;

  const tiles = await Promise.all(
    Array.from({ length: gridCols * gridRows }, (_, ti) => {
      const tileCol    = ti % gridCols;
      const tileRow    = Math.floor(ti / gridCols);
      const frames     = comp.frames[ti]     ?? [new Uint8Array(MAP_BYTES)];
      const delays     = comp.frameDelays[ti] ?? new Array(frames.length).fill(100);
      return encodeTile(
        frames, delays, tileCol, tileRow,
        gridCols, gridRows, allShades, codecMode, opts,
      );
    }),
  );

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
