/**
 * Payload state management — port of PayloadState.java.
 *
 * In the web editor, there is no "active tile" concept.  All tiles are always
 * in memory together.  The JSON schema is kept identical to the mod's
 * loominary_state.json for round-trip compatibility.
 */

import type { CodecMode } from './codec-mode.js';
import { DEFAULT_CODEC } from './codec-mode.js';

const STORAGE_KEY = 'loominary_state';

// ─── TileData ─────────────────────────────────────────────────────────────────

export interface TileData {
  /** Banner name chunks (hex-indexed CJK strings or overflow). */
  chunks: string[];
  /** Next chunk index to rename (progress cursor; written as 0 by web tool). */
  currentIndex: number;
  /** v2 manifest nonce; 0 = no nonce. */
  nonce: number;
  frameCount: number;
  /** null → single global 100ms delay. */
  frameDelays: number[] | null;
  /** frameSourceIndices[editorFrameIdx] = originalGifFrameIdx. */
  frameSourceIndices: number[] | null;
  /** True when this tile uses the carpet-hybrid encoding. */
  carpetEncoded: boolean;
  /** True when this tile uses the LOOM-header format. */
  loomEncoded: boolean;
  /** Full zstd payload as base64 (for preview / re-export without physical carpet). */
  carpetCompressedB64: string | null;
  muxed: boolean;
  muxReceiver: boolean;
  isDonorOnly: boolean;
  muxCargoB64: string | null;
}

// ─── PayloadState ─────────────────────────────────────────────────────────────

export interface PayloadState {
  sourceFilename: string | null;
  columns: number;
  rows: number;
  /** Always 0 when written by web tool (no active-tile concept). */
  activeTileIndex: number;
  allShades: boolean;
  dither: boolean;
  title: string | null;
  codecMode: CodecMode;
  authorOverride: string | null;
  whitelist: string[];
  tiles: TileData[];
}

// ─── Composition state (in-memory, editor-facing) ─────────────────────────────

/**
 * The editor's primary data model.  Separate from PayloadState to avoid
 * coupling the hot pixel-editing path to the serialization schema.
 */
export interface CompositionState {
  gridCols: number;
  gridRows: number;
  /** [tileIdx][frameIdx] = Uint8Array of 16384 map-colour bytes. */
  frames: Uint8Array[][];
  /** [tileIdx][frameIdx] = delay in ms. */
  frameDelays: number[][];
  /** Currently displayed frame index (all tiles advance together). */
  activeFrame: number;
  sourceFilename: string | null;
  title: string | null;
  author: string | null;
  allShades: boolean;
  codecMode: CodecMode;
}

// ─── Defaults ─────────────────────────────────────────────────────────────────

export function emptyTileData(): TileData {
  return {
    chunks: [],
    currentIndex: 0,
    nonce: 0,
    frameCount: 1,
    frameDelays: null,
    frameSourceIndices: null,
    carpetEncoded: false,
    loomEncoded: false,
    carpetCompressedB64: null,
    muxed: false,
    muxReceiver: false,
    isDonorOnly: false,
    muxCargoB64: null,
  };
}

export function emptyPayloadState(cols = 1, rows = 1): PayloadState {
  return {
    sourceFilename: null,
    columns: cols,
    rows: rows,
    activeTileIndex: 0,
    allShades: false,
    dither: false,
    title: null,
    codecMode: DEFAULT_CODEC,
    authorOverride: null,
    whitelist: [],
    tiles: Array.from({ length: cols * rows }, emptyTileData),
  };
}

// ─── localStorage persistence ─────────────────────────────────────────────────

export function saveState(state: PayloadState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state, null, 2));
  } catch (e) {
    console.error('[Loominary] Failed to save state:', e);
  }
}

export function loadState(): PayloadState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as PayloadState;
  } catch (e) {
    console.error('[Loominary] Failed to load state:', e);
    return null;
  }
}

export function clearState(): void {
  localStorage.removeItem(STORAGE_KEY);
}

// ─── File import / export ─────────────────────────────────────────────────────

/**
 * Trigger a download of the current state as `loominary_state.json`.
 * The file is byte-for-byte compatible with the mod's config dir.
 */
export function downloadState(state: PayloadState, filename = 'loominary_state.json'): void {
  const json = JSON.stringify(state, null, 2);
  const blob = new Blob([json], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

/**
 * Parse an uploaded loominary_state.json file.
 */
export async function importStateFile(file: File): Promise<PayloadState> {
  const text = await file.text();
  return JSON.parse(text) as PayloadState;
}

// ─── Composition ↔ PayloadState conversion ────────────────────────────────────

/**
 * Build a CompositionState from a loaded PayloadState + per-tile pixel data.
 * The pixel data must be supplied separately (decoded from carpetCompressedB64
 * or assembled from chunks by the caller).
 */
export function compositionFromState(
  ps: PayloadState,
  pixelData: Uint8Array[][],  // [tileIdx][frameIdx] = Uint8Array[16384]
): CompositionState {
  const frameDelays: number[][] = ps.tiles.map((t, ti) => {
    const count = pixelData[ti]?.length ?? 1;
    if (!t.frameDelays) return new Array(count).fill(100);
    if (t.frameDelays.length === 1) return new Array(count).fill(t.frameDelays[0]);
    return t.frameDelays.slice(0, count);
  });

  return {
    gridCols: ps.columns,
    gridRows: ps.rows,
    frames: pixelData,
    frameDelays,
    activeFrame: 0,
    sourceFilename: ps.sourceFilename,
    title: ps.title,
    author: ps.authorOverride,
    allShades: ps.allShades,
    codecMode: ps.codecMode,
  };
}
