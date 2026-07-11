/**
 * Main-thread client for the tile encode worker.  Keeps a single worker, correlates requests by
 * id, and forwards frame-level progress.  Falls back to synchronous in-thread encoding if Workers
 * aren't available (e.g. some test environments).
 */

import { encodeTilePayload, type TilePayload } from '../encode.js';
import { computeComposition, type ComputeResult, type ComputeOpts } from './computeExport.js';
import type { CompositionState } from '../payload-state.js';

export interface EncodeProgress { phase: string; done: number; total: number }
export interface ComputeProgressMsg { tilesDone: number; tilesTotal: number; phase: string; frameDone: number; frameTotal: number }

export interface EncodeTileArgs {
  frames:      Uint8Array[];
  frameDelays: number[];
  tileCol:     number;
  tileRow:     number;
  gridCols:    number;
  gridRows:    number;
  allShades:   boolean;
  author:      string | null;
  title:       string | null;
  nonce:       number;
  lossyQuality?: number | null;
}

type Pending = {
  resolve: (v: unknown) => void;   // done-message payload; each caller extracts its fields
  reject:  (e: Error) => void;
  onProgress?: (m: unknown) => void; // raw progress message; each caller adapts it
};

let worker: Worker | null = null;
let nextId = 1;
const pending = new Map<number, Pending>();

function getWorker(): Worker | null {
  if (worker) return worker;
  if (typeof Worker === 'undefined') return null;
  worker = new Worker(new URL('./encodeWorker.ts', import.meta.url), { type: 'module' });
  worker.onmessage = (e: MessageEvent) => {
    const m = e.data;
    const p = pending.get(m.id);
    if (!p) return;
    if (m.type === 'progress') {
      p.onProgress?.(m);
    } else if (m.type === 'done') {
      pending.delete(m.id);
      p.resolve(m); // caller extracts the fields it needs
    } else if (m.type === 'error') {
      pending.delete(m.id);
      p.reject(new Error(m.message));
    }
  };
  worker.onerror = (e) => {
    for (const [, p] of pending) p.reject(new Error(`encode worker error: ${e.message}`));
    pending.clear();
  };
  return worker;
}

// The worker holds a SINGLE wasm encoder with global state, so requests must run one at a time.
// Serialize them here so overlapping callers (e.g. a debounced stats recompute racing an export)
// can't corrupt each other.
let chain: Promise<unknown> = Promise.resolve();

function dispatch(args: EncodeTileArgs, onProgress?: (p: EncodeProgress) => void): Promise<TilePayload> {
  const w = getWorker();
  if (!w) {
    // No Worker (tests): run inline.
    return encodeTilePayload(
      args.frames, args.frameDelays, args.tileCol, args.tileRow, args.gridCols, args.gridRows, args.allShades,
      { author: args.author, title: args.title, nonce: args.nonce, lossyQuality: args.lossyQuality,
        onProgress: (phase, done, total) => onProgress?.({ phase, done, total }) },
    );
  }
  const id = nextId++;
  return new Promise<TilePayload>((resolve, reject) => {
    pending.set(id, {
      resolve: (m: unknown) => resolve(m as TilePayload), reject,
      onProgress: onProgress && ((m) => { const x = m as EncodeProgress; onProgress({ phase: x.phase, done: x.done, total: x.total }); }),
    });
    w.postMessage({ id, kind: 'encode', ...args });
  });
}

/** Encode one tile, off the main thread when possible, reporting frame-level progress. */
export function encodeTileAsync(args: EncodeTileArgs, onProgress?: (p: EncodeProgress) => void): Promise<TilePayload> {
  const run = chain.then(() => dispatch(args, onProgress));
  // Keep the chain alive even if this request rejects.
  chain = run.then(() => undefined, () => undefined);
  return run;
}

/**
 * Encode the whole composition AND produce the preview in ONE off-thread pass (no duplicated
 * encode).  Returns tile payloads (for sizes/mux) + the preview GIF + lossy fidelity.
 */
export function computeCompositionAsync(
  comp: CompositionState, opts: ComputeOpts, onProgress?: (p: ComputeProgressMsg) => void,
): Promise<ComputeResult> {
  const w = getWorker();
  if (!w) return computeComposition(comp, opts, (td, tt, phase, fd, ft) => onProgress?.({ tilesDone: td, tilesTotal: tt, phase, frameDone: fd, frameTotal: ft }));
  const id = nextId++;
  const run = chain.then(() => new Promise<ComputeResult>((resolve, reject) => {
    pending.set(id, {
      resolve: (m: unknown) => resolve(m as ComputeResult), reject,
      onProgress: onProgress && ((m) => onProgress(m as ComputeProgressMsg)),
    });
    w.postMessage({ id, kind: 'compute', comp, opts });
  }));
  chain = run.then(() => undefined, () => undefined);
  return run;
}

/** Abort any in-flight/queued encode by terminating the worker (recreated lazily next call). */
export function cancelEncodes(): void {
  if (worker) { worker.terminate(); worker = null; }
  for (const [, p] of pending) p.reject(new Error('encode cancelled'));
  pending.clear();
  chain = Promise.resolve();
}
