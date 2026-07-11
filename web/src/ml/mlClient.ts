/**
 * Main-thread client for the ML worker.
 *
 * Owns a single lazily-created worker, a request/response correlation map, and
 * the high-level operations features use: ensureModel / run / preprocess.
 * The worker (and therefore ORT) is only created on first use, so nothing ML
 * touches the page until a feature is invoked.
 */

import { getModel, type ModelId } from './registry.js';
import { fetchModelWeights, type ProgressFn } from './loader.js';
import type { PlainTensor, WorkerRequest, WorkerResponse } from './types.js';

interface Pending {
  resolve: (resp: WorkerResponse) => void;
  reject: (err: Error) => void;
}

let worker: Worker | null = null;
let reqCounter = 0;
const pending = new Map<number, Pending>();

export interface ModelInfo { inputNames: string[]; outputNames: string[]; backend: string; }
const initialized = new Map<string, ModelInfo>();

function ensureWorker(): Worker {
  if (!worker) {
    worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (ev: MessageEvent<WorkerResponse>) => {
      const resp = ev.data;
      const p = pending.get(resp.reqId);
      if (!p) return;
      pending.delete(resp.reqId);
      if (resp.kind === 'error') p.reject(new Error(resp.message));
      else p.resolve(resp);
    };
    worker.onerror = (e) => {
      const err = new Error(`ML worker error: ${e.message}`);
      for (const p of pending.values()) p.reject(err);
      pending.clear();
    };
  }
  return worker;
}

function send(req: WorkerRequest, transfer: Transferable[] = []): Promise<WorkerResponse> {
  const w = ensureWorker();
  return new Promise((resolve, reject) => {
    pending.set(req.reqId, { resolve, reject });
    w.postMessage(req, transfer);
  });
}

/** Download (or cache-hit) a model's weights and initialize its session. */
export async function ensureModel(modelId: ModelId, onProgress?: ProgressFn): Promise<ModelInfo> {
  const cached = initialized.get(modelId);
  if (cached) return cached;
  const model = getModel(modelId);
  const buffer = await fetchModelWeights(model, onProgress);
  const reqId = ++reqCounter;
  const resp = await send(
    { kind: 'init', reqId, modelId, buffer, minBackend: model.minBackend },
    [buffer],
  );
  if (resp.kind !== 'init-done') throw new Error('unexpected init response');
  const info: ModelInfo = { inputNames: resp.inputNames, outputNames: resp.outputNames, backend: resp.backend };
  initialized.set(modelId, info);
  return info;
}

/** Run inference on an already-initialized model. */
export async function run(
  modelId: ModelId,
  feeds: Record<string, PlainTensor>,
): Promise<Record<string, PlainTensor>> {
  const reqId = ++reqCounter;
  // Do NOT transfer feed buffers: callers may reuse them (e.g. SAM reuses the
  // cached image embeddings across clicks). Structured-clone copies them so the
  // caller's buffers stay valid. The worker still transfers results back out.
  const resp = await send({ kind: 'run', reqId, modelId, feeds });
  if (resp.kind !== 'run-done') throw new Error('unexpected run response');
  return resp.outputs;
}

export interface PreprocessSpec {
  targetW: number;
  targetH: number;
  mean: [number, number, number];
  std: [number, number, number];
  fit: 'pad' | 'stretch';
  layout: 'nchw' | 'nhwc';
}

export interface PreprocessResult {
  tensor: PlainTensor;
  scale: number;
  padX: number;
  padY: number;
  drawW: number;
  drawH: number;
}

/** Resize + normalize an image in the worker (off the main thread). */
export async function preprocess(bitmap: ImageBitmap, spec: PreprocessSpec): Promise<PreprocessResult> {
  const reqId = ++reqCounter;
  // Do NOT transfer the bitmap: callers reuse it (e.g. background removal reads
  // the source pixels afterward, auto-crop crops it). ImageBitmap is structured-
  // cloneable, so the worker gets its own copy and the caller keeps the original.
  const resp = await send({ kind: 'preprocess', reqId, bitmap, ...spec });
  if (resp.kind !== 'preprocess-done') throw new Error('unexpected preprocess response');
  const { tensor, scale, padX, padY, drawW, drawH } = resp;
  return { tensor, scale, padX, padY, drawW, drawH };
}

/** Tear down the worker (e.g. after a long idle) to release ORT memory. */
export function disposeMlWorker(): void {
  worker?.terminate();
  worker = null;
  initialized.clear();
  pending.clear();
}
