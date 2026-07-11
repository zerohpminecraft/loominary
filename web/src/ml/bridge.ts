/**
 * EditorBridge — the narrow surface the ML features use to read editor state and
 * apply results. Keeping this interface small means the giant Editor component
 * only has to construct one object, and every ML feature stays decoupled from
 * the editor's internals (and unit-testable with a fake bridge).
 */

import type { CompositionState } from '../payload-state.js';
import type { RequantizeParams } from '../quantize.js';
import { MAP_BYTES } from '../quantize.js';

export interface EditorBridge {
  getComp(): CompositionState;
  /**
   * Full-resolution source bitmap for the active frame, or null when no source
   * is retained (e.g. stolen maps) — features then operate on the 128-grid and
   * warn about reduced quality.
   */
  getSourceBitmap(): ImageBitmap | null;
  getSelMask(): Uint8Array | null;
  setSelMask(mask: Uint8Array | null): void;
  getReqParams(): RequantizeParams;
  /** Show a non-destructive preview composition (null clears it). */
  showPreview(comp: CompositionState | null): void;
  /** Commit a composition: snapshots undo history, then applies + recomputes stats. */
  commit(comp: CompositionState): void;
  setStatus(msg: string): void;
}

/**
 * Produce a new CompositionState that replaces the active frame's tiles with
 * `tiles`, leaving every other frame untouched. Used by features that re-derive
 * the visible frame (background removal, denoise, pixelize re-import).
 */
export function withActiveFrameTiles(comp: CompositionState, tiles: Uint8Array[]): CompositionState {
  const frames = comp.frames.map((tileFrames, ti) =>
    tileFrames.map((frame, fi) => (fi === comp.activeFrame ? (tiles[ti] ?? frame) : frame)),
  );
  return { ...comp, frames };
}

/** Deep-ish clone of just the active frame's tile bytes (for in-place edits). */
export function cloneActiveTiles(comp: CompositionState): Uint8Array[] {
  return comp.frames.map((tf) => {
    const f = tf[comp.activeFrame];
    return f ? f.slice() : new Uint8Array(MAP_BYTES);
  });
}
