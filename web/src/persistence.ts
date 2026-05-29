/**
 * Session persistence — saves and restores the active composition to IndexedDB
 * so that pixel edits are not lost on page reload or browser crash.
 *
 * Only the composition frames (pixel data) and import parameters are stored.
 * The source bitmap is not persisted (re-import if re-quantize from source is
 * needed after a reload).
 *
 * Auto-save fires 3 seconds after the last composition change.
 * On load, if a recent session exists it is silently restored and a dismissable
 * banner is shown.
 */

import type { CompositionState } from './payload-state.js';
import type { RequantizeParams }  from './quantize.js';
import type { PreprocessParams }  from './preprocess.js';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SavedSession {
  /** Bump when the format changes so stale sessions are ignored. */
  formatVersion: 1;
  savedAt:  number;   // Date.now()

  // Composition metadata (plain JSON)
  gridCols:      number;
  gridRows:      number;
  frameDelays:   number[][];
  activeFrame:   number;
  sourceFilename: string | null;
  title:         string | null;
  author:        string | null;
  allShades:     boolean;
  codecMode:     string;
  /** Number of animation frames per tile (length = numTiles). */
  frameCounts:   number[];

  /**
   * All tile-frame pixel data packed into one ArrayBuffer.
   * Layout (row-major tile order, frames in order):
   *   tile0_frame0 (16 384 B) | tile0_frame1 | … | tile1_frame0 | …
   */
  pixelData: ArrayBuffer;

  // Import parameters (so requantize from pixels still works after reload)
  cropMode:  'scale' | 'center';
  pre:       PreprocessParams;
  reqParams: RequantizeParams | null;
}

// ─── IndexedDB helpers ────────────────────────────────────────────────────────

const DB_NAME    = 'loominary';
const DB_VERSION = 1;
const STORE      = 'session';
const KEY        = 'current';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror  = () => reject(req.error);
  });
}

async function idbPut(value: SavedSession): Promise<void> {
  const db = await openDb();
  return new Promise((res, rej) => {
    const tx  = db.transaction(STORE, 'readwrite');
    tx.objectStore(STORE).put(value, KEY);
    tx.oncomplete = () => res();
    tx.onerror    = () => rej(tx.error);
  });
}

async function idbGet(): Promise<SavedSession | null> {
  const db = await openDb();
  return new Promise((res, rej) => {
    const tx  = db.transaction(STORE, 'readonly');
    const req = tx.objectStore(STORE).get(KEY);
    req.onsuccess = () => res((req.result as SavedSession) ?? null);
    req.onerror   = () => rej(req.error);
  });
}

async function idbDelete(): Promise<void> {
  const db = await openDb();
  return new Promise((res, rej) => {
    const tx = db.transaction(STORE, 'readwrite');
    tx.objectStore(STORE).delete(KEY);
    tx.oncomplete = () => res();
    tx.onerror    = () => rej(tx.error);
  });
}

// ─── Frame packing / unpacking ────────────────────────────────────────────────

const FRAME_BYTES = 128 * 128; // 16 384

function packFrames(frames: Uint8Array[][]): { pixelData: ArrayBuffer; frameCounts: number[] } {
  const frameCounts = frames.map(tf => tf.length);
  const totalFrames = frameCounts.reduce((s, c) => s + c, 0);
  const buf  = new ArrayBuffer(totalFrames * FRAME_BYTES);
  const dest = new Uint8Array(buf);
  let offset = 0;
  for (const tileFames of frames) {
    for (const frame of tileFames) {
      dest.set(frame.length === FRAME_BYTES ? frame : frame.subarray(0, FRAME_BYTES), offset);
      offset += FRAME_BYTES;
    }
  }
  return { pixelData: buf, frameCounts };
}

function unpackFrames(pixelData: ArrayBuffer, frameCounts: number[]): Uint8Array[][] {
  const src    = new Uint8Array(pixelData);
  const frames: Uint8Array[][] = [];
  let offset = 0;
  for (const count of frameCounts) {
    const tileFrames: Uint8Array[] = [];
    for (let f = 0; f < count; f++) {
      tileFrames.push(src.slice(offset, offset + FRAME_BYTES) as Uint8Array);
      offset += FRAME_BYTES;
    }
    frames.push(tileFrames);
  }
  return frames;
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Serialize a composition + import params and write to IndexedDB.
 * Safe to call frequently — errors are silently swallowed so a storage failure
 * never interrupts editing.
 */
export async function saveSession(
  comp:      CompositionState,
  cropMode:  'scale' | 'center',
  pre:       PreprocessParams,
  reqParams: RequantizeParams | null,
): Promise<void> {
  try {
    const { pixelData, frameCounts } = packFrames(comp.frames);
    const session: SavedSession = {
      formatVersion: 1,
      savedAt:       Date.now(),
      gridCols:      comp.gridCols,
      gridRows:      comp.gridRows,
      frameDelays:   comp.frameDelays,
      activeFrame:   comp.activeFrame,
      sourceFilename: comp.sourceFilename,
      title:         comp.title,
      author:        comp.author,
      allShades:     comp.allShades,
      codecMode:     comp.codecMode,
      frameCounts,
      pixelData,
      cropMode,
      pre,
      reqParams,
    };
    await idbPut(session);
  } catch {
    // Storage failure should never interrupt editing.
  }
}

/**
 * Load the most recent saved session from IndexedDB.
 * Returns null if nothing is stored or the format is stale.
 */
export async function loadSession(): Promise<SavedSession | null> {
  try {
    const s = await idbGet();
    if (!s || s.formatVersion !== 1) return null;
    return s;
  } catch {
    return null;
  }
}

/** Reconstruct a CompositionState from a SavedSession. */
export function sessionToComposition(s: SavedSession): CompositionState {
  return {
    gridCols:      s.gridCols,
    gridRows:      s.gridRows,
    frames:        unpackFrames(s.pixelData, s.frameCounts),
    frameDelays:   s.frameDelays,
    activeFrame:   s.activeFrame,
    sourceFilename: s.sourceFilename,
    title:         s.title,
    author:        s.author,
    allShades:     s.allShades,
    codecMode:     s.codecMode as import('./payload-state.js').CompositionState['codecMode'],
  };
}

/** Delete the stored session (call when the user deliberately starts fresh). */
export async function clearSession(): Promise<void> {
  try { await idbDelete(); } catch { /* ignore */ }
}

/** Human-readable "saved X ago" string. */
export function savedAgoLabel(savedAt: number): string {
  const s = Math.round((Date.now() - savedAt) / 1000);
  if (s < 60)   return 'just now';
  if (s < 3600) return Math.floor(s / 60)   + ' min ago';
  if (s < 86400)return Math.floor(s / 3600) + ' hr ago';
  return Math.floor(s / 86400) + ' days ago';
}
